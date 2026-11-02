package com.valerogarte.comicsreader.nativeapp.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.valerogarte.comicsreader.R
import java.text.DateFormat
import java.text.NumberFormat
import java.util.Date

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingStatisticsScreen(statistics: UiReadingStatistics, onClose: () -> Unit, onOpenEntry: (String) -> Unit = {}) {
    var chronologyVisible by remember { mutableStateOf(false) }
    if (chronologyVisible) {
        ChronologyScreen(statistics.timeline, onBack = { chronologyVisible = false }, onOpenEntry = onOpenEntry)
        return
    }
    val locale = LocalConfiguration.current.locales[0]
    val numbers = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val percentages = remember(locale) { NumberFormat.getPercentInstance(locale).apply { maximumFractionDigits = 0 } }
    val finishedProgress = if (statistics.totalBooks > 0)
        (statistics.finishedBooks.toDouble() / statistics.totalBooks).coerceIn(0.0, 1.0).toFloat() else 0f
    val pageProgress = if (statistics.totalPages > 0)
        (statistics.progressPages.toDouble() / statistics.totalPages).coerceIn(0.0, 1.0).toFloat() else 0f

    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = {
                    TopAppBar(
                        title = { Text(stringResource(R.string.native_reading_statistics), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        navigationIcon = { IconButton(onClick = onClose) { Icon(Icons.Default.Close, stringResource(R.string.native_close)) } },
                    )
                },
            ) { insets ->
                Column(
                    Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).verticalScroll(rememberScrollState()),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Column(
                        Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 24.dp, vertical = 20.dp),
                        verticalArrangement = Arrangement.spacedBy(24.dp),
                    ) {
                        Text(stringResource(R.string.native_statistics_current_library), style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatisticCard(stringResource(R.string.native_books), numbers.format(statistics.totalBooks), Modifier.weight(1f).fillMaxHeight())
                                StatisticCard(stringResource(R.string.native_unread), numbers.format(statistics.unreadBooks), Modifier.weight(1f).fillMaxHeight())
                            }
                            Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                StatisticCard(stringResource(R.string.native_reading), numbers.format(statistics.readingBooks), Modifier.weight(1f).fillMaxHeight())
                                StatisticCard(stringResource(R.string.native_finished), numbers.format(statistics.finishedBooks), Modifier.weight(1f).fillMaxHeight())
                            }
                        }
                        if (statistics.totalBooks == 0) {
                            Text(stringResource(R.string.native_statistics_empty_library), style = MaterialTheme.typography.bodyMedium)
                        }
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatisticsHeading(stringResource(R.string.native_statistics_completed_books))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(pluralStringResource(R.plurals.native_statistics_books_progress, statistics.totalBooks,
                                    numbers.format(statistics.finishedBooks), numbers.format(statistics.totalBooks)), Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                                if (statistics.totalBooks > 0) Text(percentages.format(finishedProgress), style = MaterialTheme.typography.labelLarge)
                            }
                            if (statistics.totalBooks > 0) LinearProgressIndicator(progress = { finishedProgress }, modifier = Modifier.fillMaxWidth())
                        }
                        HorizontalDivider()
                        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatisticsHeading(stringResource(R.string.native_statistics_pages))
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(pluralStringResource(R.plurals.native_statistics_pages_progress,
                                    if (statistics.totalPages == 1L) 1 else 2,
                                    numbers.format(statistics.progressPages), numbers.format(statistics.totalPages)),
                                    Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                                if (statistics.totalPages > 0) Text(percentages.format(pageProgress), style = MaterialTheme.typography.labelLarge)
                            }
                            if (statistics.totalPages > 0) {
                                LinearProgressIndicator(progress = { pageProgress }, modifier = Modifier.fillMaxWidth())
                                Text(pluralStringResource(R.plurals.native_statistics_known_pages_books, statistics.totalBooks,
                                    numbers.format(statistics.booksWithKnownPages), numbers.format(statistics.totalBooks)), style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            } else {
                                Text(stringResource(R.string.native_statistics_open_book), style = MaterialTheme.typography.bodyMedium)
                            }
                            Text(stringResource(R.string.native_statistics_saved_positions), style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        HorizontalDivider()
                        // The chronology has its own page: a long history no longer buries the statistics.
                        ElevatedCard(Modifier.fillMaxWidth().clickable { chronologyVisible = true }) {
                            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.History, null, tint = MaterialTheme.colorScheme.primary)
                                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                    Text(stringResource(R.string.native_statistics_timeline), style = MaterialTheme.typography.titleMedium)
                                    Text(pluralStringResource(R.plurals.native_chronology_count, statistics.timeline.size, statistics.timeline.size),
                                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                }
                                Box(Modifier.width(96.dp).height(54.dp)) {
                                    statistics.timeline.take(3).reversed().forEachIndexed { index, entry ->
                                        CoverImage(entry.coverPath, entry.title, Modifier.align(Alignment.CenterEnd).offset(x = (-index * 22).dp)
                                            .size(width = 36.dp, height = 54.dp).clip(RoundedCornerShape(4.dp)))
                                    }
                                }
                                Icon(Icons.Default.ChevronRight, null)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StatisticCard(label: String, value: String, modifier: Modifier = Modifier) {
    OutlinedCard(modifier.heightIn(min = 112.dp).semantics(mergeDescendants = true) {}) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.SemiBold)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChronologyScreen(timeline: List<UiReadingTimelineEntry>, onBack: () -> Unit, onOpenEntry: (String) -> Unit) {
    val locale = LocalConfiguration.current.locales[0]
    val dates = remember(locale) { DateFormat.getDateInstance(DateFormat.MEDIUM, locale) }
    val months = remember(locale) { java.text.SimpleDateFormat("LLLL yyyy", locale) }
    androidx.activity.compose.BackHandler(onBack = onBack)
    Dialog(onDismissRequest = onBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(topBar = {
                TopAppBar(title = { Text(stringResource(R.string.native_statistics_timeline)) },
                    navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.native_close)) } })
            }) { insets ->
                if (timeline.isEmpty()) Text(stringResource(R.string.native_statistics_timeline_empty), Modifier.padding(insets).padding(24.dp),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                else LazyColumn(Modifier.fillMaxSize().padding(insets), contentPadding = PaddingValues(horizontal = 20.dp, vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    val groups = timeline.groupBy { months.format(Date(it.finishedAt ?: it.startedAt)).replaceFirstChar { c -> c.titlecase(locale) } }
                    groups.forEach { (month, entries) ->
                        item(key = "month:$month") {
                            Text(month, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.padding(top = 14.dp, bottom = 2.dp).semantics { heading() })
                        }
                        items(entries, key = { "entry:${it.bookId}" }) { entry ->
                            TimelineRow(entry, dates, onClick = { onOpenEntry(entry.bookId) }.takeIf { entry.canOpen })
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun TimelineRow(entry: UiReadingTimelineEntry, dates: DateFormat, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
        .padding(6.dp).semantics(mergeDescendants = true) {}, horizontalArrangement = Arrangement.spacedBy(14.dp),
        verticalAlignment = Alignment.CenterVertically) {
        CoverImage(entry.coverPath, entry.title, Modifier.size(width = 44.dp, height = 66.dp).clip(RoundedCornerShape(4.dp)))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(entry.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val started = dates.format(Date(entry.startedAt))
            Text(
                if (entry.finishedAt != null) stringResource(R.string.native_statistics_timeline_finished, dates.format(Date(entry.finishedAt)))
                else stringResource(R.string.native_statistics_timeline_reading, started),
                style = MaterialTheme.typography.bodyMedium,
            )
            val minutes = (entry.readingSeconds / 60).toInt()
            val details = listOfNotNull(
                stringResource(R.string.native_statistics_timeline_started, started).takeIf { entry.finishedAt != null },
                if (minutes >= 60) stringResource(R.string.native_statistics_reading_time_hours, minutes / 60, minutes % 60)
                else stringResource(R.string.native_statistics_reading_time_minutes, minutes),
            )
            Text(details.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onClick != null && !entry.local) Icon(Icons.Default.CloudDownload, stringResource(R.string.native_reopen_from_server),
            tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatisticsHeading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}
