package com.valerogarte.comicsreader.nativeapp.reader

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.ParcelFileDescriptor
import android.os.ProxyFileDescriptorCallback
import android.os.storage.StorageManager
import android.system.ErrnoException
import android.system.OsConstants
import androidx.annotation.RequiresApi
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

/** Android's seekable proxy descriptor feeds PDFium directly; no document spool is created. */
internal object NativePagePreview {
    fun inspect(context: Context, name: String, input: RemoteInput): DocumentMetadata {
        val basic = DocumentMetadata(baseTitle(name))
        // Local reading still supports API 24. Proxy file descriptors were added in API 26.
        if (extension(name) != "pdf" || Build.VERSION.SDK_INT < 26) return basic
        val info = PdfInfoPreview.read(input)
        val details = basic.copy(
            title = info["Title"].orEmpty().ifBlank { basic.title },
            author = info["Author"].orEmpty(), description = info["Subject"].orEmpty(),
        )
        return runCatching { render(context, input, details) }.getOrDefault(details)
    }

    @RequiresApi(26)
    private fun render(context: Context, input: RemoteInput, metadata: DocumentMetadata): DocumentMetadata {
        val callbacks = HandlerThread("SftpPdfPreview").apply { start() }
        val released = AtomicBoolean(false)
        var descriptor: ParcelFileDescriptor? = null
        try {
            val manager = context.getSystemService(StorageManager::class.java)
            descriptor = manager.openProxyFileDescriptor(ParcelFileDescriptor.MODE_READ_ONLY, object : ProxyFileDescriptorCallback() {
                override fun onGetSize(): Long = input.size
                override fun onRead(offset: Long, size: Int, data: ByteArray): Int {
                    if (released.get()) throw ErrnoException("SFTP PDF cerrado", OsConstants.EBADF)
                    return try {
                        val count = minOf(size.toLong(), data.size.toLong(), (input.size - offset).coerceAtLeast(0), 4L * 1024 * 1024).toInt()
                        if (count <= 0) 0 else { input.bytes(offset, count).copyInto(data); count }
                    } catch (error: Exception) { throw ErrnoException("Lectura SFTP PDF", OsConstants.EIO, error) }
                }
                override fun onRelease() { released.set(true) }
            }, Handler(callbacks.looper))
            PdfRenderer(descriptor).use { renderer ->
                val count = renderer.pageCount
                if (count !in 1..100_000) return metadata
                val cover = runCatching {
                    renderer.openPage(0).use { page ->
                        if (page.width <= 0 || page.height <= 0) return@use null
                        val scale = minOf(360f / page.width, 540f / page.height)
                        val bitmap = Bitmap.createBitmap((page.width * scale).toInt().coerceAtLeast(1), (page.height * scale).toInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        bitmap.eraseColor(Color.WHITE)
                        try { page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY); bitmap }
                        catch (error: Throwable) { bitmap.recycle(); throw error }
                    }
                }.getOrNull()
                return metadata.copy(pageCount = count, cover = cover)
            }
        } finally {
            runCatching { descriptor?.close() }
            released.set(true)
            callbacks.quitSafely()
        }
    }
}

