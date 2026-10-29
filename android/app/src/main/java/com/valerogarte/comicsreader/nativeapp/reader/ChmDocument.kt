package com.valerogarte.comicsreader.nativeapp.reader

import android.graphics.Bitmap
import org.jchmlib.ChmFile
import org.jchmlib.ChmTopicsTree
import org.jchmlib.ChmUnitInfo
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.Collections
import java.util.IdentityHashMap

/** Compiled HTML books are decoded locally (Java LZX) and laid out by Android text APIs. */
internal class ChmDocument(file: File, options: DocumentOptions = DocumentOptions()) : DocumentSession {
    private val content = load(file)
    private val text = TextDocument(content.text, options)
    override val pageCount: Int get() = text.pageCount
    override val isReflowable: Boolean get() = true
    override fun renderPage(index: Int, width: Int): Bitmap = text.renderPage(index, width)
    fun metadata(name: String = fileName): DocumentMetadata = DocumentMetadata(
        title = content.title.ifBlank { baseTitle(name) }, pageCount = pageCount,
        cover = content.cover ?: runCatching { renderPage(0, 360) }.getOrNull(),
    )
    private val fileName = file.name
    override fun close() = text.close()

    private data class Content(val title: String, val text: String, val cover: Bitmap?)

    companion object {
        private fun load(file: File): Content {
            val chm = ChmFile(file.absolutePath)
            try {
                val paths = LinkedHashSet<String>()
                chm.homeFile?.takeIf { it.isNotBlank() }?.let { paths += it }
                val seen = Collections.newSetFromMap(IdentityHashMap<ChmTopicsTree, Boolean>())
                fun addTopics(node: ChmTopicsTree?, depth: Int = 0) {
                    if (node == null || !seen.add(node)) return
                    if (depth > 128 || seen.size > 100_000) throw IOException("Índice CHM demasiado complejo")
                    node.path?.takeIf { it.isNotBlank() }?.let { paths += it.substringBefore('#') }
                    for (child in node.children) addTopics(child, depth + 1)
                }
                runCatching { addTopics(chm.topicsTree) }
                val units = LinkedHashMap<String, ChmUnitInfo>()
                chm.enumerate(ChmFile.CHM_ENUMERATE_NORMAL or ChmFile.CHM_ENUMERATE_FILES) { unit ->
                    if (units.size >= 100_000) throw IOException("Demasiadas entradas CHM")
                    if (extension(unit.path) in setOf("htm", "html", "xhtml", "txt")) units[unit.path] = unit
                }
                if (paths.size <= 1) paths += units.keys.sortedWith(naturalOrder)
                var cover: Bitmap? = null
                val result = StringBuilder()
                val charset = runCatching { Charset.forName(chm.encoding) }.getOrDefault(Charsets.UTF_8)
                var totalBytes = 0L
                for (path in paths) {
                    if (paths.size > 10_000) throw IOException("Demasiados capítulos CHM")
                    val unit = chm.resolveObject(if (path.startsWith('/')) path else "/$path") ?: continue
                    if (unit.length <= 0 || unit.length > 8 * 1024 * 1024) throw IOException("Capítulo CHM demasiado grande")
                    totalBytes += unit.length
                    if (totalBytes > 64 * 1024 * 1024) throw IOException("Contenido CHM demasiado grande")
                    val bytes = chm.retrieveObject(unit)?.let { data -> ByteArray(data.remaining()).also { data.get(it) } } ?: continue
                    val html = String(bytes, charset)
                    if (cover == null && result.isEmpty()) {
                        val image = Regex("<img\\b[^>]*src\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE).find(html)?.groupValues?.get(1)
                        val resolved = image?.let { resolveArchivePath(path.removePrefix("/"), it) }
                        val imageUnit = resolved?.let { chm.resolveObject("/$it") }
                        if (imageUnit != null && imageUnit.length in 1..8 * 1024 * 1024) {
                            cover = runCatching {
                                chm.retrieveObject(imageUnit)?.let { data -> imageBitmap(ByteArray(data.remaining()).also { data.get(it) }, 360) }
                            }.getOrNull()
                        }
                    }
                    val text = if (extension(path) == "txt") html else TextContent.html(html)
                    if (result.length.toLong() + text.length > 16_000_000) throw IOException("Texto CHM demasiado grande")
                    if (text.isNotBlank()) result.append(text).append("\n\n")
                }
                if (result.isBlank()) throw IOException("El CHM no contiene capítulos de texto legibles")
                return Content(chm.title.orEmpty(), result.toString().trim(), cover)
            } finally {
                // jchmlib 0.5.4 owns this RandomAccessFile but has no public close() API.
                // Its pinned field is kept in proguard-rules.pro so handles are released immediately.
                val field = ChmFile::class.java.getDeclaredField("rf").apply { isAccessible = true }
                (field.get(chm) as? RandomAccessFile)?.close()
            }
        }
    }
}
