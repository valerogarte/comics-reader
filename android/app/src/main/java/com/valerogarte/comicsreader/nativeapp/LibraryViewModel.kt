package com.valerogarte.comicsreader.nativeapp

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.valerogarte.comicsreader.nativeapp.data.LibraryRepository
import com.valerogarte.comicsreader.nativeapp.model.*
import com.valerogarte.comicsreader.nativeapp.reader.DocumentOptions
import com.valerogarte.comicsreader.nativeapp.reader.DocumentSession
import com.valerogarte.comicsreader.nativeapp.reader.NativeDocumentEngine
import com.valerogarte.comicsreader.nativeapp.ui.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentSkipListMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

class LibraryViewModel(application: Application) : AndroidViewModel(application), AppActions {
    private val repository = LibraryRepository(application)
    private val engine = NativeDocumentEngine(application)
    private val preferences = application.getSharedPreferences("native-appearance", 0)
    private val mutableState = MutableStateFlow(AppUiState(settings = readAppearance()))
    val state = mutableState.asStateFlow()
    @Volatile private var books = emptyList<BookEntry>()
    @Volatile private var downloadedBooks = emptyMap<Pair<String, String>, BookEntry>()
    private class RemoteBrowser {
        var path = "/"
        var folder: RemoteFolder? = null
        var generation = 0
        var scanJob: Job? = null
        var scanMessage: String? = null
        var transferJob: Job? = null
        var transferGeneration = 0
        var transferringPaths = emptySet<String>()
        var transferMessage: String? = null
        var transferProgress: Float? = null
    }
    // Browser state is confined to the main thread; background callbacks publish through viewModelScope.
    private val browsers = mutableMapOf<String, RemoteBrowser>()
    private var importJob: Job? = null
    private var initializeJob: Job? = null
    private val settingsSave = Mutex()
    private val operationIds = AtomicLong()
    private val operationMessages = ConcurrentSkipListMap<Long, String>()
    private val emptyShelves = ArrayDeque<String>()
    private val readerLock = Mutex()
    private val libraryReload = Mutex()
    private var session: DocumentSession? = null
    @Volatile private var sessionOptions: DocumentOptions? = null
    private var sessionGeneration = -1
    @Volatile private var readerGeneration = 0
    private var renderJob: Job? = null

    init {
        initializeJob = launchOperation {
            try {
                val configs = repository.loadSftpConfigs()
                mutableState.update { it.copy(settings = it.settings.withSftp(configs), selectedSftpServerId = configs.firstOrNull()?.profileId) }
            } finally {
                // A damaged server configuration must not hide the independent local library.
                reloadLibrary()
            }
        }
    }

