package com.valerogarte.comicsreader.nativeapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.valerogarte.comicsreader.R
import kotlinx.coroutines.launch

private val ReadGreen = Color(0xFF2E8B57)
private val ReadingAmber = Color(0xFFE0A030)
private enum class GuideFilter { ALL, TO_READ, READ, MISSING }

/** A reading order: progress at a glance, the next book to read, and every gap still to obtain. */
@Composable
internal fun ReadingGuideContent(guide: UiGuideView?, covers: List<String>, actions: AppActions, modifier: Modifier = Modifier) {
    if (guide == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var filter by rememberSaveable(guide.id) { mutableStateOf(GuideFilter.ALL) }
    // Only the section being read starts open; the user's own toggles are kept, also while a
    // book or a server folder is shown, so Back returns to the same section and scroll.
    val collapsed = rememberSaveable(guide.id, saver = sectionToggles) { mutableStateMapOf() }
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val items = guide.items
    val read = items.count { it.status == GuideItemStatus.READ }
    val missing = items.count { it.status == GuideItemStatus.MISSING }
    val available = items.count { it.status in setOf(GuideItemStatus.AVAILABLE, GuideItemStatus.READING, GuideItemStatus.PARTIAL) }
    val next = items.firstOrNull { it.status == GuideItemStatus.READING }
        ?: items.firstOrNull { it.status == GuideItemStatus.AVAILABLE || it.status == GuideItemStatus.PARTIAL }
    val activeSection = guide.sections.indexOfFirst { section -> section.items.any { it === next } }
    LazyColumn(modifier.fillMaxSize().testTag("reading_guide"), state = listState, contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "hero") { GuideHero(guide, covers, read, available, missing, items.size) }
        if (guide.checking) item(key = "checking") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.native_guide_checking), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
            }
        }
        if (next != null) item(key = "next") { NextReading(next, actions) }
        item(key = "filters") {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GuideFilter.entries.forEach { option ->
                    val count = when (option) {
                        GuideFilter.ALL -> items.size
                        GuideFilter.TO_READ -> items.count { it.status != GuideItemStatus.READ && it.status != GuideItemStatus.MISSING }
                        GuideFilter.READ -> read
                        GuideFilter.MISSING -> missing
                    }
                    FilterChip(selected = filter == option, onClick = { filter = option },
                        label = { Text("${stringResource(option.label)} · $count") },
                        leadingIcon = if (filter == option) { { Icon(Icons.Default.Check, null, Modifier.size(18.dp)) } } else null)
                }
            }
        }
        guide.sections.forEachIndexed { index, section ->
            val visible = section.items.filter { filter.matches(it) }
            if (visible.isEmpty()) return@forEachIndexed
            val isCollapsed = collapsed[index] ?: (index != activeSection)
            // Every header is sticky: an open one stays under the top bar while its books scroll,
            // a closed one is pushed away at once by the next header, as a plain row would be.
            stickyHeader(key = "section:$index") { position ->
                val pinned by remember(position) {
                    derivedStateOf {
                        listState.firstVisibleItemIndex > position ||
                            listState.firstVisibleItemIndex == position && listState.firstVisibleItemScrollOffset > 0
                    }
                }
                SectionHeader(section, isCollapsed, pinned = pinned && !isCollapsed, onDownload = { actions.onDownloadGuideSection(index) }) {
                    collapsed[index] = !isCollapsed
                    // Closing it from the top keeps the header in sight instead of landing further down the guide.
                    if (!isCollapsed && pinned) scope.launch { listState.scrollToItem(position) }
                }
            }
            if (!isCollapsed) {
                val list = visible
                items(list, key = { "item:$index:${it.key}" }) { item ->
                    GuideItemRow(item, first = item === list.first(), last = item === list.last(), actions = actions)
                }
            }
        }
    }
}

/** Section index to "collapsed", saved as a flat list of pairs. */
private val sectionToggles = listSaver<SnapshotStateMap<Int, Boolean>, Int>(
    save = { toggles -> toggles.flatMap { (index, closed) -> listOf(index, if (closed) 1 else 0) } },
    restore = { saved -> mutableStateMapOf<Int, Boolean>().apply { saved.chunked(2).forEach { (index, closed) -> put(index, closed == 1) } } },
)

