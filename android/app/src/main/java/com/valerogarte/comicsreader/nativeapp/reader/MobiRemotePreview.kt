package com.valerogarte.comicsreader.nativeapp.reader

import java.io.IOException
import java.nio.charset.Charset

/** PDB/EXTH metadata and one image record, independent of MOBI text compression. */
internal object MobiRemotePreview {
    fun inspect(name: String, input: RemoteInput): DocumentMetadata {
        if (input.size <= 78) return DocumentMetadata(baseTitle(name))
        val pdb = input.bytes(0, 78)
        val count = be16(pdb, 76)
        if (count !in 1..65535 || 78L + count * 8L >= input.size) throw IOException("Directorio MOBI no válido")
        val table = input.bytes(78, count * 8)
        val offsets = (0 until count).map { be32(table, it * 8) } + input.size
        if (offsets.first() < 78L + count * 8L || offsets.zipWithNext().any { (a, b) -> a > b || b > input.size })
            throw IOException("Registros MOBI fuera del documento")
        fun record(index: Int, limit: Int): ByteArray {
            if (index !in 0 until count) throw IOException("Registro MOBI fuera del documento")
            val length = offsets[index + 1] - offsets[index]
            if (length < 0 || length > limit) throw IOException("Registro MOBI demasiado grande para la vista previa")
            return input.span(offsets[index], length.toInt())
        }
        val first = parseHeader(record(0, 1024 * 1024), 0)
        val selected = first.values[121]?.firstOrNull()?.let { data ->
            if (data.size != 4) null else be32(data, 0).takeIf { it in 1 until count.toLong() }
        }?.let { index -> runCatching { parseHeader(record(index.toInt(), 1024 * 1024), index.toInt()) }.getOrNull() } ?: first
        val charset = if (selected.encoding == 1252L) Charset.forName("windows-1252") else Charsets.UTF_8
        fun metadata(tag: Int): String = (selected.values[tag] ?: first.values[tag]).orEmpty()
            .joinToString(", ") { TextContent.html(it.toString(charset).trimEnd('\u0000')) }.trim()
        val title = metadata(503).ifBlank { metadata(99) }.ifBlank { selected.title }.ifBlank {
            pdb.copyOfRange(0, 32).toString(Charset.forName("windows-1252")).substringBefore('\u0000')
        }.ifBlank { baseTitle(name) }
        // Hybrid files keep shared resources at the original MOBI7 image index.
        val imageStart = first.firstImage.takeIf { it in 0 until count.toLong() } ?: selected.firstImage
        val candidates = LinkedHashSet<Int>()
        for (tag in listOf(201, 202)) {
            (selected.values[tag] ?: first.values[tag])?.firstOrNull()?.takeIf { it.size == 4 }?.let { data ->
                (imageStart + be32(data, 0)).takeIf { it in 0 until count.toLong() }?.let { candidates += it.toInt() }
            }
        }
        if (imageStart in 0 until count.toLong()) {
            for (index in imageStart until minOf(imageStart + 16, count.toLong())) candidates += index.toInt()
        }
        var cover: android.graphics.Bitmap? = null
        for (index in candidates) {
            if (cover != null) break
            cover = runCatching {
                val size = offsets[index + 1] - offsets[index]
                if (size !in 4..12L * 1024 * 1024) return@runCatching null
                val magic = input.bytes(offsets[index], minOf(size, 8L).toInt())
                if (!imageMagic(magic)) return@runCatching null
                imageBitmap(record(index, 12 * 1024 * 1024), 360)
            }.getOrNull()
        }
        if (cover == null && selected.encryption == 0 && selected.compression in setOf(1, 2)) {
            cover = runCatching {
                val compressed = record(selected.recordIndex + 1, 256 * 1024)
                val bytes = if (selected.compression == 2) palmDoc(compressed) else compressed
                val text = TextContent.html(bytes.toString(charset).substringBefore('\u0000'))
                if (text.isBlank()) null else TextDocument(text).use { it.renderPage(0, 360) }
            }.getOrNull()
        }
        return DocumentMetadata(title, metadata(100), metadata(103), metadata(101), cover = cover)
    }

