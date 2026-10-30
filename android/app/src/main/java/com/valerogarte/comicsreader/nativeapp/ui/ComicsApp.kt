package com.valerogarte.comicsreader.nativeapp.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.Font
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
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DecimalFormat

private val Amber = Color(0xFF946719)
private val Cream = Color(0xFFFAF7F0)
private val DownloadGreen = Color(0xFF23864A)

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
    var coverSize by rememberSaveable { mutableFloatStateOf(136f) }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var pendingDelete by remember { mutableStateOf<List<String>>(emptyList()) }
    var creatingShelf by remember { mutableStateOf(false) }
    var pendingShelfBooks by remember { mutableStateOf<List<String>>(emptyList()) }
    var statisticsVisible by rememberSaveable { mutableStateOf(false) }
    val gridState = rememberLazyGridState()
    val bookListState = rememberLazyListState()
    var lastCollection by rememberSaveable { mutableStateOf<String?>(null) }
    val isRemote = state.destination == LibraryDestination.SFTP
    val server = state.settings.sftpServers.find { it.id == state.selectedSftpServerId }
    val shelf = state.shelves.find { it.id == state.selectedShelfId }
    val localBooks = if (state.destination == LibraryDestination.SHELF)
        state.books.filter { it.shelfIds.contains(state.selectedShelfId) } else state.books
    val visibleBooks = localBooks.filter { matchesFilter(it.progress, filter) }
    val remoteBooks = if (server != null) state.remoteEntries.filter { !it.isDirectory && matchesFilter(it.progress, filter) } else emptyList()
    val folders = if (server != null) state.remoteEntries.filter { it.isDirectory } else emptyList()
    val title = when (state.destination) {
        LibraryDestination.BOOKS -> stringResource(R.string.native_books)
        LibraryDestination.SFTP -> server?.name ?: stringResource(R.string.native_sftp_library)
        LibraryDestination.SHELF -> shelf?.name ?: stringResource(R.string.native_shelves)
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
                    label = { Text(stringResource(R.string.native_books)) },
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
                Row(Modifier.fillMaxWidth().padding(start = 28.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.native_shelves), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
                    IconButton(onClick = { creatingShelf = true; scope.launch { drawer.close() } }) {
                        Icon(Icons.Default.Add, stringResource(R.string.native_create_shelf))
                    }
                }
                }
                        items(state.shelves, key = { "shelf:${it.id}" }) { item ->
                            NavigationDrawerItem(
                                label = { Text(item.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                icon = { Icon(Icons.Default.CollectionsBookmark, null) },
                                selected = state.destination == LibraryDestination.SHELF && item.id == state.selectedShelfId,
                                onClick = { actions.onNavigate(LibraryDestination.SHELF, item.id); scope.launch { drawer.close() } },
                                modifier = Modifier.padding(horizontal = 12.dp),
                            )
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
                        if (selected.isNotEmpty()) {
                            IconButton(onClick = { pendingShelfBooks = selected.toList() }) {
                                Icon(Icons.Default.LibraryAdd, stringResource(R.string.native_add_to_shelf))
                            }
                            IconButton(onClick = { pendingDelete = selected.toList() }) {
                                Icon(Icons.Default.DeleteOutline, stringResource(R.string.native_delete_forever))
                            }
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
                LibraryFooter(
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
                if (state.loading || state.busyMessage != null) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)) {
                        Text(state.busyMessage ?: stringResource(R.string.native_loading), style = MaterialTheme.typography.bodySmall, maxLines = 2)
                        Spacer(Modifier.height(8.dp))
                        if (state.operationProgress != null) LinearProgressIndicator(progress = { state.operationProgress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth())
                        else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
                val empty = if (isRemote) remoteBooks.isEmpty() && folders.isEmpty() else visibleBooks.isEmpty()
                if (isRemote && state.settings.sftpServers.isEmpty()) {
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
                        modifier = Modifier.fillMaxSize().testTag("book_grid"), contentPadding = PaddingValues(18.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(22.dp),
                    ) {
                        if (isRemote) items(folders, key = { "folder:${it.path}" }, span = { GridItemSpan(maxLineSpan) }) { folder ->
                            FolderRow(folder, actions)
                        }
                        if (isRemote) items(remoteBooks, key = { "remote:${it.path}" }) { entry ->
                            BookTile(entry.path, entry.title, entry.format, entry.size, entry.coverPath, entry.progress,
                                downloaded = entry.downloaded, downloading = entry.downloading,
                                onClick = { actions.onOpenRemoteBook(entry.path) }, onDownload = { actions.onDownloadRemote(entry.path) })
                        } else items(visibleBooks, key = { it.id }) { book ->
                            BookTile(book.id, book.title, book.format, book.size, book.coverPath, book.progress,
                                selected = selected.contains(book.id),
                                onClick = { if (selected.isNotEmpty()) selected = toggleSelection(selected, book.id) else actions.onOpenBook(book.id) },
                                onLongClick = { selected = toggleSelection(selected, book.id) },
                                onDelete = { pendingDelete = listOf(book.id) })
                        }
                    }
                } else {
                    LazyColumn(Modifier.fillMaxSize().testTag("book_list"), state = bookListState, contentPadding = PaddingValues(horizontal = 18.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (isRemote) items(folders, key = { "folder:${it.path}" }) { FolderRow(it, actions) }
                        if (isRemote) items(remoteBooks, key = { "remote:${it.path}" }) { entry ->
                            BookListRow(entry.path, entry.title, entry.format, entry.size, entry.coverPath, entry.progress,
                                downloaded = entry.downloaded, downloading = entry.downloading,
                                onClick = { actions.onOpenRemoteBook(entry.path) }, onDownload = { actions.onDownloadRemote(entry.path) })
                        } else items(visibleBooks, key = { it.id }) { book ->
                            BookListRow(book.id, book.title, book.format, book.size, book.coverPath, book.progress,
                                selected = selected.contains(book.id),
                                onClick = { if (selected.isNotEmpty()) selected = toggleSelection(selected, book.id) else actions.onOpenBook(book.id) },
                                onLongClick = { selected = toggleSelection(selected, book.id) },
                                onDelete = { pendingDelete = listOf(book.id) })
                        }
                    }
                }
            }
        }
        }
    }
    if (statisticsVisible) ReadingStatisticsScreen(state.readingStatistics, onClose = { statisticsVisible = false })
    if (creatingShelf || pendingShelfBooks.isNotEmpty()) {
        var name by remember { mutableStateOf("") }
        val addingBooks = pendingShelfBooks.isNotEmpty()
        AlertDialog(
            onDismissRequest = { creatingShelf = false; pendingShelfBooks = emptyList() },
            title = { Text(stringResource(if (addingBooks) R.string.native_add_to_shelf else R.string.native_create_shelf)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    if (addingBooks && state.shelves.isNotEmpty()) {
                        Text(stringResource(R.string.native_existing_shelf), style = MaterialTheme.typography.bodySmall)
                        LazyColumn(Modifier.heightIn(max = 180.dp)) {
                            items(state.shelves, key = { it.id }) { item ->
                                Row(Modifier.fillMaxWidth().clickable { name = item.name }.padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Default.CollectionsBookmark, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                                    Text(item.name, Modifier.weight(1f).padding(horizontal = 12.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    if (name == item.name) Icon(Icons.Default.Check, null, tint = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                    OutlinedTextField(name, onValueChange = { name = it }, label = { Text(stringResource(R.string.native_shelf_name)) }, singleLine = true)
                }
            },
            confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = {
                if (addingBooks) { actions.onAddBooksToShelf(pendingShelfBooks, name.trim()); selected = emptySet() }
                else actions.onCreateShelf(name.trim())
                creatingShelf = false
                pendingShelfBooks = emptyList()
            }) { Text(stringResource(if (addingBooks) R.string.native_add else R.string.native_create)) } },
            dismissButton = { TextButton(onClick = { creatingShelf = false; pendingShelfBooks = emptyList() }) { Text(stringResource(R.string.native_cancel)) } },
        )
    }
    if (pendingDelete.isNotEmpty()) AlertDialog(
        onDismissRequest = { pendingDelete = emptyList() },
        icon = { Icon(Icons.Default.DeleteOutline, null) },
        title = { Text(stringResource(R.string.native_delete_forever)) },
        text = { Column {
            Text(stringResource(R.string.native_delete_books_message))
            Spacer(Modifier.height(12.dp))
            Text(stringResource(R.string.native_delete_local_only), style = MaterialTheme.typography.bodySmall)
        } },
        confirmButton = { TextButton(onClick = { actions.onDeleteBooks(pendingDelete); selected = emptySet(); pendingDelete = emptyList() }) { Text(stringResource(R.string.native_delete), color = MaterialTheme.colorScheme.error) } },
        dismissButton = { TextButton(onClick = { pendingDelete = emptyList() }) { Text(stringResource(R.string.native_cancel)) } },
    )
    state.emptyShelf?.let { item ->
        AlertDialog(
            onDismissRequest = actions::onKeepEmptyShelf,
            title = { Text(stringResource(R.string.native_empty_shelf_question)) },
            text = { Text(item.name) },
            confirmButton = { TextButton(onClick = { actions.onDeleteShelf(item.id) }) { Text(stringResource(R.string.native_delete)) } },
            dismissButton = { TextButton(onClick = actions::onKeepEmptyShelf) { Text(stringResource(R.string.native_keep_shelf)) } },
        )
    }
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
            FilledTonalButton(onClick = actions::onDownloadAll, enabled = !state.loading && state.remoteEntries.none { it.downloading } && state.remoteEntries.any { !it.isDirectory && !it.downloaded }) {
                Icon(Icons.Default.Download, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.native_download_all))
            }
        }
    }
}

@Composable
private fun FolderRow(folder: UiRemoteEntry, actions: AppActions) {
    Surface(Modifier.fillMaxWidth().clickable { actions.onOpenRemoteFolder(folder.path) }, color = MaterialTheme.colorScheme.surfaceContainerLow, shape = RoundedCornerShape(14.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Folder, stringResource(R.string.native_open_folder), tint = MaterialTheme.colorScheme.primary)
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

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    id: String, title: String, format: String, size: Long, coverPath: String?, progress: Float,
    selected: Boolean = false, downloaded: Boolean? = null, downloading: Boolean = false,
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
) {
    Column(Modifier.fillMaxWidth().testTag("book:$id").semantics { this.selected = selected }.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Box(Modifier.fillMaxWidth().aspectRatio(2f / 3f).clip(RoundedCornerShape(8.dp))) {
            CoverImage(coverPath, title, Modifier.fillMaxSize())
            if (selected) Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.primary.copy(alpha = .22f)))
            if (selected) Icon(Icons.Default.CheckCircle, null, Modifier.align(Alignment.TopEnd).padding(8.dp), tint = MaterialTheme.colorScheme.primary)
            if (progress > 0f) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().height(4.dp).align(Alignment.BottomCenter), color = DownloadGreen)
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(fileDetails(format, size), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(top = 4.dp))
            }
            BookAction(downloaded, downloading, onDownload, onDelete)
        }
    }
}

