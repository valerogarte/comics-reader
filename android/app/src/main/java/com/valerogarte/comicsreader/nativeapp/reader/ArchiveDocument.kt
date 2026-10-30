package com.valerogarte.comicsreader.nativeapp.reader

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import me.zhanghai.android.libarchive.Archive
import me.zhanghai.android.libarchive.ArchiveEntry
import me.zhanghai.android.libarchive.ArchiveException
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.text.Normalizer
import java.util.Locale
import kotlin.math.min

internal data class ArchiveItem(val name: String, val size: Long)

/** libarchive is native code and handles ZIP, TAR, RAR/RAR5 and 7z without JavaScript. */
internal class BookArchive(private val file: File? = null, private val remote: ArchiveInput? = null) {
    private fun <T> withArchive(block: (Long) -> T): T {
        val archive = Archive.readNew()
        try {
            Archive.readSupportFilterAll(archive)
            Archive.readSupportFormatAll(archive)
            if (file != null) {
                Archive.readOpenFileName(archive, file.absolutePath.toByteArray(Charsets.UTF_8), 64 * 1024L)
            } else {
                val source = remote ?: throw IOException("Archivo no disponible")
                source.seek(0, 0)
                val buffer = ByteBuffer.allocateDirect(source.readBufferSize)
                val bytes = ByteArray(buffer.capacity())
                Archive.readSetCallbackData(archive, source)
                Archive.readSetReadCallback<ArchiveInput>(archive) { _, input ->
                    try {
                        val count = input.read(bytes)
                        if (count < 0) null else {
                            buffer.clear()
                            buffer.put(bytes, 0, count)
                            buffer.flip()
                            buffer
                        }
                    } catch (error: IOException) { throw ArchiveException(Archive.ERRNO_FATAL, error.message, error) }
                }
                Archive.readSetSeekCallback<ArchiveInput>(archive) { _, input, offset, whence ->
                    try { input.seek(offset, whence) }
                    catch (error: IOException) { throw ArchiveException(Archive.ERRNO_FATAL, error.message, error) }
                }
                Archive.readSetSkipCallback<ArchiveInput>(archive) { _, input, requested ->
                    try {
                        val before = input.position()
                        input.seek(min(input.size, before + requested.coerceAtLeast(0)), 0) - before
                    } catch (error: IOException) { throw ArchiveException(Archive.ERRNO_FATAL, error.message, error) }
                }
                Archive.readOpen1(archive)
            }
            return block(archive)
        } finally { Archive.free(archive) }
    }

    fun entries(): List<ArchiveItem> = withArchive { archive ->
        val result = ArrayList<ArchiveItem>()
        var entry = Archive.readNextHeader(archive)
        while (entry != 0L) {
            if (Thread.currentThread().isInterrupted) throw IOException("Lectura cancelada")
            if (result.size >= 100_000) throw IOException("Demasiadas entradas en el archivo")
            val name = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.let { String(it, Charsets.UTF_8) }
                ?: throw IOException("Nombre de entrada no válido")
            result += ArchiveItem(name, ArchiveEntry.size(entry))
            Archive.readDataSkip(archive)
            entry = Archive.readNextHeader(archive)
        }
        result
    }

    fun read(name: String, maxSize: Int = 32 * 1024 * 1024): ByteArray = withArchive { archive ->
        var entry = Archive.readNextHeader(archive)
        var scanned = 0
        while (entry != 0L) {
            if (Thread.currentThread().isInterrupted) throw IOException("Lectura cancelada")
            if (++scanned > 100_000) throw IOException("Demasiadas entradas en el archivo")
            val path = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.let { String(it, Charsets.UTF_8) }
                ?: throw IOException("Nombre de entrada no válido")
            if (path == name) {
                val size = ArchiveEntry.size(entry)
                if (size < 0 || size > maxSize) throw IOException("Entrada demasiado grande")
                val result = ByteArray(size.toInt())
                val data = ByteBuffer.wrap(result)
                while (data.hasRemaining()) {
                    if (Thread.currentThread().isInterrupted) throw IOException("Lectura cancelada")
                    val before = data.position()
                    Archive.readData(archive, data)
                    if (data.position() == before) throw IOException("Entrada incompleta")
                }
                return@withArchive result
            }
            Archive.readDataSkip(archive)
            entry = Archive.readNextHeader(archive)
        }
        throw IOException("Entrada no encontrada: $name")
    }

    /** Extraction is reserved for books already downloaded, and never buffers the whole book. */
    fun copyTo(name: String, destination: File, maxSize: Long = 4L * 1024 * 1024 * 1024) {
        check(file != null) { "La extracción requiere un archivo local" }
        try {
            withArchive { archive ->
                var entry = Archive.readNextHeader(archive)
                var scanned = 0
                while (entry != 0L) {
                    if (++scanned > 100_000) throw IOException("Demasiadas entradas en el archivo")
                    val path = ArchiveEntry.pathnameUtf8(entry) ?: ArchiveEntry.pathname(entry)?.let { String(it, Charsets.UTF_8) }
                        ?: throw IOException("Nombre de entrada no válido")
                    if (path == name) {
                        val expected = ArchiveEntry.size(entry)
                        if (expected !in 1..maxSize) throw IOException("El libro extraído supera el límite permitido")
                        val buffer = ByteBuffer.allocateDirect(64 * 1024)
                        val bytes = ByteArray(buffer.capacity())
                        FileOutputStream(destination).use { output ->
                            var total = 0L
                            while (total < expected) {
                                if (Thread.currentThread().isInterrupted) throw IOException("Extracción cancelada")
                                buffer.clear()
                                buffer.limit(minOf(buffer.capacity().toLong(), expected - total).toInt())
                                Archive.readData(archive, buffer)
                                val count = buffer.position()
                                if (count <= 0) throw IOException("Libro extraído incompleto")
                                buffer.flip()
                                buffer.get(bytes, 0, count)
                                output.write(bytes, 0, count)
                                total += count
                            }
                            output.fd.sync()
                        }
                        return@withArchive
                    }
                    Archive.readDataSkip(archive)
                    entry = Archive.readNextHeader(archive)
                }
                throw IOException("Entrada no encontrada: $name")
            }
        } catch (error: Throwable) {
            destination.delete()
            throw error
        }
    }
}

