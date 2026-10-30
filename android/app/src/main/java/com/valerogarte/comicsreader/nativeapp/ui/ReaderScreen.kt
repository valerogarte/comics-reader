package com.valerogarte.comicsreader.nativeapp.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NavigateBefore
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FitScreen
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valerogarte.comicsreader.R
import kotlin.math.abs
import kotlinx.coroutines.delay

private const val READER_LOADING_DELAY_MS = 1_000L
private const val PAGE_TRANSITION_MS = 200

private data class ReaderPageImage(val index: Int, val bitmap: Bitmap)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReaderScreen(reader: UiReaderState, settings: UiSettings, actions: AppActions) {
    var controlsVisible by remember(reader.bookId) { mutableStateOf(true) }
    var readerOptions by remember(reader.bookId) { mutableStateOf(false) }
    var loadingIndicatorVisible by remember(reader.bookId, reader.pageIndex, reader.loading, reader.error) { mutableStateOf(false) }
    LaunchedEffect(reader.bookId, reader.pageIndex, reader.loading, reader.error) {
        if (reader.loading && reader.error == null) {
            delay(READER_LOADING_DELAY_MS)
            loadingIndicatorVisible = true
        }
    }
    var scale by remember(reader.bookId, reader.pageIndex) { mutableFloatStateOf(1f) }
    var offset by remember(reader.bookId, reader.pageIndex) { mutableStateOf(Offset.Zero) }
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
    fun navigateSide(right: Boolean) {
        if (right != settings.rightToLeft) nextPage() else previousPage()
    }
    DisposableEffect(context, settings.keepScreenOn) {
        val activity = context.findActivity()
        if (settings.keepScreenOn) activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }
    BackHandler { if (readerOptions) readerOptions = false else actions.onCloseReader() }
    Box(Modifier.fillMaxSize().background(background)) {
        if (reader.bitmap != null) {
            val transform = rememberTransformableState { zoomChange, panChange, _ ->
                scale = (scale * zoomChange).coerceIn(1f, 6f)
                offset = if (scale <= 1f) Offset.Zero else offset + panChange
            }
            var swipe by remember { mutableFloatStateOf(0f) }
            val zoomLabel = stringResource(R.string.native_reader_zoom, (scale * 100).toInt())
            Box(
                Modifier.fillMaxSize().clipToBounds().testTag("reader_page").semantics { stateDescription = zoomLabel }
                    .pointerInput(reader.bookId, pageIndex, pageCount, settings.rightToLeft, canNavigate) {
                        detectTapGestures(
                            onTap = { controlsVisible = !controlsVisible },
                            onDoubleTap = { tap ->
                                if (scale > 1f) { scale = 1f; offset = Offset.Zero }
                                else { scale = 2.5f; offset = Offset((size.width / 2f - tap.x) * 1.5f, (size.height / 2f - tap.y) * 1.5f) }
                            },
                        )
                    }
                    .pointerInput(pageIndex, pageCount, settings.rightToLeft, scale, canNavigate) {
                        if (scale <= 1.01f) detectHorizontalDragGestures(
                            onDragStart = { swipe = 0f },
                            onHorizontalDrag = { change, amount -> change.consume(); swipe += amount },
                            onDragEnd = { if (canNavigate && abs(swipe) > 70.dp.toPx()) navigateSide(swipe < 0f) },
                        )
                    }
                    .transformable(transform, canPan = { scale > 1f }),
                contentAlignment = Alignment.Center,
            ) {
                key(reader.bookId) {
                    AnimatedContent(
                        targetState = ReaderPageImage(pageIndex, reader.bitmap),
                        contentKey = { it.index },
                        transitionSpec = {
                            val direction = if ((targetState.index > initialState.index) != settings.rightToLeft) 1 else -1
                            val enter = fadeIn(tween(PAGE_TRANSITION_MS)) + slideInHorizontally(
                                tween(PAGE_TRANSITION_MS, easing = FastOutSlowInEasing),
                            ) { width -> direction * width / 12 }
                            val exit = slideOutHorizontally(
                                tween(PAGE_TRANSITION_MS, easing = FastOutSlowInEasing),
                            ) { width -> -direction * width / 12 }
                            (enter togetherWith exit).using(null)
                        },
                        modifier = Modifier.fillMaxSize().graphicsLayer(
                            scaleX = scale, scaleY = scale, translationX = offset.x, translationY = offset.y,
                        ),
                        label = "reader_page_transition",
                    ) { page ->
                        val image = remember(page.bitmap) { page.bitmap.asImageBitmap() }
                        Image(image, if (page.index == pageIndex) reader.title else null,
                            modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
                    }
                }
            }
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
                    if (reader.bitmap != null) IconButton(onClick = { scale = 1f; offset = Offset.Zero }) { Icon(Icons.Default.FitScreen, stringResource(R.string.native_fit_page)) }
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
                    if (reader.text != null || reader.reflowable) {
                        Text(stringResource(R.string.native_font_size, draft.fontSize.toInt()))
                        Slider(draft.fontSize.coerceIn(12f, 32f), onValueChange = { draft = draft.copy(fontSize = it) }, valueRange = 12f..32f)
                        ChoiceRow(stringResource(R.string.native_font), draft.font, listOf("sans" to stringResource(R.string.native_sans), "serif" to stringResource(R.string.native_serif), "mono" to stringResource(R.string.native_mono))) { draft = draft.copy(font = it) }
                    }
                    SettingSwitch(stringResource(R.string.native_keep_screen_on), draft.keepScreenOn) { draft = draft.copy(keepScreenOn = it) }
                    SettingSwitch(stringResource(R.string.native_right_to_left), draft.rightToLeft) { draft = draft.copy(rightToLeft = it) }
                }
            },
            confirmButton = { TextButton(onClick = { actions.onSaveSettings(draft); readerOptions = false }) { Text(stringResource(R.string.native_save)) } },
            dismissButton = { TextButton(onClick = { readerOptions = false }) { Text(stringResource(R.string.native_cancel)) } },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private fun Color.luminanceValue(): Float = .2126f * red + .7152f * green + .0722f * blue
