package com.valerogarte.comicsreader.nativeapp.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import com.valerogarte.comicsreader.nativeapp.model.BookEntry
import com.valerogarte.comicsreader.nativeapp.model.BookFormats
import com.valerogarte.comicsreader.nativeapp.model.DownloadSummary
import com.valerogarte.comicsreader.nativeapp.model.RemoteEntry
import com.valerogarte.comicsreader.nativeapp.model.RemoteFolder
import com.valerogarte.comicsreader.nativeapp.model.SftpConfig
import com.valerogarte.comicsreader.nativeapp.reader.DocumentMetadata
import com.valerogarte.comicsreader.nativeapp.reader.NativeDocumentEngine
import com.valerogarte.comicsreader.nativeapp.reader.RangeSource
import com.valerogarte.comicsreader.sftp.SftpClient
import java.io.Closeable
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.ContinuationInterceptor

/** All book bytes live in Android's private files directory, independently of browser storage. */
class LibraryRepository(context: Context) : Closeable {
    private val appContext = context.applicationContext
    private val root = File(appContext.filesDir, "native-library").apply { ensureDirectory() }
    private val booksDirectory = File(root, "books").apply { ensureDirectory() }
    private val coversDirectory = File(root, "covers").apply { ensureDirectory() }
    private val remoteCoversDirectory = File(root, "remote-covers").apply { ensureDirectory() }
    private val database = LibraryDatabase(appContext)
    private val configuration = SftpConfigStore(appContext)
    private val engine = NativeDocumentEngine(appContext)
    private val legacyKnownHosts = File(root, "known-hosts")
    private val knownHostsDirectory = File(root, "profile-known-hosts").apply { ensureDirectory() }
    private val clients = ConcurrentHashMap<String, SftpClient>()
    private val profileLocks = ConcurrentHashMap<String, ProfileLocks>()
    private val mutation = Mutex()
    private val configurationMutation = Mutex()
    private val remoteCacheMutation = Mutex()

    init { reconcileStorageOnce() }

    private class ProfileLocks(val scan: Mutex = Mutex(), val transfer: Mutex = Mutex())

    suspend fun loadBooks(shelfName: String? = null): List<BookEntry> = withContext(Dispatchers.IO) { database.books(shelfName) }
    suspend fun loadBook(id: String): BookEntry? = withContext(Dispatchers.IO) { database.book(id) }
    suspend fun loadShelves() = withContext(Dispatchers.IO) { database.shelves() }

    suspend fun createShelf(name: String) = withContext(Dispatchers.IO) {
        mutation.withLock { database.createShelf(validShelfName(name)) }
    }

    suspend fun addBookToShelf(id: String, name: String) = withContext(Dispatchers.IO) {
        mutation.withLock { database.addBookToShelf(id, validShelfName(name)) }
    }

    /** Removing a shelf keeps its downloaded books in the main Books section. */
    suspend fun deleteShelf(name: String) = withContext(Dispatchers.IO) {
        mutation.withLock { database.deleteShelf(name) }
    }

    suspend fun loadSftpConfigs(): List<SftpConfig> = withContext(Dispatchers.IO) { configuration.loadAll() }

    /** The list changes atomically. Only changed connection profiles wait for their own operations. */
    suspend fun saveSftpConfigs(configs: List<SftpConfig>) = withContext(Dispatchers.IO) {
        val normalized = configs.map(::normalizeConfig)
        require(normalized.map { it.profileId }.distinct().size == normalized.size) { "Hay identificadores de servidor SFTP duplicados" }
        require(normalized.map { it.id }.distinct().size == normalized.size) { "Ya existe un servidor con esa carpeta y ese usuario" }
        configurationMutation.withLock {
            // Explicitly saving a valid replacement also recovers damaged encrypted settings.
            // Loading remains strict so the initial error is still visible to the user.
            val oldProfiles = try { configuration.loadAll() }
                catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { null }
            if (oldProfiles == normalized) return@withLock
            val newById = normalized.associateBy { it.profileId }
            val changed = oldProfiles?.filter { old ->
                val updated = newById[old.profileId]
                updated == null || old.id != updated.id || old.password != updated.password
            }?.map { it.profileId }?.sorted()
                ?: (clients.keys + profileLocks.keys).distinct().sorted()
            val acquired = ArrayList<Mutex>()
            try {
                changed.forEach { serverId ->
                    val locks = locksFor(serverId)
                    locks.scan.lock(); acquired += locks.scan
                    locks.transfer.lock(); acquired += locks.transfer
                }
                configuration.saveAll(normalized)
                changed.forEach { serverId -> clients.remove(serverId)?.disconnectAll() }
            } finally { acquired.asReversed().forEach { it.unlock() } }
        }
    }

