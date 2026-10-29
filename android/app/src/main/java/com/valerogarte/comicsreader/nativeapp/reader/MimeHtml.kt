package com.valerogarte.comicsreader.nativeapp.reader

import android.util.Base64
import java.io.ByteArrayOutputStream
import java.nio.charset.Charset

/** Decode a saved MIME HTML document locally; no URL or embedded script is executed. */
internal object MimeHtml {
    fun text(raw: String): String {
        val boundary = Regex("boundary\\s*=\\s*(?:\"([^\"]+)\"|([^;\\s]+))", RegexOption.IGNORE_CASE)
            .find(raw.substringBefore("\r\n\r\n").substringBefore("\n\n"))
            ?.let { it.groupValues[1].ifBlank { it.groupValues[2] } }
        val parts = if (boundary != null) raw.split("--$boundary") else listOf(raw)
        val part = parts.firstOrNull { Regex("Content-Type:\\s*text/html", RegexOption.IGNORE_CASE).containsMatchIn(it.substringBefore("\r\n\r\n").substringBefore("\n\n")) }
            ?: parts.firstOrNull { it.contains("<html", true) } ?: raw
        val split = Regex("\\r?\\n\\r?\\n").find(part)
        val headers = if (split != null) part.substring(0, split.range.first) else ""
        val body = if (split != null) part.substring(split.range.last + 1).trimEnd('\r', '\n') else part
        val bytes = when {
            headers.contains("base64", true) -> runCatching { Base64.decode(body, Base64.DEFAULT) }.getOrElse { body.toByteArray() }
            headers.contains("quoted-printable", true) -> quotedPrintable(body)
            else -> body.toByteArray(Charsets.ISO_8859_1)
        }
        val charset = Regex("charset\\s*=\\s*[\"']?([^;\\s\"']+)", RegexOption.IGNORE_CASE).find(headers)?.groupValues?.get(1)
            ?.let { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
        return TextContent.html(String(bytes, charset))
    }

    private fun quotedPrintable(raw: String): ByteArray {
        val output = ByteArrayOutputStream()
        var i = 0
        while (i < raw.length) {
            if (raw[i] == '=' && i + 1 < raw.length) {
                if (raw[i + 1] == '\n') { i += 2; continue }
                if (i + 2 < raw.length && raw[i + 1] == '\r' && raw[i + 2] == '\n') { i += 3; continue }
                if (i + 2 < raw.length) {
                    val decoded = raw.substring(i + 1, i + 3).toIntOrNull(16)
                    if (decoded != null) { output.write(decoded); i += 3; continue }
                }
            }
            output.write(raw[i++].code and 0xff)
        }
        return output.toByteArray()
    }
}
