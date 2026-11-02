package com.valerogarte.comicsreader.nativeapp.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A reading order kept apart from the files: entries point to books where they already are, so
 * nothing is copied into a folder just to keep an order. An entry without files, or whose files
 * are not on the server, is a gap still to be obtained. Guides are the `guia_lectura_*.json`
 * files of a server's root folder.
 *
 * ```json
 * { "format": "comics-reader-guide", "version": 1, "id": "…", "title": "…", "description": "…",
 *   "server": "NAS", "tiempo_aproximado_lectura": 4180,
 *   "sections": [ { "title": "01 · Bases", "items": [
 *     { "number": "01", "title": "Los Cuatro Fantásticos", "scope": "Fantastic Four (1961) #1",
 *       "detail": "Por qué leerlo…", "priority": "Imprescindible", "note": "…",
 *       "tiempo_aproximado_lectura": 10,
 *       "files": ["/volume1/recursos/Comics/…/Los_Cuatro_Fantasticos_001_1961.cbr"] } ] } ] }
 * ```
 * File paths may be absolute on the server (the profile's root folder is removed) or relative to it.
 * `tiempo_aproximado_lectura` is an estimate in minutes, for the whole guide and for each entry.
 */
data class ReadingGuide(
    val id: String,
    val title: String,
    val description: String = "",
    val server: String? = null,
    val sections: List<GuideSection> = emptyList(),
    /** Approximate reading time in minutes; null when the guide does not say. */
    val readingMinutes: Int? = null,
) {
    val items get() = sections.flatMap { it.items }
}

data class GuideSection(val title: String, val items: List<GuideItem>)

data class GuideItem(
    val key: String,
    val number: String,
    val title: String,
    val detail: String = "",
    /** Exactly what the entry covers: issues, chapters or volumes. */
    val scope: String = "",
    val priority: String = "",
    val note: String = "",
    val files: List<String> = emptyList(),
    val readingMinutes: Int? = null,
)

object GuideJson {
    const val FORMAT = "comics-reader-guide"
    private const val READING_TIME = "tiempo_aproximado_lectura"

    fun parse(text: String): ReadingGuide {
        val root = JSONObject(text)
        require(root.optString("format", FORMAT) == FORMAT) { "No es una guía de lectura" }
        val title = root.optString("title").trim()
        require(title.isNotEmpty()) { "La guía no tiene título" }
        val sections = root.optJSONArray("sections") ?: JSONArray()
        require(sections.length() > 0) { "La guía no tiene secciones" }
        val parsed = (0 until sections.length()).map { s ->
            val section = sections.getJSONObject(s)
            val items = section.optJSONArray("items") ?: JSONArray()
            GuideSection(section.optString("title").trim(), (0 until items.length()).map { i ->
                val item = items.getJSONObject(i)
                val files = item.optJSONArray("files") ?: JSONArray()
                GuideItem(
                    key = item.optString("key").ifBlank { "$s:$i" },
                    number = item.optString("number").trim(),
                    title = item.optString("title").trim().ifEmpty { "Sin título" },
                    detail = item.optString("detail").trim(),
                    scope = item.optString("scope").trim(),
                    priority = item.optString("priority").trim(),
                    note = item.optString("note").trim(),
                    files = (0 until files.length()).map { files.getString(it).trim() }.filter { it.isNotEmpty() },
                    readingMinutes = item.minutes(),
                )
            })
        }
        return ReadingGuide(root.optString("id").ifBlank { UUID.randomUUID().toString() }, title,
            root.optString("description").trim(), root.optString("server").trim().ifEmpty { null }, parsed, root.minutes())
    }

    private fun JSONObject.minutes(): Int? = optInt(READING_TIME, -1).takeIf { it >= 0 }

    fun write(guide: ReadingGuide): String = JSONObject().apply {
        put("format", FORMAT); put("version", 1); put("id", guide.id); put("title", guide.title)
        put("description", guide.description); guide.server?.let { put("server", it) }
        guide.readingMinutes?.let { put(READING_TIME, it) }
        put("sections", JSONArray().apply {
            guide.sections.forEach { section -> put(JSONObject().apply {
                put("title", section.title)
                put("items", JSONArray().apply { section.items.forEach { item -> put(JSONObject().apply {
                    put("key", item.key); put("number", item.number); put("title", item.title)
                    if (item.detail.isNotEmpty()) put("detail", item.detail)
                    if (item.scope.isNotEmpty()) put("scope", item.scope)
                    if (item.priority.isNotEmpty()) put("priority", item.priority)
                    if (item.note.isNotEmpty()) put("note", item.note)
                    item.readingMinutes?.let { put(READING_TIME, it) }
                    put("files", JSONArray(item.files))
                }) } })
            }) }
        })
    }.toString(2)
}