    /** Tests read access only. A missing remote folder is never created. */
    suspend fun checkSftpConfig(config: SftpConfig) = withContext(Dispatchers.IO) {
        val normalized = normalizeConfig(config)
        locksFor(normalized.profileId).scan.withLock {
            runInterruptible(Dispatchers.IO) {
                clientFor(normalized.profileId).listShelf(normalized.credentials(), normalized.rootPath, "/")
            }
        }
        Unit
    }

    suspend fun importBook(uri: Uri): BookEntry = withContext(Dispatchers.IO) {
        mutation.withLock {
            val fileName = displayName(uri)
            require(BookFormats.supports(fileName)) { "Formato no compatible: ${fileName.substringAfterLast('.', "")}" }
            val id = UUID.randomUUID().toString()
            val part = File(booksDirectory, "$id.part")
            try {
                val job = currentCoroutineContext()
                val digest = MessageDigest.getInstance("SHA-256")
                val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("No se pudo abrir el archivo seleccionado")
                input.use { source -> FileOutputStream(part).use { output ->
                    val buffer = ByteArray(65536)
                    while (true) {
                        job.ensureActive()
                        val count = source.read(buffer)
                        if (count == -1) break
                        output.write(buffer, 0, count)
                        digest.update(buffer, 0, count)
                    }
                    output.fd.sync()
                } }
                check(part.length() > 0) { "El archivo está vacío" }
                val hash = digest.digest().hex()
                database.byHash(hash)?.takeIf { File(it.localPath).isFile }?.let { return@withLock it }
                commitBook(id, part, fileName, hash)
            } finally { part.delete() }
        }
    }

    /** Returns the names of shelves that became empty so the UI can offer to delete them. */
    suspend fun deleteBooks(ids: Collection<String>): List<String> = withContext(Dispatchers.IO) {
        mutation.withLock {
            val selected = ids.distinct().mapNotNull { database.book(it) }
            val affected = selected.mapNotNull { it.shelfName }.toSet()
            selected.forEach { book ->
                currentCoroutineContext().ensureActive()
                val local = privateFile(book.localPath)
                book.coverPath?.let { path ->
                    val cover = privateFile(path)
                    if (cover.exists() && !cover.delete()) throw IOException("No se pudo borrar la portada de «${book.title}»")
                }
                if (local.exists() && !local.delete()) throw IOException("No se pudo borrar el archivo de «${book.title}»")
                database.deleteBook(book.id)
            }
            database.shelves().filter { it.name in affected && it.bookCount == 0 }.map { it.name }
        }
    }

    suspend fun saveProgress(id: String, page: Int, total: Int) = withContext(Dispatchers.IO) {
        val actualTotal = total.coerceAtLeast(0)
        database.progress(id, page.coerceIn(0, (actualTotal - 1).coerceAtLeast(0)), actualTotal)
    }

