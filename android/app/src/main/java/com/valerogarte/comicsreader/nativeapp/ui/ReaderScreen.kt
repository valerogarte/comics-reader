package com.valerogarte.comicsreader.nativeapp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valerogarte.comicsreader.R
import kotlinx.coroutines.delay

private const val READER_LOADING_DELAY_MS = 1_000L

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(reader: UiReaderState, settings: UiSettings, actions: AppActions) {
    var controlsVisible by remember(reader.bookId) { mutableStateOf(true) }
    var readerOptions by remember(reader.bookId) { mutableStateOf(false) }
    var resetZoomRequest by remember(reader.bookId) { mutableIntStateOf(0) }
    var loadingIndicatorVisible by remember(reader.bookId, reader.pageIndex, reader.loading, reader.error) { mutableStateOf(false) }
    LaunchedEffect(reader.bookId, reader.pageIndex, reader.loading, reader.error) {
        if (reader.loading && reader.error == null) {
            delay(READER_LOADING_DELAY_MS)
            loadingIndicatorVisible = true
        }
    }
    var sliderPosition by remember(reader.pageIndex) { mutableFloatStateOf(reader.pageIndex.toFloat()) }
    val context = LocalContext.current
    val pageCount = reader.pageCount.coerceAtLeast(1)
    val pageIndex = reader.pageIndex.coerceIn(0, pageCount - 1)
    val dark = MaterialTheme.colorScheme.background.luminanceValue() < .3f
    val background = if (reader.text != null) readerBackground(settings, dark) else Color(0xFF121212)
    val textColor = if (dark) Color(0xFFEDE8DF) else Color(0xFF29241E)
    val canNavigate = !reader.loading && reader.error == null
    val previousPage = { if (canNavigate && pageIndex > 0) actions.onReaderPage(pageIndex - 1) }
    val nextPage = { if (canNavigate && pageIndex + 1 < pageCount) actions.onReaderPage(pageIndex + 1) }
    DisposableEffect(context, settings.keepScreenOn) {
        val activity = context.findActivity()
        if (settings.keepScreenOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    BackHandler { if (readerOptions) readerOptions = false else actions.onCloseReader() }
    Box(Modifier.fillMaxSize().background(background)) {
        if (settings.readingMode == ReaderMode.VERTICAL && reader.bitmap != null) {
            VerticalReaderPages(
                reader = reader,
                actions = actions,
                onTap = { controlsVisible = !controlsVisible },
                resetZoomRequest = resetZoomRequest,
            )
        } else if (reader.bitmap != null) {
            HorizontalReaderPages(
                reader = reader,
                settings = settings,
                actions = actions,
                onTap = { controlsVisible = !controlsVisible },
                resetZoomRequest = resetZoomRequest,
            )
        } else if (reader.text != null) {
            val scroll = rememberScrollState()
            LaunchedEffect(pageIndex) { scroll.scrollTo(0) }
            SelectionContainer {
                Text(reader.text, fontFamily = readerFont(settings), fontSize = settings.fontSize.sp,
                    lineHeight = (settings.fontSize * 1.65f).sp, color = textColor,
                    modifier = Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 24.dp, vertical = 96.dp))
            }
        } else if (!reader.loading && reader.error == null) {
            Text(stringResource(R.string.native_reader_unavailable), color = Color.White, modifier = Modifier.align(Alignment.Center).padding(32.dp))
        }
        if (reader.loading && loadingIndicatorVisible && reader.error == null) {
            Surface(Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), shape = MaterialTheme.shapes.large) {
                Row(Modifier.padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.native_reader_loading), Modifier.padding(start = 16.dp))
                }
            }
        }
        reader.error?.let { error ->
            Surface(Modifier.align(Alignment.Center).padding(32.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(24.dp)) {
                    Text(error)
                    TextButton(onClick = actions::onCloseReader, modifier = Modifier.align(Alignment.End).padding(top = 12.dp)) { Text(stringResource(R.string.native_close)) }
                }
            }
        }
        if (controlsVisible || reader.text != null) {
            TopAppBar(
                title = { Text(reader.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                navigationIcon = { IconButton(onClick = actions::onCloseReader) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.native_close)) } },
                actions = {
                    if (reader.bitmap != null) IconButton(onClick = { resetZoomRequest++ }) { Icon(Icons.Default.FitScreen, stringResource(R.string.native_fit_page)) }
                    IconButton(onClick = { readerOptions = true }) { Icon(Icons.Default.Settings, stringResource(R.string.native_reader_settings)) }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = .96f)),
                modifier = Modifier.align(Alignment.TopCenter),
            )
            Surface(Modifier.align(Alignment.BottomCenter), color = MaterialTheme.colorScheme.surface.copy(alpha = .96f)) {
                Column(Modifier.navigationBarsPadding().padding(horizontal = 20.dp, vertical = 6.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = previousPage, enabled = pageIndex > 0 && canNavigate) { Icon(Icons.AutoMirrored.Filled.NavigateBefore, stringResource(R.string.native_previous_page)) }
                        Text(stringResource(R.string.native_reader_page, pageIndex + 1, pageCount), modifier = Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                        IconButton(onClick = nextPage, enabled = pageIndex < pageCount - 1 && canNavigate) { Icon(Icons.AutoMirrored.Filled.NavigateNext, stringResource(R.string.native_next_page)) }
                    }
                    val pageSliderLabel = stringResource(R.string.native_go_to_page)
                    if (pageCount > 1) Slider(value = sliderPosition.coerceIn(0f, (pageCount - 1).toFloat()), onValueChange = { sliderPosition = it },
                        onValueChangeFinished = { if (canNavigate) actions.onReaderPage(sliderPosition.toInt()) }, valueRange = 0f..(pageCount - 1).toFloat(), enabled = canNavigate,
                        modifier = Modifier.fillMaxWidth().height(48.dp).testTag("reader_slider").semantics { contentDescription = pageSliderLabel })
                }
            }
        }
    }
    if (readerOptions) {
        var draft by remember(settings) { mutableStateOf(settings) }
        AlertDialog(
            onDismissRequest = { readerOptions = false },
            title = { Text(stringResource(R.string.native_reader_settings)) },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    ChoiceRow(
                        stringResource(R.string.native_reading_mode), draft.readingMode.name,
                        listOf(
                            ReaderMode.HORIZONTAL.name to stringResource(R.string.native_reading_horizontal),
                            ReaderMode.VERTICAL.name to stringResource(R.string.native_reading_vertical),
                        ),
                    ) { draft = draft.copy(readingMode = ReaderMode.valueOf(it)) }
                    ReaderTransitionSetting(draft.readerTransition, draft.readingMode == ReaderMode.HORIZONTAL) {
                        draft = draft.copy(readerTransition = it)
                    }
                    if (reader.text != null || reader.reflowable) {
                        Text(stringResource(R.string.native_font_size, draft.fontSize.toInt()))
                        Slider(draft.fontSize.coerceIn(12f, 32f), onValueChange = { draft = draft.copy(fontSize = it) }, valueRange = 12f..32f)
                        ChoiceRow(stringResource(R.string.native_font), draft.font, listOf("sans" to stringResource(R.string.native_sans), "serif" to stringResource(R.string.native_serif), "mono" to stringResource(R.string.native_mono))) { draft = draft.copy(font = it) }
                    }
                    SettingSwitch(stringResource(R.string.native_keep_screen_on), draft.keepScreenOn) { draft = draft.copy(keepScreenOn = it) }
                    if (draft.readingMode == ReaderMode.HORIZONTAL) {
                        SettingSwitch(stringResource(R.string.native_right_to_left), draft.rightToLeft) { draft = draft.copy(rightToLeft = it) }
                    }
                }
            },
            confirmButton = { TextButton(onClick = {
                actions.onUpdateAppearanceSettings { current -> current.copy(
                    font = draft.font, fontSize = draft.fontSize, keepScreenOn = draft.keepScreenOn,
                    rightToLeft = draft.rightToLeft, readingMode = draft.readingMode, readerTransition = draft.readerTransition,
                ) }
                readerOptions = false
            }) { Text(stringResource(R.string.native_save)) } },
            dismissButton = { TextButton(onClick = { readerOptions = false }) { Text(stringResource(R.string.native_cancel)) } },
        )
    }
}