/** A whole percentage that does not show 0 once something is read. */
private fun percentRead(fraction: Float, anyRead: Boolean) = (fraction * 100).toInt().let { if (anyRead) it.coerceAtLeast(1) else it }

/** An approximate time: minutes up to an hour, then hours and minutes, then whole hours. */
internal fun readingTime(minutes: Int): String = when {
    minutes < 60 -> "$minutes min"
    minutes < 600 -> {
        val rest = (minutes % 60 + 2) / 5 * 5
        if (rest == 0 || rest == 60) "${(minutes + 30) / 60} h" else "${minutes / 60} h $rest min"
    }
    else -> "${(minutes + 30) / 60} h"
}

/** Every reading guide of the servers, with a star to show it in the side menu. */
@Composable
internal fun ReadingGuideList(guides: List<UiGuideSummary>, syncing: Boolean, actions: AppActions, modifier: Modifier = Modifier) {
    // One column on a phone or a tablet upright, more side by side when there is room.
    LazyVerticalGrid(GridCells.Adaptive(340.dp), modifier.fillMaxSize().testTag("reading_guides"), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        if (syncing) item(key = "syncing", span = { GridItemSpan(maxLineSpan) }) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text(stringResource(R.string.native_guides_syncing), style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 10.dp))
            }
        }
        if (guides.isEmpty() && !syncing) item(key = "empty", span = { GridItemSpan(maxLineSpan) }) {
            Column(Modifier.fillMaxWidth().padding(top = 96.dp, start = 24.dp, end = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Default.AutoStories, null, Modifier.size(56.dp), tint = MaterialTheme.colorScheme.primary)
                Text(stringResource(R.string.native_guides_empty), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 16.dp))
                Text(stringResource(R.string.native_guides_empty_detail), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
            }
        }
        gridItems(guides, key = { it.id }) { guide -> GuideCard(guide, actions, Modifier.animateItem()) }
    }
}

@Composable
private fun GuideCard(guide: UiGuideSummary, actions: AppActions, modifier: Modifier = Modifier) {
    ElevatedCard(onClick = { actions.onOpenGuide(guide.id) }, shape = RoundedCornerShape(24.dp),
        modifier = modifier.fillMaxWidth().testTag("guide_card:${guide.id}")) {
        Box(Modifier.fillMaxWidth().height(156.dp)) {
            GuideCoverStrip(guide.covers, Modifier.matchParentSize())
            // Darker towards the bottom, so the title reads over any cover.
            Box(Modifier.matchParentSize().background(Brush.verticalGradient(
                0f to Color.Black.copy(alpha = .1f), .45f to Color.Black.copy(alpha = .35f), 1f to Color.Black.copy(alpha = .88f))))
            Text(guide.title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.align(Alignment.BottomStart).padding(start = 18.dp, end = 18.dp, bottom = 14.dp))
            IconButton(onClick = { actions.onToggleGuideFavorite(guide.id) },
                modifier = Modifier.align(Alignment.TopEnd).padding(8.dp).size(40.dp)
                    .background(Color.Black.copy(alpha = .4f), CircleShape).testTag("favorite:${guide.id}")) {
                Icon(if (guide.favorite) Icons.Default.Star else Icons.Default.StarBorder,
                    stringResource(if (guide.favorite) R.string.native_guide_unfavorite else R.string.native_guide_favorite),
                    tint = if (guide.favorite) ReadingAmber else Color.White)
            }
        }
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (guide.description.isNotBlank()) Text(guide.description, style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            GuideProgress(guide)
            GuideNext(guide)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Pill(stringResource(R.string.native_guide_entries, guide.itemCount), MaterialTheme.colorScheme.primary)
                if (guide.sectionCount > 0) Pill(pluralStringResource(R.plurals.native_guide_sections, guide.sectionCount, guide.sectionCount),
                    MaterialTheme.colorScheme.secondary)
                guide.fileName?.let { Pill(listOfNotNull(guide.serverName, it).joinToString(" · "), MaterialTheme.colorScheme.outline) }
            }
        }
    }
}

/** The guide's covers side by side; without any yet, its colour and icon. */
@Composable
private fun GuideCoverStrip(covers: List<String>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    if (covers.isEmpty()) Box(modifier.background(Brush.linearGradient(listOf(lerp(primary, Color.Black, .55f), primary)))) {
        Icon(Icons.Default.AutoStories, null, tint = Color.White.copy(alpha = .5f), modifier = Modifier.padding(18.dp).size(40.dp))
    } else Row(modifier.background(Color.Black), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        covers.forEach { CoverImage(it, "", Modifier.weight(1f).fillMaxHeight()) }
    }
}