    /** Cached folders return without opening an SSH connection, including when offline. */
    suspend fun loadRemoteFolder(
        serverId: String,
        path: String = "/",
        refresh: Boolean = false,
        onUpdate: ((RemoteFolder, Int, Int) -> Unit)? = null,
    ): RemoteFolder = withContext(Dispatchers.IO) {
        locksFor(serverId).scan.withLock {
            val config = captureConfig(serverId)
            val folderPath = normalizePath(path)
            val cached = database.remoteFolder(config.id, folderPath)?.withDownloadState()
            if (cached != null && !refresh) return@withLock cached
            val client = clientFor(serverId)
            val previous = cached?.entries?.associateBy { it.path }.orEmpty()
            val listing = runInterruptible(Dispatchers.IO) { client.listShelf(config.credentials(), config.rootPath, folderPath) }
                .filter { it.directory || BookFormats.supports(it.name) }
                .sortedWith(compareByDescending<SftpClient.Entry> { it.directory }.thenBy(String.CASE_INSENSITIVE_ORDER) { it.name })
            val downloads = existingDownloads(config.id)
            val entries = listing.map { item ->
                val remotePath = join(folderPath, item.name)
                val old = previous[remotePath]
                if (old != null && old.isDirectory == item.directory) old.copy(size = item.size, modified = item.modifiedMillis)
                else RemoteEntry(item.name, remotePath, item.directory, item.size, item.modifiedMillis,
                    title = if (item.directory) item.name else item.name.substringBeforeLast('.', item.name),
                    format = BookFormats.format(item.name), downloadedBookId = downloads[remotePath]?.id, sourceId = config.id)
            }.toMutableList()
            val cachedAt = System.currentTimeMillis()
            fun snapshot() = RemoteFolder(folderPath, entries.toList(), cachedAt, config.id)
            var folder = snapshot()
            remoteCacheMutation.withLock {
                database.cacheFolder(folder)
                val listedPaths = entries.mapTo(HashSet()) { it.path }
                previous.values.filter { it.path !in listedPaths }.forEach { deleteRemoteCover(it.coverPath) }
            }
            val total = entries.count { !it.isDirectory }
            var completed = 0
            onUpdate?.invoke(folder, completed, total)
            var lastPublished = System.nanoTime()
            entries.indices.forEach { index ->
                currentCoroutineContext().ensureActive()
                val entry = entries[index]
                if (entry.isDirectory) return@forEach
                val old = previous[entry.path]
                val unchanged = old != null && old.size == entry.size && old.modified == entry.modified
                val hasCover = old?.coverPath?.let { File(it).isFile } == true
                if (!unchanged || !hasCover) {
                    entries[index] = inspectRemote(config, entry, folderPath)
                }
                completed += 1
                val now = System.nanoTime()
                if (onUpdate != null && (completed == total || now - lastPublished >= 100_000_000L)) {
                    folder = snapshot()
                    onUpdate.invoke(folder, completed, total)
                    lastPublished = now
                }
            }
            remoteCacheMutation.withLock { pruneRemoteCovers() }
            snapshot()
        }
    }

    /** Only this explicit action transfers a complete remote book. */
    suspend fun downloadRemote(
        serverId: String,
        entry: RemoteEntry,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): BookEntry = withContext(Dispatchers.IO) {
        locksFor(serverId).transfer.withLock {
            downloadCaptured(captureConfig(serverId), entry, onProgress)
        }
    }

    private suspend fun downloadCaptured(
        config: SftpConfig,
        entry: RemoteEntry,
        onProgress: ((Long, Long) -> Unit)? = null,
    ): BookEntry {
        require(!entry.isDirectory && BookFormats.supports(entry.name)) { "Selecciona un libro compatible" }
        require(entry.sourceId == null || entry.sourceId == config.id) { "La configuración SFTP ha cambiado; vuelve a abrir la carpeta" }
        val path = normalizePath(entry.path)
        val existing = mutation.withLock {
            database.downloaded(config.id, path)?.let { book ->
                if (File(book.localPath).isFile) book else {
                    book.coverPath?.let { privateFile(it).delete() }
                    database.deleteBook(book.id)
                    null
                }
            }
        }
        if (existing != null) return existing
        val id = UUID.randomUUID().toString()
        val part = File(booksDirectory, "$id.part")
        val job = currentCoroutineContext()
        try {
            runInterruptible(Dispatchers.IO) {
                clientFor(config.profileId).downloadShelf(config.credentials(), config.rootPath, path, part) { transferred ->
                    job.ensureActive()
                    onProgress?.invoke(transferred, entry.size)
                }
            }
            job.ensureActive()
            check(part.length() > 0) { "El archivo está vacío" }
            val hash = part.inputStream().use { input ->
                val digest = MessageDigest.getInstance("SHA-256")
                val buffer = ByteArray(65536)
                while (true) {
                    job.ensureActive()
                    val count = input.read(buffer)
                    if (count == -1) break
                    digest.update(buffer, 0, count)
                }
                digest.digest().hex()
            }
            return mutation.withLock {
                database.downloaded(config.id, path)?.takeIf { File(it.localPath).isFile }
                    ?: commitBook(id, part, entry.name, hash, config, path, entry.modified, entry.coverPath)
            }
        } catch (error: Exception) {
            job.ensureActive()
            throw error
        } finally { part.delete() }
    }

