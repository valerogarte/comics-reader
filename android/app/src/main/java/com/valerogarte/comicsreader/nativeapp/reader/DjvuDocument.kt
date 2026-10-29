package com.valerogarte.comicsreader.nativeapp.reader

import android.graphics.Bitmap
import androidx.annotation.Keep
import java.io.File
import java.io.IOException

/** DjVuLibre/JNI decoding and rendering; the document stays in app-private storage. */
internal class DjvuDocument(file: File) : DocumentSession {
    private var handle: Long = NativeDjvu.open(file.absolutePath)
    override val pageCount: Int = try {
        NativeDjvu.pageCount(handle).also {
            if (it !in 1..100_000) throw IOException("El documento DjVu no contiene páginas válidas")
        }
    } catch (error: Throwable) {
        NativeDjvu.close(handle)
        handle = 0
        throw error
    }

    @Synchronized
    override fun renderPage(index: Int, width: Int): Bitmap {
        if (handle == 0L) throw IOException("Documento DjVu cerrado")
        require(index in 0 until pageCount) { "Página fuera del documento" }
        return NativeDjvu.render(handle, index, width.coerceIn(180, 2048))
    }

    @Synchronized
    override fun close() {
        if (handle != 0L) {
            NativeDjvu.close(handle)
            handle = 0
        }
    }
}

@Keep
private object NativeDjvu {
    init { System.loadLibrary("comics_djvu") }
    external fun open(path: String): Long
    external fun pageCount(handle: Long): Int
    external fun render(handle: Long, index: Int, width: Int): Bitmap
    external fun close(handle: Long)
}
