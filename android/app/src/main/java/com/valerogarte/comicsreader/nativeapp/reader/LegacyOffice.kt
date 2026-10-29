package com.valerogarte.comicsreader.nativeapp.reader

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset

/** Reads documented OLE2/Word97 pieces and PowerPoint97 text records, then uses native text pagination. */
internal object LegacyOffice {
    fun inspectLocal(file: File, name: String = file.name): DocumentMetadata = CompoundFile(file).use { summary(it, name) }

    /** Metadata and first text page use stream slices; RemoteInput enforces the shared byte budget. */
    fun inspectRemote(name: String, input: RemoteInput): DocumentMetadata = CompoundFile(input).use { compound ->
        val metadata = runCatching { summary(compound, name) }.getOrElse { DocumentMetadata(baseTitle(name)) }
        val text = runCatching {
            clean(if (extension(name) == "doc") wordPreview(compound) else powerpointPreview(compound))
        }.getOrDefault("")
        if (text.isBlank()) metadata else metadata.copy(cover = runCatching {
            TextDocument(text).use { it.renderPage(0, 360) }
        }.getOrNull())
    }

    fun read(file: File, extension: String): String = CompoundFile(file).use { compound ->
        val text = if (extension == "doc") word(compound) else powerpoint(compound)
        clean(text)
            .ifBlank { throw IOException("El documento no contiene texto legible; conviértelo a PDF para conservar su diseño") }
    }

    private fun clean(value: String) = value.replace('\r', '\n').filter { it == '\n' || it == '\t' || it.code >= 32 }.trim()

    /** MS-OLEPS SummaryInformation: typed properties are relative to their property-set section. */
    private fun summary(compound: CompoundFile, name: String): DocumentMetadata {
        val fallback = DocumentMetadata(baseTitle(name))
        val stream = "\u0005SummaryInformation"
        if (!compound.hasStream(stream)) return fallback
        val length = compound.streamLength(stream)
        if (length !in 48..2L * 1024 * 1024) return fallback
        val data = compound.streamSlice(stream, 0, length.toInt())
        if (u16(data, 0) != 0xfffe || u32(data, 24) !in 1L..2L) return fallback
        val start = u32(data, 44)
        if (start < 48 || start > data.size - 8) return fallback
        val sectionSize = u32(data, start.toInt())
        val count = u32(data, start.toInt() + 4)
        if (sectionSize < 8 || start + sectionSize > data.size || count > 16_000 || 8 + count * 8 > sectionSize) return fallback
        val properties = HashMap<Long, Int>()
        repeat(count.toInt()) { index ->
            val at = start.toInt() + 8 + index * 8
            val relative = u32(data, at + 4)
            if (relative >= 8 + count * 8 && relative <= sectionSize - 4) properties[u32(data, at)] = (start + relative).toInt()
        }
        fun integer(id: Long): Int? = properties[id]?.let { at ->
            when (u16(data, at)) {
                2 -> if (at + 6 <= start + sectionSize) u16(data, at + 4) else null
                3 -> if (at + 8 <= start + sectionSize) u32(data, at + 4).toInt() else null
                else -> null
            }
        }
        val codePage = integer(1)?.and(0xffff) ?: 1252
        val charset = runCatching { Charset.forName(when (codePage) {
            1200 -> "UTF-16LE"; 1201 -> "UTF-16BE"; 65001 -> "UTF-8"; 10000 -> "x-MacRoman"; else -> "windows-$codePage"
        }) }.getOrDefault(Charset.forName("windows-1252"))
        fun text(id: Long): String = properties[id]?.let { at ->
            if (at + 8 > start + sectionSize) return@let ""
            val type = u16(data, at)
            val chars = u32(data, at + 4)
            val size = chars * if (type == 31) 2 else 1
            if (type !in setOf(30, 31) || size > 256 * 1024 || at + 8L + size > start + sectionSize) return@let ""
            String(data, at + 8, size.toInt(), if (type == 31) Charsets.UTF_16LE else charset).trimEnd('\u0000').trim()
        }.orEmpty()
        return fallback.copy(title = text(2).ifBlank { fallback.title }, author = text(4),
            description = text(6).ifBlank { text(3) }, pageCount = (integer(14) ?: 0).coerceIn(0, 100_000))
    }

