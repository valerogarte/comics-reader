package com.valerogarte.comicsreader.nativeapp.reader

import android.net.Uri
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.util.zip.CRC32
import java.util.zip.Inflater

/** Read just the ZIP directory, metadata entries and one image/text page from a remote book. */
internal object RemoteBookPreview {
    /** Index ZIP comics once and fetch only ComicInfo and the selected cover. */
    fun comic(name: String, input: RemoteInput): DocumentMetadata {
        val zip = RemoteZip(input)
        val images = zip.entries.filter { imageExtension.containsMatchIn(it.name) && !it.name.startsWith("__MACOSX/") }
            .sortedWith(compareBy(naturalOrder) { it.name })
        if (images.isEmpty()) throw IOException("El archivo no contiene páginas de imágenes")
        val info = zip.entries.firstOrNull { it.name.substringAfterLast('/').equals("ComicInfo.xml", true) }
            ?.let { entry -> optionalComicPart { XmlContent.parse(zip.read(entry, 1024 * 1024)) } }
        val metadataIndex = info?.descendants("Page")?.firstOrNull { it.attr("Type").equals("FrontCover", true) }
            ?.attr("Image")?.toIntOrNull()
        val coverIndex = comicCoverIndex(name, images.map { it.name }, metadataIndex)
        fun cover(index: Int) = optionalComicPart { imageBitmap(zip.read(images[index], 16 * 1024 * 1024), 360) }
        return DocumentMetadata(
            title = XmlContent.value(info, "Title").ifBlank { baseTitle(name) },
            author = XmlContent.value(info, "Writer"),
            description = XmlContent.value(info, "Summary"),
            publisher = XmlContent.value(info, "Publisher"),
            pageCount = images.size,
            cover = cover(coverIndex) ?: if (coverIndex != 0) cover(0) else null,
        )
    }

    private inline fun <T> optionalComicPart(read: () -> T): T? = try { read() }
    catch (unsupported: UnsupportedZipPreviewException) { throw unsupported }
    catch (_: Exception) { null }

    fun inspect(name: String, input: RemoteInput): DocumentMetadata {
        val zip = RemoteZip(input)
        return when (extension(name)) {
            "epub" -> epub(name, zip)
            "kdz" -> {
                // A KDZ contains a complete nested book. Never inflate that book while browsing.
                val cover = zip.entries.firstOrNull { it.name.substringAfterLast('/').lowercase() in setOf("cover.png", "cover.jpg", "cover.jpeg", "cover.webp") }
                    ?.let { runCatching { imageBitmap(zip.read(it, 8 * 1024 * 1024), 360) }.getOrNull() }
                DocumentMetadata(baseTitle(name), cover = cover)
            }
            else -> office(name, zip)
        }
    }

    private fun epub(name: String, zip: RemoteZip): DocumentMetadata {
        val container = XmlContent.parse(zip.read("META-INF/container.xml", 256 * 1024))
        val opfPath = container.first("rootfile")?.attr("full-path").orEmpty()
        if (opfPath.isBlank()) throw IOException("EPUB sin manifiesto")
        val opf = XmlContent.parse(zip.read(opfPath, 1024 * 1024))
        val metadata = opf.first("metadata")
        val items = opf.first("manifest")?.descendants("item").orEmpty()
        val coverId = metadata?.descendants("meta")?.firstOrNull { it.attr("name").equals("cover", true) }?.attr("content")
        val coverItem = items.firstOrNull { it.attr("properties").split(' ').contains("cover-image") }
            ?: items.firstOrNull { !coverId.isNullOrBlank() && it.attr("id") == coverId }
        var cover = coverItem?.attr("href")?.let { resolveArchivePath(opfPath, it) }
            ?.let { path -> runCatching { imageBitmap(zip.read(path, 12 * 1024 * 1024), 360) }.getOrNull() }
        if (cover == null) {
            val firstId = opf.first("spine")?.descendants("itemref")?.firstOrNull { it.attr("linear") != "no" }?.attr("idref")
            val first = items.firstOrNull { it.attr("id") == firstId }
            val firstPath = first?.attr("href")?.let { resolveArchivePath(opfPath, it) }
            if (firstPath != null) {
                val chapter = runCatching { TextContent.decode(zip.read(firstPath, 1024 * 1024)) }.getOrNull()
                if (chapter != null) {
                    val image = Regex("<(?:[a-z]+:)?(?:img|image)\\b[^>]*(?:src|href|xlink:href)\\s*=\\s*[\"']([^\"']+)[\"']", RegexOption.IGNORE_CASE)
                        .find(chapter)?.groupValues?.get(1)
                    cover = image?.let { resolveArchivePath(firstPath, it) }
                        ?.let { path -> runCatching { imageBitmap(zip.read(path, 12 * 1024 * 1024), 360) }.getOrNull() }
                    if (cover == null) {
                        val text = TextContent.html(chapter)
                        if (text.isNotBlank()) TextDocument(text).use { cover = it.renderPage(0, 360) }
                    }
                }
            }
        }
        return DocumentMetadata(
            title = XmlContent.value(metadata, "title").ifBlank { baseTitle(name) },
            author = metadata?.descendants("creator")?.joinToString(", ") { it.allText() }.orEmpty(),
            description = TextContent.html(XmlContent.value(metadata, "description")),
            publisher = XmlContent.value(metadata, "publisher"),
            cover = cover,
        )
    }

