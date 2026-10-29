package com.valerogarte.comicsreader.nativeapp.reader

import com.artifex.mupdf.fitz.SeekableInputStream
import java.io.IOException
import java.util.LinkedHashMap
import kotlin.math.min

fun interface RangeSource {
    fun read(offset: Long, length: Int): ByteArray
}

/** A memory-only preview transport. It cannot fetch a complete remote document. */
internal class RemoteInput(val size: Long, private val source: RangeSource) : SeekableInputStream {
    private var cursor = 0L
    private var transferred = 0L
    private val limit = min(16L * 1024 * 1024, (size - 1).coerceAtLeast(0))
    private val deadline = System.nanoTime() + 30_000_000_000L
    private val cached = LinkedHashMap<Pair<Long, Int>, ByteArray>()
    private val covered = ArrayList<LongRange>()

    @Synchronized
    fun bytes(offset: Long, length: Int): ByteArray {
        if (Thread.currentThread().isInterrupted) throw IOException("Lectura cancelada")
        if (System.nanoTime() > deadline) throw IOException("Tiempo de vista previa agotado")
        if (length < 0 || offset < 0 || offset > size || length.toLong() > size - offset) {
            throw IOException("Rango fuera del documento")
        }
        if (length == 0) return ByteArray(0)
        if (length > 4 * 1024 * 1024) throw IOException("Rango de vista previa demasiado grande")
        cached[offset to length]?.let { return it }
        cached.entries.firstOrNull { (key, _) -> offset >= key.first && offset + length <= key.first + key.second }
            ?.let { (key, data) -> return data.copyOfRange((offset - key.first).toInt(), (offset - key.first).toInt() + length) }
        if (transferred + length > limit) throw IOException("Límite de lectura de vista previa alcanzado")
        // The byte budget also prevents completing the document using overlapping requests.
        val union = (covered + listOf(offset until offset + length)).sortedBy { it.first }
        var end = -1L
        var total = 0L
        for (range in union) {
            if (range.first > end) total += range.last - range.first + 1
            else if (range.last > end) total += range.last - end
            end = maxOf(end, range.last)
        }
        if (total >= size) throw IOException("La vista previa no descarga el documento completo")
        val data = source.read(offset, length)
        if (data.size != length) throw IOException("Lectura SFTP incompleta")
        transferred += data.size
        covered += offset until offset + length
        cached[offset to length] = data
        return data
    }

    fun span(offset: Long, length: Int): ByteArray {
        if (length < 0 || length > 16 * 1024 * 1024) throw IOException("Vista previa demasiado grande")
        val result = ByteArray(length)
        var done = 0
        while (done < length) {
            val count = min(4 * 1024 * 1024, length - done)
            bytes(offset + done, count).copyInto(result, done)
            done += count
        }
        return result
    }

    @Synchronized
    override fun read(buffer: ByteArray): Int {
        if (cursor == size) return -1
        val length = min(min(buffer.size.toLong(), size - cursor), 64L * 1024).toInt()
        if (length == 0) return 0
        bytes(cursor, length).copyInto(buffer)
        cursor += length
        return length
    }

    @Synchronized
    override fun seek(offset: Long, whence: Int): Long {
        val target = when (whence) {
            0 -> offset
            1 -> cursor + offset
            2 -> size + offset
            else -> throw IOException("Origen de lectura desconocido")
        }
        if (target < 0 || target > size) throw IOException("Posición fuera del documento")
        cursor = target
        return target
    }

    @Synchronized
    override fun position(): Long = cursor
}
