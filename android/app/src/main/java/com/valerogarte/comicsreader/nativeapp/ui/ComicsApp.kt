package com.valerogarte.comicsreader.nativeapp.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.valerogarte.comicsreader.BuildConfig
import com.valerogarte.comicsreader.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat

private val Amber = Color(0xFF946719)
private val Cream = Color(0xFFFAF7F0)
private val DownloadGreen = Color(0xFF23864A)
/** Large libraries are shown in pages; the next pages keep arriving in the background. */
private const val PAGE_SIZE = 50

/** Long press and drag selects every book in between; near the edges the list scrolls by itself. */
private class DragSelector {
    var anchor = -1
    var base = emptySet<String>()
    var startId: String? = null
    var moved = false
    var startWasSelected = false
    var lastPosition = Offset.Zero
    var speed by mutableFloatStateOf(0f)
}

private fun selectionId(key: Any?): String? = when (val text = key as? String) {
    null -> null
    else -> when {
        text.startsWith("folder:") -> null
        text.startsWith("remote:") -> text.removePrefix("remote:")
        else -> text
    }
}

@Composable
fun ComicsApp(state: AppUiState, actions: AppActions) {
    val libraryState = rememberSaveableStateHolder()
    val dark = when (state.settings.theme) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val colors = if (dark) darkColorScheme(primary = Color(0xFFE7BD75), secondary = Color(0xFFD2BD9A))
    else lightColorScheme(primary = Amber, secondary = Color(0xFF71604A), background = Cream, surface = Cream)
    MaterialTheme(colorScheme = colors) {
        Surface(modifier = Modifier.fillMaxSize()) {
            if (state.reader != null) ReaderScreen(state.reader, state.settings, actions)
            else libraryState.SaveableStateProvider("library") { LibraryScreen(state, actions) }
        }
        if (state.settingsVisible) SettingsScreen(state.settings, actions)
        if (state.error != null || state.message != null) AlertDialog(
            onDismissRequest = actions::onDismissMessage,
            text = { Text(state.error ?: state.message.orEmpty()) },
            confirmButton = { TextButton(onClick = actions::onDismissMessage) { Text(stringResource(R.string.native_message_close)) } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LibraryScreen(state: AppUiState, actions: AppActions) {
    val drawer = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var filter by rememberSaveable { mutableStateOf(ReadFilter.ALL) }
    var layout by rememberSaveable { mutableStateOf(LibraryLayout.GRID) }
    var coverSize by rememberSaveable { mutableFloatStateOf(100f) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pendingDelete by remember { mutableStateOf<List<String>>(emptyList()) }
    var statisticsVisible by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val bookListState = rememberLazyListState()
    var lastCollection by rememberSaveable { mutableStateOf<String?>(null) }
    val isRemote = state.destination == LibraryDestination.SFTP
    val server = state.settings.sftpServers.find { it.id == state.selectedSftpServerId }
    val shelfPath = state.shelfPaths.find { it.id == state.selectedShelfId }
    val isGuide = state.destination == LibraryDestination.GUIDE
    val isGuideList = state.destination == LibraryDestination.GUIDES
    // Each guide keeps its open sections and scroll while a book or a server folder is shown.
    val guideStates = rememberSaveableStateHolder()
    val localBooks = if (state.destination == LibraryDestination.SHELF && shelfPath != null) state.books.filter { book ->
        val path = book.remotePath
        book.sourceId == shelfPath.sourceId && path != null && (shelfPath.path == "/" || path.startsWith(shelfPath.path.trimEnd('/') + "/"))
    } else state.books
    val visibleBooks = localBooks.filter { matchesFilter(it.progress, filter) }
    val remoteBooks = if (server != null) state.remoteEntries.filter { !it.isDirectory && matchesFilter(it.progress, filter) } else emptyList()
    val folders = if (server != null) state.remoteEntries.filter { it.isDirectory } else emptyList()
    val title = when (state.destination) {
        LibraryDestination.BOOKS -> stringResource(R.string.native_collection)
        LibraryDestination.SFTP -> server?.name ?: stringResource(R.string.native_sftp_library)
        LibraryDestination.SHELF -> shelfPath?.name ?: stringResource(R.string.native_shelves)
        LibraryDestination.GUIDES -> stringResource(R.string.native_reading_guides)
        LibraryDestination.GUIDE -> state.guide?.title ?: state.guides.find { it.id == state.selectedGuideId }?.title ?: stringResource(R.string.native_reading_guides)
    }
    var pendingShelfDelete by remember { mutableStateOf<UiShelfPath?>(null) }
    val collectionKey = "${state.destination}:${state.selectedShelfId}:${state.selectedSftpServerId}:${state.remotePath}:$filter"
    val totalItems = if (isRemote) folders.size + remoteBooks.size else visibleBooks.size
    var shown by remember(collectionKey) { mutableIntStateOf(PAGE_SIZE) }
    LaunchedEffect(collectionKey, totalItems) {
        while (shown < totalItems) { delay(200); shown += PAGE_SIZE }
    }
    val pagedFolders = folders.take(shown)
    val pagedRemote = remoteBooks.take((shown - pagedFolders.size).coerceAtLeast(0))
    val pagedLocal = visibleBooks.take(shown)
    val selectableIds by rememberUpdatedState(if (isRemote) pagedRemote.map { it.path } else pagedLocal.map { it.id })
    val selector = remember { DragSelector() }
    val density = androidx.compose.ui.platform.LocalDensity.current
    val horizontalPadding = with(density) { 18.dp.toPx() }
    // Item offsets leave out the content padding; the finger in a gap picks the nearest book.
    val itemAt by rememberUpdatedState { position: Offset ->
        if (layout == LibraryLayout.GRID) {
            val info = gridState.layoutInfo
            val x = position.x - horizontalPadding
            val y = position.y - info.beforeContentPadding
            selectionId(info.visibleItemsInfo.minByOrNull {
                val dx = (x - (it.offset.x + it.size.width / 2f)) / it.size.width
                val dy = (y - (it.offset.y + it.size.height / 2f)) / it.size.height
                dx * dx + dy * dy
            }?.key)
        } else {
            val info = bookListState.layoutInfo
            val y = position.y - info.beforeContentPadding
            selectionId(info.visibleItemsInfo.minByOrNull { kotlin.math.abs(y - (it.offset + it.size / 2f)) }?.key)
        }
    }
    val dragUpdate by rememberUpdatedState { position: Offset ->
        selector.lastPosition = position
        val id = itemAt(position)
        val index = id?.let { selectableIds.indexOf(it) } ?: -1
        if (selector.anchor >= 0 && index >= 0) {
            if (id != selector.startId) selector.moved = true
            selected = selector.base + selectableIds.subList(minOf(selector.anchor, index), maxOf(selector.anchor, index) + 1)
        }
    }
    LaunchedEffect(selector.speed, layout) {
        while (isActive && selector.speed != 0f) {
            if (layout == LibraryLayout.GRID) gridState.scrollBy(selector.speed) else bookListState.scrollBy(selector.speed)
            dragUpdate(selector.lastPosition)
            delay(16)
        }
    }
    // After a long press the gesture belongs to the selection: events are taken in the initial
    // pass, before the grid can turn the movement into scrolling.
    val dragSelect = Modifier.pointerInput(layout, collectionKey) {
        awaitEachGesture {
            val down = awaitFirstDown(requireUnconsumed = false)
            val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
            val id = itemAt(press.position) ?: return@awaitEachGesture
            selector.anchor = selectableIds.indexOf(id).takeIf { it >= 0 } ?: return@awaitEachGesture
            selector.base = selected
            selector.startId = id
            selector.moved = false
            selector.startWasSelected = id in selected
            selector.lastPosition = press.position
            selected = selected + id
            try {
                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == press.id } ?: break
                    change.consume()
                    if (!change.pressed) break
                    val y = change.position.y
                    val edge = size.height * .14f
                    selector.speed = when {
                        y < edge -> -36f * ((edge - y) / edge).coerceIn(0.15f, 1f)
                        y > size.height - edge -> 36f * ((y - (size.height - edge)) / edge).coerceIn(0.15f, 1f)
                        else -> 0f
                    }
                    dragUpdate(change.position)
                }
                if (!selector.moved && selector.startWasSelected) selected = selector.base - setOfNotNull(selector.startId)
            } finally {
                selector.speed = 0f
                selector.anchor = -1
            }
        }
    }
    // A book shown from a guide: make sure its page is loaded and bring it into view.
    val highlightIndex = if (isRemote && state.highlightedPaths.isNotEmpty())
        remoteBooks.indexOfFirst { it.path in state.highlightedPaths }.takeIf { it >= 0 }?.plus(folders.size) else null
    LaunchedEffect(highlightIndex, layout) {
        val index = highlightIndex ?: return@LaunchedEffect
        if (shown <= index) shown = index + PAGE_SIZE
        delay(150)
        if (layout == LibraryLayout.GRID) gridState.animateScrollToItem(index) else bookListState.animateScrollToItem(index)
    }
    LaunchedEffect(state.destination, state.selectedShelfId, state.selectedSftpServerId, state.remotePath) {
        selected = emptySet()
        val collection = "${state.destination}:${state.selectedShelfId}:${state.selectedSftpServerId}:${state.remotePath}"
        if (lastCollection != null && lastCollection != collection) {
            gridState.scrollToItem(0)
            bookListState.scrollToItem(0)
        }
        lastCollection = collection
    }
    BackHandler(drawer.isOpen || selected.isNotEmpty() || state.destination != LibraryDestination.BOOKS) {
        when {
            drawer.isOpen -> scope.launch { drawer.close() }
            selected.isNotEmpty() -> selected = emptySet()
            else -> state.navigateLibraryBack(actions)
        }
    }
    ModalNavigationDrawer(
        drawerState = drawer,
        drawerContent = {
            ModalDrawerSheet {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                ) {
                    Image(painterResource(R.mipmap.ic_launcher_foreground), null, Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)))
                    Text(stringResource(R.string.native_app_name), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 14.dp))
                }
                LazyColumn(Modifier.weight(1f)) {
                item { NavigationDrawerItem(
                    label = { Text(stringResource(R.string.native_collection)) },
                    icon = { Icon(Icons.AutoMirrored.Filled.MenuBook, null) },
                    selected = state.destination == LibraryDestination.BOOKS,
                    onClick = { actions.onNavigate(LibraryDestination.BOOKS); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp),
                ) }
                if (state.settings.sftpServers.isNotEmpty()) item {
                    Text(stringResource(R.string.native_sftp_servers), style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 28.dp, top = 24.dp, bottom = 8.dp))
                }
                items(state.settings.sftpServers, key = { "server:${it.id}" }) { item -> NavigationDrawerItem(
                    label = { Column {
                        Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(item.host, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    } },
                    icon = { Icon(Icons.Default.Storage, null) },
                    selected = isRemote && state.selectedSftpServerId == item.id,
                    onClick = { actions.onOpenSftpServer(item.id); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("server_navigation:${item.id}"),
                ) }
                item {
                HorizontalDivider(Modifier.padding(20.dp))
                Text(stringResource(R.string.native_shelves), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp, end = 16.dp, bottom = 4.dp))
                if (state.shelfPaths.isEmpty()) Text(stringResource(R.string.native_shelves_empty_hint), style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp))
                }
                        items(state.shelfPaths, key = { "shelf:${it.id}" }) { item ->
                            NavigationDrawerItem(
                                label = { Column {
                                    Text(item.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    Text(item.path, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                } },
                                icon = { Icon(Icons.Default.CollectionsBookmark, null) },
                                badge = { IconButton(onClick = { pendingShelfDelete = item }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Close, stringResource(R.string.native_remove_shelf), Modifier.size(16.dp)) } },
                                selected = state.destination == LibraryDestination.SHELF && item.id == state.selectedShelfId,
                                onClick = { actions.onOpenShelfPath(item.id); scope.launch { drawer.close() } },
                                modifier = Modifier.padding(horizontal = 12.dp).testTag("shelf:${item.id}"),
                            )
                        }
                item {
                HorizontalDivider(Modifier.padding(20.dp))
                Text(stringResource(R.string.native_reading_guides), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 28.dp, end = 16.dp, bottom = 4.dp))
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.native_all_guides)) },
                    icon = { Icon(Icons.AutoMirrored.Filled.LibraryBooks, null) },
                    badge = { if (state.guides.isNotEmpty()) Text("${state.guides.size}", style = MaterialTheme.typography.labelMedium) },
                    selected = isGuideList,
                    onClick = { actions.onNavigate(LibraryDestination.GUIDES); scope.launch { drawer.close() } },
                    modifier = Modifier.padding(horizontal = 12.dp).testTag("all_guides"),
                )
                if (state.guides.isNotEmpty() && state.guides.none { it.favorite }) Text(stringResource(R.string.native_guides_favorites_hint),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp))
                }
                        // Only favourites here; the full list, with the stars, is "Todas las guías".
                        items(state.guides.filter { it.favorite }, key = { "guide:${it.id}" }) { item ->
                            FavoriteGuideItem(item, selected = isGuide && item.id == state.selectedGuideId,
                                onClick = { actions.onOpenGuide(item.id); scope.launch { drawer.close() } },
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 2.dp).testTag("guide:${item.id}"))
                        }
                }
                NavigationDrawerItem(
                    label = { Text(stringResource(R.string.native_reading_statistics)) },
                    icon = { Icon(Icons.Default.BarChart, null) }, selected = statisticsVisible,
                    onClick = { statisticsVisible = true; scope.launch { drawer.close() } },
                    modifier = Modifier.padding(12.dp).testTag("reading_statistics"),
                )
                Text(
                    text = stringResource(R.string.native_app_version, BuildConfig.VERSION_NAME),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        },
    ) {
        Box(Modifier.fillMaxSize()) {
        if (state.settings.backgroundImagePath != null) {
            CoverImage(state.settings.backgroundImagePath, "", Modifier.fillMaxSize())
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background.copy(alpha = .88f)))
        }
        Scaffold(
            containerColor = if (state.settings.backgroundImagePath != null) Color.Transparent else MaterialTheme.colorScheme.background,
            topBar = {
                TopAppBar(
                    title = {
                        Text(if (selected.isEmpty()) title else pluralStringResource(R.plurals.native_selected, selected.size, selected.size), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    navigationIcon = {
                        IconButton(onClick = {
                            if (selected.isNotEmpty()) selected = emptySet() else scope.launch { drawer.open() }
                        }) { Icon(if (selected.isEmpty()) Icons.Default.Menu else Icons.Default.Close, stringResource(if (selected.isEmpty()) R.string.native_menu else R.string.native_close)) }
                    },
                    actions = {
                        if (selected.isNotEmpty() && isRemote) {
                            val chosen = state.remoteEntries.filter { !it.isDirectory && it.path in selected }
                            val downloadable = chosen.filter { !it.downloaded && !it.downloading && !it.queued }
                            val local = chosen.mapNotNull { it.bookId }
                            if (downloadable.isNotEmpty()) IconButton(onClick = { actions.onDownloadRemotePaths(downloadable.map { it.path }); selected = emptySet() },
                                modifier = Modifier.testTag("download_selected")) {
                                Icon(Icons.Default.Download, stringResource(R.string.native_download_selected))
                            }
                            if (local.isNotEmpty()) IconButton(onClick = { pendingDelete = local }, modifier = Modifier.testTag("delete_selected")) {
                                Icon(Icons.Default.CleaningServices, stringResource(R.string.native_delete_local))
                            }
                        } else if (selected.isNotEmpty()) {
                            val chosen = state.books.filter { it.id in selected }
                            if (chosen.any { it.progress < 1f }) IconButton(onClick = { actions.onMarkBooksRead(selected.toList()); selected = emptySet() },
                                modifier = Modifier.testTag("mark_read_selected")) {
                                Icon(Icons.Default.DoneAll, stringResource(R.string.native_mark_read))
                            }
                            if (chosen.any { it.progress >= 1f }) IconButton(onClick = { actions.onMarkBooksUnread(chosen.filter { it.progress >= 1f }.map { it.id }); selected = emptySet() },
                                modifier = Modifier.testTag("mark_unread_selected")) {
                                Icon(Icons.Default.RemoveDone, stringResource(R.string.native_mark_unread))
                            }
                            IconButton(onClick = { pendingDelete = selected.toList() }, modifier = Modifier.testTag("delete_selected")) {
                                Icon(Icons.Default.CleaningServices, stringResource(R.string.native_delete_local))
                            }
                        } else if (isGuide) {
                            val guideId = state.selectedGuideId
                            val favorite = state.guides.find { it.id == guideId }?.favorite == true
                            if (guideId != null) IconButton(onClick = { actions.onToggleGuideFavorite(guideId) }) {
                                Icon(if (favorite) Icons.Default.Star else Icons.Default.StarBorder,
                                    stringResource(if (favorite) R.string.native_guide_unfavorite else R.string.native_guide_favorite))
                            }
                            IconButton(onClick = actions::onRefreshGuide, enabled = state.guide?.checking != true) {
                                Icon(Icons.Default.Sync, stringResource(R.string.native_sync))
                            }
                            if (guideId != null) IconButton(onClick = { actions.onExportGuide(guideId) }) {
                                Icon(Icons.Default.IosShare, stringResource(R.string.native_export_guide))
                            }
                        } else if (isGuideList) {
                            IconButton(onClick = actions::onSyncGuides, enabled = !state.guidesSyncing) {
                                Icon(Icons.Default.Sync, stringResource(R.string.native_sync))
                            }
                            IconButton(onClick = actions::onShowSettings) { Icon(Icons.Default.Settings, stringResource(R.string.native_settings)) }
                        } else {
                            if (isRemote) IconButton(onClick = actions::onSyncRemote, enabled = server != null && !state.loading) {
                                Icon(Icons.Default.Sync, stringResource(R.string.native_sync))
                            } else IconButton(onClick = actions::onImportBooks) {
                                Icon(Icons.Default.Add, stringResource(R.string.native_import))
                            }
                            IconButton(onClick = actions::onShowSettings) { Icon(Icons.Default.Settings, stringResource(R.string.native_settings)) }
                        }
                    },
                )
            },
            bottomBar = {
                if (!isGuide && !isGuideList) LibraryFooter(
                    count = if (isRemote) if (server != null) state.remoteEntries.count { !it.isDirectory } else 0 else localBooks.size,
                    filter = filter, onFilter = { filter = it }, layout = layout,
                    onLayout = { layout = it }, coverSize = coverSize,
                    onCoverSize = { coverSize = it },
                )
            },
        ) { inset ->
            Column(Modifier.fillMaxSize().padding(inset)) {
                if (isRemote) {
                    RemoteNavigation(state, actions)
                }
                if (state.destination == LibraryDestination.SHELF && shelfPath != null) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 6.dp)
                        .background(MaterialTheme.colorScheme.secondaryContainer, RoundedCornerShape(12.dp)).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.CloudOff, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        Text(stringResource(R.string.native_shelf_offline, shelfPath.path), style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSecondaryContainer, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
                        TextButton(onClick = { actions.onOpenShelfPath(shelfPath.id) }) { Text(stringResource(R.string.native_retry)) }
                    }
                }
                // Content keeps its position while work is in progress, so a tap during a download
                // still reaches the book under the finger.
                Box(Modifier.weight(1f).fillMaxWidth()) {
                val empty = if (isRemote) remoteBooks.isEmpty() && folders.isEmpty() else visibleBooks.isEmpty()
                if (isGuideList) {
                    ReadingGuideList(state.guides, state.guidesSyncing, actions)
                } else if (isGuide) {
                    val covers = state.guides.find { it.id == state.selectedGuideId }?.covers.orEmpty()
                    guideStates.SaveableStateProvider("guide:${state.selectedGuideId}") { ReadingGuideContent(state.guide, covers, actions) }
                } else if (isRemote && state.settings.sftpServers.isEmpty()) {
                    EmptyLibrary(Icons.Default.Storage, stringResource(R.string.native_configure_sftp), stringResource(R.string.native_configure_sftp_detail), actions::onShowSettings, R.string.native_configure_sftp)
                } else if (isRemote && server == null) {
                    EmptyLibrary(Icons.Default.Storage, stringResource(R.string.native_choose_server), stringResource(R.string.native_choose_server_detail),
                        { scope.launch { drawer.open() } }, R.string.native_menu)
                } else if (empty && !state.loading) {
                    val heading = when {
                        filter != ReadFilter.ALL -> stringResource(R.string.native_no_filter_results)
                        isRemote -> stringResource(R.string.native_empty_folder)
                        state.destination == LibraryDestination.SHELF -> stringResource(R.string.native_empty_shelf)
                        else -> stringResource(R.string.native_empty_books)
                    }
                    EmptyLibrary(Icons.AutoMirrored.Filled.MenuBook, heading,
                        if (!isRemote && state.destination == LibraryDestination.BOOKS && filter == ReadFilter.ALL) stringResource(R.string.native_empty_books_detail) else null,
                        if (!isRemote && filter == ReadFilter.ALL) actions::onImportBooks else null)
                } else if (layout == LibraryLayout.GRID) {
                    LazyVerticalGrid(
                        columns = GridCells.Adaptive(coverSize.dp),
                        state = gridState,
                        modifier = Modifier.fillMaxSize().testTag("book_grid").then(dragSelect), contentPadding = PaddingValues(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        if (isRemote) items(pagedFolders, key = { "folder:${it.path}" }) { folder -> FolderTile(folder, actions) }
                        if (isRemote) items(pagedRemote, key = { "remote:${it.path}" }) { entry ->
                            BookTile(entry.path, entry.title, entry.format, entry.size, entry.coverPath, entry.progress,
                                selected = entry.path in selected, downloaded = entry.downloaded, downloading = entry.downloading, queued = entry.queued,
                                highlighted = entry.path in state.highlightedPaths,
                                onClick = { if (selected.isNotEmpty()) selected = toggleSelection(selected, entry.path) else actions.onOpenRemoteBook(entry.path) },
                                onDownload = { actions.onDownloadRemote(entry.path) })
                        } else items(pagedLocal, key = { it.id }) { book ->
                            BookTile(book.id, book.title, book.format, book.size, book.coverPath, book.progress,
                                selected = selected.contains(book.id),
                                onClick = { if (selected.isNotEmpty()) selected = toggleSelection(selected, book.id) else actions.onOpenBook(book.id) },
                                onDelete = { pendingDelete = listOf(book.id) },
                                onMarkRead = { actions.onMarkBooksRead(listOf(book.id)) }.takeIf { book.progress < 1f },
                                onMarkUnread = { actions.onMarkBooksUnread(listOf(book.id)) }.takeIf { book.progress >= 1f })
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().testTag("book_list").then(dragSelect), state = bookListState, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isRemote) items(pagedFolders, key = { "folder:${it.path}" }) { FolderRow(it, actions) }
                        if (isRemote) items(pagedRemote, key = { "remote:${it.path}" }) { entry ->
                            BookListRow(entry.path, entry.title, entry.format, entry.size, entry.coverPath, entry.progress,
                                selected = entry.path in selected, downloaded = entry.downloaded, downloading = entry.downloading, queued = entry.queued,
                                highlighted = entry.path in state.highlightedPaths,
                                onClick = { if (selected.isNotEmpty()) selected = toggleSelection(selected, entry.path) else actions.onOpenRemoteBook(entry.path) },
                                onDownload = { actions.onDownloadRemote(entry.path) })
                        } else items(pagedLocal, key = { it.id }) { book ->
                            BookListRow(book.id, book.title, book.format, book.size, book.coverPath, book.progress,
                                selected = selected.contains(book.id),
                                onClick = { if (selected.isNotEmpty()) selected = toggleSelection(selected, book.id) else actions.onOpenBook(book.id) },
                                onDelete = { pendingDelete = listOf(book.id) },
                                onMarkRead = { actions.onMarkBooksRead(listOf(book.id)) }.takeIf { book.progress < 1f },
                                onMarkUnread = { actions.onMarkBooksUnread(listOf(book.id)) }.takeIf { book.progress >= 1f })
                        }
                    }
                }
                if (state.loading || state.busyMessage != null) {
                    // Not a Surface: Material surfaces consume touches, and the books below stay tappable.
                    Box(Modifier.align(Alignment.BottomCenter).padding(12.dp).widthIn(max = 560.dp).fillMaxWidth()
                        .shadow(6.dp, RoundedCornerShape(14.dp)).background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(14.dp))) {
                        Row(Modifier.padding(start = 16.dp, end = 6.dp, top = 6.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f).padding(vertical = 4.dp)) {
                                Text(state.busyMessage ?: stringResource(R.string.native_loading), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                                Spacer(Modifier.height(8.dp))
                                if (state.operationProgress != null) LinearProgressIndicator(progress = { state.operationProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                            }
                            if (state.cancelableDownload) TextButton(onClick = actions::onCancelDownloads, modifier = Modifier.padding(start = 8.dp).testTag("cancel_downloads")) {
                                Text(stringResource(R.string.native_cancel))
                            }
                        }
                    }
                }
                }
            }
        }
        }
    }
    if (statisticsVisible) ReadingStatisticsScreen(state.readingStatistics, onClose = { statisticsVisible = false },
        onOpenEntry = { statisticsVisible = false; actions.onOpenTimelineEntry(it) })
    pendingShelfDelete?.let { item ->
        AlertDialog(
            onDismissRequest = { pendingShelfDelete = null },
            title = { Text(stringResource(R.string.native_remove_shelf)) },
            text = { Text(stringResource(R.string.native_remove_shelf_message, item.name)) },
            confirmButton = { TextButton(onClick = { actions.onDeleteShelfPath(item.id); pendingShelfDelete = null }) { Text(stringResource(R.string.native_remove)) } },
            dismissButton = { TextButton(onClick = { pendingShelfDelete = null }) { Text(stringResource(R.string.native_cancel)) } },
        )
    }
    if (pendingDelete.isNotEmpty()) AlertDialog(
        onDismissRequest = { pendingDelete = emptyList() },
        icon = { Icon(Icons.Default.CleaningServices, null) },
        title = { Text(stringResource(R.string.native_delete_local)) },
        text = { Column {
            Text(stringResource(R.string.native_delete_books_message))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.native_delete_local_only), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { actions.onDeleteBooks(pendingDelete); selected = emptySet(); pendingDelete = emptyList() }) { Text(stringResource(R.string.native_clean), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { pendingDelete = emptyList() }) { Text(stringResource(R.string.native_cancel)) } },
    )
}

