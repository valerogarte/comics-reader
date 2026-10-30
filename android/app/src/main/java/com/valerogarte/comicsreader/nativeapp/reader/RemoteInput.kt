package com.valerogarte.comicsreader.nativeapp.reader

import com.artifex.mupdf.fitz.SeekableInputStream
import java.io.IOException
import java.util.TreeMap
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
    private val cached = TreeMap<Long, ByteArray>()

    @Synchronized
    fun bytes(offset: Long, length: Int): ByteArray {
        checkAvailable()
        if (length < 0 || offset < 0 || offset > size || length.toLong() > size - offset) {
            throw IOException("Rango fuera del documento")
        }
        if (length == 0) return ByteArray(0)
        if (length > 4 * 1024 * 1024) throw IOException("Rango de vista previa demasiado grande")
        val end = offset + length
        cached.floorEntry(offset)?.let { (start, data) ->
            if (end <= start + data.size) {
                if (offset == start && length == data.size) return data
                val begin = (offset - start).toInt()
                return data.copyOfRange(begin, begin + length)
            }
        }

        // Blocks never overlap: transferred also counts unique cached bytes. The size - 1
        // limit prevents completing a document, even across many adjacent requests.
        var position = offset
        var missing = 0L
        while (position < end) {
            checkAvailable()
            val block = cached.floorEntry(position)
            val blockEnd = block?.let { it.key + it.value.size } ?: position
            if (blockEnd > position) position = min(end, blockEnd)
            else {
                val gapEnd = min(end, cached.ceilingKey(position) ?: end)
                missing += gapEnd - position
                position = gapEnd
            }
        }
        if (transferred + missing > limit) throw IOException("Límite de lectura de vista previa alcanzado")
        if (missing == length.toLong()) return fetch(offset, length)

        val result = ByteArray(length)
        position = offset
        while (position < end) {
            checkAvailable()
            val block = cached.floorEntry(position)
            val blockEnd = block?.let { it.key + it.value.size } ?: position
            if (block != null && blockEnd > position) {
                val count = (min(end, blockEnd) - position).toInt()
                val begin = (position - block.key).toInt()
                block.value.copyInto(result, (position - offset).toInt(), begin, begin + count)
                position += count
            } else {
                val gapEnd = min(end, cached.ceilingKey(position) ?: end)
                val count = (gapEnd - position).toInt()
                fetch(position, count).copyInto(result, (position - offset).toInt())
                position = gapEnd
            }
        }
        return result
    }

    private fun checkAvailable() {
        if (Thread.currentThread().isInterrupted) throw IOException("Lectura cancelada")
        if (System.nanoTime() > deadline) throw IOException("Tiempo de vista previa agotado")
    }

    /** Called under the monitor, only for a gap already checked against the byte budget. */
    private fun fetch(offset: Long, length: Int): ByteArray {
        checkAvailable()
        val data = source.read(offset, length)
        if (data.size != length) throw IOException("Lectura SFTP incompleta")
        cached[offset] = data
        transferred += data.size
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
