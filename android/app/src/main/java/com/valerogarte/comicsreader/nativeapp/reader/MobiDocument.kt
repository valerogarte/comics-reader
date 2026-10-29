package com.valerogarte.comicsreader.nativeapp.reader

import android.graphics.Bitmap
import androidx.annotation.Keep
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Native MOBI7/KF8 and HUFF/CDIC reconstruction, followed by MuPDF rendering. */
internal class MobiDocument(file: File, cacheDirectory: File, options: DocumentOptions) : DocumentSession {
    private val temporary: File
    private var document: MuPdfDocument? = null

    init {
        if (!file.isFile || file.length() !in 1..256L * 1024 * 1024)
            throw IOException("El archivo MOBI/AZW está vacío o supera el límite de 256 MB")
        cacheDirectory.mkdirs()
        temporary = File.createTempFile("mobi-", ".epub", cacheDirectory)
        try {
            ZipOutputStream(temporary.outputStream().buffered()).use { zip ->
                val sink = MobiZipSink(zip)
                sink.writeContainer()
                NativeMobi.unpack(file.absolutePath, sink)
            }
            document = MuPdfDocument(temporary, options)
        } catch (error: Throwable) {
            temporary.delete()
            throw error
        }
    }

    override val isReflowable: Boolean get() = document?.isReflowable ?: true
    override val pageCount: Int get() = synchronized(this) { document?.pageCount ?: 0 }

    @Synchronized
    override fun renderPage(index: Int, width: Int): Bitmap =
        (document ?: throw IOException("Documento MOBI/AZW cerrado")).renderPage(index, width)

    @Synchronized
    fun metadata(name: String): DocumentMetadata =
        (document ?: throw IOException("Documento MOBI/AZW cerrado")).metadata(name)

    @Synchronized
    override fun close() {
        try { document?.close() }
        finally { document = null; temporary.delete() }
    }
}

@Keep
private object NativeMobi {
    init { System.loadLibrary("comics_mobi") }
    external fun unpack(path: String, sink: MobiZipSink)
}

/** JNI consumes each libmobi buffer during this call; no direct buffer escapes. */
@Keep
private class MobiZipSink(private val zip: ZipOutputStream) {
    private val buffer = ByteArray(64 * 1024)
    private val names = HashSet<String>()
    private var total = 0L

    fun writeContainer() {
        val mime = "application/epub+zip".toByteArray(Charsets.US_ASCII)
        zip.putNextEntry(ZipEntry("mimetype").apply {
            method = ZipEntry.STORED
            size = mime.size.toLong()
            compressedSize = size
            crc = CRC32().apply { update(mime) }.value
        })
        zip.write(mime)
        zip.closeEntry()
        zip.putNextEntry(ZipEntry("META-INF/container.xml"))
        zip.write("""<?xml version="1.0" encoding="UTF-8"?><container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container"><rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles></container>""".toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    fun addPart(name: String, data: ByteBuffer) {
        if (!name.startsWith("OEBPS/") || '/' in name.removePrefix("OEBPS/") || '\\' in name || name.contains("..") || !names.add(name))
            throw IOException("El libro contiene recursos duplicados o no válidos")
        if (names.size > 10_000 || data.remaining() > 64 * 1024 * 1024 || total + data.remaining() > 256L * 1024 * 1024)
            throw IOException("El contenido del libro supera el límite de lectura de 256 MB")
        total += data.remaining()
        zip.putNextEntry(ZipEntry(name))
        while (data.hasRemaining()) {
            if (Thread.currentThread().isInterrupted) throw IOException("Lectura MOBI/AZW cancelada")
            val count = minOf(buffer.size, data.remaining())
            data.get(buffer, 0, count)
            zip.write(buffer, 0, count)
        }
        zip.closeEntry()
    }
}