@Composable
private fun GuideProgress(guide: UiGuideSummary) {
    val percent = percentRead(guide.progress, guide.readCount > 0)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            LinearProgressIndicator(progress = { guide.progress }, color = ReadGreen, trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                drawStopIndicator = {}, gapSize = 0.dp, modifier = Modifier.weight(1f).height(6.dp).clip(RoundedCornerShape(50)))
            Text("$percent%", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, modifier = Modifier.padding(start = 12.dp))
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.native_guide_read_of, guide.readCount, guide.itemCount), style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
            guide.readingMinutes?.let { minutes ->
                Icon(Icons.Default.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(14.dp))
                val left = guide.remainingMinutes?.takeIf { it < minutes && readingTime(it) != readingTime(minutes) }
                Text(listOfNotNull("≈ ${readingTime(minutes)}", left?.let { stringResource(R.string.native_guide_time_left, readingTime(it)) })
                    .joinToString(" · "), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
}

/** The entry to read next or, once every entry is read, that the guide is complete. */
@Composable
private fun GuideNext(guide: UiGuideSummary) {
    val complete = guide.itemCount > 0 && guide.readCount >= guide.itemCount
    val title = guide.nextTitle
    if (!complete && title == null) return
    val color = when { complete -> ReadGreen; guide.nextStarted -> ReadingAmber; else -> MaterialTheme.colorScheme.primary }
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(color.copy(alpha = .1f))
        .padding(horizontal = 12.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (complete) Icons.Default.CheckCircle else Icons.AutoMirrored.Filled.MenuBook, null, tint = color, modifier = Modifier.size(20.dp))
        Column(Modifier.weight(1f).padding(start = 12.dp)) {
            if (complete) Text(stringResource(R.string.native_guide_completed), style = MaterialTheme.typography.labelLarge,
                fontWeight = FontWeight.Bold, color = color)
            else {
                Text(stringResource(if (guide.nextStarted) R.string.native_guide_continue else R.string.native_guide_next).uppercase(),
                    style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold, color = color)
                Text(title.orEmpty(), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A favourite guide in the side menu: its covers, its title and how much of it is read. */
@Composable
internal fun FavoriteGuideItem(guide: UiGuideSummary, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(selected = selected, onClick = onClick, shape = RoundedCornerShape(18.dp),
        color = if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            GuideThumbnail(guide.covers, Modifier.size(width = 38.dp, height = 56.dp))
            Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(guide.title, style = MaterialTheme.typography.labelLarge, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    LinearProgressIndicator(progress = { guide.progress }, color = ReadGreen,
                        trackColor = MaterialTheme.colorScheme.surfaceContainerHighest, drawStopIndicator = {}, gapSize = 0.dp,
                        modifier = Modifier.weight(1f).height(4.dp).clip(RoundedCornerShape(50)))
                    Text("${guide.readCount}/${guide.itemCount}", style = MaterialTheme.typography.labelSmall, modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

/** Two covers of the guide, one tilted behind the other like a small pile. */
@Composable
private fun GuideThumbnail(covers: List<String>, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val shape = RoundedCornerShape(5.dp)
    Box(modifier) {
        covers.getOrNull(1)?.let { CoverImage(it, "", Modifier.matchParentSize().offset(x = 5.dp, y = (-2).dp).rotate(8f).clip(shape)) }
        if (covers.isEmpty()) Box(Modifier.matchParentSize().clip(shape)
            .background(Brush.linearGradient(listOf(lerp(primary, Color.Black, .45f), primary))), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.AutoStories, null, tint = Color.White, modifier = Modifier.size(20.dp))
        } else CoverImage(covers.first(), "", Modifier.matchParentSize().shadow(4.dp, shape).clip(shape))
    }
}

private val GuideFilter.label get() = when (this) {
    GuideFilter.ALL -> R.string.native_all
    GuideFilter.TO_READ -> R.string.native_guide_to_read
    GuideFilter.READ -> R.string.native_guide_read
    GuideFilter.MISSING -> R.string.native_guide_missing
}

private fun GuideFilter.matches(item: UiGuideItem) = when (this) {
    GuideFilter.ALL -> true
    GuideFilter.TO_READ -> item.status != GuideItemStatus.READ && item.status != GuideItemStatus.MISSING
    GuideFilter.READ -> item.status == GuideItemStatus.READ
    GuideFilter.MISSING -> item.status == GuideItemStatus.MISSING
}

@Composable
private fun GuideHero(guide: UiGuideView, covers: List<String>, read: Int, available: Int, missing: Int, total: Int) {
    val fraction = if (total > 0) read.toFloat() / total else 0f
    val animated by animateFloatAsState(fraction, tween(900), label = "guide-progress")
    Box(Modifier.fillMaxWidth().padding(16.dp).shadow(10.dp, RoundedCornerShape(28.dp))
        .clip(RoundedCornerShape(28.dp))) {
        // The same covers as in the list of guides, darkened so the white text reads over any of them.
        GuideCoverStrip(covers, Modifier.matchParentSize())
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(
            0f to Color.Black.copy(alpha = .82f), .65f to Color.Black.copy(alpha = .62f), 1f to Color.Black.copy(alpha = .5f))))
        Row(Modifier.fillMaxWidth().padding(24.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.AutoStories, null, tint = Color.White.copy(alpha = .85f), modifier = Modifier.size(18.dp))
                    Text(stringResource(R.string.native_reading_guides).uppercase(), style = MaterialTheme.typography.labelMedium,
                        color = Color.White.copy(alpha = .85f), modifier = Modifier.padding(start = 8.dp))
                }
                Text(guide.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold, color = Color.White,
                    maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (guide.description.isNotBlank()) Text(guide.description, style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = .85f), maxLines = 4, overflow = TextOverflow.Ellipsis)
                guide.readingMinutes?.let { minutes ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Schedule, null, tint = Color.White, modifier = Modifier.size(16.dp))
                        val left = guide.remainingMinutes?.takeIf { it < minutes && readingTime(it) != readingTime(minutes) }
                        Text(listOfNotNull(stringResource(R.string.native_guide_time, readingTime(minutes)),
                            left?.let { stringResource(R.string.native_guide_time_left, readingTime(it)) }).joinToString(" · "),
                            style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold, color = Color.White,
                            modifier = Modifier.padding(start = 6.dp))
                    }
                }
                Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    HeroStat(Icons.Default.DoneAll, "$read")
                    HeroStat(Icons.AutoMirrored.Filled.MenuBook, "$available")
                    HeroStat(Icons.Default.HourglassEmpty, "$missing")
                }
                Text(stringResource(R.string.native_guide_stats_legend), style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .7f))
            }
            Box(Modifier.padding(start = 16.dp).size(108.dp), contentAlignment = Alignment.Center) {
                Canvas(Modifier.fillMaxSize()) {
                    val stroke = 11.dp.toPx()
                    drawArc(Color.White.copy(alpha = .18f), 0f, 360f, false, style = Stroke(stroke))
                    drawArc(Color.White, -90f, 360f * animated, false, style = Stroke(stroke, cap = StrokeCap.Round))
                }
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${percentRead(animated, read > 0)}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
                    Text("$read/$total", style = MaterialTheme.typography.labelSmall, color = Color.White.copy(alpha = .8f))
                }
            }
        }
    }
}