    private fun wordPreview(compound: CompoundFile): String {
        val header = compound.streamSlice("WordDocument", 0, minOf(compound.streamLength("WordDocument"), 4096).toInt())
        if (header.size < 154 || u16(header, 0) != 0xa5ec) throw IOException("Documento Word binario no válido")
        val flags = u16(header, 10)
        if (flags and 0x100 != 0) throw IOException("Documento Word cifrado")
        var at = 32
        at += 2 + u16(header, at) * 2
        at += 2 + u16(header, at) * 4
        val pairs = u16(header, at); at += 2
        if (pairs <= 33) throw IOException("Versión Word no compatible")
        val tableOffset = u32(header, at + 33 * 8)
        val tableLength = u32(header, at + 33 * 8 + 4)
        if (tableLength !in 1L..1024L * 1024) throw IOException("Tabla Word demasiado grande para la vista previa")
        val table = compound.streamSlice(if (flags and 0x200 != 0) "1Table" else "0Table", tableOffset, tableLength.toInt())
        var pieceOffset = 0
        while (pieceOffset < table.size && table[pieceOffset].toInt() and 0xff == 1) pieceOffset += 3 + u16(table, pieceOffset + 1)
        if (pieceOffset + 5 > table.size || table[pieceOffset].toInt() and 0xff != 2) throw IOException("Tabla de piezas Word no válida")
        val length = u32(table, pieceOffset + 1).toInt()
        val start = pieceOffset + 5
        if (length < 4 || length > table.size - start || (length - 4) % 12 != 0) throw IOException("Tabla Word dañada")
        val count = (length - 4) / 12
        val pieces = start + (count + 1) * 4
        val output = StringBuilder()
        for (index in 0 until count) {
            if (output.length >= 16_000) break
            val available = u32(table, start + (index + 1) * 4) - u32(table, start + index * 4)
            if (available < 0) throw IOException("Orden de texto Word no válido")
            val chars = minOf(available, (16_000 - output.length).toLong()).toInt()
            val fc = u32(table, pieces + index * 8 + 2)
            val compressed = fc and 0x40000000L != 0L
            val offset = (fc and 0x3fffffffL) / if (compressed) 2 else 1
            val bytes = compound.streamSlice("WordDocument", offset, chars * if (compressed) 1 else 2)
            output.append(String(bytes, if (compressed) Charset.forName("windows-1252") else Charsets.UTF_16LE))
        }
        return output.toString()
    }

    private fun powerpointPreview(compound: CompoundFile): String {
        val stream = "PowerPoint Document"
        val output = StringBuilder()
        var recordsRead = 0
        fun records(start: Long, end: Long, depth: Int) {
            if (depth > 64) throw IOException("Presentación demasiado compleja")
            var offset = start
            while (offset <= end - 8 && output.length < 16_000 && recordsRead++ < 4000) {
                val header = compound.streamSlice(stream, offset, 8)
                val version = u16(header, 0) and 0xf
                val type = u16(header, 2)
                val length = u32(header, 4)
                if (length > end - offset - 8) throw IOException("Registro PowerPoint no válido")
                val next = offset + 8 + length
                if (version == 0xf) records(offset + 8, next, depth + 1)
                else if (type == 4000 || type == 4008) {
                    val maximum = (16_000 - output.length) * if (type == 4000) 2 else 1
                    val bytes = compound.streamSlice(stream, offset + 8, minOf(length, maximum.toLong()).toInt())
                    output.append(String(bytes, if (type == 4000) Charsets.UTF_16LE else Charset.forName("windows-1252")))
                    output.append('\n')
                }
                offset = next
            }
        }
        records(0, compound.streamLength(stream), 0)
        return output.toString()
    }