/** Basic uncompressed PDF Info dictionaries; other encodings retain the file name. */
private object PdfInfoPreview {
    fun read(input: RemoteInput): Map<String, String> = runCatching {
        val count = minOf(64L * 1024, input.size / 4).toInt()
        if (count < 16) return emptyMap()
        val start = String(input.bytes(0, count), Charsets.ISO_8859_1)
        val tail = String(input.bytes(input.size - count, count), Charsets.ISO_8859_1)
        val reference = Regex("/Info\\s+(\\d+)\\s+(\\d+)\\s+R").find(tail) ?: return emptyMap()
        val number = reference.groupValues[1]
        val generation = reference.groupValues[2]
        val objectPattern = Regex("(?s)(?<![0-9])$number\\s+$generation\\s+obj\\b(.*?)endobj")
        var dictionary = objectPattern.find(start)?.groupValues?.get(1) ?: objectPattern.find(tail)?.groupValues?.get(1)
        if (dictionary == null) {
            val xrefOffset = Regex("startxref\\s+(\\d+)").findAll(tail).lastOrNull()?.groupValues?.get(1)?.toLongOrNull()
                ?: return emptyMap()
            val bytes = input.bytes(xrefOffset, minOf(64L * 1024, input.size - xrefOffset).toInt())
            val xref = String(bytes, Charsets.ISO_8859_1).lineSequence().toList()
            if (xref.firstOrNull()?.trim() != "xref") return emptyMap()
            var index = 1
            var objectOffset: Long? = null
            while (index < xref.size) {
                val section = Regex("(\\d+)\\s+(\\d+)").matchEntire(xref[index++].trim()) ?: break
                val first = section.groupValues[1].toIntOrNull() ?: break
                val length = section.groupValues[2].toIntOrNull() ?: break
                val id = number.toIntOrNull() ?: break
                if (length < 0 || length > 1_000_000) break
                if (id >= first && id.toLong() < first.toLong() + length) {
                    val row = xref.getOrNull(index + id - first)?.trim().orEmpty()
                    if (row.endsWith("n")) objectOffset = row.substringBefore(' ').toLongOrNull()
                    break
                }
                index += length
            }
            val offset = objectOffset?.takeIf { it >= 0 && it < input.size } ?: return emptyMap()
            dictionary = objectPattern.find(String(input.bytes(offset, minOf(64L * 1024, input.size - offset).toInt()), Charsets.ISO_8859_1))?.groupValues?.get(1)
        }
        val data = dictionary ?: return emptyMap()
        listOf("Title", "Author", "Subject").mapNotNull { key ->
            val position = Regex("/$key\\s*").find(data)?.range?.last?.plus(1) ?: return@mapNotNull null
            readString(data, position)?.let { key to it }
        }.toMap()
    }.getOrDefault(emptyMap())

    private fun readString(value: String, offset: Int): String? {
        val bytes = ByteArrayOutputStream()
        if (value.getOrNull(offset) == '<' && value.getOrNull(offset + 1) != '<') {
            val end = value.indexOf('>', offset + 1).takeIf { it >= 0 } ?: return null
            val hex = value.substring(offset + 1, end).filterNot { it.isWhitespace() }.let { if (it.length % 2 == 1) it + "0" else it }
            if (hex.length > 16 * 1024) return null
            for (i in hex.indices step 2) bytes.write(hex.substring(i, i + 2).toIntOrNull(16) ?: return null)
        } else if (value.getOrNull(offset) == '(') {
            var depth = 1
            var i = offset + 1
            while (i < value.length && depth > 0 && bytes.size() <= 8192) {
                val c = value[i++]
                when (c) {
                    '(' -> { depth++; bytes.write(c.code) }
                    ')' -> { depth--; if (depth > 0) bytes.write(c.code) }
                    '\\' -> if (i < value.length) {
                        val escaped = value[i++]
                        when {
                            escaped in '0'..'7' -> {
                                var octal = escaped.toString()
                                repeat(2) { if (value.getOrNull(i) in '0'..'7') octal += value[i++] }
                                bytes.write(octal.toInt(8) and 0xff)
                            }
                            escaped == '\n' -> Unit
                            escaped == '\r' -> if (value.getOrNull(i) == '\n') i++
                            else -> bytes.write(when (escaped) { 'n' -> 10; 'r' -> 13; 't' -> 9; 'b' -> 8; 'f' -> 12; else -> escaped.code })
                        }
                    }
                    else -> bytes.write(c.code)
                }
            }
            if (depth != 0) return null
        } else return null
        val data = bytes.toByteArray()
        return when {
            data.size >= 2 && data[0] == 0xfe.toByte() && data[1] == 0xff.toByte() -> String(data, 2, data.size - 2, Charsets.UTF_16BE)
            data.size >= 2 && data[0] == 0xff.toByte() && data[1] == 0xfe.toByte() -> String(data, 2, data.size - 2, Charsets.UTF_16LE)
            else -> String(data, Charset.forName("windows-1252"))
        }.trim()
    }
}