@Composable
private fun HeroStat(icon: androidx.compose.ui.graphics.vector.ImageVector, value: String) {
    Row(Modifier.background(Color.White.copy(alpha = .16f), RoundedCornerShape(50)).padding(horizontal = 10.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.size(14.dp))
        Text(value, style = MaterialTheme.typography.labelMedium, color = Color.White, modifier = Modifier.padding(start = 5.dp))
    }
}

@Composable
private fun NextReading(item: UiGuideItem, actions: AppActions) {
    val target = item.files.firstOrNull { it.progress < 1f && it.available != false } ?: item.files.firstOrNull()
    ElevatedCard(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), shape = RoundedCornerShape(22.dp)) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            // Fixed to the cover's size, so the progress bar below it cannot take the text's width.
            Box(Modifier.size(width = 86.dp, height = 129.dp)) {
                CoverImage(item.coverPath, item.title, Modifier.matchParentSize().shadow(8.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)))
                if (item.progress > 0f) LinearProgressIndicator(progress = { item.progress }, color = ReadingAmber,
                    trackColor = Color.Black.copy(alpha = .45f), drawStopIndicator = {}, gapSize = 0.dp,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp)
                        .clip(RoundedCornerShape(bottomStart = 8.dp, bottomEnd = 8.dp)))
            }
            Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(if (item.status == GuideItemStatus.READING) R.string.native_guide_continue else R.string.native_guide_next).uppercase(),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(listOf(item.number, item.title).filter { it.isNotBlank() }.joinToString(". "), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.scope.isNotBlank()) ScopeLine(item.scope)
                if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 4, overflow = TextOverflow.Ellipsis)
                if (target != null) Button(onClick = { actions.onOpenGuideFile(target.path) }, modifier = Modifier.padding(top = 8.dp),
                    enabled = !target.downloading) {
                    Icon(if (target.bookId != null) Icons.AutoMirrored.Filled.MenuBook else Icons.Default.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(if (target.bookId != null) R.string.native_guide_read_now else R.string.native_guide_download_read))
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(section: UiGuideSection, collapsed: Boolean, pinned: Boolean, onDownload: () -> Unit, onToggle: () -> Unit) {
    val done = section.items.count { it.status == GuideItemStatus.READ }
    val files = section.items.flatMap { it.files }
    val pending = files.count { it.bookId == null && it.available != false }
    val downloading = files.any { it.downloading }
    val total = section.items.size
    val rotation by animateFloatAsState(if (collapsed) -90f else 0f, label = "section-arrow")
    // Pinned under the top bar it needs its own background, so the books scrolling below do not show through.
    val background by animateColorAsState(if (pinned) MaterialTheme.colorScheme.background else Color.Transparent, label = "section-background")
    val shadow by animateDpAsState(if (pinned) 6.dp else 0.dp, label = "section-shadow")
    Surface(color = background, contentColor = LocalContentColor.current, shadowElevation = shadow, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 20.dp, end = 16.dp, top = 22.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                // Download state leads the header, before the title and its number.
                when {
                    downloading -> CircularProgressIndicator(Modifier.padding(start = 8.dp, end = 18.dp).size(20.dp), strokeWidth = 2.dp)
                    pending > 0 -> FilledTonalIconButton(onClick = onDownload, modifier = Modifier.padding(end = 10.dp).size(36.dp)) {
                        Icon(Icons.Default.Download, stringResource(R.string.native_guide_download_section, pending), Modifier.size(18.dp))
                    }
                    files.isNotEmpty() -> Icon(Icons.Default.DownloadDone, stringResource(R.string.native_guide_section_downloaded),
                        tint = ReadGreen, modifier = Modifier.padding(start = 8.dp, end = 18.dp).size(20.dp))
                }
                Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                section.readingMinutes?.let { Text("≈ ${readingTime(it)} · ", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Text("$done/$total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(Icons.Default.ExpandMore, null, Modifier.padding(start = 6.dp).rotate(rotation))
            }
            LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, color = ReadGreen,
                trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(5.dp).clip(RoundedCornerShape(50)))
        }
    }
}

@Composable
private fun GuideItemRow(item: UiGuideItem, first: Boolean, last: Boolean, actions: AppActions) {
    val missing = item.status == GuideItemStatus.MISSING
    val target = item.files.firstOrNull { it.progress < 1f && it.available != false } ?: item.files.firstOrNull { it.available != false }
    val lineColor = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)
        .clickable(enabled = target != null) { target?.let { actions.onOpenGuideFile(it.path) } }
        .padding(start = 16.dp, end = 16.dp)
        .animateContentSize()) {
        // The timeline: a line through every entry and a node that tells its state.
        Box(Modifier.width(36.dp).fillMaxHeight(), contentAlignment = Alignment.TopCenter) {
            Box(Modifier.fillMaxHeight().width(2.dp).drawBehind {
                val top = if (first) size.height * .18f else 0f
                val bottom = if (last) size.height * .18f else size.height
                drawLine(lineColor, Offset(size.width / 2, top), Offset(size.width / 2, bottom), 2.dp.toPx())
            })
            StatusNode(item, Modifier.padding(top = 14.dp))
        }
        val shape = RoundedCornerShape(16.dp)
        val dashed = MaterialTheme.colorScheme.outline
        Surface(shape = shape, tonalElevation = if (missing) 0.dp else 1.dp,
            color = if (missing) Color.Transparent else MaterialTheme.colorScheme.surfaceContainerLow,
            modifier = Modifier.weight(1f).padding(vertical = 5.dp).then(if (missing) Modifier.drawBehind {
                drawRoundRect(dashed.copy(alpha = .6f), cornerRadius = CornerRadius(16.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))))
            } else Modifier)) {
            Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                GuideCover(item, missing, Modifier.align(Alignment.Top))
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(listOf(item.number, item.title).filter { it.isNotBlank() }.joinToString(". "),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (missing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    if (item.scope.isNotBlank()) ScopeLine(item.scope)
                    Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusChip(item.status)
                        if (item.priority.isNotBlank()) PriorityChip(item.priority)
                        item.readingMinutes?.let { Pill("≈ ${readingTime(it)}", MaterialTheme.colorScheme.secondary) }
                    }
                    if (item.detail.isNotBlank()) WhyRead(item.detail)
                    if (item.files.size > 1) FileStrip(item, actions)
                    if (item.note.isNotBlank()) Text(item.note, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.files.size <= 1 && item.files.any { it.downloading }) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                if (item.files.any { it.available != false }) IconButton(onClick = { actions.onRevealGuideItem(item.key) },
                    modifier = Modifier.align(Alignment.Top).size(40.dp).testTag("reveal:${item.key}")) {
                    Icon(Icons.Default.FolderOpen, stringResource(R.string.native_guide_reveal), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun StatusNode(item: UiGuideItem, modifier: Modifier = Modifier) {
    val size = 26.dp
    val outline = MaterialTheme.colorScheme.outline
    val primary = MaterialTheme.colorScheme.primary
    when (item.status) {
        GuideItemStatus.READ -> Box(modifier.size(size).background(ReadGreen, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Default.Check, null, tint = Color.White, modifier = Modifier.size(16.dp))
        }
        GuideItemStatus.READING -> Box(modifier.size(size).background(MaterialTheme.colorScheme.surface, CircleShape), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                drawArc(ReadingAmber.copy(alpha = .25f), 0f, 360f, false, style = Stroke(3.dp.toPx()))
                drawArc(ReadingAmber, -90f, 360f * item.progress.coerceIn(.1f, 1f), false, style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            }
            Icon(Icons.AutoMirrored.Filled.MenuBook, null, tint = ReadingAmber, modifier = Modifier.size(12.dp))
        }
        GuideItemStatus.MISSING -> Box(modifier.size(size).background(MaterialTheme.colorScheme.surface, CircleShape).drawBehind {
            drawCircle(outline, style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f))))
        }, contentAlignment = Alignment.Center) {
            Icon(Icons.Default.HourglassEmpty, null, tint = outline, modifier = Modifier.size(13.dp))
        }
        else -> Box(modifier.size(size).background(MaterialTheme.colorScheme.surface, CircleShape).border(2.dp, primary, CircleShape),
            contentAlignment = Alignment.Center) {
            Text(item.number.takeLast(3).ifBlank { "•" }, style = MaterialTheme.typography.labelSmall, color = primary, maxLines = 1)
        }
    }
}

@Composable
private fun GuideCover(item: UiGuideItem, missing: Boolean, modifier: Modifier = Modifier) {
    val width = 52.dp
    Box(modifier.size(width = width, height = width * 1.5f)) {
        if (item.files.size > 1 && !missing) Box(Modifier.matchParentSize().offset(x = 4.dp, y = (-4).dp).rotate(4f)
            .background(MaterialTheme.colorScheme.surfaceContainerHighest, RoundedCornerShape(6.dp)))
        if (missing) Box(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceContainerHigh, RoundedCornerShape(6.dp)),
            contentAlignment = Alignment.Center) {
            Icon(Icons.Default.QuestionMark, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(22.dp))
        } else CoverImage(item.coverPath, item.title, Modifier.matchParentSize().shadow(3.dp, RoundedCornerShape(6.dp)).clip(RoundedCornerShape(6.dp)))
        if (item.status == GuideItemStatus.READING) LinearProgressIndicator(progress = { item.progress }, color = ReadingAmber,
            modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp))
    }
}

