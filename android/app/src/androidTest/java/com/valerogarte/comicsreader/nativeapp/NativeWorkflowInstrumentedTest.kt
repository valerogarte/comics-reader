package com.valerogarte.comicsreader.nativeapp

import android.graphics.Color
import android.graphics.Bitmap
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.SystemClock
import androidx.lifecycle.ViewModelStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.valerogarte.comicsreader.nativeapp.qa.QaApplication
import com.valerogarte.comicsreader.nativeapp.ui.*
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.net.Socket

@RunWith(AndroidJUnit4::class)
class NativeWorkflowInstrumentedTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private lateinit var application: QaApplication
    private lateinit var model: LibraryViewModel
    private var store = ViewModelStore()
    private val port get() = InstrumentationRegistry.getArguments().getString("sftpQaPort", "22991")!!.toInt()
    private val controlPort get() = InstrumentationRegistry.getArguments().getString("sftpQaControlPort", "22992")!!.toInt()
    private val servers get() = listOf(
        UiSftpServer("workflow-a", "Servidor A", "10.0.2.2", port.toString(), "qaA", "fixture-password", "/library"),
        UiSftpServer("workflow-b", "Servidor B", "10.0.2.2", port.toString(), "qaB", "fixture-password", "/library"),
    )

    @Before fun setup() {
        application = QaApplication(instrumentation.targetContext)
        createModel()
        saveSettings(model.state.value.settings.copy(sftpServers = emptyList()))
    }
    @After fun cleanup() {
        main { store.clear() }
        SystemClock.sleep(300)
        application.dispose()
    }
    private fun main(action: () -> Unit) = instrumentation.runOnMainSync(action)
    private fun createModel() {
        main { model = LibraryViewModel(application); store.put("qa", model); model.onShowSettings() }
        await("initial settings loaded") { it.settingsVisible }
        main { model.onCloseSettings() }
    }
    private fun restartModel() {
        main { store.clear(); store = ViewModelStore() }
        SystemClock.sleep(300)
        createModel()
    }
    private fun await(label: String, timeout: Long = 45_000, condition: (AppUiState) -> Boolean): AppUiState {
        val start = SystemClock.elapsedRealtime()
        while (SystemClock.elapsedRealtime() - start < timeout) {
            val current = model.state.value
            if (condition(current)) return current
            SystemClock.sleep(25)
        }
        val current = model.state.value
        fail("Timeout: $label; error=${current.error}; busy=${current.busyMessage}; books=${current.books.size}; loading=${current.loading}")
        return current
    }
    private fun saveSettings(settings: UiSettings) {
        main { model.onShowSettings() }
        await("settings visible") { it.settingsVisible }
        main { model.onSaveSettings(settings) }
        await("settings saved") { !it.settingsVisible && it.settings.sftpServers.map { server -> server.id } == settings.sftpServers.map { server -> server.id } }
    }
    private fun pdf(name: String, color: Int): File {
        val file = File(application.filesDir, name)
        val document = PdfDocument()
        try {
            repeat(3) { index ->
                val page = document.startPage(PdfDocument.PageInfo.Builder(600, 900, index + 1).create())
                page.canvas.drawColor(color)
                page.canvas.drawText("QA $name página ${index + 1}", 50f, 100f, Paint().apply { textSize = 24f; this.color = Color.BLACK })
                document.finishPage(page)
            }
            file.outputStream().use(document::writeTo)
        } finally { document.close() }
        return file
    }
    private fun importFile(file: File, expectedCount: Int) {
        main { model.importBooks(listOf(Uri.fromFile(file))) }
        await("imported") { it.books.size == expectedCount && it.busyMessage == null }
        main { model.onDismissMessage() }
    }
    private fun control(op: String, user: String? = null, value: Boolean? = null, seconds: Double? = null): JSONObject {
        val request = JSONObject().put("op", op)
        user?.let { request.put("user", it) }; value?.let { request.put("value", it) }; seconds?.let { request.put("seconds", it) }
        Socket("10.0.2.2", controlPort).use { socket ->
            socket.soTimeout = 10_000
            socket.getOutputStream().write((request.toString() + "\n").toByteArray())
            socket.getOutputStream().flush()
            return JSONObject(socket.getInputStream().bufferedReader().readLine())
        }
    }
    private fun openFolder(serverId: String) {
        main { model.onOpenSftpServer(serverId); model.onOpenRemoteFolder("/Comics/DC/Sandman") }
        await("SFTP folder $serverId") { it.selectedSftpServerId == serverId && !it.loading && it.remotePath == "/Comics/DC/Sandman" && it.remoteEntries.count { entry -> !entry.isDirectory } == 2 }
    }

    @Test fun settingsProfilesPersistAndCloseRepeatedly() {
        saveSettings(model.state.value.settings.copy(sftpServers = servers))
        repeat(5) {
            main { model.onShowSettings() }; await("settings open") { it.settingsVisible }
            main { model.onCloseSettings() }; assertFalse(model.state.value.settingsVisible)
        }
        restartModel()
        assertEquals(listOf("workflow-a", "workflow-b"), model.state.value.settings.sftpServers.map { it.id })
        saveSettings(model.state.value.settings.copy(sftpServers = emptyList()))
        restartModel()
        assertTrue(model.state.value.settings.sftpServers.isEmpty())
    }

    @Test fun importReadProgressAndPermanentDeleteEmptyShelf() {
        val file = pdf("workflow.pdf", Color.WHITE)
        importFile(file, 1)
        val id = model.state.value.books.single().id
        importFile(file, 1)
        main { model.onCreateShelf("QA estantería"); model.onAddBooksToShelf(listOf(id), "QA estantería") }
        await("shelf membership") { it.books.single().shelfIds.contains("QA estantería") }
        main { model.onOpenBook(id) }
        await("reader loaded") { it.reader?.bitmap != null && it.reader?.loading == false }
        assertEquals(3, model.state.value.reader!!.pageCount)
        main { model.onReaderPage(2) }
        await("last page") { it.reader?.pageIndex == 2 && it.reader?.loading == false }
        main { model.onCloseReader() }
        await("progress saved") { it.reader == null && it.books.single().progress == 1f }
        restartModel()
        assertEquals(1f, model.state.value.books.single().progress)
        main { model.onDeleteBooks(listOf(id)) }
        await("permanent delete prompt") { it.books.isEmpty() && it.emptyShelf?.name == "QA estantería" }
        assertTrue(File(application.filesDir, "native-library/books").listFiles().orEmpty().isEmpty())
        assertTrue(file.isFile)
        main { model.onDeleteShelf("QA estantería") }
        await("empty shelf removed") { it.emptyShelf == null && it.shelves.none { shelf -> shelf.name == "QA estantería" } }
    }

    @Test fun cancelledReaderCannotReplaceNewBook() {
        importFile(pdf("first.pdf", Color.RED), 1)
        importFile(pdf("second.pdf", Color.GREEN), 2)
        val ids = model.state.value.books.map { it.id }
        main { model.onOpenBook(ids[0]); model.onCloseReader(); model.onOpenBook(ids[1]) }
        val loaded = await("second reader remains active") { it.reader?.bookId == ids[1] && it.reader?.bitmap != null && it.reader?.loading == false }
        assertNull(loaded.reader!!.error)
        SystemClock.sleep(250)
        assertEquals(ids[1], model.state.value.reader?.bookId)
        main { model.onCloseReader() }
    }

    @Test fun damagedServerConfigurationDoesNotHideTheLocalLibrary() {
        importFile(pdf("local-survives.pdf", Color.WHITE), 1)
        application.getSharedPreferences("native-sftp", 0).edit().putString("configurations", "{invalid encrypted record").commit()
        restartModel()
        assertEquals(1, model.state.value.books.size)
        assertNotNull(model.state.value.error)
        main { model.onCloseSettings(); model.onDismissMessage() }
        assertFalse(model.state.value.settingsVisible)
        saveSettings(model.state.value.settings.copy(sftpServers = servers))
        restartModel()
        assertEquals(1, model.state.value.books.size)
        assertEquals(2, model.state.value.settings.sftpServers.size)
        assertNull(model.state.value.error)
    }

    @Test fun duplicateImportsReportOnlyNewBooks() {
        val file = pdf("duplicate.pdf", Color.YELLOW)
        main { model.importBooks(listOf(Uri.fromFile(file), Uri.fromFile(file))) }
        val result = await("duplicate import result") { it.books.size == 1 && it.busyMessage == null && it.message != null }
        assertTrue(result.message!!.startsWith("1 libro importado"))
        assertTrue(result.message!!.contains("1 ya estaba"))
    }

    @Test fun parallelServersKeepSelectionCacheAndDownloadsSeparate() {
        control("reset")
        saveSettings(model.state.value.settings.copy(sftpServers = servers))
        openFolder("workflow-a")
        main { model.onDownloadAll(); model.onOpenSftpServer("workflow-b"); model.onOpenRemoteFolder("/Comics/DC/Sandman") }
        await("server B visible") { !it.loading && it.selectedSftpServerId == "workflow-b" && it.remoteEntries.size == 2 }
        assertTrue(model.state.value.remoteEntries.all { it.title.startsWith("qaB-") })
        main { model.onDownloadAll() }
        await("both downloads completed", 90_000) { it.books.size == 4 && it.busyMessage == null }
        assertTrue(model.state.value.remoteEntries.all { it.downloaded })
        control("deny", "qaA", true)
        main { model.onOpenSftpServer("workflow-a") }
        await("server A offline cached") { !it.loading && it.remoteEntries.size == 2 && it.remoteEntries.all { entry -> entry.downloaded } }
        assertTrue(model.state.value.remoteEntries.all { it.title.startsWith("qaA-") })
        main { model.onOpenSftpServer("workflow-b"); model.onSyncRemote() }
        await("B still works") { !it.loading && it.remoteEntries.all { entry -> entry.title.startsWith("qaB-") } }
        assertNull(model.state.value.error)
        control("deny", "qaA", false)
    }

    @Test fun invalidSaveDoesNotLeaveCatalogLoadingOrAffectOtherServer() {
        control("reset")
        saveSettings(model.state.value.settings.copy(sftpServers = servers))
        control("delay", "qaA", seconds = 0.03)
        main { model.onOpenSftpServer("workflow-a") }
        await("scan started") { it.loading }
        val invalid = model.state.value.settings.copy(sftpServers = servers.map { if (it.id == "workflow-a") it.copy(rootPath = "/../invalid") else it })
        main { model.onSaveSettings(invalid) }
        await("invalid save clears loading") { it.error != null && !it.loading && it.busyMessage == null }
        assertEquals("/library", model.state.value.settings.sftpServers.first().rootPath)
        main { model.onDismissMessage() }
        openFolder("workflow-b")
        assertNull(model.state.value.error)
    }

    @Test fun nativeFontBackgroundAndReaderAppearancePersist() {
        val text = File(application.filesDir, "reflow.txt").apply { writeText((1..2500).joinToString("\n") { "Párrafo $it de lectura nativa para verificar la fuente y el progreso." }) }
        importFile(text, 1)
        val id = model.state.value.books.single().id
        main { model.onOpenBook(id) }
        val original = await("reflow reader") { it.reader?.bitmap != null && it.reader?.loading == false }
        assertTrue(original.reader!!.reflowable)
        val originalCount = original.reader!!.pageCount
        main { model.onReaderPage(originalCount / 2) }
        await("middle page") { it.reader?.pageIndex == originalCount / 2 && it.reader?.loading == false }
        val sourceFont = listOf("NotoSerif-Regular.ttf", "Roboto-Regular.ttf", "RobotoStatic-Regular.ttf").map { File("/system/fonts", it) }.first { it.isFile }
        main { model.importFont(Uri.fromFile(sourceFont)) }
        await("font copied") { it.settings.fontPath?.let { path -> File(path).isFile } == true }
        val background = File(application.filesDir, "background.png")
        val image = Bitmap.createBitmap(40, 60, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.BLUE) }
        try { background.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { image.recycle() }
        main { model.importBackground(Uri.fromFile(background)) }
        await("background copied") { it.settings.backgroundImagePath?.let { path -> File(path).isFile } == true }
        val settings = model.state.value.settings.copy(theme = "light", background = "sepia", fontSize = 30f)
        main { model.onSaveSettings(settings) }
        val changed = await("reflow appearance applied") { it.reader?.bitmap != null && it.reader?.loading == false && it.settings.fontSize == 30f && it.reader!!.pageCount > originalCount }
        val fraction = changed.reader!!.pageIndex.toFloat() / (changed.reader!!.pageCount - 1)
        assertTrue("reading position retained", fraction in .40f.. .60f)
        main { model.onCloseReader() }
        await("reader closed") { it.reader == null }
        restartModel()
        assertEquals(30f, model.state.value.settings.fontSize)
        assertEquals("custom", model.state.value.settings.font)
        assertTrue(File(model.state.value.settings.fontPath!!).isFile)
        assertTrue(File(model.state.value.settings.backgroundImagePath!!).isFile)
    }
}
