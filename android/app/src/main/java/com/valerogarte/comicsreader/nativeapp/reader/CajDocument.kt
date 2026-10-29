package com.valerogarte.comicsreader.nativeapp.reader

import android.graphics.Bitmap
import java.io.File
import java.io.IOException

/** Native Rust conversion is restricted to an already imported/downloaded local document. */
internal class CajDocument(file: File, workDirectory: File, options: DocumentOptions = DocumentOptions()) : DocumentSession {
    private val output = File.createTempFile("caj-", ".pdf", workDirectory)
    private val document: MuPdfDocument
    init {
        try {
            val cjk = listOf("/system/fonts/NotoSerifCJK-Regular.ttc", "/system/fonts/NotoSansCJK-Regular.ttc", "/system/fonts/DroidSansFallback.ttf")
                .firstOrNull { File(it).isFile }.orEmpty()
            val latin = options.fontPath?.takeIf { File(it).isFile }
                ?: listOf("/system/fonts/NotoSerif-Regular.ttf", "/system/fonts/Roboto-Regular.ttf", "/system/fonts/RobotoStatic-Regular.ttf")
                    .firstOrNull { File(it).isFile }.orEmpty()
            val error = CajNative.convert(file.absolutePath, output.absolutePath, cjk, latin)
            if (error != null) throw IOException("No se puede leer este CAJ: $error")
            if (output.length() == 0L) throw IOException("La conversión CAJ no contiene páginas")
            document = MuPdfDocument(output, options)
        } catch (error: Throwable) { output.delete(); throw error }
    }
    override val pageCount: Int get() = document.pageCount
    override fun renderPage(index: Int, width: Int): Bitmap = document.renderPage(index, width)
    fun metadata(name: String): DocumentMetadata = document.metadata(name)
    override fun close() { try { document.close() } finally { output.delete() } }
}

internal object CajNative {
    init { System.loadLibrary("comics_caj") }
    @JvmStatic external fun convert(source: String, destination: String, cjkFont: String, latinFont: String): String?
}