    private fun word(compound: CompoundFile): String {
        val word = compound.stream("WordDocument")
        if (word.size < 154 || u16(word, 0) != 0xA5EC) throw IOException("Documento Word binario no válido")
        val flags = u16(word, 10)
        if (flags and 0x100 != 0) throw IOException("Documento Word cifrado")
        var offset = 32
        val csw = u16(word, offset); offset += 2 + csw * 2
        val cslw = u16(word, offset); offset += 2 + cslw * 4
        val pairs = u16(word, offset); offset += 2
        if (pairs <= 33) throw IOException("Esta versión de Word necesita conversión a DOCX o PDF")
        val fcClx = u32(word, offset + 33 * 8).toInt()
        val lcbClx = u32(word, offset + 33 * 8 + 4).toInt()
        val table = compound.stream(if (flags and 0x200 != 0) "1Table" else "0Table")
        if (fcClx < 0 || lcbClx <= 0 || fcClx.toLong() + lcbClx > table.size) throw IOException("Tabla de texto Word no válida")
        var pieceOffset = fcClx
        val end = fcClx + lcbClx
        while (pieceOffset < end && table[pieceOffset].toInt() and 0xff == 1) {
            pieceOffset += 3 + u16(table, pieceOffset + 1)
        }
        if (pieceOffset + 5 > end || table[pieceOffset].toInt() and 0xff != 2) throw IOException("Tabla de piezas Word no válida")
        val length = u32(table, pieceOffset + 1).toInt()
        val start = pieceOffset + 5
        if (length < 4 || length > end - start || (length - 4) % 12 != 0) throw IOException("Tabla Word dañada")
        val count = (length - 4) / 12
        val pieces = start + (count + 1) * 4
        val output = StringBuilder()
        for (index in 0 until count) {
            val chars = u32(table, start + (index + 1) * 4) - u32(table, start + index * 4)
            if (chars !in 0..16_000_000 || output.length + chars > 16_000_000) throw IOException("Documento Word demasiado grande")
            val fc = u32(table, pieces + index * 8 + 2)
            val compressed = fc and 0x40000000L != 0L
            val position = (fc and 0x3fffffffL) / if (compressed) 2 else 1
            val bytes = chars * if (compressed) 1 else 2
            if (position < 0 || position + bytes > word.size) throw IOException("Texto Word fuera del documento")
            output.append(String(word, position.toInt(), bytes.toInt(), if (compressed) Charset.forName("windows-1252") else Charsets.UTF_16LE))
        }
        return output.toString()
    }

    private fun powerpoint(compound: CompoundFile): String {
        val data = compound.stream("PowerPoint Document")
        val output = StringBuilder()
        fun records(start: Int, end: Int, depth: Int) {
            if (depth > 64) throw IOException("Presentación demasiado compleja")
            var offset = start
            while (offset + 8 <= end) {
                val version = u16(data, offset) and 0xf
                val type = u16(data, offset + 2)
                val length = u32(data, offset + 4)
                if (length > end - offset - 8) throw IOException("Registro PowerPoint no válido")
                val next = offset + 8 + length.toInt()
                if (version == 0xf) records(offset + 8, next, depth + 1)
                else if (type == 4000 || type == 4008) {
                    if (output.length + length > 16_000_000) throw IOException("Presentación demasiado grande")
                    output.append(String(data, offset + 8, length.toInt(), if (type == 4000) Charsets.UTF_16LE else Charset.forName("windows-1252")))
                    output.append('\n')
                }
                offset = next
            }
        }
        records(0, data.size, 0)
        return output.toString()
    }
}

private interface CompoundInput : Closeable {
    val size: Long
    fun read(offset: Long, length: Int): ByteArray
}

private class LocalCompoundInput(file: File) : CompoundInput {
    private val file = RandomAccessFile(file, "r")
    override val size: Long get() = file.length()
    override fun read(offset: Long, length: Int) = ByteArray(length).also { file.seek(offset); file.readFully(it) }
    override fun close() = file.close()
}

