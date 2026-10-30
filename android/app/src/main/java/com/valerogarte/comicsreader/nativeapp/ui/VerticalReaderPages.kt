package com.valerogarte.comicsreader.nativeapp.ui

import android.graphics.Bitmap
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListLayoutInfo
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.valerogarte.comicsreader.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.abs

/** The list owns only composed pages; document sessions and the bounded bitmap cache stay in the VM. */
@Composable
internal fun VerticalReaderPages(
    reader: UiReaderState,
    actions: AppActions,
    onTap: () -> Unit,
    resetZoomRequest: Int,
    modifier: Modifier = Modifier,
) {
    // The initial render establishes page count and saved position before the list reports progress.
    if (reader.bitmap == null) {
        Box(modifier.fillMaxSize().pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) }, contentAlignment = Alignment.Center) {
            if (!reader.loading && reader.error == null) Text(stringResource(R.string.native_reader_unavailable), color = Color.White)
        }
        return
    }
    key(reader.bookId, reader.sessionId) {
        VerticalReaderDocument(reader, actions, onTap, resetZoomRequest, modifier)
    }
}

@Composable
private fun VerticalReaderDocument(reader: UiReaderState, actions: AppActions, onTap: () -> Unit, resetZoomRequest: Int, modifier: Modifier) {
    val bookId = reader.bookId
    val sessionId = reader.sessionId
    val pageCount = reader.pageCount.coerceAtLeast(1)
    val list = rememberLazyListState(initialFirstVisibleItemIndex = reader.pageIndex.coerceIn(0, pageCount - 1))
    val latestNavigationRequest by rememberUpdatedState(reader.navigationRequest)
    val currentActions by rememberUpdatedState(actions)
    // A restored list may lag a navigation request retained by the ViewModel.
    // Do not publish its old index until the first alignment has completed.
    var appliedNavigationRequest by remember { mutableLongStateOf(-1L) }
    var consumedZoomReset by remember { mutableIntStateOf(resetZoomRequest) }
    var zoomReset by remember { mutableStateOf<VerticalZoomReset?>(null) }
    val fallbackRatio = remember(reader.bitmap) { reader.bitmap?.pageRatio() ?: (2f / 3f) }

    // Position reports change pageIndex too. Only an explicit navigationRequest scrolls the list.
    LaunchedEffect(reader.navigationRequest) {
        if (appliedNavigationRequest != reader.navigationRequest) {
            val target = reader.pageIndex.coerceIn(0, pageCount - 1)
            // Retain the restored within-page scroll offset when it is current.
            if (appliedNavigationRequest != -1L || list.firstVisibleItemIndex != target) list.scrollToItem(target)
            appliedNavigationRequest = reader.navigationRequest
        }
    }
    LaunchedEffect(bookId, sessionId, list) {
        snapshotFlow {
            val request = latestNavigationRequest
            if (request != appliedNavigationRequest || list.layoutInfo.visibleItemsInfo.isEmpty()) null
            else request to list.firstVisibleItemIndex.coerceIn(0, pageCount - 1)
        }.filterNotNull().distinctUntilChanged().collect { (request, index) ->
            currentActions.onReaderPosition(bookId, sessionId, request, index)
        }
    }
    LaunchedEffect(resetZoomRequest) {
        if (consumedZoomReset != resetZoomRequest) {
            consumedZoomReset = resetZoomRequest
            zoomReset = VerticalZoomReset(resetZoomRequest, (centralPage(list.layoutInfo) ?: list.firstVisibleItemIndex).coerceIn(0, pageCount - 1))
        }
    }

    val insets = WindowInsets.safeDrawing.asPaddingValues()
    val layoutDirection = LocalLayoutDirection.current
    BoxWithConstraints(modifier.fillMaxSize()) {
        val tailHeight = maxHeight
        LazyColumn(
            state = list,
            modifier = Modifier.fillMaxSize().background(Color(0xFF121212)).testTag("vertical_reader_pages")
                .pointerInput(onTap) { detectTapGestures(onTap = { onTap() }) },
            contentPadding = PaddingValues(
                start = insets.calculateStartPadding(layoutDirection), end = insets.calculateEndPadding(layoutDirection),
                top = insets.calculateTopPadding() + 96.dp, bottom = insets.calculateBottomPadding() + 156.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(count = pageCount, key = { it }, contentType = { "reader_page" }) { index ->
                VerticalReaderPage(
                    bookId, sessionId, index, pageCount, fallbackRatio,
                    reader.bitmap.takeIf { reader.bitmapPageIndex == index },
                    actions, onTap, zoomReset,
                )
            }
            // The final page can align at the top even when it is shorter than the viewport.
            item(key = "reader_end_space", contentType = "reader_end_space") { Spacer(Modifier.height(tailHeight)) }
        }
    }
}

private data class VerticalZoomReset(val request: Int, val pageIndex: Int)
private data class VerticalPageLoad(val bitmap: Bitmap? = null, val loading: Boolean = true)

@Composable
private fun VerticalReaderPage(
    bookId: String, sessionId: Int, index: Int, pageCount: Int, fallbackRatio: Float, initialBitmap: Bitmap?,
    actions: AppActions, onTap: () -> Unit, zoomReset: VerticalZoomReset?,
) {
    var retry by remember { mutableIntStateOf(0) }
    val load by produceState(VerticalPageLoad(initialBitmap, initialBitmap == null), bookId, sessionId, index, retry) {
        value = VerticalPageLoad(initialBitmap, initialBitmap == null)
        if (initialBitmap == null) {
            value = try {
                VerticalPageLoad(actions.loadReaderPage(bookId, sessionId, index), loading = false)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                VerticalPageLoad(loading = false)
            }
        }
    }
    var loadingVisible by remember(load.loading, retry) { mutableStateOf(false) }
    LaunchedEffect(load.loading, retry) {
        if (load.loading) { delay(1_000L); loadingVisible = true }
    }
    var scale by remember { mutableFloatStateOf(1f) }
    var panX by remember { mutableFloatStateOf(0f) }
    var panY by remember { mutableFloatStateOf(0f) }
    var frameSize by remember { mutableStateOf(IntSize.Zero) }
    fun fit() { scale = 1f; panX = 0f; panY = 0f }
    fun clampPan(pan: Offset): Offset {
        val maxX = frameSize.width * (scale - 1f) / 2f
        val maxY = frameSize.height * (scale - 1f) / 2f
        return Offset(pan.x.coerceIn(-maxX, maxX), pan.y.coerceIn(-maxY, maxY))
    }
    LaunchedEffect(zoomReset) { if (zoomReset?.pageIndex == index) fit() }
    val transform = rememberTransformableState { zoom, pan, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        val clamped = if (scale <= 1f) Offset.Zero else clampPan(Offset(panX, panY) + pan)
        panX = clamped.x; panY = clamped.y
    }
    val pageLabel = stringResource(R.string.native_reader_page, index + 1, pageCount)
    val zoomLabel = stringResource(R.string.native_reader_zoom, (scale * 100).toInt())
    val imageAlpha by animateFloatAsState(if (load.bitmap == null) 0f else 1f, tween(180), label = "vertical_page_fade")
    val hasBitmap = load.bitmap != null
    Box(
        Modifier.fillMaxWidth().aspectRatio(load.bitmap?.pageRatio() ?: fallbackRatio).clipToBounds()
            .testTag("reader_vertical_page:$index").onSizeChanged { frameSize = it }
            .semantics { contentDescription = pageLabel; stateDescription = zoomLabel; onClick { onTap(); true } }
            .pointerInput(bookId, sessionId, index, hasBitmap) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = if (!hasBitmap) null else { tap ->
                        if (scale > 1f) fit() else {
                            scale = 2.5f
                            val clamped = clampPan(Offset((size.width / 2f - tap.x) * 1.5f, (size.height / 2f - tap.y) * 1.5f))
                            panX = clamped.x; panY = clamped.y
                        }
                    },
                )
            }
            .transformable(transform, canPan = { scale > 1f }, enabled = hasBitmap),
        contentAlignment = Alignment.Center,
    ) {
        val bitmap = load.bitmap
        if (bitmap != null) {
            val image = remember(bitmap) { bitmap.asImageBitmap() }
            Image(image, null, modifier = Modifier.fillMaxSize().graphicsLayer(
                alpha = imageAlpha, scaleX = scale, scaleY = scale, translationX = panX, translationY = panY,
            ), contentScale = ContentScale.Fit)
        } else if (load.loading && loadingVisible) {
            Surface(Modifier.padding(24.dp), color = MaterialTheme.colorScheme.surface.copy(alpha = .94f), shape = MaterialTheme.shapes.large) {
                Row(Modifier.padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Text(stringResource(R.string.native_reader_loading), Modifier.padding(start = 12.dp))
                }
            }
        } else if (!load.loading) {
            Surface(Modifier.padding(24.dp), shape = MaterialTheme.shapes.large) {
                Column(Modifier.padding(20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(stringResource(R.string.native_reader_unavailable))
                    TextButton(onClick = { retry += 1 }) { Text(stringResource(R.string.native_retry)) }
                }
            }
        }
    }
}

private fun Bitmap.pageRatio(): Float = width.toFloat() / height.coerceAtLeast(1)

private fun centralPage(layout: LazyListLayoutInfo): Int? {
    val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
    return layout.visibleItemsInfo.firstOrNull { center >= it.offset && center < it.offset + it.size }?.index
        ?: layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index
}
