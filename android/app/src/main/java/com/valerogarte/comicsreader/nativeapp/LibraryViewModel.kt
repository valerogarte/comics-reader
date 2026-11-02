package com.valerogarte.comicsreader.nativeapp

import android.app.Application
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Typeface
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.valerogarte.comicsreader.nativeapp.data.LibraryRepository
import com.valerogarte.comicsreader.nativeapp.model.*
import com.valerogarte.comicsreader.nativeapp.reader.DocumentOptions
import com.valerogarte.comicsreader.nativeapp.reader.DocumentSession
import com.valerogarte.comicsreader.nativeapp.reader.NativeDocumentEngine
import com.valerogarte.comicsreader.nativeapp.reader.ReadingFonts
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
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.roundToInt

private const val MAX_READING_GAP_MILLIS = 3 * 60_000L

class LibraryViewModel(application: Application) : AndroidViewModel(application), AppActions {
    private val repository = LibraryRepository(application)
    private val engine = NativeDocumentEngine(application)
    private val preferences = application.getSharedPreferences("native-appearance", 0)
    private val mutableState = MutableStateFlow(AppUiState(settings = readAppearance()))
    val state = mutableState.asStateFlow()
    @Volatile private var books = emptyList<BookEntry>()
    @Volatile private var downloadedBooks = emptyMap<Pair<String, String>, BookEntry>()
    @Volatile private var readingLog = emptyList<ReadingLogEntry>()
    @Volatile private var shelfPaths = emptyList<ShelfPath>()
    /** Catalog source id (host, user and root) to the server profile that reaches it. */
    @Volatile private var sourceProfiles = emptyMap<String, String>()
    /** Remote books to open as soon as their download finishes: (source id, path). */
    private val pendingOpen = mutableSetOf<Pair<String, String>>()
    private var guideJob: Job? = null
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
        // Downloads wait here while another one runs; each entry keeps its own path on this server.
        val queue = ArrayDeque<RemoteEntry>()
    }
    // Browser state is confined to the main thread; background callbacks publish through viewModelScope.
    private val browsers = mutableMapOf<String, RemoteBrowser>()
    private val importLock = Mutex()
    private var initializeJob: Job? = null
    private val settingsSave = Mutex()
    private val appearanceSave = Mutex()
    private var appearanceSaveJob: Job? = null
    @Volatile private var appearanceVersion = 0L
    @Volatile private var savedAppearanceVersion = 0L
    private var fontImportVersion = 0L
    private var backgroundImportVersion = 0L
    private val operationIds = AtomicLong()
    private val operationMessages = ConcurrentSkipListMap<Long, String>()
    private val emptyShelves = ArrayDeque<String>()
    private val readerLock = Mutex()
    private val readerProgressLock = Mutex()
    private val readerProgressVersions = ConcurrentHashMap<String, Long>()
    private val readerProgressIds = AtomicLong()
    private var readerProgressJob: Job? = null
    private val pageCache = LinkedHashMap<Int, Bitmap>(8, 0.75f, true)
    private var pageCacheBytes = 0L
    private val pageCacheLimit = 24L * 1024 * 1024
    private var readerNavigationRequest = 0L
    private var readerPositionSession = -1
    private var readerCommittedPage = 0
    // Reading time not yet saved, per book. Time between two page changes counts up to
    // MAX_READING_GAP_MILLIS, so a reader left open does not add hours; it stops in background.
    private val pendingReadingMillis = ConcurrentHashMap<String, Long>()
    private var readingBookId: String? = null
    private var readingTickAt = 0L
    private val libraryReload = Mutex()
    private var session: DocumentSession? = null
    @Volatile private var sessionOptions: DocumentOptions? = null
    private var sessionGeneration = -1
    @Volatile private var readerGeneration = 0
    private var renderJob: Job? = null
    private var readerAppearanceJob: Job? = null

    init {
        initializeJob = launchOperation {
            try {
                val configs = repository.loadSftpConfigs()
                sourceProfiles = configs.associate { it.id to it.profileId }
                mutableState.update { it.copy(settings = it.settings.withSftp(configs), selectedSftpServerId = configs.firstOrNull()?.profileId) }
            } finally {
                // A damaged server configuration must not hide the independent local library.
                reloadLibrary()
            }
        }
        viewModelScope.launch {
            try {
                initializeJob?.join()
                if (repository.migrateHistory()) reloadLibrary()
                importInboxGuides()
                if (repository.repairMissingCovers()) reloadLibrary()
                if (repository.refreshComicCovers()) reloadLibrary()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { }
        }
    }

    override fun onNavigate(destination: LibraryDestination, shelfId: String?) {
        if (destination == LibraryDestination.SFTP) {
            val serverId = mutableState.value.selectedSftpServerId
                ?: mutableState.value.settings.sftpServers.firstOrNull()?.id
            if (serverId == null) onShowSettings() else onOpenSftpServer(serverId)
            return
        }
        mutableState.update { it.copy(destination = destination, selectedShelfId = shelfId, loading = false,
            highlightedPaths = emptySet(), returnGuideId = null) }
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
            selectedShelfId = null, error = null, highlightedPaths = emptySet(), returnGuideId = null) }
        publishRemote(id)
        if (browser.folder == null && browser.scanJob?.isActive != true) loadFolder(browser.path, serverId = id)
    }

    fun importBooks(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // A second DocumentsUI result or ACTION_VIEW may arrive while the first
        // batch is still copying. Queue it instead of silently dropping its books.
        val selection = uris.toList()
        launchOperation("Importando libros") { importLock.withLock {
            var imported = 0
            var duplicates = 0
            val importedIds = books.mapTo(HashSet()) { it.id }
            val failures = ArrayList<String>()
            try {
                selection.forEachIndexed { index, uri ->
                    mutableState.update { it.copy(operationProgress = index.toFloat() / selection.size) }
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
        } }
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
        launchOperation("Eliminando del dispositivo") {
            ids.forEach { readerProgressVersions[it] = readerProgressIds.incrementAndGet() }
            if (mutableState.value.reader?.bookId in ids) {
                val generation = ++readerGeneration
                readerAppearanceJob?.cancel()
                readerProgressJob?.cancel()
                renderJob?.cancelAndJoin()
                mutableState.update { it.copy(reader = null) }
                closeReaderSession(generation)
            }
            try { readerProgressLock.withLock { repository.deleteBooks(ids) } }
            // Shelves are server folders now: cleaning books never leaves one to delete.
            finally { withContext(NonCancellable) { reloadLibrary() } }
        }
    }

    override fun onMarkBooksRead(ids: List<String>) {
        launchOperation {
            try { readerProgressLock.withLock { repository.markFinished(ids) } }
            finally { withContext(NonCancellable) { reloadLibrary() } }
        }
    }

    override fun onMarkBooksUnread(ids: List<String>) {
        launchOperation {
            try { readerProgressLock.withLock { repository.markUnread(ids) } }
            finally { withContext(NonCancellable) { reloadLibrary() } }
        }
    }

    /** Opens a book of the chronology; a cleaned book comes back from its server and opens. */
    override fun onOpenTimelineEntry(bookId: String) {
        val book = books.find { it.id == bookId && File(it.localPath).isFile }
        if (book != null) { openBook(book.id); return }
        val log = readingLog.find { it.bookId == bookId }
        val profile = log?.sourceId?.let(sourceProfiles::get)
        val path = log?.remotePath
        if (profile == null || path == null) {
            mutableState.update { it.copy(error = "No se sabe de qué servidor procede este libro") }
            return
        }
        val folder = remoteParentPath(path)
        browsers.getOrPut(profile) { RemoteBrowser() }.apply { if (this.path != folder) { this.path = folder; this.folder = null } }
        mutableState.update { it.copy(destination = LibraryDestination.SFTP, selectedSftpServerId = profile, selectedShelfId = null) }
        loadFolder(folder, serverId = profile)
        downloadPath(profile, path, open = true)
    }

    /** Queues one server path, known from the catalog or not; with [open] the book opens when ready. */
    private fun downloadPath(serverId: String, path: String, open: Boolean) {
        viewModelScope.launch {
            try {
                val (sourceId, _) = repository.serverRoot(serverId) ?: throw IOException("El servidor SFTP ya no está configurado")
                val normalized = LibraryRepository.normalizePath(path)
                downloadedBooks[sourceId to normalized]?.let { if (open) openBook(it.id); return@launch }
                val entry = repository.cachedRemoteEntry(sourceId, normalized)?.takeIf { !it.isDirectory }
                    ?: RemoteEntry(normalized.substringAfterLast('/'), normalized, false, sourceId = sourceId)
                if (open) pendingOpen += sourceId to normalized
                val browser = browsers.getOrPut(serverId) { RemoteBrowser() }
                if (browser.queue.none { it.path == normalized } && normalized !in browser.transferringPaths) browser.queue.addLast(entry)
                if (browser.transferJob?.isActive == true) {
                    browser.transferringPaths.firstOrNull()?.let { active -> browser.transferMessage = downloadMessage(browser, active.substringAfterLast('/')) }
                    publishRemote(serverId); updateBusyMessage()
                } else startDownloadQueue(serverId, browser)
                publishGuide()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error) }
        }
    }

    override fun onAddCurrentFolderToShelves() {
        val serverId = mutableState.value.selectedSftpServerId ?: return
        val path = mutableState.value.remotePath
        val existing = shelfPaths.find { it.profileId == serverId && it.path == path }
        launchOperation {
            if (existing != null) {
                repository.deleteShelfPath(existing.id)
            } else {
                val name = path.trimEnd('/').substringAfterLast('/').ifBlank { serverName(serverId) }
                repository.addShelfPath(serverId, path, name)
            }
            reloadLibrary()
        }
    }

    /** Online the shelf opens its server folder; without the server, its downloaded books. */
    override fun onOpenShelfPath(id: String) {
        val shelf = shelfPaths.find { it.id == id } ?: return
        launchOperation("Conectando con ${serverName(shelf.profileId)}") {
            val reachable = mutableState.value.settings.sftpServers.any { it.id == shelf.profileId } &&
                repository.isServerReachable(shelf.profileId)
            if (reachable) {
                browsers.getOrPut(shelf.profileId) { RemoteBrowser() }.apply { if (path != shelf.path) { path = shelf.path; folder = null } }
                mutableState.update { it.copy(destination = LibraryDestination.SFTP, selectedSftpServerId = shelf.profileId, selectedShelfId = null) }
                loadFolder(shelf.path, serverId = shelf.profileId)
            } else {
                mutableState.update { it.copy(destination = LibraryDestination.SHELF, selectedShelfId = id) }
                reloadLibrary()
            }
        }
    }

    override fun onDeleteShelfPath(id: String) {
        launchOperation {
            repository.deleteShelfPath(id)
            mutableState.update { if (it.selectedShelfId == id) it.copy(destination = LibraryDestination.BOOKS, selectedShelfId = null) else it }
            reloadLibrary()
        }
    }

    override fun onOpenGuide(id: String) {
        mutableState.update { it.copy(destination = LibraryDestination.GUIDE, selectedGuideId = id,
            highlightedPaths = emptySet(), returnGuideId = null,
            guide = it.guide?.takeIf { guide -> guide.id == id }) }
        publishGuide(readFolders = true)
    }

    override fun onRefreshGuide() = publishGuide(readFolders = true, refresh = true)

    /** Opens the server folder that holds a guide entry and highlights its books there. */
    override fun onRevealGuideItem(key: String) {
        val guide = mutableState.value.guide ?: return
        val item = guide.items.find { it.key == key } ?: return
        val target = item.files.firstOrNull { it.progress < 1f && it.available != false }
            ?: item.files.firstOrNull { it.available != false } ?: return
        viewModelScope.launch {
            try {
                val context = loadGuideContext(guide.id) ?: return@launch
                val profile = context.profile ?: throw IOException("Configura el servidor SFTP de la guía «${guide.title}»")
                val folder = remoteParentPath(target.path)
                val inFolder = item.files.map { it.path }.filter { remoteParentPath(it) == folder }.toSet()
                browsers.getOrPut(profile) { RemoteBrowser() }.apply { if (path != folder) { path = folder; this.folder = null } }
                mutableState.update { it.copy(destination = LibraryDestination.SFTP, selectedSftpServerId = profile,
                    selectedShelfId = null, highlightedPaths = inFolder, returnGuideId = guide.id) }
                loadFolder(folder, serverId = profile)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error) }
        }
    }

    /** Queues every book of one guide entry that is on the server and not on the device. */
    override fun onDownloadGuideItem(key: String) {
        val guide = mutableState.value.guide ?: return
        val item = guide.items.find { it.key == key } ?: return
        downloadGuideFiles(guide, item.files)
    }

    private fun downloadGuideFiles(guide: UiGuideView, files: List<UiGuideFile>) {
        val paths = files.filter { it.bookId == null && it.available != false && !it.downloading }.map { it.path }
        if (paths.isEmpty()) return
        viewModelScope.launch {
            try {
                val context = loadGuideContext(guide.id) ?: return@launch
                val profile = context.profile ?: throw IOException("Configura el servidor SFTP de la guía «${guide.title}»")
                paths.forEach { downloadPath(profile, it, open = false) }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error) }
        }
    }

    /** Queues every book of one guide section that is on the server and not on the device. */
    override fun onDownloadGuideSection(sectionIndex: Int) {
        val guide = mutableState.value.guide ?: return
        val section = guide.sections.getOrNull(sectionIndex) ?: return
        downloadGuideFiles(guide, section.items.flatMap { it.files })
    }

    override fun onOpenGuideFile(path: String) {
        val guideId = mutableState.value.selectedGuideId ?: return
        viewModelScope.launch {
            try {
                val (guide, profile, sourceId, root) = loadGuideContext(guideId) ?: return@launch
                if (profile == null || sourceId == null) {
                    mutableState.update { it.copy(error = "Configura el servidor SFTP de la guía «${guide.title}»") }
                    return@launch
                }
                val relative = guideRelativePath(path, root)
                val book = downloadedBooks[sourceId to relative]
                if (book != null) openBook(book.id) else downloadPath(profile, relative, open = true)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error) }
        }
    }

    override fun onDeleteGuide(id: String) {
        launchOperation {
            repository.deleteGuide(id)
            mutableState.update { if (it.selectedGuideId == id) it.copy(destination = LibraryDestination.BOOKS, selectedGuideId = null, guide = null) else it }
            reloadLibrary()
        }
    }

    fun importGuide(uri: Uri) {
        launchOperation("Importando guía") {
            val text = withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openInputStream(uri).use { input ->
                    requireNotNull(input) { "No se puede abrir el archivo" }
                    val bytes = input.readBytes()
                    require(bytes.size <= 8 * 1024 * 1024) { "La guía es demasiado grande" }
                    bytes.toString(Charsets.UTF_8)
                }
            }
            val guide = saveGuideText(text)
            reloadLibrary()
            onOpenGuide(guide.id)
        }
    }

    fun exportGuide(id: String, uri: Uri) {
        launchOperation("Exportando guía") {
            val stored = repository.loadGuides().find { it.id == id } ?: throw IOException("La guía ya no existe")
            withContext(Dispatchers.IO) {
                getApplication<Application>().contentResolver.openOutputStream(uri, "wt").use { output ->
                    requireNotNull(output) { "No se puede escribir el archivo" }
                    output.write(stored.json.toByteArray(Charsets.UTF_8))
                }
            }
            mutableState.update { it.copy(message = "Guía «${stored.title}» exportada") }
        }
    }

    fun guideTitle(id: String): String? = mutableState.value.guides.find { it.id == id }?.title

    private suspend fun saveGuideText(text: String): ReadingGuide {
        val guide = withContext(Dispatchers.Default) { GuideJson.parse(text) }
        repository.saveGuide(StoredGuide(guide.id, guide.title, GuideJson.write(guide), System.currentTimeMillis()))
        return guide
    }

    private suspend fun importInboxGuides() {
        val texts = repository.takeInboxGuides()
        if (texts.isEmpty()) return
        var imported = 0
        texts.forEach { text -> try { saveGuideText(text); imported++ } catch (cancelled: CancellationException) { throw cancelled } catch (_: Exception) { } }
        reloadLibrary()
        if (imported > 0) mutableState.update { it.copy(message = if (imported == 1) "1 guía de lectura importada" else "$imported guías de lectura importadas") }
    }

    private data class GuideContext(val guide: ReadingGuide, val profile: String?, val sourceId: String?, val root: String)

    private suspend fun loadGuideContext(id: String): GuideContext? {
        val stored = repository.loadGuides().find { it.id == id } ?: return null
        val guide = withContext(Dispatchers.Default) { GuideJson.parse(stored.json) }
        val servers = mutableState.value.settings.sftpServers
        val server = guide.server?.let { name -> servers.find { it.name.equals(name, true) || it.host.equals(name, true) } } ?: servers.firstOrNull()
        val root = server?.let { repository.serverRoot(it.id) }
        return GuideContext(guide, server?.id, root?.first, root?.second ?: "/")
    }

    /** Guides may name files by their full path on the server; the profile root is removed. */
    private fun guideRelativePath(path: String, root: String): String {
        val clean = "/" + path.trim().trim('/')
        // Synology's SFTP shows shared folders at the top: /volume1/recursos is /recursos there.
        val shared = clean.replace(Regex("^/volume\\d+(?=/)"), "")
        val prefix = root.trimEnd('/')
        val relative = listOf(clean, shared).firstOrNull { prefix.isNotEmpty() && (it == prefix || it.startsWith("$prefix/")) }
            ?.removePrefix(prefix) ?: shared
        return LibraryRepository.normalizePath(relative.ifBlank { "/" })
    }

    private fun publishGuide(readFolders: Boolean = false, refresh: Boolean = false) {
        val id = mutableState.value.selectedGuideId ?: return
        if (mutableState.value.destination != LibraryDestination.GUIDE) return
        // A folder check in progress keeps running; library changes only redraw the guide.
        if (!readFolders && guideJob?.isActive == true) {
            viewModelScope.launch {
                try { showGuide(id, mutableState.value.guide?.checking == true) }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { }
            }
            return
        }
        guideJob?.cancel()
        guideJob = viewModelScope.launch {
            try {
                showGuide(id, readFolders)
                if (!readFolders) return@launch
                val context = loadGuideContext(id) ?: return@launch
                val profile = context.profile
                val sourceId = context.sourceId
                if (profile != null && sourceId != null) {
                    val folders = context.guide.items.flatMap { it.files }.map { remoteParentPath(guideRelativePath(it, context.root)) }.distinct()
                    for (folder in folders) {
                        if (!refresh && repository.isFolderCached(sourceId, folder)) continue
                        try { repository.loadRemoteFolder(profile, folder, refresh) }
                        catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) { }
                        showGuide(id, true)
                    }
                }
                showGuide(id, false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error) }
        }
    }

    private suspend fun showGuide(id: String, checking: Boolean) {
        val view = resolveGuide(id, checking)
        mutableState.update { if (it.selectedGuideId == id) it.copy(guide = view) else it }
    }

    private suspend fun resolveGuide(id: String, checking: Boolean): UiGuideView? {
        val context = loadGuideContext(id) ?: return null
        val sourceId = context.sourceId
        val logs = readingLog.filter { it.sourceId != null && it.remotePath != null }.associateBy { it.sourceId!! to it.remotePath!! }
        val transferring = browsers.values.flatMap { it.transferringPaths + it.queue.map { entry -> entry.path } }.toSet()
        val folderKnown = HashMap<String, Boolean>()
        val sections = context.guide.sections.map { section ->
            UiGuideSection(section.title, section.items.map { item ->
                val files = item.files.map { original ->
                    val path = guideRelativePath(original, context.root)
                    val name = path.substringAfterLast('/')
                    if (sourceId == null) return@map UiGuideFile(path, name)
                    val book = downloadedBooks[sourceId to path]
                    val entry = repository.cachedRemoteEntry(sourceId, path)
                    val log = logs[sourceId to path]
                    val parent = remoteParentPath(path)
                    val known = folderKnown.getOrPut(parent) { repository.isFolderCached(sourceId, parent) }
                    val progress = book?.let(::progress) ?: when {
                        log?.finishedAt != null -> 1f
                        log?.startedAt != null -> 0.5f
                        else -> 0f
                    }
                    UiGuideFile(path, entry?.title ?: name.substringBeforeLast('.'), entry?.coverPath ?: book?.coverPath ?: log?.coverPath,
                        book?.id, progress, available = when { book != null || entry != null -> true; known -> false; else -> null },
                        downloading = path in transferring)
                }
                val status = when {
                    files.isEmpty() -> GuideItemStatus.MISSING
                    files.all { it.progress >= 1f } -> GuideItemStatus.READ
                    files.any { it.progress > 0f } -> GuideItemStatus.READING
                    files.all { it.available == true } -> GuideItemStatus.AVAILABLE
                    files.any { it.available == true } -> GuideItemStatus.PARTIAL
                    files.all { it.available == false } -> GuideItemStatus.MISSING
                    else -> GuideItemStatus.UNKNOWN
                }
                UiGuideItem(item.key, item.number, item.title, item.detail, item.scope, item.priority, item.note, files, status)
            })
        }
        val serverName = context.profile?.let(::serverName)
        return UiGuideView(context.guide.id, context.guide.title, context.guide.description, serverName, sections, checking)
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

    override fun onOpenRemoteFolder(path: String) {
        // Moving to another folder ends the "shown from the guide" highlight; Back works normally again.
        mutableState.update { it.copy(highlightedPaths = emptySet(), returnGuideId = null) }
        loadFolder(path)
    }
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
        // The guide shows which of its books are queued or downloading.
        if (mutableState.value.destination == LibraryDestination.GUIDE) publishGuide()
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

    override fun onDownloadRemote(path: String) = onDownloadRemotePaths(listOf(path))

    override fun onDownloadAll() {
        val folder = currentBrowser()?.folder ?: return
        onDownloadRemotePaths(folder.entries.filter { !it.isDirectory }.map { it.path })
    }

    /** Queues downloads; they keep running in order while browsing other folders or servers. */
    override fun onDownloadRemotePaths(paths: List<String>) {
        val serverId = mutableState.value.selectedSftpServerId ?: return
        val browser = browsers[serverId] ?: return
        val folder = browser.folder ?: return
        val waiting = browser.queue.mapTo(HashSet()) { it.path } + browser.transferringPaths
        val wanted = paths.toSet()
        folder.entries.filter { !it.isDirectory && it.path in wanted && it.path !in waiting && downloadedBooks[folder.sourceId to it.path] == null }
            .forEach { browser.queue.addLast(it) }
        if (browser.transferJob?.isActive == true) {
            browser.transferringPaths.firstOrNull()?.let { active ->
                browser.transferMessage = downloadMessage(browser, active.substringAfterLast('/'))
            }
            publishRemote(serverId)
        } else startDownloadQueue(serverId, browser)
    }

    /** Stops the download on screen (or every download) and empties its queue; partial files are removed. */
    override fun onCancelDownloads() {
        val shown = currentBrowser()?.takeIf { it.transferJob?.isActive == true }
        val targets = shown?.let(::listOf) ?: browsers.values.filter { it.transferJob?.isActive == true }
        targets.forEach { browser ->
            browser.queue.clear()
            browser.transferJob?.cancel()
        }
    }

    private fun downloadMessage(browser: RemoteBrowser, name: String) =
        if (browser.queue.isEmpty()) "Descargando $name" else "Descargando $name · ${browser.queue.size} en cola"

    private fun startDownloadQueue(serverId: String, browser: RemoteBrowser) {
        if (browser.queue.isEmpty()) return
        val generation = ++browser.transferGeneration
        browser.transferJob = launchOperation {
            var failed = 0
            var lastError: Exception? = null
            try {
                // Browser state is main-thread only: no other code runs between taking the last
                // entry and finishing, so a download queued meanwhile always starts a new run.
                while (true) {
                    val entry = browser.queue.removeFirstOrNull() ?: break
                    browser.transferringPaths = setOf(entry.path)
                    browser.transferMessage = downloadMessage(browser, entry.name)
                    browser.transferProgress = null
                    publishRemote(serverId)
                    try {
                        repository.downloadRemote(serverId, entry) { done, total ->
                            viewModelScope.launch {
                                if (browser.transferGeneration == generation && entry.path in browser.transferringPaths && browsers[serverId] === browser) {
                                    browser.transferProgress = if (total > 0) (done.toFloat() / total).coerceIn(0f, 1f) else null
                                    updateBusyMessage()
                                }
                            }
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) { failed += 1; lastError = error }
                    // The book stays marked as downloading until the library knows its local copy.
                    withContext(NonCancellable) { reloadLibrary() }
                    browser.transferringPaths = emptySet()
                    publishRemote(serverId)
                    entry.sourceId?.let { source ->
                        if (pendingOpen.remove(source to entry.path) && mutableState.value.reader == null)
                            downloadedBooks[source to entry.path]?.let { openBook(it.id) }
                    }
                }
                if (failed == 1) lastError?.let(::report)
                else if (failed > 1) mutableState.update { it.copy(error = "${serverName(serverId)}: $failed archivos no se han podido descargar") }
            } finally {
                if (browser.transferGeneration == generation) {
                    if (browser.queue.isNotEmpty() && !currentCoroutineContext().isActive) browser.queue.clear()
                    browser.transferringPaths = emptySet()
                    browser.transferMessage = null
                    browser.transferProgress = null
                    publishRemote(serverId)
                }
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
    override fun onCloseSettings() {
        mutableState.update { it.copy(settingsVisible = false) }
        scheduleAppearanceSave(immediate = true)
    }

    override fun onUpdateAppearanceSettings(update: (UiSettings) -> UiSettings) {
        if (applyAppearanceUpdate(update)) scheduleAppearanceSave()
    }

    override fun onUpdateSftpServers(update: (List<UiSftpServer>) -> List<UiSftpServer>) {
        launchOperation("Guardando servidores") {
            settingsSave.withLock {
                saveSftpServers(update(mutableState.value.settings.sftpServers))
            }
        }
    }

    override fun onSaveSettings(settings: UiSettings) {
        launchOperation("Guardando configuración") {
            settingsSave.withLock {
                // Preserve the full-snapshot action for existing callers; unchanged servers
                // require no encrypted reads, writes, channel cancellation or reconnection.
                if (settings.sftpServers != mutableState.value.settings.sftpServers) saveSftpServers(settings.sftpServers)
                applyAppearanceUpdate { settings }
                appearanceSaveJob?.cancel()
                persistAppearanceLatest()
                refreshReaderAppearance()
                mutableState.update { it.copy(settingsVisible = false) }
            }
        }
    }

    private fun applyAppearanceUpdate(update: (UiSettings) -> UiSettings): Boolean {
        val current = mutableState.value.settings
        val updated = update(current).copy(sftpServers = current.sftpServers)
        if (updated == current) return false
        if (updated.font != current.font || updated.fontPath != current.fontPath) fontImportVersion++
        if (updated.backgroundImagePath != current.backgroundImagePath) backgroundImportVersion++
        mutableState.update { it.copy(settings = updated.copy(sftpServers = it.settings.sftpServers)) }
        appearanceVersion++
        return true
    }

    private fun scheduleAppearanceSave(immediate: Boolean = false) {
        appearanceSaveJob?.cancel()
        if (appearanceVersion == savedAppearanceVersion) {
            if (immediate) refreshReaderAppearance()
            return
        }
        appearanceSaveJob = viewModelScope.launch {
            try {
                if (!immediate) delay(250)
                persistAppearanceLatest()
                refreshReaderAppearance()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error) }
        }
    }

    private suspend fun persistAppearanceLatest() = withContext(NonCancellable) {
        appearanceSave.withLock {
            val version = appearanceVersion
            if (version != savedAppearanceVersion) {
                writeAppearance(mutableState.value.settings)
                savedAppearanceVersion = version
            }
        }
    }

    private fun refreshReaderAppearance() {
        val reader = mutableState.value.reader ?: return
        if (!reader.reflowable) return
        val settings = mutableState.value.settings
        val version = appearanceVersion
        val generation = readerGeneration
        readerAppearanceJob?.cancel()
        readerAppearanceJob = viewModelScope.launch {
            try {
                val options = withContext(Dispatchers.IO) { documentOptions(settings) }
                if (generation != readerGeneration || version != appearanceVersion) return@launch
                val current = mutableState.value.reader ?: return@launch
                if (current.bookId == reader.bookId && current.sessionId == reader.sessionId && sessionOptions != options) {
                    val fraction = current.pageIndex.toFloat() / (current.pageCount - 1).coerceAtLeast(1)
                    openBook(current.bookId, fraction)
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error) }
        }
    }

    /** Called under settingsSave; appearance changes may continue while server persistence waits. */
    private suspend fun saveSftpServers(servers: List<UiSftpServer>) {
        val oldServers = mutableState.value.settings.sftpServers
        if (servers == oldServers) return
        val oldConfigs = oldServers.map { it.toConfig() }.associateBy { it.profileId }
        val configs = servers.map { it.toConfig() }
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
        changed.forEach { id ->
            browsers[id]?.apply {
                scanMessage = null; transferMessage = null; transferProgress = null; transferringPaths = emptySet()
            }
            publishRemote(id)
        }
        repository.saveSftpConfigs(configs)
        val saved = repository.loadSftpConfigs()
        sourceProfiles = saved.associate { it.id to it.profileId }
        saved.forEach { config ->
            val old = oldConfigs[config.profileId]
            if (old != null && old.id != config.id) browsers.remove(config.profileId)
        }
        browsers.keys.retainAll(saved.map { it.profileId }.toSet())
        val selected = mutableState.value.selectedSftpServerId?.takeIf { id -> saved.any { it.profileId == id } }
            ?: saved.firstOrNull()?.profileId
        mutableState.update { it.copy(settings = it.settings.withSftp(saved), selectedSftpServerId = selected) }
        reloadLibrary()
        if (mutableState.value.destination == LibraryDestination.SFTP) {
            if (selected == null) {
                mutableState.update { it.copy(remotePath = "/", remoteEntries = emptyList(), loading = false) }
                onNavigate(LibraryDestination.BOOKS)
            } else onOpenSftpServer(selected)
        }
        updateBusyMessage()
    }

    fun importFont(uri: Uri) {
        val version = ++fontImportVersion
        launchOperation {
            val file = copyAsset(uri, "reader-font", 16L * 1024 * 1024) { Typeface.createFromFile(it) }
            if (version != fontImportVersion) { file.delete(); return@launchOperation }
            onUpdateAppearanceSettings { it.copy(font = "custom", fontPath = file.path) }
        }
    }

    fun importBackground(uri: Uri) {
        val version = ++backgroundImportVersion
        launchOperation {
            val file = copyAsset(uri, "library-background", 32L * 1024 * 1024) {
                val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(it.path, options)
                require(options.outWidth > 0 && options.outHeight > 0 && options.outWidth.toLong() * options.outHeight <= 200_000_000L) { "Selecciona una imagen válida" }
            }
            if (version != backgroundImportVersion) { file.delete(); return@launchOperation }
            onUpdateAppearanceSettings { it.copy(backgroundImagePath = file.path) }
        }
    }

    private suspend fun copyAsset(uri: Uri, name: String, maximum: Long, validate: (File) -> Unit): File = withContext(Dispatchers.IO) {
        val directory = File(getApplication<Application>().filesDir, "appearance").apply { mkdirs() }
        val part = File.createTempFile(name, ".part", directory)
        val destination = File(directory, "$name-${java.util.UUID.randomUUID()}")
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
        refreshReaderAppearance()
    }

    private fun openBook(id: String, resumeFraction: Float? = null) {
        flushReaderProgress()
        readingBookId = id
        readingTickAt = SystemClock.elapsedRealtime()
        val generation = ++readerGeneration
        readerAppearanceJob?.cancel()
        renderJob?.cancel()
        mutableState.update { it.copy(reader = UiReaderState(id, books.find { book -> book.id == id }?.title ?: "Libro",
            loading = true, sessionId = generation, navigationRequest = ++readerNavigationRequest)) }
        renderJob = viewModelScope.launch {
            try {
                val book = readerProgressLock.withLock { repository.loadBook(id) }
                    ?: throw IOException("El libro ya no está en la biblioteca")
                val settings = mutableState.value.settings
                withContext(Dispatchers.IO) {
                    val options = documentOptions(settings)
                    readerLock.withLock {
                        ensureActive()
                        if (generation != readerGeneration) throw CancellationException()
                        session?.close(); session = null; sessionOptions = null
                        clearPageCache()
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
        if (reader.loading || reader.error != null || reader.sessionId != readerGeneration) return
        val page = pageIndex.coerceIn(0, (reader.pageCount - 1).coerceAtLeast(0))
        // Both reading layouts load their own pages. This is a navigation request,
        // not a confirmation that the requested page has reached the viewport.
        mutableState.update { it.copy(reader = reader.copy(pageIndex = page, navigationRequest = ++readerNavigationRequest)) }
    }

    override suspend fun loadReaderPage(bookId: String, sessionId: Int, pageIndex: Int): Bitmap? {
        val reader = mutableState.value.reader ?: return null
        if (reader.bookId != bookId || reader.sessionId != sessionId || readerGeneration != sessionId) return null
        val bitmap = withContext(Dispatchers.IO) {
            readerLock.withLock {
                ensureActive()
                val current = mutableState.value.reader ?: return@withLock null
                if (sessionGeneration != sessionId || readerGeneration != sessionId || current.bookId != bookId) return@withLock null
                val document = session ?: return@withLock null
                require(pageIndex in 0 until document.pageCount) { "Página fuera del documento" }
                // A very large page may be deliberately excluded from the bounded cache.
                current.bitmap?.takeIf { current.bitmapPageIndex == pageIndex && !it.isRecycled }
                    ?: cachedReaderPage(document, pageIndex, sessionId)
            }
        }
        currentCoroutineContext().ensureActive()
        return bitmap.takeIf { readerGeneration == sessionId && mutableState.value.reader?.bookId == bookId }
    }

    override fun onReaderPosition(bookId: String, sessionId: Int, navigationRequest: Long, pageIndex: Int) {
        val reader = mutableState.value.reader ?: return
        if (reader.loading || reader.error != null ||
            reader.bookId != bookId || reader.sessionId != sessionId || readerGeneration != sessionId ||
            reader.navigationRequest != navigationRequest) return
        val page = pageIndex.coerceIn(0, (reader.pageCount - 1).coerceAtLeast(0))
        if (page != reader.pageIndex) mutableState.update { it.copy(reader = reader.copy(pageIndex = page)) }
        readerCommittedPage = page
        tickReading()
        scheduleReaderProgress()
    }

    private fun tickReading() {
        if (readingTickAt == 0L) return
        val now = SystemClock.elapsedRealtime()
        readingBookId?.let { pendingReadingMillis.merge(it, (now - readingTickAt).coerceIn(0L, MAX_READING_GAP_MILLIS), Long::plus) }
        readingTickAt = now
    }

    /** Only time with the reader on screen counts as reading. */
    fun onHostVisible(visible: Boolean) {
        if (visible) {
            if (readingBookId != null && readingTickAt == 0L) readingTickAt = SystemClock.elapsedRealtime()
        } else {
            flushReaderProgress()
            readingTickAt = 0L
        }
    }

    private suspend fun renderPage(pageIndex: Int, generation: Int, id: String, title: String, resumeFraction: Float? = null) {
        if (generation != readerGeneration) return
        mutableState.update { it.copy(reader = it.reader?.copy(loading = true, error = null)) }
        val rendered = withContext(Dispatchers.IO) {
            readerLock.withLock {
                ensureActive()
                if (sessionGeneration != generation || generation != readerGeneration) throw CancellationException()
                val document = session ?: throw IOException("El lector está cerrado")
                val count = document.pageCount.coerceAtLeast(1)
                val page = (resumeFraction?.let { (it.coerceIn(0f, 1f) * (count - 1)).roundToInt() } ?: pageIndex).coerceIn(0, count - 1)
                val bitmap = cachedReaderPage(document, page, generation)
                RenderedPage(bitmap, page, count, document.isReflowable)
            }
        }
        if (generation != readerGeneration) return
        mutableState.update { it.copy(reader = UiReaderState(id, title, rendered.page, rendered.count,
            bitmap = rendered.bitmap, reflowable = rendered.reflowable, sessionId = generation,
            navigationRequest = ++readerNavigationRequest, bitmapPageIndex = rendered.page)) }
        readerPositionSession = generation
        readerCommittedPage = rendered.page
        readerProgressJob?.cancel()
        captureReaderProgress()?.let { persistReaderProgress(it) }
        // A system change can also arrive while the previous page was still rendering.
        onHostConfigurationChanged()
    }

    /** All cache accesses happen under readerLock. Published pages are borrowed, never recycled. */
    private suspend fun cachedReaderPage(document: DocumentSession, page: Int, generation: Int): Bitmap {
        pageCache[page]?.let { if (!it.isRecycled) return it }
        val bitmap = document.renderPage(page, 1080)
        try {
            currentCoroutineContext().ensureActive()
            if (generation != readerGeneration || generation != sessionGeneration) throw CancellationException()
            val bytes = bitmap.allocationByteCount.toLong()
            if (bytes <= pageCacheLimit) {
                val iterator = pageCache.entries.iterator()
                while (pageCacheBytes + bytes > pageCacheLimit && iterator.hasNext()) {
                    pageCacheBytes -= iterator.next().value.allocationByteCount.toLong()
                    iterator.remove()
                }
                pageCache.put(page, bitmap)?.let { pageCacheBytes -= it.allocationByteCount.toLong() }
                pageCacheBytes += bytes
            }
            return bitmap
        } catch (error: Throwable) {
            bitmap.recycle()
            throw error
        }
    }

    private fun clearPageCache() { pageCache.clear(); pageCacheBytes = 0 }

    private data class ReaderProgress(val bookId: String, val page: Int, val total: Int, val version: Long)

    private fun captureReaderProgress(): ReaderProgress? {
        tickReading()
        val reader = mutableState.value.reader ?: return null
        if (reader.sessionId != readerPositionSession) return null
        val version = readerProgressIds.incrementAndGet()
        readerProgressVersions[reader.bookId] = version
        return ReaderProgress(reader.bookId, readerCommittedPage, reader.pageCount, version)
    }

    private suspend fun persistReaderProgress(progress: ReaderProgress) = withContext(NonCancellable) {
        readerProgressLock.withLock {
            if (readerProgressVersions[progress.bookId] == progress.version) {
                // Taken when saving, so a superseded capture can never count the same time twice.
                val millis = pendingReadingMillis.remove(progress.bookId) ?: 0L
                try { repository.saveProgress(progress.bookId, progress.page, progress.total, millis / 1000) }
                catch (error: Throwable) { pendingReadingMillis.merge(progress.bookId, millis, Long::plus); throw error }
                if (millis % 1000 > 0) pendingReadingMillis.merge(progress.bookId, millis % 1000, Long::plus)
            }
        }
    }

    private fun scheduleReaderProgress() {
        readerProgressJob?.cancel()
        val progress = captureReaderProgress() ?: return
        readerProgressJob = viewModelScope.launch {
            try { delay(300); persistReaderProgress(progress) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error) }
        }
    }

    private fun flushReaderProgress() {
        readerProgressJob?.cancel()
        val progress = captureReaderProgress() ?: return
        viewModelScope.launch {
            try { persistReaderProgress(progress) }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error) }
        }
    }

    private data class RenderedPage(val bitmap: Bitmap, val page: Int, val count: Int, val reflowable: Boolean)

    override fun onCloseReader() {
        readerProgressJob?.cancel()
        val progress = captureReaderProgress()
        readingBookId = null
        readingTickAt = 0L
        val generation = ++readerGeneration
        readerAppearanceJob?.cancel()
        renderJob?.cancel()
        mutableState.update { it.copy(reader = null) }
        viewModelScope.launch {
            try {
                try { progress?.let { persistReaderProgress(it) } }
                finally { closeReaderSession(generation) }
                reloadLibrary()
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { report(error) }
        }
    }

    private suspend fun closeReaderSession(generation: Int? = null) = withContext(Dispatchers.IO) {
        readerLock.withLock {
            if (generation == null || sessionGeneration <= generation) {
                session?.close(); session = null; sessionOptions = null
                clearPageCache()
            }
        }
    }

    override fun onDismissMessage() { mutableState.update { it.copy(error = null, message = null) } }

    private suspend fun reloadLibrary() = libraryReload.withLock {
        val loadedBooks = repository.loadBooks()
        val readingLog = repository.loadReadingLog()
        val shelves = repository.loadShelves().map { UiShelf(it.name, it.name) }
        val paths = repository.loadShelfPaths()
        val guides = repository.loadGuides().map { stored ->
            UiGuideSummary(stored.id, stored.title, runCatching { GuideJson.parse(stored.json).items.size }.getOrDefault(0))
        }
        val downloads = withContext(Dispatchers.IO) {
            loadedBooks.filter { it.sourceId != null && it.remotePath != null && File(it.localPath).isFile }
                .associateBy { requireNotNull(it.sourceId) to requireNotNull(it.remotePath) }
        }
        books = loadedBooks
        downloadedBooks = downloads
        this.readingLog = readingLog
        shelfPaths = paths
        mutableState.update {
            val selectedShelf = it.selectedShelfId?.takeIf { id -> paths.any { shelf -> shelf.id == id } }
            val selectedGuide = it.selectedGuideId?.takeIf { id -> guides.any { guide -> guide.id == id } }
            it.copy(
                selectedShelfId = selectedShelf,
                selectedGuideId = selectedGuide,
                destination = when {
                    it.destination == LibraryDestination.SHELF && selectedShelf == null -> LibraryDestination.BOOKS
                    it.destination == LibraryDestination.GUIDE && selectedGuide == null -> LibraryDestination.BOOKS
                    else -> it.destination
                },
                books = loadedBooks.map { book -> UiBook(book.id, book.title, book.format, book.size, book.coverPath, listOfNotNull(book.shelfName),
                    progress(book), book.sourceId, book.remotePath) },
                shelves = shelves,
                shelfPaths = paths.map { shelf -> UiShelfPath(shelf.id, shelf.name, shelf.profileId, shelf.sourceId, shelf.path) },
                guides = guides,
                remoteEntries = currentBrowser()?.let { browser -> browser.folder?.let { folder -> mapRemote(folder, browser) } } ?: it.remoteEntries,
                readingStatistics = readingStatistics(loadedBooks).copy(timeline = readingLog.map { entry ->
                    val book = loadedBooks.find { it.id == entry.bookId }
                    val local = book != null && File(book.localPath).isFile
                    UiReadingTimelineEntry(entry.bookId, entry.title,
                        entry.coverPath?.takeIf { path -> File(path).isFile } ?: book?.coverPath,
                        entry.startedAt ?: entry.lastReadAt, entry.finishedAt, entry.seconds,
                        canOpen = local || (entry.remotePath != null && entry.sourceId in sourceProfiles), local = local)
                }),
            )
        }
        publishGuide()
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
        UiRemoteEntry(entry.path, entry.name, entry.isDirectory, entry.title, entry.format, entry.size,
            entry.coverPath ?: book?.coverPath,
            downloaded = book != null, downloading = entry.path in browser.transferringPaths, progress = book?.let(::progress) ?: 0f,
            queued = browser.queue.any { it.path == entry.path }, bookId = book?.id,
            folderCovers = entry.previewCovers,
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
            cancelableDownload = operationMessages.isEmpty() && transfers.isNotEmpty(),
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
        font = preferences.getString("font", ReadingFonts.DEFAULT_ID) ?: ReadingFonts.DEFAULT_ID, fontSize = preferences.getFloat("fontSize", 18f),
        fontPath = preferences.getString("fontPath", null), backgroundImagePath = preferences.getString("backgroundImagePath", null),
        keepScreenOn = preferences.getBoolean("keepScreenOn", true), rightToLeft = preferences.getBoolean("rightToLeft", false),
        readingMode = runCatching { ReaderMode.valueOf(preferences.getString("readingMode", ReaderMode.HORIZONTAL.name)!!) }.getOrDefault(ReaderMode.HORIZONTAL),
        readerTransition = runCatching { ReaderTransition.valueOf(preferences.getString("readerTransition", ReaderTransition.SLIDE.name)!!) }.getOrDefault(ReaderTransition.SLIDE),
        aiProvider = preferences.getString("aiProvider", "") ?: "", aiModel = preferences.getString("aiModel", "") ?: "",
    )

    private suspend fun writeAppearance(settings: UiSettings) = withContext(Dispatchers.IO) {
        check(preferences.edit().putString("theme", settings.theme).putString("background", settings.background)
            .putString("font", settings.font).putFloat("fontSize", settings.fontSize).putString("fontPath", settings.fontPath)
            .putString("backgroundImagePath", settings.backgroundImagePath).putBoolean("keepScreenOn", settings.keepScreenOn)
            .putBoolean("rightToLeft", settings.rightToLeft).putString("readingMode", settings.readingMode.name)
            .putString("readerTransition", settings.readerTransition.name)
            .putString("aiProvider", settings.aiProvider).putString("aiModel", settings.aiModel).commit()) { "No se pudo guardar la apariencia" }
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
        val fontId = when {
            settings.font == "custom" && settings.fontPath?.let { File(it).isFile } == true -> "custom"
            ReadingFonts.find(settings.font) != null || settings.font in setOf("sans", "sans-serif", "serif", "mono", "monospace") -> settings.font
            else -> ReadingFonts.DEFAULT_ID
        }
        val fontPath = if (fontId == "custom") settings.fontPath
            else ReadingFonts.resolve(getApplication<Application>(), fontId)?.absolutePath
        return DocumentOptions(settings.fontSize, if (fontId == "mono") "monospace" else fontId,
            fontPath, background,
            if (dark) Color.rgb(237, 232, 223) else Color.rgb(41, 36, 30))
    }

    override fun onCleared() {
        readerAppearanceJob?.cancel()
        appearanceSaveJob?.cancel()
        readerProgressJob?.cancel()
        val progress = captureReaderProgress()
        readerGeneration++
        val pending = viewModelScope.coroutineContext[Job]?.children?.toList().orEmpty()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                pending.joinAll()
                // Independent stores each get a final attempt after the UI has gone away.
                try { persistAppearanceLatest() } catch (_: Exception) { }
                try { progress?.let { persistReaderProgress(it) } } catch (_: Exception) { }
            } finally {
                try { closeReaderSession() } finally { repository.close() }
            }
        }
        super.onCleared()
    }
}