@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
private fun BookListRow(
    id: String, title: String, format: String, size: Long, coverPath: String?, progress: Float,
    selected: Boolean = false, downloaded: Boolean? = null, downloading: Boolean = false,
    onClick: () -> Unit, onLongClick: (() -> Unit)? = null,
    onDownload: (() -> Unit)? = null, onDelete: (() -> Unit)? = null,
) {
    Surface(shape = RoundedCornerShape(12.dp), color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        modifier = Modifier.fillMaxWidth().testTag("book:$id").semantics { this.selected = selected }.combinedClickable(onClick = onClick, onLongClick = onLongClick)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            CoverImage(coverPath, title, Modifier.size(width = 54.dp, height = 81.dp).clip(RoundedCornerShape(4.dp)))
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(fileDetails(format, size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
                if (progress > 0f) LinearProgressIndicator(progress = { progress.coerceIn(0f, 1f) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(3.dp), color = DownloadGreen)
            }
            if (selected) Icon(Icons.Default.CheckCircle, null, tint = MaterialTheme.colorScheme.primary)
            else BookAction(downloaded, downloading, onDownload, onDelete)
        }
    }
}

@Composable
private fun BookAction(downloaded: Boolean?, downloading: Boolean, onDownload: (() -> Unit)?, onDelete: (() -> Unit)?) {
    var expanded by remember { mutableStateOf(false) }
    when {
        downloading -> {
            val label = stringResource(R.string.native_downloading)
            Box(Modifier.size(48.dp).semantics { contentDescription = label }, contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
        }
        downloaded == true -> Icon(Icons.Default.CheckCircle, stringResource(R.string.native_downloaded), tint = DownloadGreen, modifier = Modifier.padding(8.dp).size(24.dp))
        onDownload != null -> IconButton(onClick = onDownload, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.Download, stringResource(R.string.native_download)) }
        onDelete != null -> Box {
            IconButton(onClick = { expanded = true }, modifier = Modifier.size(48.dp)) { Icon(Icons.Default.MoreVert, stringResource(R.string.native_more), Modifier.size(20.dp)) }
            DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
                DropdownMenuItem(text = { Text(stringResource(R.string.native_delete_forever)) }, leadingIcon = { Icon(Icons.Default.DeleteOutline, null) }, onClick = { expanded = false; onDelete() })
            }
        }
    }
}

