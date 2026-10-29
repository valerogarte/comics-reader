package com.valerogarte.comicsreader.nativeapp.reader

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.text.Html
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.nio.charset.Charset
import kotlin.math.roundToInt

/** Native Android text pagination used for text documents and extracted Office content. */
internal class TextDocument(private val text: String, options: DocumentOptions = DocumentOptions()) : DocumentSession {
    private val backgroundColor = options.backgroundColor
    private val paint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = options.textColor
        textSize = options.fontSize.coerceIn(12f, 40f)
        typeface = options.fontPath?.let { runCatching { Typeface.createFromFile(it) }.getOrNull() }
            ?: Typeface.create(when (options.fontFamily) { "sans" -> "sans-serif"; "mono" -> "monospace"; else -> options.fontFamily }, Typeface.NORMAL)
    }
    // Layout exposes the same integer strategy on API 23+, before LineBreaker existed in API 29.
    @android.annotation.SuppressLint("WrongConstant")
    private val layout = StaticLayout.Builder.obtain(text, 0, text.length, paint, 512)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL)
        .setIncludePad(false)
        .setLineSpacing(6f, 1f)
        .setBreakStrategy(Layout.BREAK_STRATEGY_HIGH_QUALITY)
        .build()
    private val pageStarts = ArrayList<Int>().apply {
        add(0)
        var line = 0
        while (line < layout.lineCount) {
            val top = layout.getLineTop(line)
            var next = line + 1
            while (next < layout.lineCount && layout.getLineBottom(next) - top <= BODY_HEIGHT) next++
            if (next < layout.lineCount) add(layout.getLineTop(next))
            line = next
        }
    }
    override val pageCount: Int get() = pageStarts.size.coerceAtLeast(1)
    override val isReflowable: Boolean get() = true
    override fun renderPage(index: Int, width: Int): Bitmap {
        require(index in 0 until pageCount) { "Página fuera del documento" }
        val targetWidth = width.coerceIn(180, 2048)
        val bitmap = Bitmap.createBitmap(targetWidth, (targetWidth * 1.5f).roundToInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(backgroundColor)
        canvas.scale(targetWidth / 600f, targetWidth / 600f)
        canvas.save()
        canvas.clipRect(44f, 44f, 556f, 44f + BODY_HEIGHT)
        canvas.translate(44f, 44f - pageStarts[index])
        layout.draw(canvas)
        canvas.restore()
        return bitmap
    }
    override fun close() = Unit
    companion object { private const val BODY_HEIGHT = 800 }
}

internal object TextContent {
    fun decode(bytes: ByteArray): String = when {
        bytes.size >= 2 && bytes[0] == 0xff.toByte() && bytes[1] == 0xfe.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16LE)
        bytes.size >= 2 && bytes[0] == 0xfe.toByte() && bytes[1] == 0xff.toByte() -> String(bytes, 2, bytes.size - 2, Charsets.UTF_16BE)
        else -> {
            val utf8 = String(bytes, Charsets.UTF_8).removePrefix("\uFEFF")
            if (utf8.count { it == '\uFFFD' } > utf8.length / 100) String(bytes, Charset.forName("windows-1252")) else utf8
        }
    }

    fun html(value: String): String {
        val stripped = value.replace(Regex("<(script|style|iframe|object|head)\\b[^>]*>.*?</\\1\\s*>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), "")
        return Html.fromHtml(stripped, Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }

    /** RTF character/control parser; hidden destinations and binary payloads are skipped. */
    fun rtf(value: String): String {
        data class State(var hidden: Boolean = false, var unicodeSkip: Int = 1)
        val stack = ArrayList<State>()
        var state = State()
        val output = StringBuilder()
        var i = 0
        var fallback = 0
        val hidden = setOf("fonttbl", "colortbl", "stylesheet", "info", "pict", "object", "header", "footer", "filetbl", "listtable", "listoverridetable", "datastore", "themedata", "xmlnstbl")
        while (i < value.length) {
            when (val c = value[i++]) {
                '{' -> { stack += state.copy(); state = state.copy() }
                '}' -> if (stack.isNotEmpty()) state = stack.removeAt(stack.lastIndex)
                '\\' -> {
                    if (i >= value.length) break
                    val next = value[i]
                    when {
                        next in "\\{}" -> { i++; if (!state.hidden && fallback-- <= 0) output.append(next) }
                        next == '*' -> { i++; state.hidden = true }
                        next == '\'' && i + 2 < value.length -> {
                            val code = value.substring(i + 1, i + 3).toIntOrNull(16)
                            i += 3
                            if (!state.hidden && fallback-- <= 0 && code != null) output.append(String(byteArrayOf(code.toByte()), Charset.forName("windows-1252")))
                        }
                        next.isLetter() -> {
                            val start = i
                            while (i < value.length && value[i].isLetter()) i++
                            val word = value.substring(start, i)
                            val numberStart = i
                            if (i < value.length && value[i] == '-') i++
                            while (i < value.length && value[i].isDigit()) i++
                            val number = value.substring(numberStart, i).toIntOrNull()
                            if (i < value.length && value[i] == ' ') i++
                            when {
                                word in hidden -> state.hidden = true
                                word == "bin" -> i = (i + (number ?: 0).coerceAtLeast(0)).coerceAtMost(value.length)
                                word == "uc" -> state.unicodeSkip = (number ?: 1).coerceIn(0, 10)
                                !state.hidden -> when (word) {
                                    "par", "line", "page" -> output.append('\n')
                                    "tab" -> output.append('\t')
                                    "emdash" -> output.append('—')
                                    "endash" -> output.append('–')
                                    "u" -> { if (number != null) output.append((number and 0xffff).toChar()); fallback = state.unicodeSkip }
                                }
                            }
                        }
                        else -> { i++; if (!state.hidden && next == '~') output.append(' ') }
                    }
                }
                '\r', '\n' -> Unit
                else -> if (!state.hidden && fallback-- <= 0) output.append(c)
            }
            if (fallback < 0) fallback = 0
        }
        return output.toString().trim()
    }

    fun markdown(value: String): String = value
        .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
        .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
        .replace(Regex("(?m)^\\s{0,3}#{1,6}\\s+"), "")
        .replace(Regex("(?m)^\\s*```[^\\n]*"), "")
        .replace("**", "").replace("__", "").trim()
}
