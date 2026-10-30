package com.valerogarte.comicsreader

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.espresso.Espresso
import com.valerogarte.comicsreader.nativeapp.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Real Compose/Android windows, with isolated UI fixtures and no network or private NAS credentials. */
@RunWith(AndroidJUnit4::class)
class NativeUiInstrumentedTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private lateinit var actions: RecordingActions

    @Before fun launch() {
        actions = RecordingActions()
        compose.setContent { ComicsApp(actions.state, actions) }
    }

    @Test fun emptyLibraryOffersImportAndFooterWithoutTrash() {
        compose.runOnIdle { actions.state = actions.state.copy(books = emptyList()) }
        compose.onNodeWithText("0 libros").assertIsDisplayed()
        compose.onNodeWithContentDescription("Tamaño de portadas").assertExists()
        compose.onNodeWithTag("filter:ALL").assertIsSelected()
        compose.onNodeWithText("Papelera").assertDoesNotExist()
        compose.onNodeWithContentDescription("Añadir libros").performClick()
        compose.runOnIdle { assertEquals(1, actions.imports) }
    }

    @Test fun settingsCloseAndSystemBackDiscardChanges() {
        openSettings()
        compose.onNodeWithText("Oscuro").performClick()
        compose.onNodeWithContentDescription("Cerrar").performClick()
        compose.runOnIdle { assertFalse(actions.state.settingsVisible); assertEquals("system", actions.state.settings.theme) }
        openSettings()
        compose.onNodeWithText("Sistema").assertIsSelected()
        back()
        compose.runOnIdle { assertFalse(actions.state.settingsVisible); assertTrue(actions.saved.isEmpty()) }
    }

    @Test fun serverEditorValidatesEmptyFieldsAndCancelsWithoutSaving() {
        openSettings()
        compose.onNodeWithTag("add_server").performScrollTo().performClick()
        compose.onNodeWithTag("apply_server").assertIsNotEnabled()
        field("Nombre del servidor", "Nuevo")
        field("Servidor o dirección IP", "sftp://invalid/path")
        field("Usuario", "reader")
        compose.onNodeWithTag("apply_server").assertIsNotEnabled()
        field("Servidor o dirección IP", "192.0.2.5")
        field("Puerto", "0")
        compose.onNodeWithTag("apply_server").assertIsNotEnabled()
        field("Puerto", "22")
        field("Carpeta raíz", "relative")
        compose.onNodeWithTag("apply_server").assertIsNotEnabled()
        field("Carpeta raíz", "/Books")
        compose.onNodeWithTag("apply_server").assertIsEnabled()
        compose.onNodeWithText("Cancelar").performClick()
        compose.onNodeWithTag("save_settings").performClick()
        compose.runOnIdle { assertEquals(2, actions.saved.single().sftpServers.size) }
    }

    @Test fun serverDraftAddsEditsDeletesAndCommitsOnlyOnGlobalSave() {
        openSettings()
        compose.onNodeWithTag("add_server").performScrollTo().performClick()
        field("Nombre del servidor", "Vacaciones")
        field("Servidor o dirección IP", "192.0.2.10")
        field("Usuario", "reader")
        field("Contraseña", "fixture-password")
        field("Carpeta raíz", "/Comics")
        compose.onNodeWithTag("apply_server").performClick()
        compose.runOnIdle { assertEquals(2, actions.state.settings.sftpServers.size); assertTrue(actions.saved.isEmpty()) }
        compose.onNodeWithTag("edit_server:nas").performScrollTo().performClick()
        field("Nombre del servidor", "NAS Casa")
        compose.onNodeWithTag("apply_server").performClick()
        compose.onNodeWithTag("delete_server:backup").performScrollTo().performClick()
        compose.onNodeWithText("Los libros que hayas descargado se conservarán", substring = true).assertExists()
        compose.onNodeWithText("Borrar", substring = false).performClick()
        compose.onNodeWithTag("server_settings:backup").assertDoesNotExist()
        compose.onNodeWithTag("save_settings").performClick()
        compose.runOnIdle {
            val saved = actions.saved.single()
            assertEquals(listOf("NAS Casa", "Vacaciones"), saved.sftpServers.map { it.name })
            assertEquals("nas", saved.sftpServers.first().id)
            assertEquals("/Comics", saved.sftpServers.last().rootPath)
        }
    }

    @Test fun appliedServerDraftIsDiscardedWhenSettingsAreClosed() {
        openSettings()
        compose.onNodeWithTag("delete_server:backup").performScrollTo().performClick()
        compose.onNodeWithText("Borrar", substring = false).performClick()
        compose.onNodeWithContentDescription("Cerrar").performClick()
        openSettings()
        compose.onNodeWithTag("server_settings:backup").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { assertEquals(2, actions.state.settings.sftpServers.size); assertTrue(actions.saved.isEmpty()) }
    }

    @Test fun externalFontAndBackgroundUpdatesPreserveSettingsDraft() {
        openSettings()
        compose.onNodeWithText("Oscuro").performClick()
        compose.onNodeWithText("Añadir fuente TTF u OTF").performScrollTo().performClick()
        compose.onNodeWithText("Elegir imagen de fondo").performScrollTo().performClick()
        compose.runOnIdle {
            assertEquals(1, actions.fontImports); assertEquals(1, actions.backgroundImports)
            actions.state = actions.state.copy(settings = actions.state.settings.copy(fontPath = "/fixture.ttf", font = "custom", backgroundImagePath = "/fixture.png"))
        }
        compose.onNodeWithTag("save_settings").performClick()
        compose.runOnIdle { assertEquals("dark", actions.saved.single().theme); assertEquals("/fixture.ttf", actions.saved.single().fontPath) }
    }

    @Test fun drawerSwitchesIndependentSftpSourcesAndBackReturnsToBooks() {
        compose.onNodeWithContentDescription("Abrir menú").performClick()
        compose.onNodeWithTag("server_navigation:nas").performClick()
        compose.onNodeWithText("Remoto NAS").assertIsDisplayed()
        compose.onNodeWithText("Remoto Backup").assertDoesNotExist()
        compose.onNodeWithContentDescription("Abrir menú").performClick()
        compose.onNodeWithTag("server_navigation:nas").assertIsSelected()
        compose.onNodeWithTag("server_navigation:backup").performClick()
        compose.onNodeWithText("Remoto Backup").assertIsDisplayed()
        compose.onNodeWithText("Remoto NAS").assertDoesNotExist()
        back()
        compose.runOnIdle { assertEquals(LibraryDestination.BOOKS, actions.state.destination) }
    }

    @Test fun sftpParentSyncDownloadsAndGreenTickUseSelectedSource() {
        compose.runOnIdle { actions.onOpenSftpServer("nas"); actions.state = actions.state.copy(remotePath = "/Comics/DC") }
        compose.onNodeWithText("Carpeta superior").performClick()
        compose.runOnIdle { assertEquals("/Comics", actions.folders.last()) }
        compose.onNodeWithContentDescription("Sincronizar carpeta").performClick()
        compose.runOnIdle { assertEquals(1, actions.syncs) }
        compose.onNode(hasContentDescription("Descargar") and hasAnyAncestor(hasTestTag("book:/nas.cbz")), useUnmergedTree = true).performClick()
        compose.onNodeWithContentDescription("Descargado").assertExists()
        compose.runOnIdle { assertEquals(listOf("nas" to "/nas.cbz"), actions.downloads) }
        compose.onNodeWithText("Descargar todos").assertIsNotEnabled()
        compose.runOnIdle { actions.state = actions.state.copy(remoteEntries = actions.state.remoteEntries.map { it.copy(downloaded = false) }) }
        compose.onNodeWithText("Descargar todos").performClick()
        compose.runOnIdle { assertEquals(1, actions.downloadAll) }
        compose.runOnIdle { actions.state = actions.state.copy(remotePath = "/Comics/DC") }
        back()
        compose.runOnIdle { assertEquals("/Comics", actions.folders.last()) }
    }

    @Test fun filtersLayoutAndScrollStateSurviveReaderRoundTrip() {
        compose.onNodeWithTag("filter:UNREAD").performClick()
        compose.onNodeWithTag("book:unread").assertExists()
        compose.onNodeWithTag("book:reading").assertDoesNotExist()
        compose.onNodeWithContentDescription("Lista").performClick()
        compose.onNodeWithTag("book_list").assertIsDisplayed()
        compose.onNodeWithTag("book:unread").performClick()
        compose.onNodeWithContentDescription("Cerrar").performClick()
        compose.onNodeWithTag("filter:UNREAD").assertIsSelected()
        compose.onNodeWithTag("book_list").assertIsDisplayed()
        compose.onNodeWithTag("filter:READING").performClick()
        compose.onNodeWithTag("book:reading").assertExists()
        compose.onNodeWithTag("book:unread").assertDoesNotExist()
        compose.onNodeWithTag("filter:FINISHED").performScrollTo().performClick()
        compose.onNodeWithTag("book:finished").assertExists()
        compose.onNodeWithText("3 libros").assertIsDisplayed()
    }

    @Test fun selectionCreatesShelfAndPermanentDeletionOffersEmptyShelfRemoval() {
        compose.onNodeWithTag("book:unread").performTouchInput { longClick(center) }
        compose.onNodeWithTag("book:reading").performClick()
        compose.onNodeWithContentDescription("Añadir a una estantería").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Nueva")
        compose.onNodeWithText("Añadir", substring = false).performClick()
        compose.runOnIdle { assertEquals(setOf("unread", "reading"), actions.shelfBooks.single().first.toSet()); assertEquals("Nueva", actions.shelfBooks.single().second) }
        compose.onNodeWithTag("book:unread").performTouchInput { longClick(center) }
        compose.onNodeWithTag("book:reading").performClick()
        compose.onNodeWithContentDescription("Borrar definitivamente").performClick()
        compose.onNodeWithText("Cancelar").performClick()
        compose.runOnIdle { assertTrue(actions.deletions.isEmpty()) }
        compose.onNodeWithContentDescription("Borrar definitivamente").performClick()
        compose.onNodeWithText("Borrar", substring = false).performClick()
        compose.onNodeWithText("¿Quieres borrar también la estantería?").assertIsDisplayed()
        compose.onNodeWithText("Conservar").performClick()
        compose.runOnIdle { assertEquals(setOf("unread", "reading"), actions.deletions.single().toSet()); assertNull(actions.state.emptyShelf) }
    }

    @Test fun emptyShelfCanBeRemovedWithoutDeletingOtherBooks() {
        compose.runOnIdle { actions.state = actions.state.copy(emptyShelf = UiShelf("empty", "Vacía")) }
        compose.onNodeWithText("Borrar", substring = false).performClick()
        compose.runOnIdle { assertEquals(listOf("empty"), actions.deletedShelves); assertEquals(3, actions.state.books.size) }
    }

    @Test fun drawerCreatesAndOpensLocalShelf() {
        compose.onNodeWithContentDescription("Abrir menú").performClick()
        compose.onNodeWithContentDescription("Nueva estantería").performClick()
        compose.onNode(hasSetTextAction()).performTextInput("Colección")
        compose.onNodeWithText("Crear", substring = false).performClick()
        compose.runOnIdle { assertEquals(listOf("Colección"), actions.createdShelves) }
        compose.onNodeWithContentDescription("Abrir menú").performClick()
        compose.onNodeWithText("Colección", substring = false).performClick()
        compose.runOnIdle { assertEquals(LibraryDestination.SHELF, actions.state.destination); assertEquals("Colección", actions.state.selectedShelfId) }
        compose.onNodeWithText("Esta estantería está vacía").assertIsDisplayed()
        back()
        compose.runOnIdle { assertEquals(LibraryDestination.BOOKS, actions.state.destination) }
    }

    @Test fun readerSliderAndBoundaryButtonsExposeAccessibleState() {
        compose.runOnIdle { actions.openReader() }
        compose.onNodeWithContentDescription("Página anterior").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Ir a una página").assertIsDisplayed()
        compose.onNodeWithTag("reader_slider").performSemanticsAction(SemanticsActions.SetProgress) { action -> assertTrue(action(4f)) }
        compose.runOnIdle { assertEquals(4, actions.pages.last()) }
        compose.onNodeWithText("5 de 10").assertIsDisplayed()
        compose.runOnIdle { actions.state = actions.state.copy(reader = actions.state.reader!!.copy(pageIndex = 9)) }
        compose.onNodeWithContentDescription("Página siguiente").assertIsNotEnabled()
    }

    @Test fun readerTapSwipeRtlZoomAndLoadingGuard() {
        compose.runOnIdle { actions.openReader(page = 1) }
        compose.onNodeWithTag("reader_page").performTouchInput { click(Offset(width * .9f, height * .45f)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Opciones de lectura").fetchSemanticsNodes().isEmpty() }
        compose.runOnIdle { assertTrue(actions.pages.isEmpty()); assertEquals(1, actions.state.reader!!.pageIndex) }
        compose.onNodeWithTag("reader_page").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertEquals(2, actions.pages.last()); actions.state = actions.state.copy(settings = actions.state.settings.copy(rightToLeft = true)) }
        compose.onNodeWithTag("reader_page").performTouchInput { click(Offset(width * .1f, height * .45f)) }
        compose.waitUntil(5_000) { compose.onAllNodesWithContentDescription("Opciones de lectura").fetchSemanticsNodes().size == 1 }
        compose.runOnIdle { assertEquals(listOf(2), actions.pages) }
        compose.onNodeWithTag("reader_page").performTouchInput { swipeRight() }
        compose.runOnIdle { assertEquals(3, actions.pages.last()) }
        compose.onNodeWithTag("reader_page").performTouchInput { doubleClick(center) }
        compose.onNodeWithTag("reader_page").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom 250 %"))
        compose.onNodeWithContentDescription("Ajustar página").performClick()
        compose.onNodeWithTag("reader_page").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "Zoom 100 %"))
        compose.runOnIdle { actions.state = actions.state.copy(reader = actions.state.reader!!.copy(loading = true)); actions.pages.clear() }
        compose.onNodeWithContentDescription("Página siguiente").assertIsNotEnabled()
        compose.onNodeWithTag("reader_page").performTouchInput { swipeLeft() }
        compose.runOnIdle { assertTrue(actions.pages.isEmpty()) }
    }

    @Test fun readerSettingsScreenFlagAndBackAreLifecycleSafe() {
        compose.runOnIdle { actions.openReader() }
        compose.runOnIdle { assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON != 0) }
        compose.onNodeWithContentDescription("Opciones de lectura").performClick()
        compose.onNodeWithText("Tamaño de texto:", substring = true).assertExists()
        compose.onNodeWithText("Mantener pantalla encendida").assertIsOn().performClick()
        compose.onNodeWithText("Guardar").performClick()
        compose.runOnIdle { assertFalse(actions.state.settings.keepScreenOn); assertEquals(0, compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        back()
        compose.onNodeWithTag("book_grid").assertIsDisplayed()
    }

    @Test fun messagesAreVisibleAboveSettingsAndReader() {
        openSettings()
        compose.runOnIdle { actions.state = actions.state.copy(error = "Error de prueba") }
        compose.onNodeWithText("Error de prueba").assertIsDisplayed()
        compose.onNodeWithText("Aceptar").performClick()
        compose.onNodeWithContentDescription("Cerrar").performClick()
        compose.runOnIdle { actions.openReader(); actions.state = actions.state.copy(message = "Mensaje de prueba") }
        compose.onNodeWithText("Mensaje de prueba").assertIsDisplayed()
        compose.onNodeWithText("Aceptar").performClick()
        compose.onNodeWithTag("reader_page").assertIsDisplayed()
    }

    private fun openSettings() { compose.onNodeWithContentDescription("Configuración").performClick() }
    private fun field(label: String, value: String) { compose.onNode(hasSetTextAction() and hasText(label)).performScrollTo().performTextReplacement(value) }
    private fun back() { Espresso.pressBack(); compose.waitForIdle() }

    private class RecordingActions : AppActions {
        private val bitmap = Bitmap.createBitmap(300, 450, Bitmap.Config.ARGB_8888).apply { eraseColor(android.graphics.Color.WHITE) }
        var state by mutableStateOf(AppUiState(
            books = listOf(UiBook("unread", "Sin comenzar", "epub", 1024), UiBook("reading", "En lectura", "cbz", 2048, progress = .4f), UiBook("finished", "Terminado", "pdf", 4096, progress = 1f)),
            shelves = listOf(UiShelf("empty", "Vacía")),
            settings = UiSettings(sftpServers = listOf(UiSftpServer("nas", "NAS", "192.0.2.1", username = "reader", password = "fixture"), UiSftpServer("backup", "Backup", "192.0.2.2", username = "reader", password = "fixture"))),
        ))
        val saved = ArrayList<UiSettings>()
        val folders = ArrayList<String>()
        val downloads = ArrayList<Pair<String?, String>>()
        val shelfBooks = ArrayList<Pair<List<String>, String>>()
        val deletions = ArrayList<List<String>>()
        val deletedShelves = ArrayList<String>()
        val createdShelves = ArrayList<String>()
        val pages = ArrayList<Int>()
        var imports = 0; var fontImports = 0; var backgroundImports = 0; var syncs = 0; var downloadAll = 0
        override fun onShowSettings() { state = state.copy(settingsVisible = true) }
        override fun onCloseSettings() { state = state.copy(settingsVisible = false) }
        override fun onSaveSettings(settings: UiSettings) { saved += settings; state = state.copy(settings = settings, settingsVisible = false) }
        override fun onImportBooks() { imports++ }
        override fun onImportFont() { fontImports++ }
        override fun onPickBackground() { backgroundImports++ }
        override fun onNavigate(destination: LibraryDestination, shelfId: String?) { state = state.copy(destination = destination, selectedShelfId = shelfId) }
        override fun onOpenSftpServer(id: String) {
            state = state.copy(destination = LibraryDestination.SFTP, selectedSftpServerId = id, remotePath = "/", remoteEntries = listOf(UiRemoteEntry("/$id.cbz", "Remoto ${if (id == "nas") "NAS" else "Backup"}", false, format = "cbz", size = 1000)))
        }
        override fun onOpenRemoteFolder(path: String) { folders += path; state = state.copy(remotePath = path) }
        override fun onSyncRemote() { syncs++ }
        override fun onDownloadRemote(path: String) { downloads += state.selectedSftpServerId to path; state = state.copy(remoteEntries = state.remoteEntries.map { if (it.path == path) it.copy(downloaded = true) else it }) }
        override fun onDownloadAll() { downloadAll++; state = state.copy(remoteEntries = state.remoteEntries.map { it.copy(downloaded = true) }) }
        override fun onOpenBook(id: String) { openReader(id = id) }
        fun openReader(page: Int = 0, id: String = "unread") { state = state.copy(reader = UiReaderState(id, "Lectura", pageIndex = page, pageCount = 10, bitmap = bitmap, reflowable = true)) }
        override fun onReaderPage(pageIndex: Int) { pages += pageIndex; state = state.copy(reader = state.reader!!.copy(pageIndex = pageIndex)) }
        override fun onCloseReader() { state = state.copy(reader = null) }
        override fun onAddBooksToShelf(ids: List<String>, name: String) { shelfBooks += ids to name; state = state.copy(shelves = state.shelves + UiShelf(name, name)) }
        override fun onCreateShelf(name: String) { createdShelves += name; state = state.copy(shelves = state.shelves + UiShelf(name, name)) }
        override fun onDeleteBooks(ids: List<String>) { deletions += ids; state = state.copy(books = state.books.filterNot { it.id in ids }, emptyShelf = UiShelf("Nueva", "Nueva")) }
        override fun onKeepEmptyShelf() { state = state.copy(emptyShelf = null) }
        override fun onDeleteShelf(id: String) { deletedShelves += id; state = state.copy(shelves = state.shelves.filterNot { it.id == id }, emptyShelf = null) }
        override fun onDismissMessage() { state = state.copy(error = null, message = null) }
    }
}