@Composable
internal fun CoverImage(path: String?, title: String, modifier: Modifier = Modifier) {
    val image by produceState<androidx.compose.ui.graphics.ImageBitmap?>(null, path) {
        value = if (path.isNullOrBlank()) null else withContext(Dispatchers.IO) {
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
        Column(Modifier.navigationBarsPadding().padding(horizontal = 18.dp, vertical = 8.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.native_total_books, count, count), style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                val coverSizeLabel = stringResource(R.string.native_card_size)
                if (layout == LibraryLayout.GRID) Slider(value = coverSize, onValueChange = onCoverSize, valueRange = 100f..210f,
                    modifier = Modifier.width(96.dp).height(48.dp).testTag("cover_size").semantics { contentDescription = coverSizeLabel })
                IconButton(onClick = { onLayout(LibraryLayout.GRID) }, modifier = Modifier.size(48.dp).semantics { selected = layout == LibraryLayout.GRID }) {
                    Icon(Icons.Default.GridView, stringResource(R.string.native_grid), tint = if (layout == LibraryLayout.GRID) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
                IconButton(onClick = { onLayout(LibraryLayout.LIST) }, modifier = Modifier.size(48.dp).semantics { selected = layout == LibraryLayout.LIST }) {
                    Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.native_list), tint = if (layout == LibraryLayout.LIST) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ReadFilter.entries.forEach { item ->
                    FilterChip(modifier = Modifier.testTag("filter:${item.name}"), selected = filter == item, onClick = { onFilter(item) }, label = { Text(stringResource(when (item) {
                        ReadFilter.ALL -> R.string.native_all
                        ReadFilter.UNREAD -> R.string.native_unread
                        ReadFilter.READING -> R.string.native_reading
                        ReadFilter.FINISHED -> R.string.native_finished
                    })) })
                }
            }
        }
    }
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

private fun fileDetails(format: String, bytes: Long): String {
    val extension = format.trimStart('.').uppercase()
    if (bytes <= 0) return extension
    val units = arrayOf("B", "KB", "MB", "GB")
    var value = bytes.toDouble()
    var unit = 0
    while (value >= 1024 && unit < units.lastIndex) { value /= 1024; unit++ }
    val size = "${DecimalFormat(if (value < 10 && unit > 0) "0.#" else "0").format(value)} ${units[unit]}"
    return if (extension.isBlank()) size else "$extension · $size"
}

internal fun readerFont(settings: UiSettings): FontFamily = when (settings.font) {
    "custom" -> settings.fontPath?.let { path -> runCatching { FontFamily(Font(File(path))) }.getOrNull() } ?: FontFamily.SansSerif
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    else -> FontFamily.SansSerif
}

internal fun readerBackground(settings: UiSettings, dark: Boolean): Color = if (dark) Color(0xFF181715) else when (settings.background) {
    "white" -> Color.White
    "sepia" -> Color(0xFFEEE0C1)
    else -> Cream
}