@Composable
private fun RemoteNavigation(state: AppUiState, actions: AppActions) {
    val server = state.settings.sftpServers.find { it.id == state.selectedSftpServerId } ?: return
    Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
        Text("${server.name} · ${server.host}:${server.port}", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(state.remotePath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 8.dp))
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(
                onClick = { actions.onOpenRemoteFolder(remoteParentPath(state.remotePath)) },
                enabled = state.remotePath != "/",
            ) { Icon(Icons.Default.DriveFolderUpload, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.native_parent_folder)) }
            FilledTonalButton(onClick = actions::onDownloadAll, enabled = !state.loading && state.remoteEntries.any { !it.isDirectory && !it.downloaded && !it.downloading && !it.queued }) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.native_download_all))
            }
            val onShelf = state.shelfPaths.any { it.serverId == server.id && it.path == state.remotePath }
            OutlinedButton(onClick = actions::onAddCurrentFolderToShelves, modifier = Modifier.testTag("toggle_shelf")) {
                Icon(if (onShelf) Icons.Default.BookmarkAdded else Icons.Default.BookmarkAdd, null, Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(stringResource(if (onShelf) R.string.native_on_shelf else R.string.native_add_folder_to_shelf))
            }
        }
    }
}

@Composable
private fun FolderRow(folder: UiRemoteEntry, actions: AppActions) {
    Surface(Modifier.fillMaxWidth().clickable { actions.onOpenRemoteFolder(folder.path) }, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            FolderCovers(folder.folderCovers, folder.name, Modifier.size(width = 72.dp, height = 96.dp))
            Column(Modifier.padding(start = 14.dp).weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(folder.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                val count = folder.fileCount
                Text(
                    if (count != null) pluralStringResource(R.plurals.native_folder_files, count, count)
                    else stringResource(R.string.native_file_count_unavailable),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * A folder drawing: a back panel with its tab, the covers of the first books standing inside
 * like paper in a file, and a front pocket that hides their lower half. It scales to its box.
 */
@Composable
private fun FolderCovers(covers: List<String>, name: String, modifier: Modifier = Modifier, fileCount: Int? = null) {
    val primary = MaterialTheme.colorScheme.primary
    val backTop = lerp(primary, Color.Black, .28f)
    val backBottom = lerp(primary, Color.Black, .12f)
    val frontTop = lerp(primary, Color.White, .18f)
    val frontBottom = primary
    BoxWithConstraints(modifier.semantics(mergeDescendants = true) {}) {
        val width = maxWidth
        val height = maxHeight
        val corner = width * .08f
        val tabHeight = height * .07f
        val bodyTop = tabHeight
        val pocketHeight = height * .40f
        // Back panel with its tab, slightly darker so the pocket stands in front of it.
        Box(Modifier.offset(x = width * .06f).size(width = width * .42f, height = tabHeight + corner)
            .background(backTop, RoundedCornerShape(topStart = corner * .7f, topEnd = corner * .7f)))
        Box(Modifier.padding(top = bodyTop).fillMaxSize().shadow(2.dp, RoundedCornerShape(corner))
            .background(Brush.verticalGradient(listOf(backTop, backBottom)), RoundedCornerShape(corner)))
        if (covers.isEmpty()) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, null, Modifier.align(Alignment.Center).offset(y = -pocketHeight * .35f).size(width * .3f),
                tint = Color.White.copy(alpha = .35f))
        }
        // Covers stand inside the folder, a little fanned, with a paper border like real comics.
        val coverWidth = width * (if (covers.size <= 1) .66f else .58f)
        val coverHeight = coverWidth * 1.5f
        val center = (covers.size - 1) / 2f
        covers.withIndex().sortedByDescending { kotlin.math.abs(it.index - center) }.forEach { (index, path) ->
            val shift = index - center
            Box(Modifier.align(Alignment.BottomCenter)
                .offset(x = width * .15f * shift, y = -pocketHeight * .3f - (if (shift == 0f) height * .03f else 0.dp))
                .graphicsLayer { rotationZ = shift * 5f; transformOrigin = TransformOrigin(.5f, 1f) }
                .size(width = coverWidth, height = coverHeight)
                .shadow(5.dp, RoundedCornerShape(3.dp))
                .background(Color.White, RoundedCornerShape(3.dp))
                .padding(2.dp)) {
                CoverImage(path, name, Modifier.fillMaxSize().clip(RoundedCornerShape(2.dp)))
            }
        }
        // Front pocket: a lighter panel with a highlight on its upper edge.
        val pocket = RoundedCornerShape(topStart = corner * .6f, topEnd = corner * .6f, bottomStart = corner, bottomEnd = corner)
        Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(pocketHeight)
            .shadow(8.dp, pocket).background(Brush.verticalGradient(listOf(frontTop, frontBottom)), pocket)) {
            Box(Modifier.fillMaxWidth().padding(horizontal = corner).height(1.5.dp).background(Color.White.copy(alpha = .45f)))
            Icon(Icons.Default.Folder, stringResource(R.string.native_open_folder),
                Modifier.align(Alignment.BottomStart).padding(start = width * .08f, bottom = pocketHeight * .16f).size(pocketHeight * .28f),
                tint = Color.White.copy(alpha = .8f))
            if (fileCount != null && fileCount > 0) Box(Modifier.align(Alignment.BottomEnd).padding(end = width * .07f, bottom = pocketHeight * .16f)
                .background(Color.Black.copy(alpha = .22f), RoundedCornerShape(50)).padding(horizontal = 8.dp, vertical = 2.dp)) {
                Text("$fileCount", style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
            }
        }
    }
}

/** A folder in the grid, sized like a book, with the same name and details layout. */
@Composable
private fun FolderTile(folder: UiRemoteEntry, actions: AppActions) {
    Column(Modifier.fillMaxWidth().testTag("folder:${folder.path}").clickable { actions.onOpenRemoteFolder(folder.path) }) {
        FolderCovers(folder.folderCovers, folder.name, Modifier.fillMaxWidth().aspectRatio(2f / 3f), folder.fileCount)
        Text(folder.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 8.dp))
        val count = folder.fileCount
        Text(if (count != null) pluralStringResource(R.plurals.native_folder_files, count, count) else stringResource(R.string.native_file_count_unavailable),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    id: String, title: String, format: String, size: Long, coverPath: String?, progress: Float,
    selected: Boolean = false, downloaded: Boolean? = null, downloading: Boolean = false, queued: Boolean = false,
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null, onDelete: (() -> Unit)? = null, onMarkRead: (() -> Unit)? = null,
    onMarkUnread: (() -> Unit)? = null, highlighted: Boolean = false,
) {
    Column(Modifier.fillMaxWidth().testTag("book:$id").semantics { this.selected = selected }
        .then(if (highlighted) Modifier.highlightGlow(RoundedCornerShape(12.dp)) else Modifier)
        .combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
            CoverImage(coverPath, title, Modifier.fillMaxSize())
            if (selected) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = .35f))
                .border(3.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(8.dp)))
            if (selected) Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(6.dp).size(28.dp)
                .background(MaterialTheme.colorScheme.surface, androidx.compose.foundation.shape.CircleShape), tint = MaterialTheme.colorScheme.primary)
            if (progress > 0f) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter), color = DownloadGreen)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FileDetails(format, size, MaterialTheme.typography.labelSmall, Modifier.padding(top = 4.dp))
            }
            BookAction(downloaded, downloading, onDownload, onDelete, onMarkRead, queued, onMarkUnread)
        }
    }
}