    override fun onNavigate(destination: LibraryDestination, shelfId: String?) {
        if (destination == LibraryDestination.SFTP) {
            val serverId = mutableState.value.selectedSftpServerId
                ?: mutableState.value.settings.sftpServers.firstOrNull()?.id
            if (serverId == null) onShowSettings() else onOpenSftpServer(serverId)
            return
        }
        mutableState.update { it.copy(destination = destination, selectedShelfId = shelfId, loading = false) }
        updateBusyMessage()
        viewModelScope.launch {
            try { reloadLibrary() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error) }
        }
    }

    override fun onOpenSftpServer(id: String) {
        if (mutableState.value.settings.sftpServers.none { it.id == id }) return
        val browser = browsers.getOrPut(id) { RemoteBrowser() }
        mutableState.update { it.copy(destination = LibraryDestination.SFTP, selectedSftpServerId = id,
            selectedShelfId = null, error = null) }
        publishRemote(id)
        if (browser.folder == null && browser.scanJob?.isActive != true) loadFolder(browser.path, serverId = id)
    }

    fun importBooks(uris: List<Uri>) {
        if (uris.isEmpty() || importJob?.isActive == true) return
        importJob = launchOperation("Importando libros") {
            var imported = 0
            var duplicates = 0
            val importedIds = books.mapTo(HashSet()) { it.id }
            val failures = ArrayList<String>()
            try {
                uris.forEachIndexed { index, uri ->
                    mutableState.update { it.copy(operationProgress = index.toFloat() / uris.size) }
                    try {
                        val book = repository.importBook(uri)
                        if (importedIds.add(book.id)) imported++ else duplicates++
                    }
                    catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failures += error.message ?: "Archivo no compatible" }
                }
            } finally {
                withContext(NonCancellable) { reloadLibrary() }
            }
            mutableState.update { it.copy(
                message = (if (imported == 1) "1 libro importado" else "$imported libros importados") +
                    (when (duplicates) { 0 -> ""; 1 -> ". 1 ya estaba en la biblioteca"; else -> ". $duplicates ya estaban en la biblioteca" }) +
                    (if (failures.isEmpty()) "" else ". ${failures.size} archivos no se han importado: ${failures.first()}"),
            ) }
        }
    }

    override fun onCreateShelf(name: String) {
        launchOperation { repository.createShelf(name); reloadLibrary() }
    }

    override fun onAddBooksToShelf(ids: List<String>, name: String) {
        launchOperation {
            repository.createShelf(name)
            try { ids.forEach { repository.addBookToShelf(it, name) } }
            finally { withContext(NonCancellable) { reloadLibrary() } }
        }
    }

    override fun onDeleteBooks(ids: List<String>) {
        launchOperation("Borrando definitivamente") {
            val affectedShelves = books.filter { it.id in ids }.mapNotNull { it.shelfName }.toSet()
            if (mutableState.value.reader?.bookId in ids) {
                val generation = ++readerGeneration
                renderJob?.cancelAndJoin()
                mutableState.update { it.copy(reader = null) }
                closeReaderSession(generation)
            }
            try { repository.deleteBooks(ids) }
            finally {
                withContext(NonCancellable) { reloadLibrary() }
                val newlyEmpty = affectedShelves.filter { shelf -> books.none { it.shelfName == shelf } }
                emptyShelves.addAll(newlyEmpty.filter { it !in emptyShelves })
                showNextEmptyShelf()
            }
        }
    }

    override fun onDeleteShelf(id: String) {
        launchOperation {
            repository.deleteShelf(id)
            emptyShelves.remove(id)
            mutableState.update {
                if (it.selectedShelfId == id) it.copy(destination = LibraryDestination.BOOKS, selectedShelfId = null) else it
            }
            reloadLibrary()
            showNextEmptyShelf()
        }
    }

    override fun onKeepEmptyShelf() {
        if (emptyShelves.isNotEmpty()) emptyShelves.removeFirst()
        showNextEmptyShelf()
    }

    private fun showNextEmptyShelf() {
        mutableState.update { it.copy(emptyShelf = emptyShelves.firstOrNull()?.let { name -> UiShelf(name, name) }) }
    }

    override fun onOpenRemoteFolder(path: String) = loadFolder(path)
    override fun onSyncRemote() = loadFolder(mutableState.value.remotePath, refresh = true)

    private fun loadFolder(path: String, refresh: Boolean = false, serverId: String? = mutableState.value.selectedSftpServerId) {
        if (serverId == null || mutableState.value.settings.sftpServers.none { it.id == serverId }) return
        val browser = browsers.getOrPut(serverId) { RemoteBrowser() }
        browser.scanJob?.cancel()
        val generation = ++browser.generation
        browser.folder = browser.folder?.takeIf { it.path == path }
        browser.path = path
        browser.scanMessage = "Leyendo carpeta"
        mutableState.update { if (it.selectedSftpServerId == serverId) it.copy(error = null) else it }
        publishRemote(serverId)
        var acceptingUpdates = true
        browser.scanJob = viewModelScope.launch {
            try {
                val folder = repository.loadRemoteFolder(serverId, path, refresh) { snapshot, completed, total ->
                    viewModelScope.launch {
                        if (acceptingUpdates && generation == browser.generation && browsers[serverId] === browser) {
                            browser.folder = snapshot
                            browser.scanMessage = if (total > 0 && completed < total) "Leyendo portadas y metadatos: $completed de $total" else null
                            publishRemote(serverId)
                        }
                    }
                }
                if (generation == browser.generation) {
                    browser.folder = folder
                    browser.path = folder.path
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == browser.generation && mutableState.value.selectedSftpServerId == serverId &&
                    mutableState.value.destination == LibraryDestination.SFTP) report(error)
            } finally {
                acceptingUpdates = false
                if (generation == browser.generation) {
                    browser.scanMessage = null
                    publishRemote(serverId)
                }
            }
        }
    }

    private fun publishRemote(serverId: String) {
        val browser = browsers[serverId] ?: return
        mutableState.update { current ->
            if (current.destination != LibraryDestination.SFTP || current.selectedSftpServerId != serverId) current
            else current.copy(remotePath = browser.path, remoteEntries = browser.folder?.let { mapRemote(it, browser) }.orEmpty(),
                loading = browser.scanMessage != null)
        }
        updateBusyMessage()
    }

    override fun onOpenRemoteBook(path: String) {
        val browser = currentBrowser() ?: return
        val folder = browser.folder ?: return
        val entry = folder.entries.find { it.path == path } ?: return
        val book = downloadedBooks[folder.sourceId to path]
        if (book != null) onOpenBook(book.id) else onDownloadRemote(entry.path)
    }

    override fun onDownloadRemote(path: String) {
        val serverId = mutableState.value.selectedSftpServerId ?: return
        val browser = browsers[serverId] ?: return
        if (browser.transferJob?.isActive == true) return
        val entry = browser.folder?.entries?.find { it.path == path && !it.isDirectory } ?: return
        browser.transferMessage = "Descargando ${entry.name}"
        browser.transferringPaths = setOf(path)
        browser.transferProgress = null
        val generation = ++browser.transferGeneration
        publishRemote(serverId)
        browser.transferJob = launchOperation {
            try {
                repository.downloadRemote(serverId, entry) { done, total ->
                    viewModelScope.launch {
                        if (browser.transferGeneration == generation && browser.transferMessage != null && browsers[serverId] === browser) {
                            browser.transferProgress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                            updateBusyMessage()
                        }
                    }
                }
            } finally {
                browser.transferringPaths = emptySet()
                browser.transferMessage = null
                browser.transferProgress = null
                withContext(NonCancellable) { reloadLibrary() }
                publishRemote(serverId)
            }
        }
    }

    override fun onDownloadAll() {
        val serverId = mutableState.value.selectedSftpServerId ?: return
        val browser = browsers[serverId] ?: return
        if (browser.transferJob?.isActive == true) return
        val folder = browser.folder ?: return
        browser.transferMessage = "Descargando carpeta"
        browser.transferringPaths = folder.entries.filter { !it.isDirectory && downloadedBooks[folder.sourceId to it.path] == null }.map { it.path }.toSet()
        browser.transferProgress = 0f
        val generation = ++browser.transferGeneration
        publishRemote(serverId)
        browser.transferJob = launchOperation {
            try {
                val result = repository.downloadAll(serverId, folder) { done, total ->
                    viewModelScope.launch {
                        if (browser.transferGeneration == generation && browser.transferMessage != null && browsers[serverId] === browser) {
                            browser.transferProgress = if (total > 0) done.toFloat() / total else 1f
                            updateBusyMessage()
                            reloadLibrary()
                            publishRemote(serverId)
                        }
                    }
                }
                if (result.failed > 0) mutableState.update { it.copy(error = "${serverName(serverId)}: ${result.failed} archivos no se han podido descargar") }
            } finally {
                browser.transferringPaths = emptySet()
                browser.transferMessage = null
                browser.transferProgress = null
                withContext(NonCancellable) { reloadLibrary() }
                publishRemote(serverId)
            }
        }
    }

    private fun currentBrowser(): RemoteBrowser? = mutableState.value.selectedSftpServerId?.let(browsers::get)
    private fun serverName(id: String): String = mutableState.value.settings.sftpServers.find { it.id == id }?.name ?: "SFTP"

    override fun onShowSettings() {
        viewModelScope.launch {
            initializeJob?.join()
            mutableState.update { it.copy(settingsVisible = true) }
        }
    }
    override fun onCloseSettings() { mutableState.update { it.copy(settingsVisible = false) } }

    override fun onSaveSettings(settings: UiSettings) {
        launchOperation("Guardando configuración") {
            settingsSave.withLock {
                val oldSettings = mutableState.value.settings
                // Saving an explicitly edited list can replace unreadable encrypted preferences.
                val oldConfigs = oldSettings.sftpServers.map { it.toConfig() }.associateBy { it.profileId }
                val configs = settings.sftpServers.map { it.toConfig() }
                val nextConfigs = configs.associateBy { it.profileId }
                val changed = oldConfigs.keys.filter { id ->
                    val old = oldConfigs.getValue(id)
                    val next = nextConfigs[id]
                    next == null || old.copy(name = next.name) != next
                }
                val cancelledJobs = changed.flatMap { id ->
                    browsers[id]?.let { browser ->
                        browser.generation++
                        listOfNotNull(browser.scanJob, browser.transferJob).onEach { it.cancel() }
                    }.orEmpty()
                }
                cancelledJobs.joinAll()
                // Clear cancelled work even when validation or encrypted persistence below fails.
                changed.forEach { id ->
                    browsers[id]?.apply {
                        scanMessage = null
                        transferMessage = null
                        transferProgress = null
                        transferringPaths = emptySet()
                    }
                    publishRemote(id)
                }
                repository.saveSftpConfigs(configs)
                val saved = repository.loadSftpConfigs()
                saved.forEach { config ->
                    val old = oldConfigs[config.profileId]
                    if (old != null && old.id != config.id) browsers.remove(config.profileId)
                    else browsers[config.profileId]?.apply { if (config.profileId in changed) { scanMessage = null; transferMessage = null } }
                }
                browsers.keys.retainAll(saved.map { it.profileId }.toSet())
                val selected = mutableState.value.selectedSftpServerId?.takeIf { id -> saved.any { it.profileId == id } }
                    ?: saved.firstOrNull()?.profileId
                writeAppearance(settings)
                mutableState.update { it.copy(settings = settings.withSftp(saved), settingsVisible = false, selectedSftpServerId = selected) }
                if (mutableState.value.destination == LibraryDestination.SFTP) {
                    if (selected == null) {
                        mutableState.update { it.copy(remotePath = "/", remoteEntries = emptyList(), loading = false) }
                        onNavigate(LibraryDestination.BOOKS)
                    } else onOpenSftpServer(selected)
                }
                updateBusyMessage()
                val reader = mutableState.value.reader
                if (reader?.reflowable == true && sessionOptions != documentOptions(settings)) {
                    val fraction = reader.pageIndex.toFloat() / (reader.pageCount - 1).coerceAtLeast(1)
                    openBook(reader.bookId, fraction)
                }
            }
        }
    }

    fun importFont(uri: Uri) {
        launchOperation {
            val file = copyAsset(uri, "reader-font", 16L * 1024 * 1024) { Typeface.createFromFile(it) }
            val settings = mutableState.value.settings.copy(font = "custom", fontPath = file.path)
            writeAppearance(settings)
            mutableState.update { it.copy(settings = settings) }
        }
    }

    fun importBackground(uri: Uri) {
        launchOperation {
            val file = copyAsset(uri, "library-background", 32L * 1024 * 1024) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(it.path, options)
                require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 200_000_000L) { "Selecciona una imagen válida" }
            }
            val settings = mutableState.value.settings.copy(backgroundImagePath = file.path)
            writeAppearance(settings)
            mutableState.update { it.copy(settings = settings) }
        }
    }

    private suspend fun copyAsset(uri: Uri, name: String, maximum: Long, validate: (File) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(getApplication<Application>().filesDir, "appearance").apply { mkdirs() }
        val part = File.createTempFile(name, ".part", directory)
        val destination = File(directory, "$name-${System.currentTimeMillis()}")
        try {
            getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                requireNotNull(input) { "No se puede abrir el archivo" }
                part.outputStream().use { output ->
                    val buffer = ByteArray(65536)
                    var size = 0L
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        size += count
                        require(size <= maximum) { "El archivo seleccionado es demasiado grande" }
                        output.write(buffer, 0, count)
                    }
                    output.fd.sync()
                }
            }
            validate(part)
            check(part.renameTo(destination)) { "No se puede guardar el archivo" }
            destination
        } finally { part.delete() }
    }

    override fun onOpenBook(id: String) = openBook(id)

    /** The ViewModel survives Activity recreation while the system theme can change. */
    fun onHostConfigurationChanged() {
        val reader = mutableState.value.reader ?: return
        if (!reader.reflowable || reader.loading || reader.error != null) return
        if (sessionOptions != documentOptions(mutableState.value.settings)) {
            val fraction = reader.pageIndex.toFloat() / (reader.pageCount - 1).coerceAtLeast(1)
            openBook(reader.bookId, fraction)
        }
    }

    private fun openBook(id: String, resumeFraction: Float? = null) {
        val generation = ++readerGeneration
        renderJob?.cancel()
        mutableState.update { it.copy(reader = UiReaderState(id, books.find { book -> book.id == id }?.title ?: "Libro", loading = true)) }
        renderJob = viewModelScope.launch {
            try {
                val book = repository.loadBook(id) ?: throw IOException("El libro ya no está en la biblioteca")
                val settings = mutableState.value.settings
                val options = documentOptions(settings)
                withContext(Dispatchers.IO) {
                    readerLock.withLock {
                        ensureActive()
                        if (generation != readerGeneration) throw CancellationException()
                        session?.close(); session = null; sessionOptions = null
                        val opened = engine.open(File(book.localPath), book.fileName, options)
                        try {
                            ensureActive()
                            if (generation != readerGeneration) throw CancellationException()
                            session = opened
                            sessionOptions = options
                            sessionGeneration = generation
                        } catch (error: Throwable) { opened.close(); throw error }
                    }
                }
                if (generation == readerGeneration) renderPage(book.currentPage, generation, id, book.title, resumeFraction)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == readerGeneration) mutableState.update { it.copy(reader = it.reader?.copy(loading = false, error = error.message ?: "No se puede abrir el libro")) }
            }
        }
    }

    override fun onReaderPage(pageIndex: Int) {
        val reader = mutableState.value.reader ?: return
        val page = pageIndex.coerceIn(0, (reader.pageCount - 1).coerceAtLeast(0))
        val generation = readerGeneration
        renderJob?.cancel()
        renderJob = viewModelScope.launch {
            try { renderPage(page, generation, reader.bookId, reader.title) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) {
                if (generation == readerGeneration) mutableState.update { it.copy(reader = it.reader?.copy(loading = false, error = error.message)) }
            }
        }
    }

    private suspend fun renderPage(pageIndex: Int, generation: Int, id: String, title: String, resumeFraction: Float? = null) {
        if (generation != readerGeneration) return
        mutableState.update { it.copy(reader = it.reader?.copy(loading = true, error = null)) }
        var producedBitmap: Bitmap? = null
        var published = false
        try {
            val rendered = withContext(Dispatchers.IO) {
                readerLock.withLock {
                    ensureActive()
                    if (sessionGeneration != generation || generation != readerGeneration) throw CancellationException()
                    val document = session ?: throw IOException("El lector está cerrado")
                    val count = document.pageCount.coerceAtLeast(1)
                    val page = (resumeFraction?.let { (it.coerceIn(0f, 1f) * (count - 1)).roundToInt() } ?: pageIndex).coerceIn(0, count - 1)
                    val bitmap = document.renderPage(page, 1080)
                    producedBitmap = bitmap
                    RenderedPage(bitmap, page, count, document.isReflowable)
                }
            }
            if (generation != readerGeneration) return
            mutableState.update { it.copy(reader = UiReaderState(id, title, rendered.page, rendered.count,
                bitmap = rendered.bitmap, reflowable = rendered.reflowable)) }
            published = true
            withContext(NonCancellable) { repository.saveProgress(id, rendered.page, rendered.count) }
            // A system change can also arrive while the previous page was still rendering.
            onHostConfigurationChanged()
        } finally {
            if (!published) producedBitmap?.recycle()
        }
    }

    private data class RenderedPage(val bitmap: Bitmap, val page: Int, val count: Int, val reflowable: Boolean)

    override fun onCloseReader() {
        val reader = mutableState.value.reader
        val generation = ++readerGeneration
        renderJob?.cancel()
        mutableState.update { it.copy(reader = null) }
        viewModelScope.launch {
            try {
                closeReaderSession(generation)
                if (generation == readerGeneration && reader?.bitmap != null && reader.error == null) {
                    repository.saveProgress(reader.bookId, reader.pageIndex, reader.pageCount)
                }
                reloadLibrary()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error) }
        }
    }

    private suspend fun closeReaderSession(generation: Int? = null) = withContext(Dispatchers.IO) {
        readerLock.withLock {
            if (generation == null || sessionGeneration <= generation) { session?.close(); session = null; sessionOptions = null }
        }
    }

    override fun onDismissMessage() { mutableState.update { it.copy(error = null, message = null) } }

    private suspend fun reloadLibrary() = libraryReload.withLock {
        val loadedBooks = repository.loadBooks()
        val shelves = repository.loadShelves().map { UiShelf(it.name, it.name) }
        val downloads = withContext(Dispatchers.IO) {
            loadedBooks.filter { it.sourceId != null && it.remotePath != null && File(it.localPath).isFile }
                .associateBy { requireNotNull(it.sourceId) to requireNotNull(it.remotePath) }
        }
        books = loadedBooks
        downloadedBooks = downloads
        mutableState.update { it.copy(
            books = loadedBooks.map { book -> UiBook(book.id, book.title, book.format, book.size, book.coverPath, listOfNotNull(book.shelfName), progress(book)) },
            shelves = shelves, remoteEntries = currentBrowser()?.let { browser -> browser.folder?.let { folder -> mapRemote(folder, browser) } } ?: it.remoteEntries,
            readingStatistics = readingStatistics(loadedBooks),
        ) }
    }

    private fun readingStatistics(books: List<BookEntry>): UiReadingStatistics {
        var unread = 0
        var reading = 0
        var finished = 0
        var progressPages = 0L
        var totalPages = 0L
        var knownPages = 0
        for (book in books) {
            when (book.readingStatus) {
                ReadingStatus.UNREAD -> unread++
                ReadingStatus.READING -> reading++
                ReadingStatus.FINISHED -> finished++
            }
            if (book.pageCount > 0) {
                knownPages++
                val count = book.pageCount.toLong()
                totalPages += count
                progressPages += when (book.readingStatus) {
                    ReadingStatus.UNREAD -> 0L
                    ReadingStatus.FINISHED -> count
                    ReadingStatus.READING -> (book.currentPage.toLong() + 1).coerceIn(0L, count)
                }
            }
        }
        return UiReadingStatistics(books.size, unread, reading, finished, progressPages, totalPages, knownPages)
    }

    private fun mapRemote(folder: RemoteFolder, browser: RemoteBrowser): List<UiRemoteEntry> = folder.entries.map { entry ->
        val book = downloadedBooks[folder.sourceId to entry.path]
        UiRemoteEntry(entry.path, entry.name, entry.isDirectory, entry.title, entry.format, entry.size, entry.coverPath,
            downloaded = book != null, downloading = entry.path in browser.transferringPaths, progress = book?.let(::progress) ?: 0f,
            fileCount = entry.fileCount)
    }

    private fun progress(book: BookEntry): Float = when (book.readingStatus) {
        ReadingStatus.UNREAD -> 0f
        ReadingStatus.FINISHED -> 1f
        ReadingStatus.READING -> ((book.currentPage + 1f) / book.pageCount.coerceAtLeast(1)).coerceIn(0.001f, 0.999f)
    }

    private fun launchOperation(message: String? = null, block: suspend () -> Unit): Job = viewModelScope.launch {
        val operation = operationIds.incrementAndGet()
        if (message != null) {
            operationMessages[operation] = message
            mutableState.update { it.copy(operationProgress = null) }
            updateBusyMessage()
        }
        try { block() }
        catch (cancelled: CancellationException) { throw cancelled }
        catch (error: Exception) { report(error) }
        finally {
            if (message != null) {
                operationMessages.remove(operation)
                mutableState.update { it.copy(operationProgress = if (operationMessages.isEmpty()) null else it.operationProgress) }
                updateBusyMessage()
            }
        }
    }

    private fun updateBusyMessage() {
        val selected = if (mutableState.value.destination == LibraryDestination.SFTP) currentBrowser() else null
        val transfers = browsers.filterValues { it.transferMessage != null }
        val transfer = if (selected?.transferMessage != null) selected else transfers.values.firstOrNull()
        val backgroundMessage = when {
            selected?.transferMessage != null -> selected.transferMessage
            transfers.size > 1 -> "Descargando desde ${transfers.size} servidores"
            transfers.size == 1 -> "${serverName(transfers.keys.first())}: ${transfer?.transferMessage}"
            else -> selected?.scanMessage
        }
        mutableState.update { it.copy(
            busyMessage = operationMessages.lastEntry()?.value ?: backgroundMessage,
            operationProgress = if (operationMessages.isNotEmpty()) it.operationProgress else if (transfers.size <= 1 || selected?.transferMessage != null) transfer?.transferProgress else null,
        ) }
    }

    private fun report(error: Exception) { mutableState.update { it.copy(error = error.message ?: "No se ha podido completar la operación") } }

    private fun UiSettings.withSftp(configs: List<SftpConfig>) = copy(sftpServers = configs.map { config ->
        UiSftpServer(config.profileId, config.name.ifBlank { config.host }, config.host, config.port.toString(),
            config.username, config.password, config.rootPath)
    })

    private fun UiSftpServer.toConfig(): SftpConfig {
        val parsedPort = port.toIntOrNull()
        require(parsedPort != null && parsedPort in 1..65535) { "El puerto SFTP debe estar entre 1 y 65535" }
        require(name.isNotBlank() && username.isNotBlank()) { "Indica el nombre y usuario del servidor" }
        return SftpConfig(host.trim(), parsedPort, username.trim(), password, rootPath.trim().ifBlank { "/" },
            profileId = id, name = name.trim())
    }

    private fun readAppearance() = UiSettings(
        theme = preferences.getString("theme", "system") ?: "system",
        background = preferences.getString("background", "cream") ?: "cream",
        font = preferences.getString("font", "sans") ?: "sans", fontSize = preferences.getFloat("fontSize", 18f),
        fontPath = preferences.getString("fontPath", null), backgroundImagePath = preferences.getString("backgroundImagePath", null),
        keepScreenOn = preferences.getBoolean("keepScreenOn", true), rightToLeft = preferences.getBoolean("rightToLeft", false),
    )

    private suspend fun writeAppearance(settings: UiSettings) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString("theme", settings.theme).putString("background", settings.background)
            .putString("font", settings.font).putFloat("fontSize", settings.fontSize).putString("fontPath", settings.fontPath)
            .putString("backgroundImagePath", settings.backgroundImagePath).putBoolean("keepScreenOn", settings.keepScreenOn)
            .putBoolean("rightToLeft", settings.rightToLeft).commit()) { "No se pudo guardar la apariencia" }
    }

    private fun documentOptions(settings: UiSettings): DocumentOptions {
        val systemDark = getApplication<Application>().resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val dark = settings.theme == "dark" || (settings.theme == "system" && systemDark)
        val background = when {
            dark -> Color.rgb(24, 23, 21)
            settings.background == "white" -> Color.WHITE
            settings.background == "sepia" -> Color.rgb(244, 232, 206)
            else -> Color.rgb(252, 250, 245)
        }
        return DocumentOptions(settings.fontSize, if (settings.font == "mono") "monospace" else settings.font,
            settings.fontPath.takeIf { settings.font == "custom" }, background,
            if (dark) Color.rgb(237, 232, 223) else Color.rgb(41, 36, 30))
    }

    override fun onCleared() {
        readerGeneration++
        val pending = viewModelScope.coroutineContext[Job]?.children?.toList().orEmpty()
        CoroutineScope(Dispatchers.IO).launch {
            pending.joinAll()
            closeReaderSession()
            repository.close()
        }
        super.onCleared()
    }
}