    /** Current folder only; subfolders are never traversed or downloaded implicitly. */
    suspend fun downloadAll(
        serverId: String,
        folder: RemoteFolder,
        onProgress: ((Int, Int) -> Unit)? = null,
    ): DownloadSummary = withContext(Dispatchers.IO) {
        locksFor(serverId).transfer.withLock {
            val config = captureConfig(serverId)
            require(config.id == folder.sourceId) { "La configuración SFTP ha cambiado; vuelve a abrir la carpeta" }
            val entries = folder.entries.filter { !it.isDirectory }
            var downloaded = 0
            var skipped = 0
            var failed = 0
            onProgress?.invoke(0, entries.size)
            entries.forEachIndexed { index, entry ->
                currentCoroutineContext().ensureActive()
                val existing = database.downloaded(config.id, entry.path)
                if (existing != null && File(existing.localPath).isFile) skipped += 1
                else try {
                    downloadCaptured(config, entry)
                    downloaded += 1
                } catch (cancelled: CancellationException) { throw cancelled }
                catch (_: Exception) { failed += 1 }
                onProgress?.invoke(index + 1, entries.size)
            }
            DownloadSummary(downloaded, skipped, failed)
        }
    }

    private suspend fun inspectRemote(config: SftpConfig, entry: RemoteEntry, folderPath: String): RemoteEntry {
        val job = currentCoroutineContext()
        val metadata = try {
            var transferred = 0L
            val budget = minOf(16L * 1024 * 1024, (entry.size - 1).coerceAtLeast(0))
            engine.inspectRemote(entry.name, entry.size, RangeSource { offset, length ->
                job.ensureActive()
                require(offset >= 0 && length >= 0 && length <= 4 * 1024 * 1024 && offset <= entry.size && length.toLong() <= entry.size - offset) { "Rango SFTP inválido" }
                require(transferred + length <= budget) { "Vista previa limitada a lecturas parciales" }
                transferred += length
                // RangeSource is synchronous and may run on a native document callback thread.
                // Keep its blocking SSH call attached to the parent cancellation on that thread.
                runBlocking(job.minusKey(ContinuationInterceptor)) {
                    runInterruptible { clientFor(config.profileId).readShelfRange(config.credentials(), config.rootPath, entry.path, offset, length) }
                }
            })
        } catch (cancelled: CancellationException) { throw cancelled }
        catch (_: Exception) { job.ensureActive(); return entry }
        try {
            return remoteCacheMutation.withLock {
                job.ensureActive()
                val cover = metadata.cover?.let { bitmap ->
                    try { writeCover(File(remoteCoversDirectory, "${sha256(config.id + ":" + entry.path)}.jpg"), bitmap, contentAddressed = true) }
                    catch (_: Exception) { entry.coverPath }
                } ?: entry.coverPath
                val updated = entry.copy(
                    title = metadata.title.ifBlank { entry.title }, author = metadata.author,
                    description = metadata.description, publisher = metadata.publisher,
                    pageCount = metadata.pageCount, coverPath = cover,
                )
                try { database.cacheEntry(config.id, folderPath, updated) }
                catch (error: Exception) {
                    if (updated.coverPath != entry.coverPath) deleteRemoteCover(updated.coverPath)
                    throw error
                }
                if (updated.coverPath != entry.coverPath) deleteRemoteCover(entry.coverPath)
                updated
            }
        } finally { metadata.cover?.takeUnless { it.isRecycled }?.recycle() }
    }

    private fun commitBook(
        id: String, part: File, fileName: String, hash: String,
        config: SftpConfig? = null, remotePath: String? = null, modified: Long = System.currentTimeMillis(),
        fallbackCoverPath: String? = null,
    ): BookEntry {
        val destination = File(booksDirectory, "$id.${BookFormats.format(fileName)}")
        check(part.renameTo(destination)) { "No se pudo guardar el libro en el almacenamiento interno" }
        var cover: String? = null
        try {
            val metadata = runCatching { engine.inspectLocal(destination, fileName) }
                .getOrElse { DocumentMetadata(fileName.substringBeforeLast('.', fileName)) }
            cover = metadata.cover?.let { writeCover(File(coversDirectory, "$id.jpg"), it) }
                ?: fallbackCoverPath?.let { cachedPath ->
                    val cached = privateFile(cachedPath)
                    if (cached.isFile) runCatching { copyPrivateFile(cached, File(coversDirectory, "$id.jpg")) }.getOrNull() else null
                }
            val book = BookEntry(
                id = id, title = metadata.title.ifBlank { fileName.substringBeforeLast('.', fileName) },
                fileName = fileName, format = BookFormats.format(fileName), localPath = destination.absolutePath,
                coverPath = cover, author = metadata.author, description = metadata.description,
                publisher = metadata.publisher, pageCount = metadata.pageCount,
                shelfName = if (config != null && remotePath != null) shelfFor(config, remotePath) else null,
                sourceId = config?.id, remotePath = remotePath, size = destination.length(), modified = modified,
            )
            database.insertBook(book, hash)
            return book
        } catch (error: Exception) {
            destination.delete()
            cover?.let { File(it).delete() }
            throw error
        }
    }