    private fun office(name: String, zip: RemoteZip): DocumentMetadata {
        val core = zip.entries.firstOrNull { it.name.equals("docProps/core.xml", true) }
            ?.let { runCatching { XmlContent.parse(zip.read(it, 1024 * 1024)) }.getOrNull() }
        val properties = zip.entries.firstOrNull { it.name.equals("docProps/app.xml", true) }
            ?.let { runCatching { XmlContent.parse(zip.read(it, 1024 * 1024)) }.getOrNull() }
        var cover = zip.entries.firstOrNull { it.name.startsWith("docProps/", true) && it.name.substringAfterLast('/').startsWith("thumbnail", true) && imageExtension.containsMatchIn(it.name) }
            ?.let { runCatching { imageBitmap(zip.read(it, 8 * 1024 * 1024), 360) }.getOrNull() }
        if (cover == null) {
            val page = zip.entries.firstOrNull { it.name.equals("word/document.xml", true) }
                ?: zip.entries.filter { it.name.matches(Regex("ppt/slides/slide\\d+\\.xml", RegexOption.IGNORE_CASE)) }.minWithOrNull(compareBy(naturalOrder) { it.name })
            val text = page?.let { runCatching { XmlContent.parse(zip.read(it, 2 * 1024 * 1024)).descendants("t").joinToString(" ") { node -> node.allText() } }.getOrNull() }
            if (!text.isNullOrBlank()) TextDocument(text).use { cover = it.renderPage(0, 360) }
        }
        return DocumentMetadata(
            title = XmlContent.value(core, "title").ifBlank { baseTitle(name) },
            author = XmlContent.value(core, "creator"),
            description = XmlContent.value(core, "description"),
            publisher = XmlContent.value(properties, "Company"),
            pageCount = (XmlContent.value(properties, "Pages").ifBlank { XmlContent.value(properties, "Slides") }).toIntOrNull()?.coerceIn(0, 100_000) ?: 0,
            cover = cover,
        )
    }
}

internal fun resolveArchivePath(base: String, href: String): String? {
    val path = Uri.decode(href.substringBefore('#').substringBefore('?'))
    if (path.isBlank() || path.startsWith('/') || path.contains(':') || path.contains('\\') || path.contains('\u0000')) return null
    val components = ArrayList<String>()
    val joined = base.substringBeforeLast('/', "").let { if (it.isEmpty()) path else "$it/$path" }
    for (component in joined.split('/')) when (component) {
        "", "." -> Unit
        ".." -> if (components.isEmpty()) return null else components.removeAt(components.lastIndex)
        else -> components += component
    }
    return components.joinToString("/")
}

internal data class RemoteZipEntry(val name: String, val method: Int, val compressedSize: Int, val size: Int, val crc: Long, val offset: Long)

/** Only unsupported ZIP features can use libarchive as a second decoder. */
internal class UnsupportedZipPreviewException(message: String) : IOException(message)

