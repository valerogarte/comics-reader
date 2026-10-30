package com.valerogarte.comicsreader.nativeapp.reader

import java.io.IOException
import java.nio.charset.CodingErrorAction
import java.util.zip.CRC32

/** Indexes RAR5 headers and presents one independent file to libarchive at a time. */
internal object Rar5RemotePreview {
    private val signature = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x01, 0x00)
    private val archivePrefix = signature + simpleHeader(1)
    private val archiveSuffix = simpleHeader(5)
    private data class Entry(val name: String, val offset: Long, val length: Long)

    /** Returns null for archive features requiring the general archive reader. */
    fun inspect(name: String, input: RemoteInput): DocumentMetadata? {
        if (input.size < signature.size || !input.bytes(0, signature.size).contentEquals(signature)) return null
        val entries = ArrayList<Entry>()
        var offset = signature.size.toLong()
        var sawMain = false
        var ended = false
        var blocks = 0
        while (offset < input.size) {
            if (++blocks > 100_000) throw IOException("Demasiadas entradas en el archivo")
            // The size vint is at most 3 bytes in RAR5. Read a small prefix once and reuse it.
            val prefix = input.bytes(offset, minOf(128L, input.size - offset).toInt())
            if (prefix.size < 5) throw IOException("Cabecera RAR5 incompleta")
            val sizeReader = HeaderReader(prefix, 4, prefix.size)
            val bodySize = sizeReader.vint(3)
            if (bodySize < 2 || bodySize > 2_097_151) throw IOException("Tamaño de cabecera RAR5 inválido")
            val headerSize = sizeReader.position.toLong() + bodySize
            if (headerSize > input.size - offset) throw IOException("Cabecera RAR5 incompleta")
            val header = if (headerSize <= prefix.size) prefix.copyOf(headerSize.toInt()) else input.bytes(offset, headerSize.toInt())
            val expectedCrc = (0 until 4).fold(0L) { value, i -> value or ((header[i].toLong() and 0xff) shl (8 * i)) }
            val crc = CRC32().apply { update(header, 4, header.size - 4) }.value
            if (crc != expectedCrc) throw IOException("Cabecera RAR5 dañada")
            val fields = HeaderReader(header, sizeReader.position, header.size)
            val type = fields.vint()
            val flags = fields.vint()
            if (flags and 0x18L != 0L) return null // Files split across volumes.
            val extraSize = if (flags and 1L != 0L) fields.vint() else 0L
            val dataSize = if (flags and 2L != 0L) fields.vint() else 0L
            if (extraSize > header.size - fields.position) throw IOException("Área adicional RAR5 inválida")
            val extraStart = header.size - extraSize.toInt()
            fields.end = extraStart
            if (fields.position > fields.end || dataSize > input.size - offset - headerSize) throw IOException("Bloque RAR5 incompleto")
            when (type) {
                1L -> {
                    if (sawMain || offset != signature.size.toLong()) return null
                    val archiveFlags = fields.vint()
                    if (archiveFlags and 0x07L != 0L) return null // Multivolume or solid.
                    sawMain = true
                }
                2L -> {
                    if (!sawMain) return null
                    if (flags and 0x20L != 0L) return null // Depends on a preceding block.
                    val fileFlags = fields.vint()
                    fields.vint() // Unpacked size; libarchive enforces the limit when extracting.
                    fields.vint() // OS attributes.
                    if (fileFlags and 2L != 0L) fields.skip(4)
                    if (fileFlags and 4L != 0L) fields.skip(4)
                    val compression = fields.vint()
                    if (compression and 0x40L != 0L || fileFlags and 8L != 0L) return null
                    val version = compression and 0x3fL
                    val method = (compression shr 7) and 7L
                    val dictionary = (compression shr 10) and 31L
                    if (version != 0L || method > 5 || (method != 0L && dictionary > 8)) return null
                    fields.vint() // Host OS.
                    val nameSize = fields.vint()
                    if (nameSize > fields.end - fields.position) throw IOException("Nombre RAR5 inválido")
                    val bytes = header.copyOfRange(fields.position, fields.position + nameSize.toInt())
                    val entryName = try {
                        Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
                    } catch (_: Exception) { return null }
                    if ('\u0000' in entryName || '\ufffe' in entryName) return null
                    // File encryption and redirection refer to data outside this virtual archive.
                    val extras = HeaderReader(header, extraStart, header.size)
                    while (extras.position < extras.end) {
                        val length = extras.vint()
                        if (length < 1 || length > extras.end - extras.position) throw IOException("Registro RAR5 inválido")
                        val recordEnd = extras.position + length.toInt()
                        val record = HeaderReader(header, extras.position, recordEnd)
                        if (record.vint() in setOf(1L, 5L)) return null
                        extras.position = recordEnd
                    }
                    if (fileFlags and 1L == 0L) entries += Entry(entryName, offset, headerSize + dataSize)
                }
                3L -> if (!sawMain) return null // Service data is not needed for file extraction.
                4L -> return null // Archive encryption.
                5L -> {
                    if (!sawMain || fields.vint() and 1L != 0L) return null
                    ended = true
                    break
                }
                else -> return null
            }
            offset += headerSize + dataSize
        }
        if (!sawMain || !ended) return null
        val images = entries.filter { imageExtension.containsMatchIn(it.name) && !it.name.startsWith("__MACOSX/") }
            .sortedWith { a, b -> naturalOrder.compare(a.name, b.name) }
        if (images.isEmpty()) throw IOException("El archivo no contiene páginas de imágenes")
        fun read(entry: Entry, maxSize: Int): ByteArray = BookArchive(
            remote = EntryInput(input, entry.offset, entry.length),
        ).read(entry.name, maxSize)
        val info = entries.firstOrNull { it.name.substringAfterLast('/').equals("ComicInfo.xml", true) }
            ?.let { runCatching { XmlContent.parse(read(it, 1024 * 1024)) }.getOrNull() }
        val coverIndex = info?.descendants("Page")?.firstOrNull { it.attr("Type").equals("FrontCover", true) }
            ?.attr("Image")?.toIntOrNull()?.takeIf { it in images.indices } ?: 0
        fun cover(index: Int) = imageBitmap(read(images[index], 64 * 1024 * 1024), 360)
            ?: throw IOException("Imagen de portada no compatible")
        val cover = if (coverIndex == 0) cover(0) else runCatching { cover(coverIndex) }.getOrElse { cover(0) }
        return DocumentMetadata(
            title = XmlContent.value(info, "Title").ifBlank { baseTitle(name) },
            author = XmlContent.value(info, "Writer"), description = XmlContent.value(info, "Summary"),
            publisher = XmlContent.value(info, "Publisher"), pageCount = images.size, cover = cover,
        )
    }

    /** Signed Long is deliberate: sizes above Long.MAX_VALUE cannot address an Android file. */
    private class HeaderReader(val bytes: ByteArray, var position: Int, var end: Int) {
        fun vint(maxBytes: Int = 10): Long {
            var value = 0L
            for (index in 0 until maxBytes) {
                if (position >= end) throw IOException("Entero RAR5 incompleto")
                val next = bytes[position++].toInt() and 0xff
                if (index == 9 && next and 0x7f != 0) throw IOException("Entero RAR5 demasiado grande")
                value = value or ((next and 0x7f).toLong() shl (7 * index))
                if (next and 0x80 == 0) return value
            }
            throw IOException("Entero RAR5 inválido")
        }
        fun skip(count: Int) {
            if (count > end - position) throw IOException("Cabecera RAR5 incompleta")
            position += count
        }
    }

    private fun simpleHeader(type: Int): ByteArray {
        val data = byteArrayOf(3, type.toByte(), 0, 0)
        val crc = CRC32().apply { update(data) }.value
        return ByteArray(4) { (crc ushr (it * 8)).toByte() } + data
    }

    /** Virtual archive: synthetic non-solid main header + original file block + end marker. */
    private class EntryInput(private val source: RemoteInput, private val offset: Long, private val length: Long) : ArchiveInput {
        override val size = archivePrefix.size.toLong() + length + archiveSuffix.size
        override val readBufferSize: Int = 512 * 1024
        private var cursor = 0L
        @Synchronized override fun read(buffer: ByteArray): Int {
            if (cursor == size) return -1
            if (buffer.isEmpty()) return 0
            val count: Int
            when {
                cursor < archivePrefix.size -> {
                    count = minOf(buffer.size.toLong(), archivePrefix.size - cursor).toInt()
                    archivePrefix.copyInto(buffer, 0, cursor.toInt(), cursor.toInt() + count)
                }
                cursor < archivePrefix.size + length -> {
                    val position = cursor - archivePrefix.size
                    count = minOf(buffer.size.toLong(), length - position, readBufferSize.toLong()).toInt()
                    source.bytes(offset + position, count).copyInto(buffer)
                }
                else -> {
                    val position = (cursor - archivePrefix.size - length).toInt()
                    count = minOf(buffer.size, archiveSuffix.size - position)
                    archiveSuffix.copyInto(buffer, 0, position, position + count)
                }
            }
            cursor += count
            return count
        }
        @Synchronized override fun seek(offset: Long, whence: Int): Long {
            val base = when (whence) { 0 -> 0L; 1 -> cursor; 2 -> size; else -> throw IOException("Origen de lectura desconocido") }
            if (offset > 0 && base > Long.MAX_VALUE - offset || offset < -base) throw IOException("Posición fuera del archivo")
            val target = base + offset
            if (target < 0 || target > size) throw IOException("Posición fuera del archivo")
            cursor = target
            return cursor
        }
        @Synchronized override fun position(): Long = cursor
    }
}
