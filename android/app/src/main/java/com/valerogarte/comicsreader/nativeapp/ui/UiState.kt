package com.valerogarte.comicsreader.nativeapp.ui

import android.graphics.Bitmap
import com.valerogarte.comicsreader.nativeapp.reader.ReadingFonts

enum class LibraryDestination { BOOKS, SFTP, SHELF, GUIDE }
enum class ReadFilter { ALL, UNREAD, READING, FINISHED }
enum class LibraryLayout { GRID, LIST }
enum class ReaderMode { HORIZONTAL, VERTICAL }
enum class ReaderTransition { NONE, FADE, SLIDE, PAGE_TURN, REVEAL, ZOOM }

data class UiBook(
    val id: String,
    val title: String,
    val format: String,
    val size: Long = 0,
    val coverPath: String? = null,
    val shelfIds: List<String> = emptyList(),
    val progress: Float = 0f,
    val sourceId: String? = null,
    val remotePath: String? = null,
)

data class UiShelf(val id: String, val name: String)

/** A shelf is a server folder; offline it lists the downloaded books under it. */
data class UiShelfPath(val id: String, val name: String, val serverId: String, val sourceId: String, val path: String)

data class UiGuideSummary(val id: String, val title: String, val itemCount: Int)

enum class GuideItemStatus { READ, READING, AVAILABLE, PARTIAL, MISSING, UNKNOWN }

data class UiGuideFile(
    val path: String,
    val name: String,
    val coverPath: String? = null,
    val bookId: String? = null,
    val progress: Float = 0f,
    /** Known on the server or downloaded; null while the folder has not been read yet. */
    val available: Boolean? = null,
    val downloading: Boolean = false,
)

data class UiGuideItem(
    val key: String,
    val number: String,
    val title: String,
    val detail: String,
    val scope: String,
    val priority: String,
    val note: String,
    val files: List<UiGuideFile>,
    val status: GuideItemStatus,
) {
    val coverPath get() = files.firstNotNullOfOrNull { it.coverPath }
    val progress get() = if (files.isEmpty()) 0f else files.sumOf { it.progress.toDouble() }.toFloat() / files.size
}

data class UiGuideSection(val title: String, val items: List<UiGuideItem>)

data class UiGuideView(
    val id: String,
    val title: String,
    val description: String,
    val serverName: String?,
    val sections: List<UiGuideSection>,
    val checking: Boolean = false,
) {
    val items get() = sections.flatMap { it.items }
}

data class UiReadingStatistics(
    val totalBooks: Int = 0,
    val unreadBooks: Int = 0,
    val readingBooks: Int = 0,
    val finishedBooks: Int = 0,
    val progressPages: Long = 0,
    val totalPages: Long = 0,
    val booksWithKnownPages: Int = 0,
    /** Books that counted as being read, newest first; kept after deleting the local copy. */
    val timeline: List<UiReadingTimelineEntry> = emptyList(),
)

data class UiReadingTimelineEntry(
    val bookId: String,
    val title: String,
    val coverPath: String?,
    val startedAt: Long,
    val finishedAt: Long?,
    val readingSeconds: Long,
    /** The local copy is still here, or its server and path are known to bring it back. */
    val canOpen: Boolean = false,
    val local: Boolean = false,
)

data class UiSftpServer(
    val id: String,
    val name: String,
    val host: String,
    val port: String = "22",
    val username: String,
    val password: String,
    val rootPath: String = "/",
)

data class UiRemoteEntry(
    val path: String,
    val name: String,
    val isDirectory: Boolean,
    val title: String = name,
    val format: String = "",
    val size: Long = 0,
    val coverPath: String? = null,
    val downloaded: Boolean = false,
    val downloading: Boolean = false,
    val progress: Float = 0f,
    val fileCount: Int? = null,
    val queued: Boolean = false,
    /** Local copy of this remote file, when it has been downloaded. */
    val bookId: String? = null,
    /** Directories: thumbnails of books inside, drawn sticking out of the folder. */
    val folderCovers: List<String> = emptyList(),
)

data class UiSettings(
    val theme: String = "system",
    val background: String = "cream",
    val font: String = ReadingFonts.DEFAULT_ID,
    val fontSize: Float = 18f,
    val fontPath: String? = null,
    val backgroundImagePath: String? = null,
    val keepScreenOn: Boolean = true,
    val rightToLeft: Boolean = false,
    val sftpServers: List<UiSftpServer> = emptyList(),
    val readingMode: ReaderMode = ReaderMode.HORIZONTAL,
    val readerTransition: ReaderTransition = ReaderTransition.SLIDE,
    /** Only the choice is stored for now; no feature uses a provider yet. */
    val aiProvider: String = "",
    val aiModel: String = "",
)

