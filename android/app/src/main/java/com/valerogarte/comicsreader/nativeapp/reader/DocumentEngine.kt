package com.valerogarte.comicsreader.nativeapp.reader

import android.content.Context
import android.graphics.Bitmap
import com.artifex.mupdf.fitz.Document
import com.artifex.mupdf.fitz.Font
import com.artifex.mupdf.fitz.RectI
import com.artifex.mupdf.fitz.Context as FitzContext
import com.artifex.mupdf.fitz.android.AndroidDrawDevice
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.util.Locale
import java.util.concurrent.CancellationException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

data class DocumentOptions(
    val fontSize: Float = 18f,
    val fontFamily: String = "sans",
    val fontPath: String? = null,
    val backgroundColor: Int = android.graphics.Color.rgb(252, 250, 245),
    val textColor: Int = android.graphics.Color.rgb(34, 34, 34),
)

data class DocumentMetadata(
    val title: String,
    val author: String = "",
    val description: String = "",
    val publisher: String = "",
    val pageCount: Int = 0,
    val cover: Bitmap? = null,
)

internal class RemotePreviewFailure(val retryable: Boolean, cause: Exception) : IOException("No se pudo crear la vista previa", cause)

interface DocumentSession : Closeable {
    val pageCount: Int
    val isReflowable: Boolean get() = false
    fun renderPage(index: Int, width: Int): Bitmap
}

internal fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
internal fun baseTitle(name: String): String = name.substringAfterLast('/').substringBeforeLast('.', name.substringAfterLast('/'))

/** All document parsing, pagination and rendering runs on Android/JNI, without a browser. */
class NativeDocumentEngine(context: Context) {
    private val applicationContext = context.applicationContext
    private val cacheDirectory = File(context.cacheDir, "document-work").apply { mkdirs() }

    fun inspectLocal(file: File, name: String = file.name): DocumentMetadata {
        return runCatching {
            if (extension(name) in comicExtensions) {
                ArchiveDocument(BookArchive(file = file)).use { it.metadata(name) }
            } else {
                open(file, name).use { session ->
                    val details = when (session) {
                        is MuPdfDocument -> session.metadata(name)
                        is OfdDocument -> session.metadata(name)
                        is ChmDocument -> session.metadata(name)
                        is CajDocument -> session.metadata(name)
                        is MobiDocument -> session.metadata(name)
                        is OfficeTextDocument -> session.metadata(name)
                        else -> if (extension(name) in setOf("doc", "ppt")) LegacyOffice.inspectLocal(file, name) else DocumentMetadata(baseTitle(name))
                    }
                    details.copy(
                        pageCount = session.pageCount,
                        cover = details.cover ?: runCatching { session.renderPage(0, 360) }.getOrNull(),
                    )
                }
            }
        }.getOrElse { DocumentMetadata(baseTitle(name)) }
    }

    fun inspectRemote(name: String, size: Long, source: RangeSource): DocumentMetadata =
        runCatching { inspectRemoteStrict(name, size, source) }.getOrElse { DocumentMetadata(baseTitle(name)) }