@Composable
private fun ReaderTransitionSetting(value: ReaderTransition, enabled: Boolean, onChange: (ReaderTransition) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val choices = listOf(
        ReaderTransition.NONE to stringResource(R.string.native_transition_none),
        ReaderTransition.FADE to stringResource(R.string.native_transition_fade),
        ReaderTransition.SLIDE to stringResource(R.string.native_transition_slide),
        ReaderTransition.PAGE_TURN to stringResource(R.string.native_transition_page_turn),
        ReaderTransition.REVEAL to stringResource(R.string.native_transition_reveal),
        ReaderTransition.ZOOM to stringResource(R.string.native_transition_zoom),
    )
    Column {
        Text(stringResource(R.string.native_reader_transition), style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }, enabled = enabled, modifier = Modifier.fillMaxWidth()) {
                Text(choices.first { it.first == value }.second, Modifier.weight(1f))
                Icon(Icons.Default.ArrowDropDown, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                choices.forEach { (effect, label) ->
                    DropdownMenuItem(
                        text = { Text(label) },
                        trailingIcon = { if (effect == value) Icon(Icons.Default.Check, null) },
                        onClick = { onChange(effect); expanded = false },
                    )
                }
            }
        }
        if (!enabled) {
            Text(stringResource(R.string.native_transition_horizontal_only), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Color.luminanceValue(): Float = .2126f * red + .7152f * green + .0722f * blue