/** A pulsing amber frame for books shown from a reading guide. */
@Composable
private fun Modifier.highlightGlow(shape: androidx.compose.ui.graphics.Shape): Modifier {
    val pulse by androidx.compose.animation.core.rememberInfiniteTransition(label = "highlight").animateFloat(
        initialValue = .35f, targetValue = 1f, label = "highlight-alpha",
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween<Float>(900),
            androidx.compose.animation.core.RepeatMode.Reverse))
    val color = Color(0xFFFFB300)
    return this.background(color.copy(alpha = .16f * pulse), shape).border(3.dp, color.copy(alpha = pulse), shape).padding(6.dp)
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookListRow(
    id: String, title: String, format: String, size: Long, coverPath: String?, progress: Float,
    selected: Boolean = false, downloaded: Boolean? = null, downloading: Boolean = false, queued: Boolean = false,
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null, onDelete: (() -> Unit)? = null, onMarkRead: (() -> Unit)? = null,
    onMarkUnread: (() -> Unit)? = null, highlighted: Boolean = false,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth().testTag("book:$id").semantics { this.selected = selected }
            .then(if (highlighted) Modifier.highlightGlow(RoundedCornerShape(12.dp)) else Modifier)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(coverPath, title, Modifier.size(width = 54.dp, height = 81.dp).clip(RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                FileDetails(format, size, MaterialTheme.typography.bodySmall, Modifier.padding(top = 6.dp))
                if (progress > 0f) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp), color = DownloadGreen)
            }
            if (selected) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            else BookAction(downloaded, downloading, onDownload, onDelete, onMarkRead, queued, onMarkUnread)
        }
    }
}

