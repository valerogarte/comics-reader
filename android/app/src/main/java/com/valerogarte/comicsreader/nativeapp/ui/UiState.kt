package com.valerogarte.comicsreader.nativeapp.ui

import android.graphics.Bitmap

enum class LibraryDestination { BOOKS, SFTP, SHELF }
enum class ReadFilter { ALL, UNREAD, READING, FINISHED }
enum class LibraryLayout { GRID, LIST }

data class UiBook(
    val id: String,
    val title: String,
    val format: String,
    val size: Long = 0,
    val coverPath: String? = null,
    val shelfIds: List<String> = emptyList(),
    val progress: Float = 0f,
)

data class UiShelf(val id: String, val name: String)

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
)

data class UiSettings(
    val theme: String = "system",
    val background: String = "cream",
    val font: String = "sans",
    val fontSize: Float = 18f,
    val fontPath: String? = null,
    val backgroundImagePath: String? = null,
    val keepScreenOn: Boolean = true,
    val rightToLeft: Boolean = false,
    val sftpServers: List<UiSftpServer> = emptyList(),
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
)

data class AppUiState(
    val books: List<UiBook> = emptyList(),
    val shelves: List<UiShelf> = emptyList(),
    val destination: LibraryDestination = LibraryDestination.BOOKS,
    val selectedShelfId: String? = null,
    val selectedSftpServerId: String? = null,
    val remotePath: String = "/",
    val remoteEntries: List<UiRemoteEntry> = emptyList(),
    val loading: Boolean = false,
    val busyMessage: String? = null,
    val operationProgress: Float? = null,
    val message: String? = null,
    val error: String? = null,
    val settings: UiSettings = UiSettings(),
    val settingsVisible: Boolean = false,
    val emptyShelf: UiShelf? = null,
    val reader: UiReaderState? = null,
)

interface AppActions {
    fun onNavigate(destination: LibraryDestination, shelfId: String? = null) {}
    fun onOpenSftpServer(id: String) {}
    fun onImportBooks() {}
    fun onImportFont() {}
    fun onPickBackground() {}
    fun onOpenBook(id: String) {}
    fun onDeleteBooks(ids: List<String>) {}
    fun onCreateShelf(name: String) {}
    fun onAddBooksToShelf(ids: List<String>, name: String) {}
    fun onDeleteShelf(id: String) {}
    fun onKeepEmptyShelf() {}
    fun onOpenRemoteFolder(path: String) {}
    fun onOpenRemoteBook(path: String) {}
    fun onDownloadRemote(path: String) {}
    fun onDownloadAll() {}
    fun onSyncRemote() {}
    fun onShowSettings() {}
    fun onCloseSettings() {}
    fun onSaveSettings(settings: UiSettings) {}
    fun onDismissMessage() {}
    fun onCloseReader() {}
    fun onReaderPage(pageIndex: Int) {}
}
