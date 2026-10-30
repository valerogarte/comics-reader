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
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.MotionDurationScale
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipRect
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
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.valerogarte.comicsreader.R
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor

/** Only visible pages and their neighbours borrow bitmaps from the bounded document cache. */
@Composable
internal fun HorizontalReaderPages(
    reader: UiReaderState,
    settings: UiSettings,
    actions: AppActions,
    onTap: () -> Unit,
    resetZoomRequest: Int,
    modifier: Modifier = Modifier,
) {
    key(reader.bookId, reader.sessionId) {
        HorizontalReaderDocument(reader, settings, actions, onTap, resetZoomRequest, modifier)
    }
}

@Composable
private fun HorizontalReaderDocument(
    reader: UiReaderState,
    settings: UiSettings,
    actions: AppActions,
    onTap: () -> Unit,
    resetZoomRequest: Int,
    modifier: Modifier,
) {
    val bookId = reader.bookId
    val sessionId = reader.sessionId
    val pageCount = reader.pageCount.coerceAtLeast(1)
    val initialPage = remember { reader.pageIndex.coerceIn(0, pageCount - 1) }
    val motionScale = rememberCoroutineScope().coroutineContext[MotionDurationScale]?.scaleFactor ?: 1f
    val effect = if (motionScale == 0f) ReaderTransition.NONE else settings.readerTransition
    val seedBitmap = remember { reader.bitmap }
    val seedPage = remember { reader.bitmapPageIndex ?: initialPage }
    val pager = rememberPagerState(initialPage = initialPage, pageCount = { pageCount })
    val latestNavigationRequest by rememberUpdatedState(reader.navigationRequest)
    val currentActions by rememberUpdatedState(actions)
    // PagerState restores independently of the retained ViewModel. Its saved
    // page can precede a navigation request that arrived during recreation.
    // Align them before accepting any viewport position as acknowledged.
    var appliedNavigationRequest by remember { mutableLongStateOf(-1L) }
    var currentZoom by remember { mutableFloatStateOf(1f) }

    LaunchedEffect(reader.navigationRequest) {
        val request = reader.navigationRequest
        if (appliedNavigationRequest != request) {
            val target = reader.pageIndex.coerceIn(0, pageCount - 1)
            try {
                if (appliedNavigationRequest == -1L || effect == ReaderTransition.NONE || abs(target - pager.currentPage) > 1) {
                    pager.scrollToPage(target)
                } else {
                    pager.animateScrollToPage(target, animationSpec = tween(240))
                }
            } finally {
                // A gesture may interrupt the animation; its eventual settled page remains valid.
                if (latestNavigationRequest == request) appliedNavigationRequest = request
            }
        }
    }
    LaunchedEffect(bookId, sessionId, pager) {
        snapshotFlow {
            val request = latestNavigationRequest
            if (request != appliedNavigationRequest || pager.isScrollInProgress || pager.layoutInfo.visiblePagesInfo.isEmpty()) null
            else request to pager.settledPage.coerceIn(0, pageCount - 1)
        }.filterNotNull().distinctUntilChanged().collect { (request, index) ->
            currentActions.onReaderPosition(bookId, sessionId, request, index)
        }
    }
    LaunchedEffect(pager.currentPage) { currentZoom = 1f }
    val zoomLabel = stringResource(R.string.native_reader_zoom, (currentZoom * 100).toInt())
    val pageLabel = stringResource(R.string.native_reader_page, pager.currentPage + 1, pageCount)
    CompositionLocalProvider(LocalLayoutDirection provides if (settings.rightToLeft) LayoutDirection.Rtl else LayoutDirection.Ltr) {
        HorizontalPager(
            state = pager,
            beyondViewportPageCount = 1,
            key = { it },
            userScrollEnabled = !reader.loading && reader.error == null && currentZoom <= 1.01f,
            modifier = modifier.fillMaxSize().background(Color(0xFF121212)).clipToBounds().testTag("reader_page")
                .semantics { stateDescription = zoomLabel; contentDescription = pageLabel; onClick { onTap(); true } },
        ) { index ->
            HorizontalReaderPage(
                bookId, sessionId, index, seedBitmap.takeIf { seedPage == index }, actions, onTap,
                active = index == pager.currentPage && !pager.isScrollInProgress,
                resetZoomRequest = resetZoomRequest,
                animateLoadedImage = effect != ReaderTransition.NONE,
                onZoom = { zoom -> if (index == pager.currentPage) currentZoom = zoom },
                modifier = pageTransition(pager, index, effect, settings.rightToLeft),
            )
        }
    }
}

