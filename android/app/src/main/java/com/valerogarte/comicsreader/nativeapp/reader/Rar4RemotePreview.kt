package com.valerogarte.comicsreader.nativeapp.reader

import java.io.IOException
import java.util.zip.CRC32

/** Reads RAR4 headers by offset and exposes individual non-solid entries to libarchive. */
internal object Rar4RemotePreview {
    private val signature = byteArrayOf(0x52, 0x61, 0x72, 0x21, 0x1a, 0x07, 0x00)
    private val endHeader = byteArrayOf(0xc4.toByte(), 0x3d, 0x7b, 0x00, 0x40, 0x07, 0x00)

    private data class Entry(val name: String, val offset: Long, val length: Long)

    /** Null means this RAR variant must use the general archive reader. */
    fun inspect(name: String, input: RemoteInput): DocumentMetadata? {
        if (input.size < signature.size || !input.bytes(0, signature.size).contentEquals(signature)) return null
        var offset = signature.size.toLong()
        var main: ByteArray? = null
        var ended = false
        var blocks = 0
        val entries = ArrayList<Entry>()
        while (offset < input.size) {
            if (++blocks > 100_000) throw IOException("Demasiadas entradas en el archivo")
            val prefix = input.bytes(offset, minOf(256L, input.size - offset).toInt())
            if (prefix.size < 7) throw IOException("Cabecera RAR incompleta")
            val type = prefix[2].toInt() and 0xff
            val flags = u16(prefix, 3)
            val headerSize = u16(prefix, 5)
            if (headerSize < 7 || headerSize.toLong() > input.size - offset) throw IOException("Cabecera RAR inválida")
            val header = if (headerSize <= prefix.size) prefix.copyOf(headerSize) else input.bytes(offset, headerSize)
            val crc = CRC32().apply { update(header, 2, header.size - 2) }.value.toInt() and 0xffff
            if (crc != u16(header, 0)) throw IOException("Cabecera RAR dañada")
            var dataSize = if (flags and 0x8000 != 0) {
                if (headerSize < 11) throw IOException("Bloque RAR incompleto")
                u32(header, 7)
            } else 0L
            when (type) {
                0x73 -> {
                    if (main != null || offset != signature.size.toLong() || flags and 0x0089 != 0) return null
                    main = signature + header
                }
                0x74 -> {
                    if (main == null || headerSize < 32) throw IOException("Entrada RAR inválida")
                    // Split, encrypted, solid and RAR's encoded Unicode names need the general reader.
                    if (flags and 0x0217 != 0) return null
                    val large = flags and 0x0100 != 0
                    val nameOffset = if (large) 40 else 32
                    if (headerSize < nameOffset) throw IOException("Entrada RAR incompleta")
                    if (large) {
                        val high = u32(header, 32)
                        if (high > 0x7fffffffL) throw IOException("Entrada RAR demasiado grande")
                        dataSize += high shl 32
                    }
                    val nameSize = u16(header, 26)
                    if (nameSize > headerSize - nameOffset) throw IOException("Nombre RAR inválido")
                    val rawName = header.copyOfRange(nameOffset, nameOffset + nameSize)
                    if (rawName.any { (it.toInt() and 0xff) !in 1..127 }) return null
                    val entryName = rawName.toString(Charsets.US_ASCII)
                    if (dataSize > input.size - offset - headerSize) throw IOException("Entrada RAR incompleta")
                    if (flags and 0x00e0 != 0x00e0) entries += Entry(entryName, offset, headerSize + dataSize)
                }
                0x7b -> { ended = true; break }
            }
            if (dataSize > input.size - offset - headerSize) throw IOException("Bloque RAR incompleto")
            offset += headerSize + dataSize
        }
        val archivePrefix = main ?: return null
        if (!ended) return null
        val images = entries.filter { isComicImage(it.name) }
            .sortedWith { a, b -> naturalOrder.compare(a.name, b.name) }
        if (images.isEmpty()) throw IOException("El archivo no contiene páginas de imágenes")
        fun read(entry: Entry, maxSize: Int): ByteArray = BookArchive(
            remote = RarEntryInput(input, archivePrefix, entry.offset, entry.length, endHeader),
        ).read(entry.name, maxSize)
        val info = entries.firstOrNull { it.name.substringAfterLast('/').equals("ComicInfo.xml", true) }
            ?.let { runCatching { XmlContent.parse(read(it, 1024 * 1024)) }.getOrNull() }
        val metadataIndex = info?.descendants("Page")?.firstOrNull { it.attr("Type").equals("FrontCover", true) }
            ?.attr("Image")?.toIntOrNull()
        val coverIndex = comicCoverIndex(name, images.map { it.name }, metadataIndex)
        fun cover(index: Int) = imageBitmap(read(images[index], 64 * 1024 * 1024), 360)
            ?: throw IOException("Imagen de portada no compatible")
        val cover = try { cover(coverIndex) }
            catch (error: Exception) { if (coverIndex == 0) throw error else cover(0) }
        return DocumentMetadata(
            title = XmlContent.value(info, "Title").ifBlank { baseTitle(name) },
            author = XmlContent.value(info, "Writer"),
            description = XmlContent.value(info, "Summary"),
            publisher = XmlContent.value(info, "Publisher"),
            pageCount = images.size,
            cover = cover,
        )
    }

    private fun u16(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun u32(bytes: ByteArray, offset: Int): Long =
        (0 until 4).fold(0L) { value, i -> value or ((bytes[offset + i].toLong() and 0xff) shl (8 * i)) }

    /** A small virtual archive; the compressed entry is read directly from the original source. */
    private class RarEntryInput(
        private val source: RemoteInput,
        private val prefix: ByteArray,
        private val entryOffset: Long,
        private val entryLength: Long,
        private val suffix: ByteArray,
    ) : ArchiveInput {
        override val size: Long = prefix.size.toLong() + entryLength + suffix.size
        override val readBufferSize: Int = 512 * 1024
        private var cursor = 0L

        @Synchronized
        override fun read(buffer: ByteArray): Int {
            if (cursor == size) return -1
            if (buffer.isEmpty()) return 0
            val count: Int
            when {
                cursor < prefix.size -> {
                    count = minOf(buffer.size.toLong(), prefix.size - cursor).toInt()
                    prefix.copyInto(buffer, 0, cursor.toInt(), cursor.toInt() + count)
                }
                cursor < prefix.size + entryLength -> {
                    val position = cursor - prefix.size
                    count = minOf(buffer.size.toLong(), entryLength - position, readBufferSize.toLong()).toInt()
                    source.bytes(entryOffset + position, count).copyInto(buffer)
                }
                else -> {
                    val position = (cursor - prefix.size - entryLength).toInt()
                    count = minOf(buffer.size, suffix.size - position)
                    suffix.copyInto(buffer, 0, position, position + count)
                }
            }
            cursor += count
            return count
        }

        @Synchronized
        override fun seek(offset: Long, whence: Int): Long {
            val target = when (whence) {
                0 -> offset
                1 -> cursor + offset
                2 -> size + offset
                else -> throw IOException("Origen de lectura desconocido")
            }
            if (target < 0 || target > size) throw IOException("Posición fuera del archivo")
            cursor = target
            return cursor
        }

        @Synchronized
        override fun position(): Long = cursor
    }
}