    private fun existingDownloads(sourceId: String): Map<String?, BookEntry> = database.books()
        .filter { it.sourceId == sourceId && File(it.localPath).isFile }.associateBy { it.remotePath }

    private fun RemoteFolder.withDownloadState(): RemoteFolder {
        val downloads = existingDownloads(sourceId)
        return copy(entries = entries.map { entry ->
        if (entry.isDirectory) entry else {
            entry.copy(downloadedBookId = downloads[entry.path]?.id)
        }
        })
    }

    private fun writeCover(destination: File, bitmap: Bitmap, contentAddressed: Boolean = false): String {
        val ratio = minOf(1f, 360f / bitmap.width.coerceAtLeast(1), 540f / bitmap.height.coerceAtLeast(1))
        val thumbnail = if (ratio < 1f) Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true) else bitmap
        var part: File? = null
        try {
            val bytes = ByteArrayOutputStream().use { output ->
                check(thumbnail.compress(Bitmap.CompressFormat.JPEG, 85, output)) { "No se pudo guardar la portada" }
                output.toByteArray()
            }
            val actual = if (contentAddressed) File(destination.parentFile,
                "${destination.nameWithoutExtension}-${MessageDigest.getInstance("SHA-256").digest(bytes).hex()}.jpg") else destination
            if (actual.isFile) return actual.absolutePath
            val temporary = File(actual.parentFile, "${actual.name}.part")
            part = temporary
            FileOutputStream(temporary).use { output ->
                output.write(bytes)
                output.fd.sync()
            }
            check(temporary.renameTo(actual)) { "No se pudo guardar la portada" }
            return actual.absolutePath
        } finally {
            part?.delete()
            if (thumbnail !== bitmap) thumbnail.recycle()
            bitmap.recycle()
        }
    }

    private fun copyPrivateFile(source: File, destination: File): String {
        val part = File(destination.parentFile, "${destination.name}.part")
        try {
            source.inputStream().use { input -> FileOutputStream(part).use { output ->
                input.copyTo(output, 65536)
                output.fd.sync()
            } }
            check(part.renameTo(destination)) { "No se pudo guardar la portada" }
            return destination.absolutePath
        } finally { part.delete() }
    }

    private fun deleteRemoteCover(path: String?) {
        if (path == null) return
        val file = File(path).canonicalFile
        if (file.parentFile == remoteCoversDirectory.canonicalFile) file.delete()
    }

    private fun pruneRemoteCovers() {
        val retained = database.remoteCoverPaths()
        remoteCoversDirectory.listFiles()?.filter { it.isFile && it.absolutePath !in retained }?.forEach { it.delete() }
    }

    /**
     * A killed process cannot run the temporary-file finally blocks. Reconcile only before the
     * first repository for this private directory starts work; later repositories may coexist
     * with active downloads and must never remove their files.
     */
    private fun reconcileStorageOnce() = synchronized(reconciledRoots) {
        val key = root.canonicalPath
        if (key in reconciledRoots) return@synchronized
        // Read every reference before deleting anything. A database failure must preserve files.
        val books = database.books()
        val archives = books.mapTo(HashSet()) { File(it.localPath).canonicalPath }
        val covers = books.mapNotNullTo(HashSet()) { it.coverPath?.let { path -> File(path).canonicalPath } }
        val remoteCovers = database.remoteCoverPaths().mapTo(HashSet()) { File(it).canonicalPath }
        fun removeUnreferenced(directory: File, retained: Set<String>, generated: Regex) {
            directory.listFiles()?.forEach { file ->
                if (file.isFile && generated.matches(file.name) && file.canonicalPath !in retained) file.delete()
            }
        }
        removeUnreferenced(booksDirectory, archives, generatedLocalFile)
        removeUnreferenced(coversDirectory, covers, generatedLocalFile)
        removeUnreferenced(remoteCoversDirectory, remoteCovers, generatedRemoteCover)
        reconciledRoots += key
    }

    private fun displayName(uri: Uri): String {
        val name = appContext.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "libro"
        require(name.isNotBlank() && name.none { it.code < 32 }) { "Nombre de archivo inválido" }
        return name.substringAfterLast('/').substringAfterLast('\\')
    }

    private fun privateFile(path: String): File = File(path).canonicalFile.also {
        require(it.path.startsWith(root.canonicalPath + File.separator)) { "Archivo fuera de la biblioteca privada" }
    }

    private fun normalizeConfig(config: SftpConfig): SftpConfig {
        val host = config.host.trim().removePrefix("sftp://").trimEnd('/')
        require(host.isNotEmpty() && host.none { it.isWhitespace() || it == '/' || it == '@' || it.code < 32 }) { "Servidor SFTP inválido" }
        require(config.port in 1..65535) { "Puerto SFTP inválido" }
        require(config.username.trim().isNotEmpty()) { "Indica el usuario SFTP" }
        require(config.profileId.isNotBlank() && config.profileId.length <= 128 && config.profileId.none { it.code < 32 || it.code == 127 }) { "Identificador de servidor SFTP inválido" }
        val name = config.name.trim().ifBlank { host }
        require(name.length <= 200 && name.none { it.code < 32 || it.code == 127 }) { "Nombre de servidor SFTP inválido" }
        return config.copy(host = host, username = config.username.trim(), rootPath = normalizePath(config.rootPath.trim()), name = name)
    }

    private fun captureConfig(serverId: String): SftpConfig = configuration.loadAll()
        .firstOrNull { it.profileId == serverId } ?: error("El servidor SFTP ya no está configurado")

    private fun locksFor(serverId: String): ProfileLocks = profileLocks.computeIfAbsent(serverId) { ProfileLocks() }
    private fun clientFor(serverId: String): SftpClient = clients.computeIfAbsent(serverId) {
        val hosts = File(knownHostsDirectory, "${sha256(serverId)}.txt")
        if (!hosts.exists()) {
            if (legacyKnownHosts.isFile) copyPrivateFile(legacyKnownHosts, hosts)
            else check(hosts.createNewFile()) { "No se pudieron guardar las claves del servidor SFTP" }
        }
        SftpClient(hosts)
    }

    private fun SftpConfig.credentials() = SftpClient.Credentials(host, port, username, password)

    private fun shelfFor(config: SftpConfig, remotePath: String): String {
        val rootNames = normalizePath(config.rootPath).trim('/')
        val parentNames = remotePath.substringBeforeLast('/', "").trim('/')
        return listOf(rootNames, parentNames).filter { it.isNotEmpty() }.joinToString("/").ifEmpty { config.host }
    }

    private fun validShelfName(name: String) = name.trim().also {
        require(it.isNotEmpty() && it.length <= 512 && it.none { character -> character.code < 32 }) { "Nombre de estantería inválido" }
    }

    override fun close() { clients.values.forEach { it.disconnectAll() }; clients.clear(); database.close() }

    companion object {
        private val reconciledRoots = HashSet<String>()
        private val generatedLocalFile = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.[a-zA-Z0-9]+(?:\\.part)?")
        private val generatedRemoteCover = Regex("[0-9a-f]{64}(?:-[0-9a-f]{64})?\\.jpg(?:\\.part)?")
        fun normalizePath(path: String): String {
            require(path.none { it == '\\' || it.code < 32 || it.code == 127 }) { "Ruta SFTP inválida" }
            val segments = path.split('/').filter { it.isNotEmpty() }
            require(segments.none { it == "." || it == ".." }) { "Ruta SFTP inválida" }
            return "/" + segments.joinToString("/")
        }
        private fun join(parent: String, name: String) = (if (parent == "/") "" else parent) + "/" + name
        private fun sha256(value: String) = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)).hex()
        private fun ByteArray.hex() = joinToString("") { "%02x".format(it) }
        private fun File.ensureDirectory() { check(isDirectory || mkdirs()) { "No se pudo crear el almacenamiento interno de la biblioteca" } }
    }
}