    private data class Header(
        val recordIndex: Int,
        val title: String = "",
        val encoding: Long = 65001,
        val firstImage: Long = 0xffffffffL,
        val compression: Int = 0,
        val encryption: Int = 0,
        val values: Map<Int, List<ByteArray>> = emptyMap(),
    )

    private fun parseHeader(data: ByteArray, recordIndex: Int): Header {
        if (data.size < 16) throw IOException("Cabecera MOBI incompleta")
        val compression = be16(data, 0)
        val encryption = be16(data, 12)
        if (data.size < 116 || data.copyOfRange(16, 20).toString(Charsets.US_ASCII) != "MOBI")
            return Header(recordIndex, compression = compression, encryption = encryption)
        val encoding = be32(data, 28)
        val charset = if (encoding == 1252L) Charset.forName("windows-1252") else Charsets.UTF_8
        val start = be32(data, 84)
        val length = be32(data, 88)
        val title = if (start in 0..data.size.toLong() && length <= data.size - start)
            data.copyOfRange(start.toInt(), (start + length).toInt()).toString(charset).trimEnd('\u0000') else ""
        val values = LinkedHashMap<Int, MutableList<ByteArray>>()
        val exth = 16L + be32(data, 20)
        if (exth <= data.size - 12 && data.copyOfRange(exth.toInt(), exth.toInt() + 4).toString(Charsets.US_ASCII) == "EXTH") {
            val end = exth + be32(data, exth.toInt() + 4)
            val count = be32(data, exth.toInt() + 8)
            if (end <= data.size && count <= 10000) {
                var cursor = exth + 12
                for (index in 0 until count.toInt()) {
                    if (cursor > end - 8) break
                    val tag = be32(data, cursor.toInt()).toInt()
                    val size = be32(data, cursor.toInt() + 4)
                    if (size < 8 || size > end - cursor) break
                    values.getOrPut(tag) { ArrayList() }.add(data.copyOfRange(cursor.toInt() + 8, (cursor + size).toInt()))
                    cursor += size
                }
            }
        }
        return Header(recordIndex, title, encoding, be32(data, 108), compression, encryption, values)
    }

    private fun imageMagic(data: ByteArray): Boolean =
        (data.size >= 3 && data[0] == 0xff.toByte() && data[1] == 0xd8.toByte() && data[2] == 0xff.toByte()) ||
            (data.size >= 4 && data[0] == 0x89.toByte() && data[1] == 'P'.code.toByte() && data[2] == 'N'.code.toByte() && data[3] == 'G'.code.toByte()) ||
            (data.size >= 4 && data.copyOfRange(0, 4).toString(Charsets.US_ASCII) == "GIF8") ||
            (data.size >= 2 && data[0] == 'B'.code.toByte() && data[1] == 'M'.code.toByte())

    private fun palmDoc(input: ByteArray): ByteArray {
        val output = ArrayList<Byte>(8192)
        var index = 0
        while (index < input.size && output.size < 64 * 1024) {
            val value = input[index++].toInt() and 0xff
            when {
                value in 1..8 -> {
                    if (index + value > input.size) throw IOException("Texto PalmDOC incompleto")
                    repeat(value) { output += input[index++] }
                }
                value <= 0x7f -> output += value.toByte()
                value >= 0xc0 -> { output += 32.toByte(); output += (value xor 0x80).toByte() }
                else -> {
                    if (index >= input.size) throw IOException("Texto PalmDOC incompleto")
                    val pair = ((value and 0x3f) shl 8) or (input[index++].toInt() and 0xff)
                    val distance = pair shr 3
                    if (distance !in 1..output.size) throw IOException("Referencia PalmDOC no válida")
                    repeat((pair and 7) + 3) { output += output[output.size - distance] }
                }
            }
        }
        return output.toByteArray()
    }

    private fun be16(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xff) shl 8) or (data[offset + 1].toInt() and 0xff)

    private fun be32(data: ByteArray, offset: Int): Long {
        var value = 0L
        repeat(4) { value = (value shl 8) or (data[offset + it].toLong() and 0xff) }
        return value
    }
}
