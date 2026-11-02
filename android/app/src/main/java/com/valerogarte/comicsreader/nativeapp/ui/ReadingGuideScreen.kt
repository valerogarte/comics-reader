package com.valerogarte.comicsreader.nativeapp.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.valerogarte.comicsreader.R

private val ReadGreen = Color(0xFF2E8B57)
private val ReadingAmber = Color(0xFFE0A030)
private enum class GuideFilter { ALL, TO_READ, READ, MISSING }

/** A reading order: progress at a glance, the next book to read, and every gap still to obtain. */
@Composable
internal fun ReadingGuideContent(guide: UiGuideView?, actions: AppActions, modifier: Modifier = Modifier) {
    if (guide == null) {
        Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }
    var filter by rememberSaveable(guide.id) { mutableStateOf(GuideFilter.ALL) }
    val collapsed = remember(guide.id) { mutableStateMapOf<Int, Boolean>() }
    val items = guide.items
    val read = items.count { it.status == GuideItemStatus.READ }
    val missing = items.count { it.status == GuideItemStatus.MISSING }
    val available = items.count { it.status in setOf(GuideItemStatus.AVAILABLE, GuideItemStatus.READING, GuideItemStatus.PARTIAL) }
    val next = items.firstOrNull { it.status == GuideItemStatus.READING }
        ?: items.firstOrNull { it.status == GuideItemStatus.AVAILABLE || it.status == GuideItemStatus.PARTIAL }
    LazyColumn(modifier.fillMaxSize().testTag("reading_guide"), contentPadding = PaddingValues(bottom = 32.dp)) {
        item(key = "hero") { GuideHero(guide, read, available, missing, items.size) }
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
            val isCollapsed = collapsed[index] == true
            item(key = "section:$index") {
                SectionHeader(section, isCollapsed) { collapsed[index] = !isCollapsed }
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
private fun GuideHero(guide: UiGuideView, read: Int, available: Int, missing: Int, total: Int) {
    val primary = MaterialTheme.colorScheme.primary
    val deep = lerp(primary, Color.Black, .45f)
    val fraction = if (total > 0) read.toFloat() / total else 0f
    val animated by animateFloatAsState(fraction, tween(900), label = "guide-progress")
    Box(Modifier.fillMaxWidth().padding(16.dp).shadow(10.dp, RoundedCornerShape(28.dp))
        .clip(RoundedCornerShape(28.dp))
        .background(Brush.linearGradient(listOf(deep, primary, lerp(primary, Color(0xFFFFE0A3), .35f))))) {
        // Soft light shapes give the card depth without an image.
        Box(Modifier.align(Alignment.TopEnd).offset(x = 40.dp, y = (-50).dp).size(180.dp).background(Color.White.copy(alpha = .08f), CircleShape))
        Box(Modifier.align(Alignment.BottomStart).offset(x = (-30).dp, y = 40.dp).size(120.dp).background(Color.White.copy(alpha = .06f), CircleShape))
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
                    Text("${(animated * 100).toInt()}%", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = Color.White)
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
            Box {
                CoverImage(item.coverPath, item.title, Modifier.size(width = 86.dp, height = 129.dp).shadow(8.dp, RoundedCornerShape(8.dp)).clip(RoundedCornerShape(8.dp)))
                if (item.progress > 0f) LinearProgressIndicator(progress = { item.progress }, color = ReadingAmber,
                    modifier = Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(4.dp))
            }
            Column(Modifier.weight(1f).padding(start = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(if (item.status == GuideItemStatus.READING) R.string.native_guide_continue else R.string.native_guide_next).uppercase(),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                Text(listOf(item.number, item.title).filter { it.isNotBlank() }.joinToString(". "), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
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
private fun SectionHeader(section: UiGuideSection, collapsed: Boolean, onToggle: () -> Unit) {
    val done = section.items.count { it.status == GuideItemStatus.READ }
    val total = section.items.size
    val rotation by animateFloatAsState(if (collapsed) -90f else 0f, label = "section-arrow")
    Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(start = 20.dp, end = 16.dp, top = 22.dp, bottom = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(section.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f),
                maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text("$done/$total", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Icon(Icons.Default.ExpandMore, null, Modifier.padding(start = 6.dp).rotate(rotation))
        }
        LinearProgressIndicator(progress = { if (total > 0) done.toFloat() / total else 0f }, color = ReadGreen,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(5.dp).clip(RoundedCornerShape(50)))
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
                GuideCover(item, missing)
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(listOf(item.number, item.title).filter { it.isNotBlank() }.joinToString(". "),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        color = if (missing) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface)
                    if (item.detail.isNotBlank()) Text(item.detail, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        StatusChip(item.status)
                        if (item.priority.isNotBlank()) PriorityChip(item.priority)
                        if (item.category.isNotBlank()) TagChip(item.category)
                    }
                    if (item.files.size > 1) FileStrip(item, actions)
                    if (item.note.isNotBlank()) Text(item.note, style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                if (item.files.any { it.downloading }) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
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
private fun GuideCover(item: UiGuideItem, missing: Boolean) {
    val width = 52.dp
    Box(Modifier.size(width = width, height = width * 1.5f)) {
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
    Row(Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
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

@Composable
private fun TagChip(text: String) = Pill(text, MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun Pill(text: String, color: Color, filled: Boolean = false, height: Dp = 22.dp) {
    Box(Modifier.heightIn(min = height).background(if (filled) color else color.copy(alpha = .12f), RoundedCornerShape(50))
        .padding(horizontal = 9.dp, vertical = 3.dp), contentAlignment = Alignment.Center) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = if (filled) Color.White else color, maxLines = 1)
    }
}