@Composable
private fun BookAction(
    downloaded: Boolean?, downloading: Boolean, onDownload: (() -> Unit)?, onDelete: (() -> Unit)?,
    onMarkRead: (() -> Unit)? = null, queued: Boolean = false, onMarkUnread: (() -> Unit)? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    when {
        downloading -> {
            val label = stringResource(R.string.native_downloading)
            Box(Modifier.size(48.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        }
        queued -> Icon(Icons.Default.Schedule, stringResource(R.string.native_queued), tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(12.dp).size(24.dp))
        downloaded == true -> Icon(Icons.Default.CheckCircle, stringResource(R.string.native_downloaded), tint = DownloadGreen, modifier = Modifier.padding(8.dp).size(24.dp))
        onDownload != null -> IconButton(onClick = onDownload, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Download, stringResource(R.string.native_download)) }
        onDelete != null -> Box {
            IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert, stringResource(R.string.native_more), Modifier.size(20.dp)) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                if (onMarkRead != null) DropdownMenuItem(text = { Text(stringResource(R.string.native_mark_read)) },
                    leadingIcon = { Icon(Icons.Default.DoneAll, null) }, onClick = { expanded = false; onMarkRead() })
                if (onMarkUnread != null) DropdownMenuItem(text = { Text(stringResource(R.string.native_mark_unread)) },
                    leadingIcon = { Icon(Icons.Default.RemoveDone, null) }, onClick = { expanded = false; onMarkUnread() })
                DropdownMenuItem(text = { Text(stringResource(R.string.native_delete_local)) }, leadingIcon = { Icon(Icons.Default.CleaningServices, null) }, onClick = { expanded = false; onDelete() })
            }
        }
    }
}

