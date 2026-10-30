package com.valerogarte.comicsreader.nativeapp.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import java.text.NumberFormat

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReadingStatisticsScreen(statistics: UiReadingStatistics, onClose: () -> Unit) {
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

@Composable
private fun StatisticsHeading(text: String) {
    Text(text, Modifier.semantics { heading() }, style = MaterialTheme.typography.titleLarge)
}