internal class RemoteZip(private val input: RemoteInput) {
    val entries: List<RemoteZipEntry>
    init {
        if (input.size < 22) throw IOException("ZIP incompleto")
        var tailOffset = input.size - 22
        var tail = input.bytes(tailOffset, 22)
        var eocd = 0
        if (u32(tail, 0) != 0x06054b50L) {
            val tailSize = minOf(65557L, (input.size / 4).coerceAtLeast(22)).toInt()
            tailOffset = input.size - tailSize
            tail = input.bytes(tailOffset, tailSize)
            eocd = (tail.size - 22 downTo 0).firstOrNull { u32(tail, it) == 0x06054b50L && it + 22 + u16(tail, it + 20) == tail.size }
                ?: throw IOException("Directorio ZIP no encontrado")
        }
        if (u16(tail, eocd + 4) != 0 || u16(tail, eocd + 6) != 0) throw IOException("ZIP dividido no compatible")
        val count = u16(tail, eocd + 10)
        val length = u32(tail, eocd + 12)
        val offset = u32(tail, eocd + 16)
        if (count == 65535 || length == 0xffffffffL || offset == 0xffffffffL) throw UnsupportedZipPreviewException("Directorio ZIP64")
        if (length > 4 * 1024 * 1024) throw IOException("Directorio ZIP demasiado grande")
        if (offset + length > tailOffset + eocd) throw IOException("Directorio ZIP fuera del documento")
        val directory = input.bytes(offset, length.toInt())
        val result = ArrayList<RemoteZipEntry>()
        var position = 0
        repeat(count) {
            if (u32(directory, position) != 0x02014b50L) throw IOException("Directorio ZIP dañado")
            val flags = u16(directory, position + 8)
            val method = u16(directory, position + 10)
            val nameLength = u16(directory, position + 28)
            val extraLength = u16(directory, position + 30)
            val commentLength = u16(directory, position + 32)
            val end = position.toLong() + 46 + nameLength + extraLength + commentLength
            if (end > directory.size) throw IOException("Directorio ZIP incompleto")
            val name = String(directory, position + 46, nameLength, if (flags and 0x800 != 0) Charsets.UTF_8 else Charset.forName("CP437"))
            val compressed = u32(directory, position + 20)
            val expanded = u32(directory, position + 24)
            val localOffset = u32(directory, position + 42)
            if (compressed == 0xffffffffL || expanded == 0xffffffffL || localOffset == 0xffffffffL) {
                throw UnsupportedZipPreviewException("Entrada ZIP64")
            }
            if (flags and 1 == 0 && compressed <= Int.MAX_VALUE && expanded <= Int.MAX_VALUE) {
                result += RemoteZipEntry(name, method, compressed.toInt(), expanded.toInt(), u32(directory, position + 16), localOffset)
            }
            position = end.toInt()
        }
        entries = result
    }
    fun read(name: String, limit: Int): ByteArray = read(entries.firstOrNull { it.name == name } ?: throw IOException("Falta $name"), limit)
    fun read(entry: RemoteZipEntry, limit: Int): ByteArray {
        if (entry.method != 0 && entry.method != 8) throw UnsupportedZipPreviewException("Compresión ZIP no compatible")
        if (entry.size !in 0..limit || entry.compressedSize !in 0..16 * 1024 * 1024) throw IOException("Entrada ZIP demasiado grande")
        val header = input.bytes(entry.offset, 30)
        if (u32(header, 0) != 0x04034b50L || u16(header, 6) and 1 != 0) throw IOException("Cabecera ZIP no válida")
        val offset = entry.offset + 30 + u16(header, 26) + u16(header, 28)
        val bytes = input.span(offset, entry.compressedSize)
        val decoded = when (entry.method) {
            0 -> bytes
            8 -> {
                val inflater = Inflater(true)
                try {
                    inflater.setInput(bytes)
                    val output = ByteArrayOutputStream(minOf(entry.size, 1024 * 1024))
                    val buffer = ByteArray(16 * 1024)
                    while (!inflater.finished()) {
                        val count = inflater.inflate(buffer)
                        if (count == 0) throw IOException("Contenido ZIP incompleto")
                        if (output.size() + count > entry.size || output.size() + count > limit) throw IOException("Contenido ZIP demasiado grande")
                        output.write(buffer, 0, count)
                    }
                    output.toByteArray()
                } finally { inflater.end() }
            }
            else -> throw IOException("Compresión ZIP no compatible")
        }
        if (decoded.size != entry.size || CRC32().apply { update(decoded) }.value != entry.crc) throw IOException("Contenido ZIP dañado")
        return decoded
    }
}