@Composable
internal fun CoverImage(path: String?, title: String, modifier: Modifier = Modifier) {
    // produceState retains its last result when its key changes. Associate each
    // decoded image with its source so a new book never displays the old cover.
    val loaded by produceState<Pair<String?, androidx.compose.ui.graphics.ImageBitmap?>>(path to null, path) {
        value = path to if (path.isNullOrBlank()) null else withContext(Dispatchers.IO) {
            runCatching {
                val localPath = path.removePrefix("file://")
                if (!File(localPath).isFile) return@runCatching null
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeFile(localPath, bounds)
                val options = BitmapFactory.Options().apply {
                    inSampleSize = 1
                    while (bounds.outWidth / inSampleSize > 800 || bounds.outHeight / inSampleSize > 1200) inSampleSize *= 2
                }
                BitmapFactory.decodeFile(localPath, options)?.asImageBitmap()
            }.getOrNull()
        }
    }
    val image = loaded.second.takeIf { loaded.first == path }
    if (image != null) Image(image!!, null, modifier.clearAndSetSemantics {}, contentScale = ContentScale.Crop)
    else Box(modifier.clearAndSetSemantics {}.background(Brush.linearGradient(listOf(MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.surfaceContainerHighest))), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(12.dp)) {
            Icon(Icons.AutoMirrored.Filled.MenuBook, null, modifier = Modifier.size(36.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .55f))
            Spacer(Modifier.height(12.dp))
            Text(title, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSecondaryContainer, maxLines = 4, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
private fun LibraryFooter(count: Int, filter: ReadFilter, onFilter: (ReadFilter) -> Unit, layout: LibraryLayout, onLayout: (LibraryLayout) -> Unit, coverSize: Float, onCoverSize: (Float) -> Unit) {
    Surface(tonalElevation = 2.dp) {
        Row(Modifier.navigationBarsPadding().fillMaxWidth().padding(start = 8.dp, end = 10.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically) {
            FilterSelector(filter, onFilter)
            Text(pluralStringResource(R.plurals.native_total_books, count, count), style = MaterialTheme.typography.labelLarge,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(horizontal = 6.dp))
            val coverSizeLabel = stringResource(R.string.native_card_size)
            if (layout == LibraryLayout.GRID) Slider(value = coverSize, onValueChange = onCoverSize, valueRange = 100f..210f,
                modifier = Modifier.width(88.dp).height(48.dp).testTag("cover_size").semantics { contentDescription = coverSizeLabel })
            IconButton(onClick = { onLayout(LibraryLayout.GRID) }, modifier = Modifier.size(44.dp).semantics { selected = layout == LibraryLayout.GRID }) {
                Icon(Icons.Default.GridView, stringResource(R.string.native_grid), tint = if (layout == LibraryLayout.GRID) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = { onLayout(LibraryLayout.LIST) }, modifier = Modifier.size(44.dp).semantics { selected = layout == LibraryLayout.LIST }) {
                Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.native_list), tint = if (layout == LibraryLayout.LIST) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FilterSelector(filter: ReadFilter, onFilter: (ReadFilter) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    val description = stringResource(R.string.native_filter_books, stringResource(filter.label))
    Box {
        TextButton(onClick = { expanded = true }, contentPadding = PaddingValues(start = 8.dp, end = 4.dp),
            modifier = Modifier.testTag("filter_selector").semantics { contentDescription = description }) {
            Icon(Icons.Default.FilterList, null, Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(filter.label), maxLines = 1)
            Icon(Icons.Default.ArrowDropDown, null)
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            ReadFilter.entries.forEach { item ->
                DropdownMenuItem(text = { Text(stringResource(item.label)) }, onClick = { onFilter(item); expanded = false },
                    leadingIcon = { if (item == filter) Icon(Icons.Default.Check, null) else Spacer(Modifier.size(24.dp)) },
                    modifier = Modifier.testTag("filter:${item.name}").semantics { selected = item == filter })
            }
        }
    }
}

private val ReadFilter.label get() = when (this) {
    ReadFilter.ALL -> R.string.native_all
    ReadFilter.UNREAD -> R.string.native_unread
    ReadFilter.READING -> R.string.native_reading
    ReadFilter.FINISHED -> R.string.native_finished
}

@Composable
private fun EmptyLibrary(icon: androidx.compose.ui.graphics.vector.ImageVector, heading: String, detail: String? = null, action: (() -> Unit)? = null, actionLabel: Int = R.string.native_import) {
    Column(Modifier.fillMaxSize().padding(36.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, modifier = Modifier.size(64.dp), tint = MaterialTheme.colorScheme.primary.copy(alpha = .6f))
        Text(heading, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 24.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        if (action != null) FilledTonalButton(onClick = action, modifier = Modifier.padding(top = 24.dp)) {
            Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(actionLabel))
        }
    }
}

private fun matchesFilter(progress: Float, filter: ReadFilter) = when (filter) {
    ReadFilter.ALL -> true
    ReadFilter.UNREAD -> progress <= 0f
    ReadFilter.READING -> progress > 0f && progress < 1f
    ReadFilter.FINISHED -> progress >= 1f
}

private fun toggleSelection(selected: Set<String>, id: String) = if (id in selected) selected - id else selected + id

/** The size always shows its unit: in narrow tiles the format gives way first. */
@Composable
private fun FileDetails(format: String, bytes: Long, style: androidx.compose.ui.text.TextStyle, modifier: Modifier = Modifier) {
    val extension = format.trimStart('.').uppercase()
    val size = fileSize(bytes)
    val color = MaterialTheme.colorScheme.onSurfaceVariant
    // The format is shown only when it fits whole next to the size.
    androidx.compose.ui.layout.Layout({
        if (extension.isNotBlank()) Text(if (size == null) extension else "$extension · ", style = style, color = color, maxLines = 1, softWrap = false)
        if (size != null) Text(size, style = style, color = color, maxLines = 1, softWrap = false)
    }, modifier.fillMaxWidth()) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, maxWidth = androidx.compose.ui.unit.Constraints.Infinity)
        val placeables = measurables.map { it.measure(loose) }
        val sizeText = if (size != null) placeables.last() else null
        val formatText = if (extension.isNotBlank()) placeables.first() else null
        val showFormat = formatText != null && formatText.width + (sizeText?.width ?: 0) <= constraints.maxWidth
        val height = placeables.maxOf { it.height }
        layout(constraints.maxWidth, height) {
            var x = 0
            if (showFormat) { formatText!!.placeRelative(0, 0); x = formatText.width }
            sizeText?.placeRelative(x, 0)
        }
    }
}

private fun fileSize(bytes: Long): String? {
    if (bytes <= 0) return null
    val units = arrayOf("B", "KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    // A non-breaking space keeps the number and its unit together.
    return "${DecimalFormat(if (value < 10 && unit > 0) "0.#" else "0").format(value)}\u00A0${units[unit]}"
}

internal fun readerBackground(settings: UiSettings, dark: Boolean): Color = if (dark) Color(0xFF181715) else when (settings.background) {
    "white" -> Color.White
    "sepia" -> Color(0xFFEEE0C1)
    else -> Cream
}