    /** Preserve failures so callers can retry remote reads after a transient interruption. */
    internal fun inspectRemoteStrict(name: String, size: Long, source: RangeSource): DocumentMetadata {
        val fallback = DocumentMetadata(baseTitle(name))
        if (Thread.currentThread().isInterrupted) throw IOException("Lectura cancelada")
        if (size <= 1) return fallback
        // Some format readers treat metadata and covers as optional and catch their errors.
        // Retain transport failures even if one of those readers swallowed the exception.
        val sourceFailure = AtomicReference<Exception?>(null)
        val input = RemoteInput(size, RangeSource { offset, length ->
            try { source.read(offset, length) }
            catch (error: Exception) { sourceFailure.compareAndSet(null, error); throw error }
        })
        try {
            val metadata = when (extension(name)) {
                "cbz", "zip" -> {
                    try { RemoteBookPreview.comic(name, input) }
                    catch (_: UnsupportedZipPreviewException) {
                        ArchiveDocument(BookArchive(remote = input)).use { it.metadata(name) }
                    }
                }
                "cbr", "rar" -> Rar5RemotePreview.inspect(name, input) ?: Rar4RemotePreview.inspect(name, input)
                    ?: ArchiveDocument(BookArchive(remote = input)).use { it.metadata(name) }
                in comicExtensions -> ArchiveDocument(BookArchive(remote = input)).use { it.metadata(name) }
                "ofd" -> OfdDocument(BookArchive(remote = input), name).use { it.metadata(name) }
                "doc", "ppt" -> LegacyOffice.inspectRemote(name, input)
                "mobi", "azw", "azw3" -> MobiRemotePreview.inspect(name, input)
                "epub", "docx", "pptx", "xlsx", "kdz" -> RemoteBookPreview.inspect(name, input)
                "txt", "md", "htm", "html", "xml", "xhtml", "mhtml", "rtf", "fb2" -> {
                    val prefix = input.bytes(0, minOf(size - 1, 256L * 1024).toInt())
                    val raw = TextContent.decode(prefix)
                    val text = decodeText(name, raw)
                    val title = if (extension(name) in setOf("html", "htm", "xhtml", "xml", "mhtml")) {
                        Regex("<title\\b[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                            .find(raw)?.groupValues?.get(1)?.let { TextContent.html(it) }.orEmpty()
                    } else if (extension(name) == "fb2") {
                        Regex("<book-title\\b[^>]*>(.*?)</book-title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
                            .find(raw)?.groupValues?.get(1)?.let { TextContent.html(it) }.orEmpty()
                    } else ""
                    TextDocument(text.ifBlank { baseTitle(name) }).use { session ->
                        fallback.copy(title = title.ifBlank { fallback.title }, cover = session.renderPage(0, 360))
                    }
                }
                "pdf", "xps", "oxps" -> NativePagePreview.inspect(applicationContext, name, input)
                else -> fallback
            }
            (sourceFailure.get() ?: input.failure()?.takeIf { metadata.cover == null })?.let { error ->
                metadata.cover?.takeUnless { it.isRecycled }?.recycle()
                throw error
            }
            return metadata
        } catch (error: Exception) {
            val cause = sourceFailure.get() ?: input.failure() ?: error
            if (cause is CancellationException) throw cause
            throw RemotePreviewFailure(
                retryable = (sourceFailure.get() != null && cause !is PreviewLimitException && cause !is PreviewRangeException) ||
                    cause is PreviewTimeoutException || cause is PreviewIncompleteException || Thread.currentThread().isInterrupted,
                cause = cause,
            )
        }
    }

    fun open(file: File, name: String = file.name, options: DocumentOptions = DocumentOptions()): DocumentSession {
        if (!file.isFile || file.length() == 0L) throw IOException("El archivo local no existe o está vacío")
        val ext = extension(name)
        return when {
            ext in comicExtensions -> ArchiveDocument(BookArchive(file = file))
            ext == "kdz" -> openKdz(file, options)
            ext == "doc" || ext == "ppt" -> TextDocument(LegacyOffice.read(file, ext), options)
            ext == "rtf" || ext == "mhtml" -> TextDocument(decodeText(name, readTextFile(file)), options)
            ext == "chm" -> ChmDocument(file, options)
            ext == "ofd" -> OfdDocument(file, options, cacheDirectory)
            ext == "caj" -> CajDocument(file, cacheDirectory, options)
            ext == "djvu" || ext == "djv" -> DjvuDocument(file)
            ext in setOf("mobi", "azw", "azw3") -> MobiDocument(file, cacheDirectory, options)
            ext == "xlsx" -> OfficeTextDocument(file, options)
            ext == "docx" -> {
                try { MuPdfDocument(file, options) }
                catch (error: RuntimeException) {
                    if (error.message?.contains(".rels", ignoreCase = true) == true) OfficeTextDocument(file, options) else throw error
                }
            }
            ext == "txt" || ext == "md" -> {
                try { MuPdfDocument(file, options) }
                catch (error: RuntimeException) { TextDocument(decodeText(name, readTextFile(file)), options) }
            }
            ext == "xml" -> {
                val prefix = file.inputStream().use { input ->
                    val buffer = ByteArray(8192)
                    val count = input.read(buffer).coerceAtLeast(0)
                    TextContent.decode(buffer.copyOf(count))
                }
                if (Regex("<(?:[a-zA-Z0-9_-]+:)?FictionBook\\b", RegexOption.IGNORE_CASE).containsMatchIn(prefix)) MuPdfDocument(file, options)
                else TextDocument(TextContent.html(readTextFile(file)), options)
            }
            ext in supportedExtensions -> {
                MuPdfDocument(file, options)
            }
            else -> throw IOException("Formato no compatible: ${ext.uppercase(Locale.ROOT)}")
        }
    }

    private fun openKdz(file: File, options: DocumentOptions): DocumentSession {
        val archive = BookArchive(file = file)
        val inner = archive.entries().firstOrNull { extension(it.name) != "kdz" && extension(it.name) in supportedExtensions }
            ?: throw IOException("El paquete KDZ no contiene un libro compatible")
        if (inner.size < 0 || inner.size > 4L * 1024 * 1024 * 1024) throw IOException("El libro del paquete KDZ supera el límite de extracción de 4 GB")
        if (inner.size + 16L * 1024 * 1024 > cacheDirectory.usableSpace) throw IOException("No hay espacio local suficiente para abrir este paquete KDZ")
        val temporary = File.createTempFile("book-", ".${extension(inner.name)}", cacheDirectory)
        try {
            archive.copyTo(inner.name, temporary)
            val document = open(temporary, inner.name, options)
            return object : DocumentSession {
                override val pageCount: Int get() = document.pageCount
                override val isReflowable: Boolean get() = document.isReflowable
                override fun renderPage(index: Int, width: Int): Bitmap = document.renderPage(index, width)
                override fun close() { try { document.close() } finally { temporary.delete() } }
            }
        } catch (error: Throwable) { temporary.delete(); throw error }
    }

    companion object {
        val comicExtensions = setOf("cbz", "cbt", "cbr", "cb7", "zip", "tar", "rar", "7z")
        val supportedExtensions = comicExtensions + setOf(
            "pdf", "epub", "mobi", "azw", "azw3", "fb2", "xps", "oxps", "docx", "pptx", "xlsx",
            "txt", "md", "html", "htm", "xml", "xhtml", "mhtml", "rtf", "doc", "ppt", "kdz", "chm", "ofd", "caj", "djvu", "djv",
        )
        val knownExtensions = supportedExtensions + setOf("chm", "ofd", "caj", "djvu", "djv")
        fun supports(name: String): Boolean = extension(name) in supportedExtensions
    }
}

internal fun readTextFile(file: File): String {
    if (file.length() > 32L * 1024 * 1024) throw IOException("El documento de texto supera el límite de 32 MB")
    return TextContent.decode(file.readBytes())
}

internal fun decodeText(name: String, raw: String): String = when (extension(name)) {
    "rtf" -> TextContent.rtf(raw)
    "md" -> TextContent.markdown(raw)
    "html", "htm", "xml", "xhtml", "fb2" -> TextContent.html(raw)
    "mhtml" -> MimeHtml.text(raw)
    else -> raw
}

internal class MuPdfDocument(
    file: File,
    private val options: DocumentOptions = DocumentOptions(),
) : DocumentSession {
    private var document: Document? = null
    init {
        synchronized(MuPdfDocument::class.java) {
            try {
                FitzContext.init()
                if (!fontLoaderInstalled) {
                    Font.installFontLoader { name, _, _, _ -> fontPaths[name]?.let { Font(it) } }
                    fontLoaderInstalled = true
                }
                val family = options.fontPath?.takeIf { File(it).isFile }?.let { path ->
                    val key = "ReaderFont" + path.hashCode().toUInt().toString(16)
                    fontPaths[key] = path
                    key
                } ?: when (options.fontFamily) { "serif" -> "serif"; "mono", "monospace" -> "monospace"; else -> "sans-serif" }
                fun cssColor(color: Int): String = String.format(Locale.ROOT, "#%06x", color and 0xffffff)
                val css = "body { background-color: ${cssColor(options.backgroundColor)} !important; color: ${cssColor(options.textColor)} !important; } body, p, div, span { font-family: '$family' !important; color: ${cssColor(options.textColor)} !important; }"
                val doc = Document.openDocument(file.absolutePath)
                document = doc
                if (doc.needsPassword()) throw IOException("El libro está protegido por contraseña o DRM")
                if (doc.isReflowable) { doc.style(true, css); doc.layout(600f, 900f, options.fontSize.coerceIn(12f, 40f)) }
                if (doc.countPages() !in 1..100_000) throw IOException("El documento no contiene páginas válidas")
            } catch (error: Throwable) { document?.destroy(); document = null; throw error }
        }
    }
    override val pageCount: Int get() = synchronized(this) { document?.countPages() ?: 0 }
    override val isReflowable: Boolean get() = synchronized(this) { document?.isReflowable ?: false }
    @Synchronized
    override fun renderPage(index: Int, width: Int): Bitmap {
        val doc = document ?: throw IOException("Documento cerrado")
        require(index in 0 until doc.countPages()) { "Página fuera del documento" }
        val page = doc.loadPage(index)
        try {
            val matrix = AndroidDrawDevice.fitPage(page, width.coerceIn(180, 2048), (width * 4).coerceIn(720, 4096))
            val bounds = RectI(page.bounds.transform(matrix))
            val bitmap = Bitmap.createBitmap((bounds.x1 - bounds.x0).coerceAtLeast(1), (bounds.y1 - bounds.y0).coerceAtLeast(1), Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(if (doc.isReflowable) options.backgroundColor else android.graphics.Color.WHITE)
                val device = AndroidDrawDevice(bitmap, bounds.x0, bounds.y0, false)
                try { page.run(device, matrix, null); device.close() }
                finally { device.destroy() }
                return bitmap
            } catch (error: Throwable) { bitmap.recycle(); throw error }
        }
        finally { page.destroy() }
    }
    @Synchronized
    fun metadata(name: String, includeCover: Boolean = false): DocumentMetadata {
        val doc = document ?: throw IOException("Documento cerrado")
        fun info(key: String): String = runCatching { doc.getMetaData(key).orEmpty() }.getOrDefault("")
        return DocumentMetadata(
            title = info(Document.META_INFO_TITLE).ifBlank { baseTitle(name) },
            author = info(Document.META_INFO_AUTHOR),
            description = info(Document.META_INFO_SUBJECT),
            pageCount = pageCount,
            cover = if (includeCover) runCatching { renderPage(0, 360) }.getOrNull() else null,
        )
    }
    @Synchronized
    override fun close() {
        try { document?.destroy() }
        finally { document = null }
    }
    companion object {
        private val fontPaths = ConcurrentHashMap<String, String>()
        private var fontLoaderInstalled = false
    }
}