/** Pager supplies the finger position; these layers transform that same position without timers. */
@Composable
private fun pageTransition(pager: PagerState, page: Int, effect: ReaderTransition, rtl: Boolean): Modifier {
    // Crossing more than one page during an interrupted fling still transforms only its current pair.
    val sourceIndex by remember(pager) {
        derivedStateOf {
            val position = pager.currentPage + pager.currentPageOffsetFraction
            (if (position >= pager.settledPage) floor(position).toInt() else ceil(position).toInt()).coerceIn(0, pager.pageCount - 1)
        }
    }
    val source = page == sourceIndex
    val depth = when {
        effect == ReaderTransition.NONE -> if (page == pager.currentPage) 2f else 0f
        effect == ReaderTransition.PAGE_TURN -> if (source) 2f else 1f
        else -> if (source) 0f else 1f
    }
    return Modifier.fillMaxSize().zIndex(depth).graphicsLayer {
        alpha = 1f
        translationX = 0f
        scaleX = 1f
        scaleY = 1f
        rotationY = 0f
        transformOrigin = TransformOrigin.Center
        val offset = pager.currentPage - page + pager.currentPageOffsetFraction
        val distance = abs(offset).coerceIn(0f, 1f)
        val direction = if (rtl) -1f else 1f
        val movement = pager.currentPage + pager.currentPageOffsetFraction - sourceIndex
        val target = sourceIndex + when { movement > 0f -> 1; movement < 0f -> -1; else -> 0 }
        if (effect != ReaderTransition.SLIDE) translationX = offset * size.width * direction
        if (effect != ReaderTransition.SLIDE && effect != ReaderTransition.NONE && !source && page != target) alpha = 0f
        when (effect) {
            ReaderTransition.NONE -> alpha = if (page == pager.currentPage) 1f else 0f
            ReaderTransition.FADE -> alpha = if (source) 1f else if (page == target) 1f - distance else 0f
            ReaderTransition.ZOOM -> {
                alpha = if (source) 1f else if (page == target) 1f - distance else 0f
                scaleX = if (source) 1f else 1f - .08f * distance
                scaleY = scaleX
            }
            ReaderTransition.PAGE_TURN -> if (source) {
                val hingeLeft = (offset >= 0f) != rtl
                transformOrigin = TransformOrigin(if (hingeLeft) 0f else 1f, .5f)
                rotationY = (-offset * direction * 89f).coerceIn(-89f, 89f)
                cameraDistance = size.width * 3f
            }
            ReaderTransition.SLIDE, ReaderTransition.REVEAL -> Unit
        }
    }.drawWithContent {
        val position = pager.currentPage + pager.currentPageOffsetFraction
        val progress = abs(position - sourceIndex).coerceIn(0f, 1f)
        when {
            effect == ReaderTransition.REVEAL && !source -> {
                val fromRight = (position >= sourceIndex) != rtl
                clipRect(left = if (fromRight) size.width * (1f - progress) else 0f,
                    right = if (fromRight) size.width else size.width * progress) { this@drawWithContent.drawContent() }
            }
            effect == ReaderTransition.PAGE_TURN && source -> {
                drawContent()
                val dark = Color.Black.copy(alpha = .42f * progress)
                val hingeLeft = (position >= sourceIndex) != rtl
                drawRect(Brush.horizontalGradient(if (hingeLeft) listOf(Color.Transparent, dark) else listOf(dark, Color.Transparent)))
            }
            else -> drawContent()
        }
    }
}

private data class HorizontalPageLoad(val bitmap: Bitmap? = null, val loading: Boolean = true)

@Composable
private fun HorizontalReaderPage(
    bookId: String,
    sessionId: Int,
    index: Int,
    initialBitmap: Bitmap?,
    actions: AppActions,
    onTap: () -> Unit,
    active: Boolean,
    resetZoomRequest: Int,
    animateLoadedImage: Boolean,
    onZoom: (Float) -> Unit,
    modifier: Modifier,
) {
    var retry by remember { mutableIntStateOf(0) }
    val load by produceState(HorizontalPageLoad(initialBitmap, initialBitmap == null), bookId, sessionId, index, retry) {
        value = HorizontalPageLoad(initialBitmap, initialBitmap == null)
        if (initialBitmap == null) {
            value = try {
                HorizontalPageLoad(actions.loadReaderPage(bookId, sessionId, index), loading = false)
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { HorizontalPageLoad(loading = false) }
        }
    }
    var loadingVisible by remember(load.loading, retry) { mutableStateOf(false) }
    LaunchedEffect(load.loading, retry) { if (load.loading) { delay(1_000L); loadingVisible = true } }
    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var frame by remember { mutableStateOf(IntSize.Zero) }
    fun fit() { scale = 1f; pan = Offset.Zero }
    fun clampPan(value: Offset): Offset {
        val maxX = frame.width * (scale - 1f) / 2f
        val maxY = frame.height * (scale - 1f) / 2f
        return Offset(value.x.coerceIn(-maxX, maxX), value.y.coerceIn(-maxY, maxY))
    }
    LaunchedEffect(active, resetZoomRequest) { fit() }
    LaunchedEffect(scale, active) { if (active) onZoom(scale) }
    val transform = rememberTransformableState { zoom, movement, _ ->
        scale = (scale * zoom).coerceIn(1f, 6f)
        pan = if (scale <= 1f) Offset.Zero else clampPan(pan + movement)
    }
    val hasBitmap = load.bitmap != null
    val imageAlpha by animateFloatAsState(if (hasBitmap) 1f else 0f, tween(if (animateLoadedImage) 180 else 0), label = "horizontal_page_loaded")
    Box(modifier.background(Color(0xFF121212)), contentAlignment = Alignment.Center) {
        Box(Modifier.fillMaxSize().clipToBounds().testTag("reader_horizontal_page:$index").onSizeChanged { frame = it }
            .pointerInput(bookId, sessionId, index, hasBitmap, active) {
                detectTapGestures(
                    onTap = { onTap() },
                    onDoubleTap = if (!hasBitmap || !active) null else { tap ->
                        if (scale > 1f) fit() else {
                            scale = 2.5f
                            pan = clampPan(Offset((size.width / 2f - tap.x) * 1.5f, (size.height / 2f - tap.y) * 1.5f))
                        }
                    },
                )
            }
            .transformable(transform, canPan = { scale > 1f }, enabled = hasBitmap && active),
            contentAlignment = Alignment.Center,
        ) {
            val bitmap = load.bitmap
            if (bitmap != null) {
                val image = remember(bitmap) { bitmap.asImageBitmap() }
                Image(image, null, Modifier.fillMaxSize().graphicsLayer(
                    alpha = imageAlpha, scaleX = scale, scaleY = scale, translationX = pan.x, translationY = pan.y,
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
}
