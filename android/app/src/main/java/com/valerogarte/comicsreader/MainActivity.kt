package com.valerogarte.comicsreader

import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import com.valerogarte.comicsreader.nativeapp.LibraryViewModel
import com.valerogarte.comicsreader.nativeapp.ui.AppActions
import com.valerogarte.comicsreader.nativeapp.ui.ComicsApp

class MainActivity : ComponentActivity() {
    private lateinit var model: LibraryViewModel
    private val importBooks = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris -> model.importBooks(uris) }
    private val importFont = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importFont) }
    private val importBackground = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(model::importBackground) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        model = ViewModelProvider(this)[LibraryViewModel::class.java]
        model.onHostConfigurationChanged()
        val actions = object : AppActions by model {
            override fun onImportBooks() { importBooks.launch(arrayOf("*/*")) }
            override fun onImportFont() { importFont.launch(arrayOf("*/*")) }
            override fun onPickBackground() { importBackground.launch(arrayOf("image/*")) }
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            ComicsApp(state, actions)
        }
        if (savedInstanceState == null) importIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        importIntent(intent)
    }

    private fun importIntent(intent: Intent?) {
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { model.importBooks(listOf(it)) }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        val reader = if (::model.isInitialized) model.state.value.reader else null
        if (reader != null && handlesReaderVolume(keyCode)) {
            if (!reader.loading && reader.error == null && event.repeatCount == 0) {
                val step = if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) 1 else -1
                model.onReaderPage(reader.pageIndex + step)
            }
            return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean =
        if (handlesReaderVolume(keyCode)) true else super.onKeyUp(keyCode, event)

    private fun handlesReaderVolume(keyCode: Int): Boolean = ::model.isInitialized &&
        model.state.value.reader != null && window.decorView.hasWindowFocus() && !model.state.value.settingsVisible &&
        (keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
}