private class RemoteCompoundInput(private val input: RemoteInput) : CompoundInput {
    override val size: Long get() = input.size
    override fun read(offset: Long, length: Int) = input.bytes(offset, length)
    override fun close() = Unit
}

/** Reads stream sectors lazily: previews never copy an entire OLE file or its root MiniStream. */
private class CompoundFile(private val input: CompoundInput) : Closeable {
    constructor(file: File) : this(LocalCompoundInput(file))
    constructor(input: RemoteInput) : this(RemoteCompoundInput(input))

    private data class Entry(val name: String, val type: Int, val sector: Int, val size: Long)
    private val header = try { input.read(0, 512) } catch (error: Throwable) { input.close(); throw error }
    private val sectorSize: Int
    private val miniSectorSize = 64
    private val sectorCount: Int
    private val fat: IntArray
    private val entries: List<Entry>
    private var miniFat: IntArray? = null

    init {
        try {
            if (!header.copyOfRange(0, 8).contentEquals(byteArrayOf(0xd0.toByte(), 0xcf.toByte(), 0x11, 0xe0.toByte(), 0xa1.toByte(), 0xb1.toByte(), 0x1a, 0xe1.toByte()))) throw IOException("El archivo no es OLE2")
            val shift = u16(header, 30)
            if (shift != 9 && shift != 12 || u16(header, 32) != 6 || u16(header, 28) != 0xfffe || u32(header, 56) != 4096L) throw IOException("Cabecera OLE2 no válida")
            sectorSize = 1 shl shift
            sectorCount = ((input.size - sectorSize) / sectorSize).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
            if (sectorCount !in 1..1_000_000) throw IOException("Archivo OLE2 demasiado grande")
            val fatSectors = ArrayList<Int>()
            for (i in 0 until 109) { val value = u32(header, 76 + i * 4).toInt(); if (value >= 0) fatSectors += value }
            var difat = u32(header, 68).toInt()
            val seen = HashSet<Int>()
            while (difat >= 0) {
                if (!seen.add(difat) || seen.size > sectorCount) throw IOException("Cadena DIFAT OLE2 cíclica")
                val data = sector(difat)
                for (i in 0 until sectorSize / 4 - 1) { val value = u32(data, i * 4).toInt(); if (value >= 0) fatSectors += value }
                difat = u32(data, sectorSize - 4).toInt()
            }
            if (fatSectors.size.toLong() * sectorSize / 4 > sectorCount.toLong() + sectorSize / 4) throw IOException("Tabla FAT OLE2 no válida")
            fat = IntArray(fatSectors.size * sectorSize / 4)
            for ((index, id) in fatSectors.withIndex()) { val bytes = sector(id); for (i in 0 until sectorSize / 4) fat[index * sectorSize / 4 + i] = u32(bytes, i * 4).toInt() }
            val directory = chain(u32(header, 48).toInt(), fat, 16 * 1024 * 1024)
            entries = (0 until directory.size / 128).mapNotNull { index ->
                val start = index * 128
                val type = directory[start + 66].toInt() and 0xff
                val nameSize = u16(directory, start + 64)
                if (type == 0 || nameSize !in 2..64 || nameSize % 2 != 0) null else Entry(
                    String(directory, start, nameSize - 2, Charsets.UTF_16LE), type,
                    u32(directory, start + 116).toInt(),
                    if (shift == 9) u32(directory, start + 120) else u32(directory, start + 120) + (u32(directory, start + 124) shl 32),
                )
            }
        } catch (error: Throwable) { input.close(); throw error }
    }

    private fun sector(id: Int): ByteArray {
        if (id < 0 || id >= sectorCount) throw IOException("Sector OLE2 fuera del archivo")
        return input.read((id.toLong() + 1) * sectorSize, sectorSize)
    }

