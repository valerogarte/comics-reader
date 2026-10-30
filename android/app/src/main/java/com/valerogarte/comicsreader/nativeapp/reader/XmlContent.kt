package com.valerogarte.comicsreader.nativeapp.reader

import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.io.StringReader

internal data class XmlNode(
    val name: String,
    val attributes: Map<String, String>,
    val children: MutableList<XmlNode> = ArrayList(),
    val content: MutableList<Any> = ArrayList(),
) {
    val text: String get() = content.filterIsInstance<String>().joinToString("")
    fun descendants(name: String): List<XmlNode> = buildList {
        if (this@XmlNode.name.equals(name, true)) add(this@XmlNode)
        for (child in children) addAll(child.descendants(name))
    }
    fun first(name: String): XmlNode? {
        if (this.name.equals(name, true)) return this
        for (child in children) child.first(name)?.let { return it }
        return null
    }
    fun allText(trim: Boolean = true): String = buildString { appendTextTo(this) }.let { if (trim) it.trim() else it }
    private fun appendTextTo(output: StringBuilder) {
        for (part in content) when (part) {
            is String -> output.append(part)
            is XmlNode -> part.appendTextTo(output)
        }
    }
    fun attr(name: String): String = attributes.entries.firstOrNull { it.key.equals(name, true) }?.value.orEmpty()
}

internal object XmlContent {
    fun parse(bytes: ByteArray): XmlNode = parse(TextContent.decode(bytes))
    fun parse(value: String): XmlNode {
        if (value.length > 4 * 1024 * 1024 || Regex("<!\\s*(DOCTYPE|ENTITY)", RegexOption.IGNORE_CASE).containsMatchIn(value)) {
            throw IOException("XML no permitido")
        }
        val parser = Xml.newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(value))
        val root = XmlNode("root", emptyMap())
        val stack = arrayListOf(root)
        var count = 0
        while (parser.eventType != XmlPullParser.END_DOCUMENT) {
            when (parser.eventType) {
                XmlPullParser.START_TAG -> {
                    if (++count > 50_000 || stack.size > 64) throw IOException("XML demasiado complejo")
                    val attributes = (0 until parser.attributeCount).associate { parser.getAttributeName(it) to parser.getAttributeValue(it) }
                    val node = XmlNode(parser.name.substringAfter(':'), attributes)
                    stack.last().children += node
                    stack.last().content += node
                    stack += node
                }
                XmlPullParser.TEXT, XmlPullParser.CDSECT -> stack.last().content += parser.text.orEmpty()
                XmlPullParser.END_TAG -> if (stack.size > 1) stack.removeAt(stack.lastIndex)
            }
            parser.next()
        }
        return root
    }
    fun value(root: XmlNode?, name: String): String = root?.first(name)?.allText().orEmpty()
}
