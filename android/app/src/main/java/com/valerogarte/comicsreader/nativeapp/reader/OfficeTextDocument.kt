package com.valerogarte.comicsreader.nativeapp.reader

import android.graphics.Bitmap
import java.io.File
import java.io.IOException

/** Native text fallback for valid OOXML variants the fixed MuPDF version cannot render. */
internal class OfficeTextDocument(file: File, options: DocumentOptions = DocumentOptions()) : DocumentSession {
    private val archive = BookArchive(file = file)
    private val entries = archive.entries().map { it.name }.toSet()
    private val core = if ("docProps/core.xml" in entries) runCatching { xml("docProps/core.xml") }.getOrNull() else null
    private val content = if (extension(file.name) == "xlsx") spreadsheet() else word()
    private val document = TextDocument(content.ifBlank { throw IOException("El documento no contiene texto legible") }, options)
    override val pageCount: Int get() = document.pageCount
    override val isReflowable: Boolean get() = true
    override fun renderPage(index: Int, width: Int): Bitmap = document.renderPage(index, width)
    fun metadata(name: String): DocumentMetadata = DocumentMetadata(
        title = XmlContent.value(core, "title").ifBlank { baseTitle(name) },
        author = XmlContent.value(core, "creator"), description = XmlContent.value(core, "description"),
        pageCount = pageCount,
    )
    override fun close() = document.close()

    private fun xml(path: String): XmlNode = XmlContent.parse(archive.read(path, 4 * 1024 * 1024))
    private fun word(): String = xml("word/document.xml").descendants("p")
        .joinToString("\n\n") { paragraph -> paragraph.descendants("t").joinToString("") { it.allText() } }

    private fun spreadsheet(): String {
        val shared = if ("xl/sharedStrings.xml" in entries) xml("xl/sharedStrings.xml").descendants("si").map { it.allText() } else emptyList()
        val workbook = xml("xl/workbook.xml")
        val relations = if ("xl/_rels/workbook.xml.rels" in entries) xml("xl/_rels/workbook.xml.rels").descendants("Relationship").associate { it.attr("Id") to it.attr("Target") } else emptyMap()
        val sheets = workbook.descendants("sheet").mapNotNull { sheet ->
            val target = relations[sheet.attr("id")] ?: return@mapNotNull null
            val path = if (target.startsWith('/')) resolveArchivePath("", target.removePrefix("/")) else resolveArchivePath("xl/workbook.xml", target)
            path?.let { sheet.attr("name") to it }
        }.ifEmpty {
            entries.filter { it.matches(Regex("xl/worksheets/sheet\\d+\\.xml")) }.sortedWith(naturalOrder).map { "" to it }
        }
        val result = StringBuilder()
        for ((name, path) in sheets) {
            if (Thread.currentThread().isInterrupted) throw IOException("Lectura cancelada")
            if (name.isNotBlank()) result.append(name).append("\n\n")
            for (row in xml(path).descendants("row")) {
                val cells = row.descendants("c").map { cell ->
                    val value = cell.first("v")?.allText().orEmpty()
                    when (cell.attr("t")) {
                        "inlineStr" -> cell.first("is")?.descendants("t")?.joinToString("") { it.allText() }.orEmpty()
                        "s" -> value.toIntOrNull()?.let { shared.getOrNull(it) }.orEmpty()
                        "b" -> if (value == "1") "true" else "false"
                        else -> value.ifBlank { cell.first("f")?.allText()?.let { "=$it" }.orEmpty() }
                    }
                }
                result.append(cells.joinToString("\t")).append('\n')
                if (result.length > 16_000_000) throw IOException("El documento contiene demasiado texto")
            }
            result.append("\n\n")
        }
        return result.toString().trim()
    }
}
