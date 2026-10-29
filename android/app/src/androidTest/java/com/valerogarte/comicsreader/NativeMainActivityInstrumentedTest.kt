package com.valerogarte.comicsreader

import android.content.ContentValues
import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.SystemClock
import android.provider.MediaStore
import android.view.KeyEvent
import android.view.inspector.WindowInspector
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import com.valerogarte.comicsreader.nativeapp.LibraryViewModel
import com.valerogarte.comicsreader.nativeapp.data.LibraryRepository
import com.valerogarte.comicsreader.nativeapp.model.BookEntry
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.OutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.regex.Pattern

/** Opt-in emulator smoke through the production Activity and Android's actual DocumentsUI. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 29)
class NativeMainActivityInstrumentedTest {
    @get:Rule val compose = createEmptyComposeRule()
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val device get() = UiDevice.getInstance(instrumentation)
    private var scenario: ActivityScenario<MainActivity>? = null
    private var repository: LibraryRepository? = null
    private lateinit var model: LibraryViewModel
    private var baselineBookIds = emptySet<String>()
    private var baselineConfigFingerprint = ""
    private var baselineAppearance = emptyMap<String, Any?>()
    private var baselineAppearanceFiles = emptySet<String>()
    private var baselineCaptured = false
    private val ownedUris = ArrayList<Uri>()
    private val ownedBookIds = HashSet<String>()
    private val prefix = "native-qa-${UUID.randomUUID()}"
    private val pdfName = "$prefix.pdf"
    private val imageName = "$prefix.png"
    private val fontName = "$prefix.ttf"
    private val textName = "$prefix.txt"
    private val appearanceKeys = setOf("theme", "background", "font", "fontSize", "fontPath", "backgroundImagePath", "keepScreenOn", "rightToLeft")

    @Before fun prepareEmulatorOnly() {
        val emulator = Build.FINGERPRINT.lowercase().let { it.contains("generic") || it.contains("emulator") || it.contains("sdk_gphone") } || Build.HARDWARE in setOf("ranchu", "goldfish")
        assumeTrue("This smoke is restricted to an explicitly enabled emulator", emulator && InstrumentationRegistry.getArguments().getString("qaAllowUiSmoke") == "true")
        assumeTrue("MediaStore Downloads requires API 29", Build.VERSION.SDK_INT >= 29)
        val repo = LibraryRepository(context)
        repository = repo
        baselineBookIds = runBlocking { repo.loadBooks().mapTo(HashSet()) { it.id } }
        baselineConfigFingerprint = configFingerprint(repo)
        baselineAppearance = context.getSharedPreferences("native-appearance", Context.MODE_PRIVATE).all.toMap()
        baselineAppearanceFiles = File(context.filesDir, "appearance").listFiles().orEmpty().mapTo(HashSet()) { it.canonicalPath }
        baselineCaptured = true
        createDocuments()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        scenario!!.onActivity { model = ViewModelProvider(it)[LibraryViewModel::class.java] }
        await("library initialization") { model.state.value.busyMessage == null }
    }

    @After fun restoreOnlyOwnedArtifacts() {
        // Activity destruction stops its jobs before touching the test-owned copies.
        scenario?.close()
        SystemClock.sleep(300)
        val repo = repository ?: return
        if (!baselineCaptured) { repo.close(); return }
        try {
            runBlocking {
                val ours = repo.loadBooks().filter { it.id !in baselineBookIds && (it.id in ownedBookIds || it.fileName in setOf(pdfName, textName)) }
                repo.deleteBooks(ours.map { it.id })
                assertTrue("Pre-existing books must remain unchanged", repo.loadBooks().map { it.id }.containsAll(baselineBookIds))
            }
            assertTrue("SFTP profiles must remain unchanged", configFingerprint(repo) == baselineConfigFingerprint)
        } finally {
            val preferences = context.getSharedPreferences("native-appearance", Context.MODE_PRIVATE)
            val newAssets = listOfNotNull(preferences.getString("fontPath", null), preferences.getString("backgroundImagePath", null))
                .map(::File).filter { it.canonicalPath !in baselineAppearanceFiles && it.parentFile?.canonicalPath == File(context.filesDir, "appearance").canonicalPath }
            val edit = preferences.edit()
            appearanceKeys.forEach { key ->
                when (val value = baselineAppearance[key]) {
                    is String -> edit.putString(key, value)
                    is Boolean -> edit.putBoolean(key, value)
                    is Float -> edit.putFloat(key, value)
                    is Int -> edit.putInt(key, value)
                    is Long -> edit.putLong(key, value)
                    null -> edit.remove(key)
                }
            }
            check(edit.commit())
            newAssets.forEach { it.delete() }
            ownedUris.forEach { uri -> runCatching { context.contentResolver.delete(uri, null, null) } }
            repo.close()
        }
    }

    @Test fun realSafImportReadRecreateFontBackgroundAndSettingsClose() {
        // Every app action below is performed through the production Compose UI.
        compose.onNodeWithContentDescription("Añadir libros").performClick()
        chooseDocument(pdfName)
        val book = awaitBook()
        ownedBookIds += book.id
        await("book import complete") { model.state.value.busyMessage == null && model.state.value.books.any { it.id == book.id } }
        dismissMessage()
        compose.onNodeWithContentDescription("Lista").performClick()
        compose.onNodeWithTag("book_list").performScrollToNode(hasTestTag("book:${book.id}"))
        captureEvidence("saf-books")
        compose.onNodeWithTag("book:${book.id}").performClick()
        await("native PDF page") { model.state.value.reader?.bookId == book.id && model.state.value.reader?.bitmap != null && model.state.value.reader?.loading == false }
        compose.onNodeWithContentDescription("Página siguiente").performClick()
        await("second page") { model.state.value.reader?.pageIndex == 1 && model.state.value.reader?.loading == false }
        device.pressKeyCode(KeyEvent.KEYCODE_VOLUME_DOWN)
        await("volume down turns to the third page") { model.state.value.reader?.pageIndex == 2 && model.state.value.reader?.loading == false }
        device.pressKeyCode(KeyEvent.KEYCODE_VOLUME_UP)
        await("volume up returns to the second page") { model.state.value.reader?.pageIndex == 1 && model.state.value.reader?.loading == false }
        scenario!!.recreate()
        scenario!!.onActivity { model = ViewModelProvider(it)[LibraryViewModel::class.java] }
        await("reader retained after Activity recreation") { model.state.value.reader?.bookId == book.id && model.state.value.reader?.pageIndex == 1 && model.state.value.reader?.bitmap != null }
        compose.onNodeWithText("2 de 3").assertIsDisplayed()
        captureEvidence("saf-reader")
        device.pressBack()
        await("reader closes") { model.state.value.reader == null }
        await("progress persisted") { model.state.value.books.any { it.id == book.id && it.progress > 0f } && runBlocking { repository!!.loadBook(book.id) }?.currentPage == 1 }
        compose.onNodeWithTag("book_list").assertIsDisplayed()
        compose.onNodeWithTag("filter:READING").performClick()
        compose.onNodeWithTag("book_list").performScrollToNode(hasTestTag("book:${book.id}"))
        compose.onNodeWithTag("book:${book.id}").assertIsDisplayed()

        compose.onNodeWithContentDescription("Configuración").performClick()
        compose.onNodeWithText("Añadir fuente TTF u OTF").performScrollTo().performClick()
        chooseDocument(fontName)
        await("custom font copied into private storage") { model.state.value.settings.fontPath?.let { it != baselineAppearance["fontPath"] && it !in baselineAppearanceFiles && File(it).isFile } == true }
        assertEquals("custom", model.state.value.settings.font)
        compose.onNodeWithText("Elegir imagen de fondo").performScrollTo().performClick()
        chooseDocument(imageName)
        await("background copied into private storage") { model.state.value.settings.backgroundImagePath?.let { it != baselineAppearance["backgroundImagePath"] && it !in baselineAppearanceFiles && File(it).isFile } == true }
        compose.onNodeWithContentDescription("Cerrar").performClick()
        await("settings close button") { !model.state.value.settingsVisible }
        compose.onNodeWithContentDescription("Configuración").performClick()
        awaitSettingsFocus()
        device.pressBack()
        await("settings system Back") { !model.state.value.settingsVisible }
        assertTrue("SFTP profiles must remain unchanged", configFingerprint(repository!!) == baselineConfigFingerprint)
    }

    @Test fun reflowableReaderFollowsSystemNightModeAcrossActivityRecreation() {
        val originalMode = Regex("(?i)Night mode:\\s*(custom_schedule|custom_bedtime|yes|no|auto)")
            .find(shell("cmd uimode night"))?.groupValues?.get(1)?.lowercase()
            ?: throw AssertionError("Cannot safely restore the emulator's original system night mode")
        try {
            setNightMode("no", Configuration.UI_MODE_NIGHT_NO)
            compose.onNodeWithContentDescription("Configuración").performClick()
            compose.onNodeWithText("Sistema").performClick()
            compose.onNodeWithText("Blanco").performClick()
            compose.onNodeWithTag("save_settings").performClick()
            await("system appearance settings saved") { !model.state.value.settingsVisible && model.state.value.settings.theme == "system" && model.state.value.settings.background == "white" }

            compose.onNodeWithContentDescription("Añadir libros").performClick()
            chooseDocument(textName)
            val book = awaitBook(textName)
            ownedBookIds += book.id
            await("text import complete") { model.state.value.busyMessage == null && model.state.value.books.any { it.id == book.id } }
            dismissMessage()
            compose.onNodeWithContentDescription("Lista").performClick()
            compose.onNodeWithTag("filter:ALL").performClick()
            compose.onNodeWithTag("book_list").performScrollToNode(hasTestTag("book:${book.id}"))
            compose.onNodeWithTag("book:${book.id}").performClick()
            await("native reflowable text page") { model.state.value.reader?.let { it.bookId == book.id && it.reflowable && !it.loading && it.bitmap != null } == true }
            assertEquals("The light text page must use the selected white background", Color.WHITE, readerBackground())
            compose.onNodeWithContentDescription("Página siguiente").performClick()
            await("second text page") { model.state.value.reader?.let { it.pageIndex == 1 && !it.loading && it.bitmap != null } == true }
            val pageCount = requireNotNull(model.state.value.reader).pageCount

            setNightMode("yes", Configuration.UI_MODE_NIGHT_YES)
            // Keep the retained production ViewModel while Android recreates the Activity.
            scenario!!.recreate()
            scenario!!.onActivity { model = ViewModelProvider(it)[LibraryViewModel::class.java] }
            await("reader bitmap adopts the system dark background after recreation") {
                model.state.value.reader?.let { it.bookId == book.id && !it.loading && it.bitmap != null && readerBackground() == Color.rgb(24, 23, 21) } == true
            }
            assertEquals("Night mode must preserve the current reading page", 1, model.state.value.reader?.pageIndex)
            assertEquals("A color-only change must preserve pagination", pageCount, model.state.value.reader?.pageCount)

            setNightMode("no", Configuration.UI_MODE_NIGHT_NO)
            scenario!!.recreate()
            scenario!!.onActivity { model = ViewModelProvider(it)[LibraryViewModel::class.java] }
            await("reader bitmap returns to the light background") { model.state.value.reader?.let { !it.loading && it.bitmap != null && readerBackground() == Color.WHITE } == true }
            assertEquals("Returning to light mode must preserve the current page", 1, model.state.value.reader?.pageIndex)
        } finally {
            shell("cmd uimode night $originalMode")
        }
    }

    private fun createDocuments() {
        writeDownload(pdfName, "application/pdf") { output ->
            val pdf = PdfDocument()
            try {
                repeat(3) { index ->
                    val page = pdf.startPage(PdfDocument.PageInfo.Builder(600, 900, index + 1).create())
                    page.canvas.drawColor(Color.WHITE)
                    page.canvas.drawText("SAF QA ${index + 1} $prefix", 24f, 90f, Paint().apply { color = Color.BLACK; textSize = 18f })
                    pdf.finishPage(page)
                }
                pdf.writeTo(output)
            } finally { pdf.close() }
        }
        writeDownload(imageName, "image/png") { output ->
            val image = Bitmap.createBitmap(180, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(220, 230, 240)) }
            try { check(image.compress(Bitmap.CompressFormat.PNG, 100, output)) } finally { image.recycle() }
        }
        writeDownload(textName, "text/plain") { output ->
            output.writer(Charsets.UTF_8).apply {
                repeat(120) { index -> append("Página de prueba $index. $prefix. Este libro de texto comprueba la paginación nativa y el cambio de apariencia del sistema.\n\n") }
                flush()
            }
        }
        val font = listOf("/system/fonts/Roboto-Regular.ttf", "/system/fonts/NotoSans-Regular.ttf").map(::File).firstOrNull { it.isFile }
            ?: File("/system/fonts").listFiles().orEmpty().firstOrNull { it.isFile && it.extension == "ttf" }
            ?: throw AssertionError("The emulator has no system TTF fixture")
        writeDownload(fontName, "font/ttf") { output -> font.inputStream().use { it.copyTo(output) } }
    }

    private fun writeDownload(name: String, mime: String, write: (OutputStream) -> Unit) {
        val resolver = context.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/ComicsReaderQA/")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }) ?: throw AssertionError("Could not create an owned MediaStore fixture")
        ownedUris += uri
        requireNotNull(resolver.openOutputStream(uri)).use(write)
        resolver.update(uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null)
    }

    private fun chooseDocument(name: String) {
        await("DocumentsUI opens", 15_000) { device.currentPackageName in setOf("com.google.android.documentsui", "com.android.documentsui") }
        val documentsPackage = device.currentPackageName
        fun directoryItem(text: String) = device.findObject(By.res(documentsPackage, "dir_list"))?.let { list ->
            // Images use thumbnail tiles with only an accessibility description,
            // while documents and directories expose their filename as text.
            list.findObject(By.text(text)) ?: list.findObject(By.descStartsWith(text))
        }
        if (directoryItem(name) == null) {
            // Query text and search history can equal a filename. Navigate to our owned
            // folder and select only inside the real directory list instead.
            repeat(2) {
                if (device.findObject(By.res(documentsPackage, "roots_list")) == null) {
                    val navigation = device.findObject(By.res(documentsPackage, "toolbar"))
                        ?.findObject(By.clazz("android.widget.ImageButton"))
                        ?: throw AssertionError("DocumentsUI has no navigation button")
                    navigation.click()
                    SystemClock.sleep(250)
                }
            }
            val roots = device.wait(Until.findObject(By.res(documentsPackage, "roots_list")), 5_000)
                ?: throw AssertionError("DocumentsUI did not show its storage roots")
            val downloads = roots.findObject(By.text(Pattern.compile("Downloads|Descargas", Pattern.CASE_INSENSITIVE)))
                ?: throw AssertionError("DocumentsUI has no Downloads root")
            downloads.click()
            await("owned Downloads directory") { directoryItem("ComicsReaderQA") != null || directoryItem(name) != null }
            directoryItem("ComicsReaderQA")?.click()
        }
        await("owned fixture inside the DocumentsUI directory list") { directoryItem(name) != null }
        requireNotNull(directoryItem(name)).click()
        SystemClock.sleep(500)
        if (device.currentPackageName == documentsPackage) {
            device.findObject(By.text(Pattern.compile("OPEN|ABRIR|SELECT|SELECCIONAR", Pattern.CASE_INSENSITIVE)))?.click()
        }
        await("DocumentsUI returns the selected content URI", 15_000) { device.currentPackageName == context.packageName }
        compose.waitForIdle()
    }

    private fun captureEvidence(name: String) {
        compose.waitForIdle()
        val directory = File(context.filesDir, "qa-evidence").apply { check(isDirectory || mkdirs()) }
        assertTrue("Capture $name", device.takeScreenshot(File(directory, "$name.png")))
    }

    private fun awaitSettingsFocus() {
        await("settings state becomes visible") { model.state.value.settingsVisible }
        compose.onNodeWithContentDescription("Cerrar").assertIsDisplayed()
        await("settings Dialog receives Android window focus") {
            var focused = false
            scenario!!.onActivity { activity ->
                focused = !activity.window.decorView.hasWindowFocus() &&
                    WindowInspector.getGlobalWindowViews().any { it.isShown && it.hasWindowFocus() }
            }
            focused
        }
        compose.waitForIdle()
    }

    private fun awaitBook(name: String = pdfName): BookEntry {
        var book: BookEntry? = null
        await("SAF-imported book in native database") {
            book = runBlocking { repository!!.loadBooks().firstOrNull { it.id !in baselineBookIds && it.fileName == name } }
            book != null
        }
        return requireNotNull(book)
    }

    private fun readerBackground(): Int? = runCatching { model.state.value.reader?.bitmap?.takeUnless { it.isRecycled }?.getPixel(0, 0) }.getOrNull()

    private fun setNightMode(mode: String, expected: Int) {
        shell("cmd uimode night $mode")
        await("system night mode becomes $mode") { context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == expected }
        scenario!!.onActivity { model = ViewModelProvider(it)[LibraryViewModel::class.java] }
        compose.waitForIdle()
    }

    private fun shell(command: String): String = android.os.ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use { it.readText() }

    private fun dismissMessage() {
        await("import status dialog") { model.state.value.message != null || model.state.value.error != null }
        assertNull("The production import must succeed", model.state.value.error)
        compose.onNodeWithText("Aceptar").performClick()
    }

    private fun await(label: String, timeout: Long = 30_000, condition: () -> Boolean) {
        val deadline = SystemClock.elapsedRealtime() + timeout
        while (SystemClock.elapsedRealtime() < deadline) {
            if (condition()) return
            SystemClock.sleep(50)
        }
        throw AssertionError("Timeout: $label")
    }

    private fun configFingerprint(repo: LibraryRepository): String = runBlocking {
        val data = repo.loadSftpConfigs().sortedBy { it.profileId }.joinToString("\u0000") { it.toString() }.toByteArray(Charsets.UTF_8)
        MessageDigest.getInstance("SHA-256").digest(data).joinToString("") { "%02x".format(it) }
    }
}