internal fun imageBitmap(bytes: ByteArray, width: Int): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth.toLong() * bounds.outHeight > 200_000_000L) return null
    var sample = 1
    while (bounds.outWidth / sample > width.coerceAtLeast(180) * 2 || bounds.outHeight / sample > width.coerceAtLeast(180) * 4) sample *= 2
    return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
}

internal val imageExtension = Regex("\\.(jpe?g|png|gif|webp|bmp|avif|heic)$", RegexOption.IGNORE_CASE)

/** AppleDouble resource forks share the image extension but contain no page image. */
internal fun isComicImage(name: String): Boolean = imageExtension.containsMatchIn(name) &&
    name.replace('\\', '/').split('/').none { it == "__MACOSX" || it.startsWith("._") }

private val naturalOrderParts = Regex("\\d+|\\D+")

internal val naturalOrder = Comparator<String> { a, b ->
    val left = naturalOrderParts.findAll(a.lowercase(Locale.ROOT)).map { it.value }.toList()
    val right = naturalOrderParts.findAll(b.lowercase(Locale.ROOT)).map { it.value }.toList()
    var comparison = 0
    for (i in 0 until min(left.size, right.size)) {
        val x = left[i]; val y = right[i]
        comparison = if (x.firstOrNull()?.isDigit() == true && y.firstOrNull()?.isDigit() == true) {
            val nx = x.trimStart('0').ifEmpty { "0" }; val ny = y.trimStart('0').ifEmpty { "0" }
            nx.length.compareTo(ny.length).takeIf { it != 0 } ?: nx.compareTo(ny)
        } else x.compareTo(y)
        if (comparison != 0) break
    }
    if (comparison != 0) comparison else left.size.compareTo(right.size)
}

/** Pick an embedded cover without reading the other archive entries. */
internal fun comicCoverIndex(archiveName: String, images: List<String>, metadataIndex: Int?): Int {
    if (metadataIndex != null && metadataIndex in images.indices) return metadataIndex
    fun normalized(value: String) = Normalizer.normalize(value.lowercase(Locale.ROOT), Normalizer.Form.NFD)
        .replace(Regex("\\p{Mn}+"), "")
    val stems = images.map { normalized(it.replace('\\', '/').substringAfterLast('/').substringBeforeLast('.')) }
    fun words(stem: String) = Regex("[a-z]+").findAll(stem).map { it.value }.toList()
    fun isBackCover(stem: String) = words(stem).any { it in setOf("back", "backcover", "rear", "trasera", "contraportada") }
    val candidates = stems.indices.filterNot { isBackCover(stems[it]) }
    // Prefer the explicitly named front cover even when back_cover sorts before it.
    for (names in listOf(setOf("frontcover", "front", "portada", "caratula"), setOf("cover", "folder"), setOf("poster"))) {
        candidates.firstOrNull { index -> words(stems[index]).any { it in names } }?.let { return it }
    }
    val titleTokens = normalized(archiveName.substringAfterLast('/').substringBeforeLast('.'))
        .split(Regex("[^a-z0-9]+"))
        .filter { it.length >= 4 && it !in setOf("el", "los", "las", "the", "and", "for", "del", "una", "uno") }
        .distinct().sortedByDescending { it.length }
    return titleTokens.firstNotNullOfOrNull { token ->
        candidates.firstOrNull { stems[it].contains(token) }
    } ?: 0
}

internal class ArchiveDocument(private val archive: BookArchive) : DocumentSession {
    private val entries = archive.entries()
    private val images = entries.filter { isComicImage(it.name) }
        .map { it.name }.sortedWith(naturalOrder)
    init { if (images.isEmpty()) throw IOException("El archivo no contiene páginas de imágenes") }
    override val pageCount: Int get() = images.size
    override fun renderPage(index: Int, width: Int): Bitmap {
        require(index in images.indices) { "Página fuera del documento" }
        return imageBitmap(archive.read(images[index], 64 * 1024 * 1024), width)
            ?: throw IOException("Imagen de página no compatible")
    }
    fun metadata(name: String): DocumentMetadata {
        val info = entries.firstOrNull { it.name.substringAfterLast('/').equals("ComicInfo.xml", true) }
            ?.let { runCatching { XmlContent.parse(archive.read(it.name, 1024 * 1024)) }.getOrNull() }
        val metadataIndex = info?.descendants("Page")?.firstOrNull { it.attr("Type").equals("FrontCover", true) }
            ?.attr("Image")?.toIntOrNull()
        val coverIndex = comicCoverIndex(name, images, metadataIndex)
        return DocumentMetadata(
            title = XmlContent.value(info, "Title").ifBlank { baseTitle(name) },
            author = XmlContent.value(info, "Writer"),
            description = XmlContent.value(info, "Summary"),
            publisher = XmlContent.value(info, "Publisher"),
            pageCount = pageCount,
            cover = runCatching { renderPage(coverIndex, 360) }.getOrNull()
                ?: if (coverIndex != 0) runCatching { renderPage(0, 360) }.getOrNull() else null,
        )
    }
    override fun close() = Unit
}