data class UiReaderState(
    val bookId: String,
    val title: String,
    val pageIndex: Int = 0,
    val pageCount: Int = 1,
    val bitmap: Bitmap? = null,
    val text: String? = null,
    val loading: Boolean = false,
    val error: String? = null,
    val reflowable: Boolean = false,
    val sessionId: Int = 0,
    val navigationRequest: Long = 0,
    val bitmapPageIndex: Int? = null,
)

data class AppUiState(
    val books: List<UiBook> = emptyList(),
    val shelves: List<UiShelf> = emptyList(),
    val shelfPaths: List<UiShelfPath> = emptyList(),
    val guides: List<UiGuideSummary> = emptyList(),
    val selectedGuideId: String? = null,
    val guide: UiGuideView? = null,
    val destination: LibraryDestination = LibraryDestination.BOOKS,
    val selectedShelfId: String? = null,
    val selectedSftpServerId: String? = null,
    val remotePath: String = "/",
    val remoteEntries: List<UiRemoteEntry> = emptyList(),
    val loading: Boolean = false,
    val busyMessage: String? = null,
    val operationProgress: Float? = null,
    /** A download is running or queued and can be cancelled from the progress notice. */
    val cancelableDownload: Boolean = false,
    /** Remote books shown highlighted after "show on the server" from a guide. */
    val highlightedPaths: Set<String> = emptySet(),
    /** The guide to return to with Back after showing one of its books on the server. */
    val returnGuideId: String? = null,
    val message: String? = null,
    val error: String? = null,
    val settings: UiSettings = UiSettings(),
    val settingsVisible: Boolean = false,
    val emptyShelf: UiShelf? = null,
    val reader: UiReaderState? = null,
    val readingStatistics: UiReadingStatistics = UiReadingStatistics(),
)

internal fun remoteParentPath(path: String): String =
    path.trimEnd('/').substringBeforeLast('/', "").ifBlank { "/" }

internal fun AppUiState.navigateLibraryBack(actions: AppActions) {
    if (destination == LibraryDestination.SFTP && returnGuideId != null) {
        actions.onOpenGuide(returnGuideId)
    } else if (destination == LibraryDestination.SFTP && remotePath != "/") {
        actions.onOpenRemoteFolder(remoteParentPath(remotePath))
    } else actions.onNavigate(LibraryDestination.BOOKS)
}

interface AppActions {
    fun onNavigate(destination: LibraryDestination, shelfId: String? = null) {}
    fun onOpenSftpServer(id: String) {}
    fun onImportBooks() {}
    fun onImportFont() {}
    fun onPickBackground() {}
    fun onOpenBook(id: String) {}
    fun onDeleteBooks(ids: List<String>) {}
    fun onMarkBooksRead(ids: List<String>) {}
    fun onMarkBooksUnread(ids: List<String>) {}
    fun onOpenTimelineEntry(bookId: String) {}
    fun onAddCurrentFolderToShelves() {}
    fun onOpenShelfPath(id: String) {}
    fun onDeleteShelfPath(id: String) {}
    fun onOpenGuide(id: String) {}
    fun onOpenGuideFile(path: String) {}
    fun onRefreshGuide() {}
    fun onDownloadGuideSection(sectionIndex: Int) {}
    fun onDownloadGuideItem(key: String) {}
    fun onRevealGuideItem(key: String) {}
    fun onDeleteGuide(id: String) {}
    fun onImportGuide() {}
    fun onExportGuide(id: String) {}
    fun onCreateShelf(name: String) {}
    fun onAddBooksToShelf(ids: List<String>, name: String) {}
    fun onDeleteShelf(id: String) {}
    fun onKeepEmptyShelf() {}
    fun onOpenRemoteFolder(path: String) {}
    fun onOpenRemoteBook(path: String) {}
    fun onDownloadRemote(path: String) {}
    fun onDownloadAll() {}
    fun onDownloadRemotePaths(paths: List<String>) {}
    fun onCancelDownloads() {}
    fun onSyncRemote() {}
    fun onShowSettings() {}
    fun onCloseSettings() {}
    fun onSaveSettings(settings: UiSettings) {}
    fun onUpdateAppearanceSettings(update: (UiSettings) -> UiSettings) {}
    fun onUpdateSftpServers(update: (List<UiSftpServer>) -> List<UiSftpServer>) {}
    fun onDismissMessage() {}
    fun onCloseReader() {}
    fun onReaderPage(pageIndex: Int) {}
    suspend fun loadReaderPage(bookId: String, sessionId: Int, pageIndex: Int): Bitmap? = null
    fun onReaderPosition(bookId: String, sessionId: Int, navigationRequest: Long, pageIndex: Int) {}
}
