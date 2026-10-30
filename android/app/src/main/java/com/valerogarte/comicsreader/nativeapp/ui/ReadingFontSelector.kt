package com.valerogarte.comicsreader.nativeapp.ui

import android.content.res.AssetManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.valerogarte.comicsreader.R
import com.valerogarte.comicsreader.nativeapp.reader.ReadingFonts
import java.io.File

private data class ReadingFontChoice(val id: String, val label: String, val family: FontFamily)

/** Both appearance settings and reader options show the same bundled, real font families. */
@Composable
internal fun ReadingFontSelector(settings: UiSettings, onChange: (String) -> Unit) {
    val assets = LocalContext.current.assets
    val bundled = remember(assets) {
        ReadingFonts.families.map { font ->
            ReadingFontChoice(font.id, font.label, readingFontFamily(assets, font.id, null))
        }
    }
    val legacy = listOf(
        ReadingFontChoice("sans", stringResource(R.string.native_sans), FontFamily.SansSerif),
        ReadingFontChoice("serif", stringResource(R.string.native_serif), FontFamily.Serif),
        ReadingFontChoice("mono", stringResource(R.string.native_mono), FontFamily.Monospace),
    )
    val customFamily = if (settings.fontPath != null) readerFont(settings.copy(font = "custom")) else null
    val custom = if (customFamily == null) emptyList() else listOf(ReadingFontChoice(
        "custom", stringResource(R.string.native_custom_font), customFamily,
    ))
    val choices = bundled + custom + legacy
    val selectedId = if (settings.font == "custom" && customFamily != null) "custom"
        else if (legacy.any { it.id == settings.font }) settings.font
        else ReadingFonts.find(settings.font)?.id ?: ReadingFonts.DEFAULT_ID
    val selected = choices.firstOrNull { it.id == selectedId } ?: bundled.first { it.id == ReadingFonts.DEFAULT_ID }
    val defaultLabel = stringResource(R.string.native_font_default)
    var expanded by rememberSaveable { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.native_font), style = MaterialTheme.typography.bodyMedium)
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth().testTag("reading_font_selector")) {
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.Start) {
                Text(selected.label, fontFamily = selected.family, style = MaterialTheme.typography.bodyLarge,
                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (selected.id == ReadingFonts.DEFAULT_ID) Text(defaultLabel, style = MaterialTheme.typography.labelSmall)
            }
            Icon(Icons.Default.ArrowDropDown, null)
        }
        Surface(color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = .55f), shape = MaterialTheme.shapes.medium) {
            Text(stringResource(R.string.native_font_preview), fontFamily = selected.family, fontSize = 20.sp,
                lineHeight = 30.sp, modifier = Modifier.fillMaxWidth().padding(16.dp).testTag("reading_font_preview"))
        }
    }
    if (expanded) {
        AlertDialog(
            onDismissRequest = { expanded = false },
            title = { Text(stringResource(R.string.native_font)) },
            text = {
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = 440.dp).selectableGroup().testTag("reading_font_choices")) {
                    items(choices, key = { it.id }) { choice ->
                        Row(
                            Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("reading_font:${choice.id}")
                                .selectable(selected = choice.id == selected.id, role = Role.RadioButton,
                                    onClick = { onChange(choice.id); expanded = false })
                                .padding(vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = choice.id == selected.id, onClick = null)
                            Column(Modifier.weight(1f).padding(start = 12.dp)) {
                                Text(choice.label, fontFamily = choice.family, fontSize = 19.sp)
                                if (choice.id == ReadingFonts.DEFAULT_ID) {
                                    Text(defaultLabel, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { expanded = false }) { Text(stringResource(R.string.native_close)) } },
        )
    }
}

@Composable
internal fun readerFont(settings: UiSettings): FontFamily {
    val assets = LocalContext.current.assets
    return remember(assets, settings.font, settings.fontPath) { readingFontFamily(assets, settings.font, settings.fontPath) }
}

private fun readingFontFamily(assets: AssetManager, id: String, customPath: String?): FontFamily {
    when (id) {
        "sans" -> return FontFamily.SansSerif
        "serif" -> return FontFamily.Serif
        "mono" -> return FontFamily.Monospace
        "custom" -> customPath?.let { path ->
            File(path).takeIf { it.isFile }?.let { file ->
                runCatching { FontFamily(Font(file)) }.getOrNull()?.let { return it }
            }
        }
    }
    val font = ReadingFonts.find(id) ?: ReadingFonts.families.first { it.id == ReadingFonts.DEFAULT_ID }
    return FontFamily(Font(path = font.assetPath, assetManager = assets))
}
