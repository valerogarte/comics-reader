package com.valerogarte.comicsreader.nativeapp.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import androidx.core.graphics.PathParser
import java.io.File
import java.io.IOException
import kotlin.math.roundToInt

/** OFD fixed pages rendered directly by Android Canvas, using the document's millimetre coordinates. */
internal class OfdDocument(
    private val archive: BookArchive,
    private val documentName: String,
    private val options: DocumentOptions = DocumentOptions(),
    private val workDirectory: File? = null,
) : DocumentSession {
    constructor(file: File, options: DocumentOptions = DocumentOptions(), workDirectory: File? = null) :
        this(BookArchive(file = file), file.name, options, workDirectory)
    private val names = archive.entries().map { it.name }.toSet()
    private val entrancePath = names.firstOrNull { it.equals("OFD.xml", true) }
        ?: throw IOException("El archivo OFD no contiene OFD.xml")
    private val entrance = xml(entrancePath)
    private val body = entrance.first("DocBody") ?: throw IOException("Documento OFD sin contenido")
    private val documentPath = resolve(entrancePath, XmlContent.value(body, "DocRoot"))
    private val document = xml(documentPath)
    private val common = document.first("CommonData")
    private val defaultBox = box(common?.first("PageArea")?.first("PhysicalBox")?.allText().orEmpty())
        ?: RectF(0f, 0f, 210f, 297f)
    private val pages = document.first("Pages")?.children?.filter { it.name == "Page" }
        ?.map { resolve(documentPath, it.attr("BaseLoc")) }.orEmpty()
    private val templates = common?.children?.filter { it.name == "TemplatePage" }
        ?.associateBy { it.attr("ID") }.orEmpty()
    private val globalResources = Resources()
    private val fontCache = HashMap<String, Typeface>()
    private val temporaryFonts = ArrayList<File>()
    private var closed = false

    init {
        if (pages.isEmpty() || pages.size > 100_000) throw IOException("El archivo OFD no contiene páginas válidas")
        common?.children?.filter { it.name == "PublicRes" || it.name == "DocumentRes" }?.forEach {
            readResources(resolve(documentPath, it.allText()), globalResources)
        }
    }

    override val pageCount: Int get() = pages.size

    @Synchronized
    override fun renderPage(index: Int, width: Int): Bitmap {
        check(!closed) { "Documento cerrado" }
        require(index in pages.indices) { "Página fuera del documento" }
        val page = xml(pages[index])
        val area = page.first("Area")?.first("PhysicalBox")?.allText()?.let(::box) ?: defaultBox
        require(area.width() > 0f && area.height() > 0f) { "Tamaño de página OFD inválido" }
        val bitmapWidth = width.coerceIn(180, 2048)
        val scale = minOf(bitmapWidth / area.width(), 4096f / area.height())
        val bitmap = Bitmap.createBitmap((area.width() * scale).roundToInt().coerceAtLeast(1),
            (area.height() * scale).roundToInt().coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(bitmap)
            canvas.drawColor(Color.WHITE)
            canvas.scale(scale, scale)
            canvas.translate(-area.left, -area.top)
            renderContents(canvas, page, pages[index], globalResources, HashSet(), 0)
            return bitmap
        } catch (error: Throwable) { bitmap.recycle(); throw error }
    }

    fun metadata(name: String = documentName): DocumentMetadata {
        val info = body.first("DocInfo")
        return DocumentMetadata(
            title = XmlContent.value(info, "Title").ifBlank { baseTitle(name) },
            author = XmlContent.value(info, "Author"),
            description = XmlContent.value(info, "Abstract"),
            publisher = XmlContent.value(info, "Creator"),
            pageCount = pageCount,
            cover = runCatching { renderPage(0, 360) }.getOrNull(),
        )
    }

    private fun renderContents(canvas: Canvas, page: XmlNode, pagePath: String, inherited: Resources, visited: MutableSet<String>, depth: Int) {
        if (depth > 8 || !visited.add(pagePath)) return
        try {
            val resources = inherited.copy()
            page.children.filter { it.name == "PageRes" }.forEach { readResources(resolve(pagePath, it.allText()), resources) }
            fun templates(foreground: Boolean) {
                page.children.filter { it.name == "Template" }.forEach { reference ->
                    val template = templates[reference.attr("TemplateID")] ?: return@forEach
                    val order = reference.attr("ZOrder").ifBlank { template.attr("ZOrder") }
                    if (order.equals("Foreground", true) != foreground) return@forEach
                    val path = resolve(documentPath, template.attr("BaseLoc"))
                    renderContents(canvas, xml(path), path, resources, visited, depth + 1)
                }
            }
            templates(false)
            page.first("Content")?.children?.forEach { drawNode(canvas, it, resources, emptyMap(), 0) }
            templates(true)
        } finally { visited.remove(pagePath) }
    }

    private fun drawNode(canvas: Canvas, node: XmlNode, resources: Resources, inherited: Map<String, String>, depth: Int) {
        if (Thread.currentThread().isInterrupted) throw IOException("Lectura cancelada")
        if (depth > 32 || node.attr("Visible").equals("false", true)) return
        val values = inherited.toMutableMap()
        drawParameters(resources, node.attr("DrawParam"), values, HashSet())
        values.putAll(node.attributes)
        node.children.firstOrNull { it.name == "FillColor" }?.let { values["_fill"] = color(it, resources).toString() }
        node.children.firstOrNull { it.name == "StrokeColor" }?.let { values["_stroke"] = color(it, resources).toString() }
        val checkpoint = canvas.save()
        try {
            val boundary = box(node.attr("Boundary"))
            if (boundary != null) {
                canvas.translate(boundary.left, boundary.top)
                canvas.clipRect(0f, 0f, boundary.width(), boundary.height())
            }
            matrix(node.attr("CTM"))?.let { canvas.concat(it) }
            when (node.name) {
                "TextObject" -> drawText(canvas, node, values, resources)
                "ImageObject" -> drawImage(canvas, node, boundary, resources)
                "PathObject" -> drawPath(canvas, node, values)
                "CompositeObject" -> resources.composites[node.attr("ResourceID")]?.let { composite ->
                    composite.children.forEach { drawNode(canvas, it, resources, values, depth + 1) }
                }
                "Layer", "Content", "PageBlock", "CompositeGraphicUnit" -> node.children.forEach { drawNode(canvas, it, resources, values, depth + 1) }
            }
        } finally { canvas.restoreToCount(checkpoint) }
    }

    private fun drawText(canvas: Canvas, node: XmlNode, values: Map<String, String>, resources: Resources) {
        val font = resources.fonts[values["Font"]]
        val bold = (values["Weight"]?.toIntOrNull() ?: 400) >= 600 || font?.node?.attr("Bold").equals("true", true)
        val italic = values["Italic"].equals("true", true) || font?.node?.attr("Italic").equals("true", true)
        val style = when { bold && italic -> Typeface.BOLD_ITALIC; bold -> Typeface.BOLD; italic -> Typeface.ITALIC; else -> Typeface.NORMAL }
        val paint = paint(values, false).apply {
            textSize = number(values["Size"], 4.2f).coerceIn(0.1f, 1000f)
            textScaleX = number(values["HScale"], 1f).coerceIn(0.01f, 100f)
            typeface = Typeface.create(font?.let { typeface(it) } ?: configuredTypeface(), style)
        }
        var x = 0f
        var y = 0f
        val rotation = number(values["CharDirection"], 0f)
        node.children.filter { it.name == "TextCode" }.forEach { code ->
            code.attr("X").toFloatOrNull()?.takeIf { it.isFinite() }?.let { x = it }
            code.attr("Y").toFloatOrNull()?.takeIf { it.isFinite() }?.let { y = it }
            val text = code.text
            val xs = deltas(code.attr("DeltaX"), text.length)
            val ys = deltas(code.attr("DeltaY"), text.length)
            var offset = 0
            var glyph = 0
            while (offset < text.length && glyph < 100_000) {
                val count = Character.charCount(Character.codePointAt(text, offset))
                val character = text.substring(offset, offset + count)
                val checkpoint = canvas.save()
                canvas.rotate(rotation, x, y)
                if (!values["Fill"].equals("false", true)) {
                    paint.style = Paint.Style.FILL
                    paint.color = fillColor(values)
                    canvas.drawText(character, x, y, paint)
                }
                if (values["Stroke"].equals("true", true)) {
                    paint.style = Paint.Style.STROKE
                    paint.color = strokeColor(values)
                    canvas.drawText(character, x, y, paint)
                }
                canvas.restoreToCount(checkpoint)
                x += xs.getOrNull(glyph) ?: paint.measureText(character)
                y += ys.getOrNull(glyph) ?: 0f
                offset += count
                glyph += 1
            }
        }
    }

    private fun drawImage(canvas: Canvas, node: XmlNode, boundary: RectF?, resources: Resources) {
        val resource = resources.images[node.attr("ResourceID")] ?: return
        val bytes = archive.read(resource.path, 32 * 1024 * 1024)
        val bitmap = imageBitmap(bytes, 2048) ?: return
        try {
            val destination = if (node.attr("CTM").isNotBlank()) RectF(0f, 0f, 1f, 1f)
                else RectF(0f, 0f, boundary?.width() ?: bitmap.width.toFloat(), boundary?.height() ?: bitmap.height.toFloat())
            canvas.drawBitmap(bitmap, null, destination, Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply {
                alpha = (node.attr("Alpha").toIntOrNull() ?: 255).coerceIn(0, 255)
            })
        } finally { bitmap.recycle() }
    }

    private fun drawPath(canvas: Canvas, node: XmlNode, values: Map<String, String>) {
        val data = node.first("AbbreviatedData")?.allText().orEmpty()
        if (data.length > 2 * 1024 * 1024) throw IOException("Trazado OFD demasiado complejo")
        val tokens = PATH_TOKEN.findAll(data).map { it.value }.toList()
        val svg = StringBuilder()
        var position = 0
        while (position < tokens.size) {
            val command = tokens[position++]
            val count = when (command) { "S", "M", "L" -> 2; "Q" -> 4; "B" -> 6; "A" -> 7; "C" -> 0; else -> break }
            if (position + count > tokens.size) break
            svg.append(when (command) { "S" -> "M"; "B" -> "C"; "C" -> "Z"; else -> command })
            repeat(count) { svg.append(' ').append(tokens[position++]) }
            svg.append(' ')
        }
        val path = runCatching { PathParser.createPathFromPathData(svg.toString()) }.getOrNull() ?: return
        path.fillType = if (values["Rule"].equals("Even-Odd", true)) Path.FillType.EVEN_ODD else Path.FillType.WINDING
        if (values["Fill"].equals("true", true)) canvas.drawPath(path, paint(values, false))
        if (!values["Stroke"].equals("false", true)) canvas.drawPath(path, paint(values, true))
    }

    private fun paint(values: Map<String, String>, stroke: Boolean) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = if (stroke) Paint.Style.STROKE else Paint.Style.FILL
        color = if (stroke) strokeColor(values) else fillColor(values)
        alpha = (values["Alpha"]?.toIntOrNull() ?: Color.alpha(color)).coerceIn(0, 255)
        strokeWidth = number(values["LineWidth"], 0.353f).coerceIn(0.01f, 1000f)
        strokeMiter = number(values["MiterLimit"], 4f).coerceIn(1f, 1000f)
        strokeCap = when (values["Cap"]) { "Round" -> Paint.Cap.ROUND; "Square" -> Paint.Cap.SQUARE; else -> Paint.Cap.BUTT }
        strokeJoin = when (values["Join"]) { "Round" -> Paint.Join.ROUND; "Bevel" -> Paint.Join.BEVEL; else -> Paint.Join.MITER }
    }

    private fun fillColor(values: Map<String, String>) = values["_fill"]?.toIntOrNull() ?: Color.BLACK
    private fun strokeColor(values: Map<String, String>) = values["_stroke"]?.toIntOrNull() ?: Color.BLACK

    private fun drawParameters(resources: Resources, id: String, values: MutableMap<String, String>, visited: MutableSet<String>) {
        if (id.isBlank() || visited.size > 16 || !visited.add(id)) return
        val parameter = resources.parameters[id] ?: return
        drawParameters(resources, parameter.attr("Relative"), values, visited)
        values.putAll(parameter.attributes)
        parameter.children.firstOrNull { it.name == "FillColor" }?.let { values["_fill"] = color(it, resources).toString() }
        parameter.children.firstOrNull { it.name == "StrokeColor" }?.let { values["_stroke"] = color(it, resources).toString() }
    }

    private fun color(node: XmlNode, resources: Resources): Int {
        var values = numbers(node.attr("Value"))
        val colorSpace = resources.colorSpaces[node.attr("ColorSpace")]
        if (values.isEmpty()) {
            val index = node.attr("Index").toIntOrNull() ?: 0
            values = colorSpace?.first("Palette")?.children?.getOrNull(index)?.allText()?.let(::numbers).orEmpty()
        }
        val bits = (colorSpace?.attr("BitsPerComponent")?.toIntOrNull() ?: 8).coerceIn(1, 16)
        val maximum = ((1 shl bits) - 1).toFloat()
        fun channel(value: Float) = (value / maximum * 255f).roundToInt().coerceIn(0, 255)
        val alpha = (node.attr("Alpha").toIntOrNull() ?: 255).coerceIn(0, 255)
        return when {
            colorSpace?.attr("Type").equals("CMYK", true) && values.size >= 4 -> {
                val c = values[0] / maximum; val m = values[1] / maximum; val y = values[2] / maximum; val k = values[3] / maximum
                Color.argb(alpha, ((1 - c) * (1 - k) * 255).roundToInt().coerceIn(0, 255),
                    ((1 - m) * (1 - k) * 255).roundToInt().coerceIn(0, 255), ((1 - y) * (1 - k) * 255).roundToInt().coerceIn(0, 255))
            }
            values.size >= 3 -> Color.argb(alpha, channel(values[0]), channel(values[1]), channel(values[2]))
            values.isNotEmpty() -> channel(values[0]).let { Color.argb(alpha, it, it, it) }
            else -> Color.BLACK
        }
    }

    private fun readResources(path: String, resources: Resources) {
        val root = xml(path).first("Res") ?: return
        val base = root.attr("BaseLoc")
        fun resourcePath(href: String) = resolve(path, listOf(base.trimEnd('/'), href.trimStart('/')).filter { it.isNotBlank() }.joinToString("/"))
        root.descendants("Font").forEach { font ->
            resources.fonts[font.attr("ID")] = FontResource(font, font.first("FontFile")?.allText()?.takeIf { it.isNotBlank() }?.let(::resourcePath))
        }
        root.descendants("MultiMedia").filter { it.attr("Type").equals("Image", true) }.forEach { image ->
            image.first("MediaFile")?.allText()?.takeIf { it.isNotBlank() }?.let { resources.images[image.attr("ID")] = ImageResource(resourcePath(it)) }
        }
        root.descendants("DrawParam").forEach { resources.parameters[it.attr("ID")] = it }
        root.descendants("ColorSpace").forEach { resources.colorSpaces[it.attr("ID")] = it }
        root.descendants("CompositeGraphicUnit").forEach { resources.composites[it.attr("ID")] = it }
    }

    private fun configuredTypeface(): Typeface = options.fontPath?.let { path -> runCatching { Typeface.createFromFile(path) }.getOrNull() }
        ?: Typeface.create(options.fontFamily, Typeface.NORMAL)

    private fun typeface(resource: FontResource): Typeface = fontCache.getOrPut(resource.path ?: resource.node.attr("FontName")) {
        val directory = workDirectory
        val embedded = if (directory != null && resource.path != null) runCatching {
            check(directory.isDirectory || directory.mkdirs())
            val temporary = File.createTempFile("ofd-font-", ".font", directory)
            temporaryFonts += temporary
            temporary.outputStream().use { it.write(archive.read(resource.path, 16 * 1024 * 1024)) }
            Typeface.createFromFile(temporary)
        }.getOrNull() else null
        embedded ?: Typeface.create(resource.node.attr("FamilyName").ifBlank {
            if (resource.node.attr("Serif").equals("true", true)) "serif" else options.fontFamily
        }, Typeface.NORMAL)
    }

    private fun xml(path: String): XmlNode = XmlContent.parse(archive.read(path, 4 * 1024 * 1024))
    private fun resolve(base: String, href: String): String {
        // OFD allows archive-root absolute locations, but never external resources.
        val result = if (href.startsWith('/')) resolveArchivePath("", href.trimStart('/')) else resolveArchivePath(base, href)
        return result?.takeIf { it in names } ?: throw IOException("Recurso OFD no encontrado")
    }

    @Synchronized
    override fun close() { closed = true; fontCache.clear(); temporaryFonts.forEach { it.delete() }; temporaryFonts.clear() }

    private data class ImageResource(val path: String)
    private data class FontResource(val node: XmlNode, val path: String?)
    private class Resources(
        val fonts: MutableMap<String, FontResource> = HashMap(),
        val images: MutableMap<String, ImageResource> = HashMap(),
        val parameters: MutableMap<String, XmlNode> = HashMap(),
        val colorSpaces: MutableMap<String, XmlNode> = HashMap(),
        val composites: MutableMap<String, XmlNode> = HashMap(),
    ) {
        fun copy() = Resources(HashMap(fonts), HashMap(images), HashMap(parameters), HashMap(colorSpaces), HashMap(composites))
    }

    private companion object {
        val PATH_TOKEN = Regex("[A-Za-z]+|[-+]?(?:\\d*\\.\\d+|\\d+\\.?\\d*)(?:[eE][-+]?\\d+)?")
        fun number(value: String?, fallback: Float) = value?.toFloatOrNull()?.takeIf { it.isFinite() } ?: fallback
        fun numbers(value: String): List<Float> = value.trim().split(Regex("\\s+")).mapNotNull { it.toFloatOrNull()?.takeIf(Float::isFinite) }
        fun box(value: String): RectF? = numbers(value).takeIf { it.size == 4 && it[2] > 0 && it[3] > 0 }
            ?.let { RectF(it[0], it[1], it[0] + it[2], it[1] + it[3]) }
        fun matrix(value: String): Matrix? = numbers(value).takeIf { it.size == 6 }?.let { v -> Matrix().apply {
            setValues(floatArrayOf(v[0], v[2], v[4], v[1], v[3], v[5], 0f, 0f, 1f))
        } }
        fun deltas(value: String, maximum: Int): List<Float> {
            if (value.isBlank()) return emptyList()
            val tokens = value.trim().split(Regex("\\s+"))
            val result = ArrayList<Float>()
            var position = 0
            while (position < tokens.size && result.size < maximum) {
                if (tokens[position] == "g") {
                    val repetitions = tokens.getOrNull(position + 1)?.toIntOrNull()?.coerceIn(0, maximum - result.size) ?: break
                    val delta = tokens.getOrNull(position + 2)?.toFloatOrNull()?.takeIf { it.isFinite() } ?: break
                    repeat(repetitions) { result += delta }
                    position += 3
                } else {
                    tokens[position++].toFloatOrNull()?.takeIf { it.isFinite() }?.let { result += it }
                }
            }
            return result
        }
    }
}
