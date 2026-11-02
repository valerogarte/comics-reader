package com.valerogarte.comicsreader.nativeapp.model

import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * A reading order kept apart from the files: entries point to books where they already are, so
 * nothing is copied into a folder just to keep an order. An entry without files, or whose files
 * are not on the server, is a gap still to be obtained.
 *
 * ```json
 * { "format": "comics-reader-guide", "version": 1, "id": "…", "title": "…", "description": "…",
 *   "server": "NAS",
 *   "sections": [ { "title": "01 · Bases", "items": [
 *     { "number": "01", "title": "…", "detail": "Fantastic Four (1961) #1", "category": "4 Fantásticos",
 *       "priority": "Imprescindible", "note": "…", "files": ["/volume1/recursos/Comics/…/001_….cbr"] } ] } ] }
 * ```
 * File paths may be absolute on the server (the profile's root folder is removed) or relative to it.
 */
data class ReadingGuide(
    val id: String,
    val title: String,
    val description: String = "",
    val server: String? = null,
    val sections: List<GuideSection> = emptyList(),
) {
    val items get() = sections.flatMap { it.items }
}

data class GuideSection(val title: String, val items: List<GuideItem>)

data class GuideItem(
    val key: String,
    val number: String,
    val title: String,
    val detail: String = "",
    val category: String = "",
    val priority: String = "",
    val note: String = "",
    val files: List<String> = emptyList(),
)

object GuideJson {
    const val FORMAT = "comics-reader-guide"

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
                    category = item.optString("category").trim(),
                    priority = item.optString("priority").trim(),
                    note = item.optString("note").trim(),
                    files = (0 until files.length()).map { files.getString(it).trim() }.filter { it.isNotEmpty() },
                )
            })
        }
        return ReadingGuide(root.optString("id").ifBlank { UUID.randomUUID().toString() }, title,
            root.optString("description").trim(), root.optString("server").trim().ifEmpty { null }, parsed)
    }

    fun write(guide: ReadingGuide): String = JSONObject().apply {
        put("format", FORMAT); put("version", 1); put("id", guide.id); put("title", guide.title)
        put("description", guide.description); guide.server?.let { put("server", it) }
        put("sections", JSONArray().apply {
            guide.sections.forEach { section -> put(JSONObject().apply {
                put("title", section.title)
                put("items", JSONArray().apply { section.items.forEach { item -> put(JSONObject().apply {
                    put("key", item.key); put("number", item.number); put("title", item.title)
                    if (item.detail.isNotEmpty()) put("detail", item.detail)
                    if (item.category.isNotEmpty()) put("category", item.category)
                    if (item.priority.isNotEmpty()) put("priority", item.priority)
                    if (item.note.isNotEmpty()) put("note", item.note)
                    put("files", JSONArray(item.files))
                }) } })
            }) }
        })
    }.toString(2)
}