    private fun chain(first: Int, allocation: IntArray, limit: Int): ByteArray {
        val output = ByteArrayOutputStream()
        val seen = HashSet<Int>()
        var id = first
        while (id >= 0) {
            if (Thread.currentThread().isInterrupted) throw IOException("Lectura cancelada")
            if (id >= allocation.size || !seen.add(id)) throw IOException("Cadena OLE2 no válida")
            val data = sector(id)
            if (output.size() + data.size > limit) throw IOException("Flujo OLE2 demasiado grande")
            output.write(data)
            id = allocation[id]
        }
        return output.toByteArray()
    }

    private fun miniAllocation(): IntArray = miniFat ?: chain(u32(header, 60).toInt(), fat, 4 * 1024 * 1024)
        .let { bytes -> IntArray(bytes.size / 4) { u32(bytes, it * 4).toInt() } }.also { miniFat = it }

    private fun entry(name: String) = entries.firstOrNull { it.type == 2 && it.name.equals(name, true) }
        ?: throw IOException("Falta el flujo OLE2 $name")

    fun hasStream(name: String) = entries.any { it.type == 2 && it.name.equals(name, true) }
    fun streamLength(name: String): Long = entry(name).size

    fun stream(name: String): ByteArray {
        val size = streamLength(name)
        if (size < 0 || size > 64L * 1024 * 1024) throw IOException("Flujo OLE2 demasiado grande")
        return streamSlice(name, 0, size.toInt())
    }

    fun streamSlice(name: String, offset: Long, length: Int): ByteArray {
        val entry = entry(name)
        require(offset >= 0 && length >= 0 && offset <= entry.size && length.toLong() <= entry.size - offset) { "Rango OLE2 inválido" }
        if (length > 64 * 1024 * 1024) throw IOException("Flujo OLE2 demasiado grande")
        return readChainSlice(entry.sector, if (entry.size < 4096) miniAllocation() else fat,
            offset, length, entry.size < 4096)
    }

    private fun readChainSlice(first: Int, allocation: IntArray, offset: Long, length: Int, mini: Boolean = false): ByteArray {
        if (length == 0) return ByteArray(0)
        val unit = if (mini) miniSectorSize else sectorSize
        val skip = offset / unit
        if (skip > allocation.size) throw IOException("Rango OLE2 fuera de la cadena")
        val seen = HashSet<Int>()
        var id = first
        repeat(skip.toInt()) {
            if (id < 0 || id >= allocation.size || !seen.add(id)) throw IOException("Cadena OLE2 no válida")
            id = allocation[id]
        }
        val output = ByteArray(length)
        var written = 0
        var within = (offset % unit).toInt()
        while (written < length) {
            if (Thread.currentThread().isInterrupted) throw IOException("Lectura cancelada")
            if (id < 0 || id >= allocation.size || !seen.add(id)) throw IOException("Flujo OLE2 incompleto")
            val count = minOf(unit - within, length - written)
            val bytes = if (mini) {
                val root = entries.firstOrNull { it.type == 5 } ?: throw IOException("Falta la raíz OLE2")
                val rootOffset = id.toLong() * miniSectorSize + within
                if (rootOffset > root.size || count > root.size - rootOffset) throw IOException("MiniSector OLE2 fuera del archivo")
                readChainSlice(root.sector, fat, rootOffset, count)
            } else {
                if (id >= sectorCount) throw IOException("Sector OLE2 fuera del archivo")
                input.read((id.toLong() + 1) * sectorSize + within, count)
            }
            bytes.copyInto(output, written)
            written += count
            within = 0
            id = allocation[id]
        }
        return output
    }

    override fun close() = input.close()
}

internal fun u16(bytes: ByteArray, offset: Int): Int {
    if (offset < 0 || offset > bytes.size - 2) throw IOException("Datos incompletos")
    return (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)
}
internal fun u32(bytes: ByteArray, offset: Int): Long {
    if (offset < 0 || offset > bytes.size - 4) throw IOException("Datos incompletos")
    return (u16(bytes, offset).toLong() or (u16(bytes, offset + 2).toLong() shl 16)) and 0xffffffffL
}