@Composable
private fun FileStrip(item: UiGuideItem, actions: AppActions) {
    val pending = item.files.count { it.bookId == null && it.available != false && !it.downloading }
    val downloading = item.files.any { it.downloading }
    // The numbers scroll; "download all" stays pinned at the right edge of the card.
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        item.files.forEachIndexed { index, file ->
            val color = when {
                file.progress >= 1f -> ReadGreen
                file.progress > 0f -> ReadingAmber
                file.available == false -> MaterialTheme.colorScheme.outline
                else -> MaterialTheme.colorScheme.primary
            }
            Row(Modifier.clip(RoundedCornerShape(50)).border(1.dp, color.copy(alpha = .6f), RoundedCornerShape(50))
                .clickable(enabled = file.available != false) { actions.onOpenGuideFile(file.path) }
                .padding(horizontal = 8.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(when {
                    file.progress >= 1f -> Icons.Default.Check
                    file.bookId != null -> Icons.AutoMirrored.Filled.MenuBook
                    file.available == false -> Icons.Default.HourglassEmpty
                    else -> Icons.Default.CloudDownload
                }, null, tint = color, modifier = Modifier.size(12.dp))
                Text("${index + 1}", style = MaterialTheme.typography.labelSmall, color = color, modifier = Modifier.padding(start = 4.dp))
            }
        }
    }
    when {
        downloading -> Row(Modifier.padding(start = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
            if (pending > 0) Text("+$pending", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp))
        }
        pending > 0 -> FilledTonalButton(onClick = { actions.onDownloadGuideItem(item.key) },
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
            modifier = Modifier.padding(start = 10.dp).height(32.dp).testTag("download_item:${item.key}")) {
            Icon(Icons.Default.Download, null, Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.native_guide_download_all), style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
    }
}

@Composable
private fun StatusChip(status: GuideItemStatus) {
    val (label, color) = when (status) {
        GuideItemStatus.READ -> R.string.native_guide_status_read to ReadGreen
        GuideItemStatus.READING -> R.string.native_guide_status_reading to ReadingAmber
        GuideItemStatus.AVAILABLE -> R.string.native_guide_status_available to MaterialTheme.colorScheme.primary
        GuideItemStatus.PARTIAL -> R.string.native_guide_status_partial to MaterialTheme.colorScheme.tertiary
        GuideItemStatus.MISSING -> R.string.native_guide_status_missing to MaterialTheme.colorScheme.outline
        GuideItemStatus.UNKNOWN -> R.string.native_guide_status_unknown to MaterialTheme.colorScheme.outline
    }
    Pill(stringResource(label), color, filled = status == GuideItemStatus.READ)
}

@Composable
private fun PriorityChip(priority: String) {
    val color = when (priority.lowercase()) {
        "imprescindible" -> Color(0xFFC0392B)
        "recomendado" -> Color(0xFF2E86C1)
        "opcional" -> Color(0xFF7F8C8D)
        "contexto" -> Color(0xFF8E44AD)
        else -> MaterialTheme.colorScheme.secondary
    }
    Pill(priority, color)
}

/** What the entry covers, in a compact line with a stack icon. */
@Composable
private fun ScopeLine(scope: String) {
    Row(verticalAlignment = Alignment.Top) {
        Icon(Icons.Default.Layers, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 2.dp).size(14.dp))
        Text(scope, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(start = 6.dp))
    }
}

/** Why the entry matters: three lines, then the whole text on demand. */
@Composable
private fun WhyRead(text: String) {
    var expanded by rememberSaveable(text) { mutableStateOf(false) }
    var overflows by remember(text) { mutableStateOf(false) }
    Column(Modifier.padding(top = 6.dp).clip(RoundedCornerShape(10.dp))
        .background(MaterialTheme.colorScheme.surfaceContainerHigh)
        .clickable(enabled = overflows || expanded) { expanded = !expanded }
        .padding(horizontal = 10.dp, vertical = 8.dp).animateContentSize()) {
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = if (expanded) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis,
            onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow })
        if (overflows || expanded) Text(stringResource(if (expanded) R.string.native_guide_less else R.string.native_guide_more),
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary, modifier = Modifier.padding(top = 4.dp))
    }
}

@Composable
private fun Pill(text: String, color: Color, filled: Boolean = false, height: Dp = 22.dp) {
    Box(Modifier.heightIn(min = height).background(if (filled) color else color.copy(alpha = .12f), RoundedCornerShape(50))
        .padding(horizontal = 9.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = if (filled) Color.White else color, maxLines = 1)
    }
}
