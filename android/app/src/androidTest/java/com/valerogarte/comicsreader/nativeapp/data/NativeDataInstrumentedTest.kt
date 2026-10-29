package com.valerogarte.comicsreader.nativeapp.data

import android.content.Context
import android.net.Uri
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valerogarte.comicsreader.nativeapp.model.BookEntry
import com.valerogarte.comicsreader.nativeapp.model.ReadingStatus
import com.valerogarte.comicsreader.nativeapp.model.RemoteEntry
import com.valerogarte.comicsreader.nativeapp.model.RemoteFolder
import com.valerogarte.comicsreader.nativeapp.model.SftpConfig
import com.valerogarte.comicsreader.nativeapp.qa.QaApplication
import com.valerogarte.comicsreader.sftp.SftpClient
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.SecretKey
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NativeDataInstrumentedTest {
    private lateinit var context: QaApplication
    private val repositories = ArrayList<LibraryRepository>()
    private val host get() = InstrumentationRegistry.getArguments().getString("sftpQaHost", "10.0.2.2")
    private val port get() = InstrumentationRegistry.getArguments().getString("sftpQaPort", "22991").toInt()
    private val controlPort get() = InstrumentationRegistry.getArguments().getString("sftpQaControlPort", "22992").toInt()
    private val remotePath = "/Comics/DC/Sandman"

    @Before fun setUp() { context = QaApplication(InstrumentationRegistry.getInstrumentation().targetContext) }
    @After fun tearDown() {
        repositories.forEach { runCatching { it.close() } }
        context.dispose()
    }

    @Test fun encryptedProfilesRoundTripKeepStableIdsWithoutPlaintextSecrets() {
        val store = SftpConfigStore(context, "")
        val profiles = listOf(profile("qaA"), profile("qaB"))
        store.saveAll(profiles)
        val loaded = SftpConfigStore(context, "").loadAll()
        assertTrue(loaded.map { it.profileId } == profiles.map { it.profileId })
        assertTrue(loaded.map { it.id } == profiles.map { it.id })
        assertTrue(loaded.zip(profiles).all { (actual, expected) -> actual.password == expected.password && actual.name == expected.name })
        val disk = context.getSharedPreferences("native-sftp", Context.MODE_PRIVATE).all.values.joinToString()
        assertFalse(disk.contains("fixture-password"))
        assertFalse(disk.contains("\"username\""))
        assertFalse(disk.contains("Servidor qaA"))
    }

    @Test fun defaultNasSeedsOnceAndExplicitEmptyListNeverReseeds() {
        val seed = jsonProfile(profile("qaA")).toString()
        val first = SftpConfigStore(context, seed).loadAll()
        val next = SftpConfigStore(context, seed).loadAll()
        assertEquals(1, first.size)
        assertTrue(first.single().profileId == next.single().profileId)
        SftpConfigStore(context, seed).saveAll(emptyList())
        assertTrue(SftpConfigStore(context, seed).loadAll().isEmpty())
    }

    @Test fun emptyBuildDefaultLeavesRoomForSeedInFutureBuild() {
        assertTrue(SftpConfigStore(context, "").loadAll().isEmpty())
        assertFalse(context.getSharedPreferences("native-sftp", Context.MODE_PRIVATE).getBoolean("profiles_initialized", false))
        assertEquals(1, SftpConfigStore(context, jsonProfile(profile("qaA")).toString()).loadAll().size)
    }

    @Test fun legacySingleProfileMigratesWithDifferentNasAndPreservesSourceFingerprint() {
        val legacy = profile("qaA")
        val seed = jsonProfile(profile("qaB")).toString()
        writeLegacy(jsonProfile(legacy).apply { remove("profileId"); remove("name") })
        val migrated = SftpConfigStore(context, seed).loadAll()
        assertEquals(2, migrated.size)
        assertTrue(migrated.first().id == legacy.id)
        assertTrue(migrated.map { it.id }.distinct().size == 2)
        assertFalse(context.getSharedPreferences("native-sftp", Context.MODE_PRIVATE).contains("configuration"))
        assertTrue(SftpConfigStore(context, seed).loadAll().map { it.profileId } == migrated.map { it.profileId })
    }

    @Test fun matchingLegacyNasIsNotDuplicatedAndGetsDefaultLabel() {
        val legacy = profile("qaA")
        val seed = jsonProfile(legacy.copy(name = "NAS de prueba")).toString()
        writeLegacy(jsonProfile(legacy).apply { remove("profileId"); remove("name") })
        val migrated = SftpConfigStore(context, seed).loadAll()
        assertEquals(1, migrated.size)
        assertTrue(migrated.single().id == legacy.id)
        assertEquals("NAS de prueba", migrated.single().name)
    }

    @Test fun duplicateSourcesOrProfileIdsAreRejectedAtomically() = runBlocking {
        val repository = repository()
        val original = profile("qaA")
        repository.saveSftpConfigs(listOf(original))
        assertTrue(runCatching { repository.saveSftpConfigs(listOf(original, original.copy(profileId = UUID.randomUUID().toString()))) }.isFailure)
        assertTrue(runCatching { repository.saveSftpConfigs(listOf(original, profile("qaB").copy(profileId = original.profileId))) }.isFailure)
        assertTrue(repository.loadSftpConfigs().map { it.profileId } == listOf(original.profileId))
        assertTrue(original.id == original.copy(name = "Otro nombre", password = "otro password").id)
    }

    @Test fun explicitSaveRecoversCorruptEncryptedProfilesWithoutRemovingLibraryCacheOrPins() = runBlocking {
        val original = profile("qaA").copy(host = "offline.invalid")
        SftpConfigStore(context, "").saveAll(listOf(original))
        val repository = repository()
        val source = File(context.filesDir, "keep.txt").apply { writeText("Este libro se conserva.\n".repeat(80)) }
        val book = repository.importBook(Uri.fromFile(source))
        repository.addBookToShelf(book.id, "Conservar")
        repository.saveProgress(book.id, 2, 8)
        val root = File(context.filesDir, "native-library")
        val pin = File(root, "profile-known-hosts/keep.txt").apply { writeText("pinned-host-key") }
        val cachedCover = File(root, "remote-covers/keep.jpg").apply { writeText("cached-cover") }
        withDatabase { db ->
            db.cacheFolder(RemoteFolder("/cached", listOf(RemoteEntry("a.cbz", "/cached/a.cbz", false,
                coverPath = cachedCover.path, title = "Catálogo conservado", sourceId = original.id)), 123, original.id))
        }
        val preferences = context.getSharedPreferences("native-sftp", Context.MODE_PRIVATE)
        // Both an unreadable record and an invalid encrypted payload require explicit replacement.
        listOf("{invalid", "{\"iv\":\"AA==\",\"data\":\"AA==\"}").forEach { damaged ->
            check(preferences.edit().putString("configurations", damaged).commit())
            assertTrue(runCatching { repository.loadSftpConfigs() }.isFailure)
            assertTrue(runCatching { repository.saveSftpConfigs(listOf(original.copy(host = ""))) }.isFailure)
            assertEquals(damaged, preferences.getString("configurations", null))
            repository.saveSftpConfigs(listOf(original.copy(name = "Recuperado")))
            assertEquals("Recuperado", repository.loadSftpConfigs().single().name)
        }
        assertEquals(book.id, repository.loadBooks().single().id)
        assertEquals(2, repository.loadBook(book.id)?.currentPage)
        assertEquals("Conservar", repository.loadBook(book.id)?.shelfName)
        assertTrue(File(book.localPath).isFile)
        book.coverPath?.let { assertTrue(File(it).isFile) }
        assertEquals("pinned-host-key", pin.readText())
        assertEquals("cached-cover", cachedCover.readText())
        assertEquals("Catálogo conservado", withTimeout(1000) { repository.loadRemoteFolder(original.profileId, "/cached") }.entries.single().title)
        repository.saveSftpConfigs(emptyList())
        assertTrue(repository.loadSftpConfigs().isEmpty())
        assertTrue(File(book.localPath).isFile)
    }

    @Test fun firstRepositoryReconcilesKilledProcessResiduesAndLaterRepositoriesPreserveActiveFiles() = runBlocking {
        val root = File(context.filesDir, "native-library").apply { mkdirs() }
        val archives = File(root, "books").apply { mkdirs() }
        val covers = File(root, "covers").apply { mkdirs() }
        val remoteCovers = File(root, "remote-covers").apply { mkdirs() }
        val id = UUID.randomUUID().toString()
        val keptArchive = File(archives, "$id.txt").apply { writeText("Libro válido") }
        val keptCover = File(covers, "$id.jpg").apply { writeText("Portada válida") }
        val keptRemoteCover = File(remoteCovers, "${"a".repeat(64)}-${"b".repeat(64)}.jpg").apply { writeText("Portada remota válida") }
        val removed = listOf(
            File(archives, "${UUID.randomUUID()}.part"),
            File(archives, "${UUID.randomUUID()}.cbz"),
            File(covers, "${UUID.randomUUID()}.jpg"),
            File(covers, "${UUID.randomUUID()}.jpg.part"),
            File(remoteCovers, "${"c".repeat(64)}-${"d".repeat(64)}.jpg"),
            File(remoteCovers, "${"e".repeat(64)}.jpg.part"),
        ).onEach { it.writeText("Residuo de proceso terminado") }
        val pin = File(root, "known-hosts").apply { writeText("Conservar pin") }
        withDatabase { db ->
            db.insertBook(BookEntry(id, "Conservado", "$id.txt", "txt", keptArchive.path, coverPath = keptCover.path), "hash")
            db.cacheFolder(RemoteFolder("/", listOf(RemoteEntry("a.cbz", "/a.cbz", false,
                coverPath = keptRemoteCover.path, sourceId = "source")), 1, "source"))
        }
        val repository = repository()
        assertEquals(id, repository.loadBooks().single().id)
        assertTrue(removed.none { it.exists() })
        assertEquals("Libro válido", keptArchive.readText())
        assertEquals("Portada válida", keptCover.readText())
        assertEquals("Portada remota válida", keptRemoteCover.readText())
        assertEquals("Conservar pin", pin.readText())
        // A second repository in the same process can be constructed during another one's copy.
        val activePart = File(archives, "${UUID.randomUUID()}.part").apply { writeText("Descarga en curso") }
        repository()
        assertEquals("Descarga en curso", activePart.readText())
    }

    @Test fun cachedFoldersStayOfflineAndSeparateIdenticalRemotePathsBySource() = runBlocking {
        val repository = repository()
        val a = profile("qaA").copy(host = "offline-a.invalid")
        val b = profile("qaB").copy(host = "offline-b.invalid")
        repository.saveSftpConfigs(listOf(a, b))
        withDatabase { db ->
            db.cacheFolder(RemoteFolder("/same", listOf(RemoteEntry("a.cbz", "/same/a.cbz", false, title = "A", sourceId = a.id)), 123, a.id))
            db.cacheFolder(RemoteFolder("/same", listOf(RemoteEntry("a.cbz", "/same/a.cbz", false, title = "B", sourceId = b.id)), 456, b.id))
        }
        withTimeout(1500) {
            assertEquals("A", repository.loadRemoteFolder(a.profileId, "/same").entries.single().title)
            assertEquals("B", repository.loadRemoteFolder(b.profileId, "/same").entries.single().title)
        }
        assertTrue(File(context.filesDir, "native-library/profile-known-hosts").listFiles().orEmpty().isEmpty())
        repository.saveSftpConfigs(listOf(a.copy(name = "Renombrado"), b))
        assertEquals("A", repository.loadRemoteFolder(a.profileId, "/same").entries.single().title)
        repository.saveSftpConfigs(emptyList())
        assertTrue(repository.loadSftpConfigs().isEmpty())
        assertTrue(runCatching { repository.loadRemoteFolder(a.profileId, "/same") }.isFailure)
    }

    @Test fun localImportIsPrivateDeduplicatedAndPermanentDeleteKeepsEmptyShelfUntilAccepted() = runBlocking {
        val repository = repository()
        val source = File(context.filesDir, "fixture.txt").apply { writeText("Una biblioteca Android nativa.\n".repeat(500)) }
        val imported = repository.importBook(Uri.fromFile(source))
        assertTrue(source.isFile)
        assertTrue(File(imported.localPath).isFile)
        assertTrue(imported.localPath.startsWith(File(context.filesDir, "native-library/books").path + "/"))
        assertEquals(imported.id, repository.importBook(Uri.fromFile(source)).id)
        assertEquals(1, repository.loadBooks().size)
        repository.addBookToShelf(imported.id, "Comics/DC")
        repository.saveProgress(imported.id, 1, 5)
        assertEquals(ReadingStatus.READING, repository.loadBook(imported.id)?.readingStatus)
        repository.saveProgress(imported.id, 4, 5)
        assertEquals(ReadingStatus.FINISHED, repository.loadBook(imported.id)?.readingStatus)
        assertEquals(listOf("Comics/DC"), repository.deleteBooks(listOf(imported.id)))
        assertFalse(File(imported.localPath).exists())
        imported.coverPath?.let { assertFalse(File(it).exists()) }
        assertTrue(repository.loadBooks().isEmpty())
        assertEquals(0, repository.loadShelves().single().bookCount)
        repository.deleteShelf("Comics/DC")
        assertTrue(repository.loadShelves().isEmpty())
        assertTrue(File(context.filesDir, "native-library/books").listFiles().orEmpty().isEmpty())
        assertTrue(source.isFile)
    }

    @Test fun realSftpBrowsingCachesOnlyPreviewsAndSyncReplacesCoverWithoutLeaks() = runBlocking {
        val (repository, a, _) = fixture()
        val first = repository.loadRemoteFolder(a.profileId, remotePath)
        assertEquals(2, first.entries.size)
        assertTrue(first.entries.all { it.title.startsWith("qaA-v1") && it.coverPath?.let { path -> File(path).isFile } == true })
        assertTrue(repository.loadBooks().isEmpty())
        assertTrue(File(context.filesDir, "native-library/books").listFiles().orEmpty().isEmpty())
        val before = control("stats").getJSONObject("qaA")
        assertPartialReads(before.getJSONArray("reads"))
        control("deny", "user" to "qaA", "value" to true)
        val offline = withTimeout(1000) { repository.loadRemoteFolder(a.profileId, remotePath) }
        assertTrue(offline.entries.map { it.title } == first.entries.map { it.title })
        val after = control("stats").getJSONObject("qaA")
        assertEquals(before.getInt("listings"), after.getInt("listings"))
        assertEquals(before.getJSONArray("reads").length(), after.getJSONArray("reads").length())
        assertTrue(runCatching { repository.loadRemoteFolder(a.profileId, remotePath, refresh = true) }.isFailure)
        control("deny", "user" to "qaA", "value" to false)
        control("mutate", "user" to "qaA", "version" to 2)
        val second = repository.loadRemoteFolder(a.profileId, remotePath, refresh = true)
        assertTrue(second.entries.all { it.title.startsWith("qaA-v2") })
        assertTrue(first.entries.map { it.coverPath }.toSet().intersect(second.entries.map { it.coverPath }.toSet()).isEmpty())
        assertTrue(first.entries.all { it.coverPath?.let { path -> !File(path).exists() } == true })
        assertEquals(2, File(context.filesDir, "native-library/remote-covers").listFiles().orEmpty().size)
        assertEquals(0, control("stats").getJSONObject("qaA").getInt("writes"))
    }

    @Test fun realSftpExplicitDownloadsAreIdempotentAndBulkOnlyDownloadsCurrentFolder() = runBlocking {
        val (repository, a, _) = fixture()
        val folder = repository.loadRemoteFolder(a.profileId, remotePath)
        val entry = folder.entries.first()
        val book = repository.downloadRemote(a.profileId, entry)
        assertTrue(File(book.localPath).isFile)
        assertEquals("library/Comics/DC/Sandman", book.shelfName)
        assertTrue(book.coverPath != entry.coverPath)
        assertTrue(book.coverPath?.let { File(it).isFile } == true)
        val reads = control("stats").getJSONObject("qaA").getJSONArray("reads").length()
        assertEquals(book.id, repository.downloadRemote(a.profileId, entry).id)
        assertEquals(reads, control("stats").getJSONObject("qaA").getJSONArray("reads").length())
        val result = repository.downloadAll(a.profileId, folder)
        assertEquals(1, result.downloaded)
        assertEquals(1, result.alreadyDownloaded)
        assertEquals(0, result.failed)
        assertEquals(2, repository.loadBooks().size)
        assertEquals(2, repository.loadShelves().single().bookCount)
        val cached = repository.loadRemoteFolder(a.profileId, remotePath)
        assertTrue(cached.entries.all { it.downloadedBookId != null })
        assertTrue(repository.loadBooks().none { it.fileName == "slow.txt" })
        assertEquals(0, control("stats").getJSONObject("qaA").getInt("writes"))
    }

    @Test fun twoRealProfilesKeepSameRemotePathAndDownloadLinksIndependent() = runBlocking {
        val (repository, a, b) = fixture()
        val fa = repository.loadRemoteFolder(a.profileId, remotePath)
        val fb = repository.loadRemoteFolder(b.profileId, remotePath)
        assertTrue(fa.entries.first().title != fb.entries.first().title)
        val ba = repository.downloadRemote(a.profileId, fa.entries.first())
        val bb = repository.downloadRemote(b.profileId, fb.entries.first())
        assertTrue(ba.id != bb.id && ba.sourceId != bb.sourceId)
        assertEquals(ba.id, repository.loadRemoteFolder(a.profileId, remotePath).entries.first().downloadedBookId)
        assertEquals(bb.id, repository.loadRemoteFolder(b.profileId, remotePath).entries.first().downloadedBookId)
        assertTrue(runCatching { repository.downloadRemote(b.profileId, fa.entries.first()) }.isFailure)
        assertEquals(2, File(context.filesDir, "native-library/profile-known-hosts").listFiles().orEmpty().size)
    }

    @Test fun differentServersDownloadInParallelAndEditingOtherServerKeepsConnectionAlive() = runBlocking {
        val (repository, a, b) = fixture()
        control("delay", "user" to "qaA", "seconds" to 0.025)
        val started = CompletableDeferred<Unit>()
        val slow = async { repository.downloadRemote(a.profileId, RemoteEntry("slow.txt", "/slow.txt", false, sourceId = a.id)) { _, _ -> started.complete(Unit) } }
        withTimeout(5000) { started.await() }
        val changedB = b.copy(name = "B renombrado", rootPath = "/library/Comics")
        repository.saveSftpConfigs(listOf(a, changedB))
        val quick = async {
            val actualFolder = repository.loadRemoteFolder(changedB.profileId, "/DC/Sandman")
            repository.downloadRemote(changedB.profileId, actualFolder.entries.first())
        }
        withTimeout(8000) { quick.await() }
        assertFalse("La transferencia de B debe completar mientras A sigue descargando", slow.isCompleted)
        withTimeout(30000) { slow.await() }
        assertEquals(2, repository.loadBooks().size)
        assertEquals(1, control("stats").getJSONObject("qaA").getInt("connections"))
    }

    @Test fun realSftpRejectsTraversalEscapingSymlinksAndInvalidRangesWithoutRemoteWrites() = runBlocking {
        val (repository, a, _) = fixture()
        val hosts = File(context.filesDir, "direct-known-hosts").apply { createNewFile() }
        val client = SftpClient(hosts)
        try {
            val credentials = SftpClient.Credentials(a.host, a.port, a.username, a.password)
            assertFalse(client.listShelf(credentials, "/library", "/").any { it.name == "escape" })
            assertTrue(runCatching { client.readShelfRange(credentials, "/library", "/../outside/secret.cbz", 0, 4) }.isFailure)
            assertTrue(runCatching { client.readShelfRange(credentials, "/library", "/escape/secret.cbz", 0, 4) }.isFailure)
            assertTrue(runCatching { client.readShelfRange(credentials, "/library", "$remotePath/a.cbz", -1, 4) }.isFailure)
            assertTrue(runCatching { client.readShelfRange(credentials, "/library", "$remotePath/a.cbz", 0, 4 * 1024 * 1024 + 1) }.isFailure)
            val magic = client.readShelfRange(credentials, "/library", "$remotePath/a.cbz", 0, 4)
            assertTrue(magic.contentEquals(byteArrayOf(0x50, 0x4b, 0x03, 0x04)))
            assertEquals(1, client.readShelfRange(credentials, "/library", "$remotePath/a.cbz", 100, 1).size)
            val wireReads = control("stats").getJSONObject("qaA").getJSONArray("reads")
            assertEquals(1, wireReads.getJSONObject(wireReads.length() - 1).getInt("length"))
            assertEquals(1, wireReads.getJSONObject(wireReads.length() - 1).getInt("requested"))
            control("delay", "user" to "qaA", "seconds" to 0.8)
            val bounded = SftpClient(hosts, 300)
            val started = System.nanoTime()
            try {
                assertTrue(runCatching { bounded.readShelfRange(credentials, "/library", "$remotePath/a.cbz", 0, 1) }.isFailure)
                assertTrue("Una respuesta SFTP detenida debe respetar el plazo", (System.nanoTime() - started) / 1_000_000 < 2500)
            } finally { bounded.disconnectAll(); control("delay", "user" to "qaA", "seconds" to 0) }
            repository.checkSftpConfig(a)
            control("delay", "user" to "qaA", "seconds" to 1.2)
            val stalled = async { repository.downloadRemote(a.profileId, RemoteEntry("slow.txt", "/slow.txt", false, sourceId = a.id)) }
            try {
                delay(200)
                withTimeout(1500) { stalled.cancelAndJoin() }
                assertTrue(repository.loadBooks().isEmpty())
                assertTrue(File(context.filesDir, "native-library/books").listFiles().orEmpty().isEmpty())
            } finally { control("delay", "user" to "qaA", "seconds" to 0); stalled.cancelAndJoin() }
            repository.checkSftpConfig(a)
            assertEquals(0, control("stats").getJSONObject("qaA").getInt("writes"))
        } finally { client.disconnectAll() }
    }

    @Test fun changedHostKeyIsRejectedAfterRepositoryRecreationAndLegacyPinIsCopied() = runBlocking {
        val (repository, a, _) = fixture()
        repository.loadRemoteFolder(a.profileId, remotePath)
        val root = File(context.filesDir, "native-library")
        val oldPin = File(root, "profile-known-hosts").listFiles().orEmpty().single().readBytes()
        File(root, "known-hosts").writeBytes(oldPin)
        repository.close()
        val reopened = repository()
        val replacement = a.copy(profileId = UUID.randomUUID().toString())
        reopened.saveSftpConfigs(listOf(replacement))
        control("rotate")
        try {
            assertTrue(runCatching { reopened.loadRemoteFolder(replacement.profileId, remotePath, refresh = true) }.isFailure)
            val pins = File(root, "profile-known-hosts").listFiles().orEmpty()
            assertEquals(2, pins.size)
            assertTrue(pins.all { it.readBytes().contentEquals(oldPin) })
        } finally { control("reset") }
    }

    // SQLiteOpenHelper did not implement AutoCloseable on API 24, even though close() exists.
    private fun <T> withDatabase(block: (LibraryDatabase) -> T): T {
        val database = LibraryDatabase(context)
        try { return block(database) }
        finally { database.close() }
    }

    private fun repository() = LibraryRepository(context).also { repositories += it }
    private fun profile(user: String) = SftpConfig(host, port, user, "fixture-password", "/library", name = "Servidor $user")
    private suspend fun fixture(): Triple<LibraryRepository, SftpConfig, SftpConfig> {
        control("reset")
        val a = profile("qaA"); val b = profile("qaB")
        val repository = repository()
        repository.saveSftpConfigs(listOf(a, b))
        return Triple(repository, a, b)
    }
    private fun jsonProfile(config: SftpConfig) = JSONObject().apply {
        put("host", config.host); put("port", config.port); put("username", config.username); put("password", config.password)
        put("rootPath", config.rootPath); put("profileId", config.profileId); put("name", config.name)
    }
    private fun writeLegacy(json: JSONObject) {
        val preferences = context.getSharedPreferences("native-sftp", Context.MODE_PRIVATE)
        SftpConfigStore(context, "").saveAll(emptyList())
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, store.getKey("comics-reader-native-sftp", null) as SecretKey)
        val encrypted = JSONObject().apply {
            put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            put("data", Base64.encodeToString(cipher.doFinal(json.toString().toByteArray(Charsets.UTF_8)), Base64.NO_WRAP))
        }
        check(preferences.edit().clear().putString("configuration", encrypted.toString()).commit())
    }
    private fun control(op: String, vararg values: Pair<String, Any>): JSONObject {
        val request = JSONObject().put("op", op)
        values.forEach { (key, value) -> request.put(key, value) }
        return Socket().use { socket ->
            socket.connect(InetSocketAddress(host, controlPort), 3000); socket.soTimeout = 5000
            socket.getOutputStream().write((request.toString() + "\n").toByteArray())
            JSONObject(socket.getInputStream().bufferedReader().readLine())
        }
    }
    private fun assertPartialReads(reads: JSONArray) {
        assertTrue(reads.length() > 0)
        val paths = (0 until reads.length()).map { reads.getJSONObject(it) }.groupBy { it.getString("path") }
        paths.values.forEach { records ->
            val size = records.first().getLong("size")
            val intervals = records.filter { it.getInt("length") > 0 }.map {
                it.getLong("offset") until it.getLong("offset") + it.getInt("length")
            }.sortedBy { it.first }
            var covered = 0L; var end = -1L
            intervals.forEach { range ->
                if (range.first > end) covered += range.last - range.first + 1
                else if (range.last > end) covered += range.last - end
                end = maxOf(end, range.last)
            }
            assertTrue("La navegación no debe transferir el documento completo", covered < size)
        }
    }
}
