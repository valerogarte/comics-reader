package com.valerogarte.comicsreader.nativeapp.model

import java.security.MessageDigest
import java.util.UUID

enum class ReadingStatus { UNREAD, READING, FINISHED }

data class BookEntry(
    val id: String,
    val title: String,
    val fileName: String,
    val format: String,
    val localPath: String,
    val coverPath: String? = null,
    val author: String = "",
    val description: String = "",
    val publisher: String = "",
    val pageCount: Int = 0,
    val currentPage: Int = 0,
    val readingStatus: ReadingStatus = ReadingStatus.UNREAD,
    val shelfName: String? = null,
    val sourceId: String? = null,
    val remotePath: String? = null,
    val size: Long = 0,
    val modified: Long = 0,
)

data class ShelfEntry(val name: String, val bookCount: Int)

data class SftpConfig(
    val host: String,
    val port: Int = 22,
    val username: String,
    val password: String,
    val rootPath: String = "/",
    val profileId: String = UUID.randomUUID().toString(),
    val name: String = "",
) {
    /** Password changes retain the catalog and existing download links. */
    val id: String = MessageDigest.getInstance("SHA-256")
        .digest("$host\u0000$port\u0000$username\u0000$rootPath".toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}

data class RemoteEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long = 0,
    val modified: Long = 0,
    val title: String = name.substringBeforeLast('.', name),
    val format: String = name.substringAfterLast('.', "").lowercase(),
    val coverPath: String? = null,
    val author: String = "",
    val description: String = "",
    val publisher: String = "",
    val pageCount: Int = 0,
    val downloadedBookId: String? = null,
    val sourceId: String? = null,
    /** Preview has been attempted for the current file and extractor version, even without a cover. */
    val previewVersion: Int = 0,
    /** Direct files in a directory, including every format; null means not counted yet. */
    val fileCount: Int? = null,
)

data class RemoteFolder(
    val path: String,
    val entries: List<RemoteEntry>,
    val cachedAt: Long,
    val sourceId: String,
)

data class DownloadSummary(val downloaded: Int, val alreadyDownloaded: Int, val failed: Int)

object BookFormats {
    val supported = setOf(
        "epub", "pdf", "txt", "mobi", "azw3", "azw", "kdz", "htm", "html", "xml",
        "xhtml", "mhtml", "docx", "doc", "rtf", "md", "fb2", "chm", "cbz", "cbt",
        "cbr", "cb7", "pptx", "ppt", "xlsx", "xps", "oxps", "djvu", "djv", "ofd", "caj",
    )
    private val aliases = mapOf("zip" to "cbz", "rar" to "cbr", "tar" to "cbt", "7z" to "cb7")
    fun format(name: String): String {
        val extension = name.substringAfterLast('.', "").lowercase()
        return aliases[extension] ?: extension
    }
    fun supports(name: String) = format(name) in supported
}
