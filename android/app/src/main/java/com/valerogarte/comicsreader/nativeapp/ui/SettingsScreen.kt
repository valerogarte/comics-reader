package com.valerogarte.comicsreader.nativeapp.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.clickable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.NavigateNext
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.valerogarte.comicsreader.R
import java.util.UUID

private enum class SettingsSection(val id: String, val title: Int, val summary: Int, val icon: ImageVector) {
    APPEARANCE("appearance", R.string.native_appearance, R.string.native_settings_appearance_summary, Icons.Default.Palette),
    TYPOGRAPHY("typography", R.string.native_font, R.string.native_settings_typography_summary, Icons.Default.TextFields),
    READING("reading", R.string.native_settings_reading, R.string.native_settings_reading_summary, Icons.AutoMirrored.Filled.MenuBook),
    SFTP("sftp", R.string.native_sftp_servers, R.string.native_settings_sftp_summary, Icons.Default.Storage),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun SettingsScreen(settings: UiSettings, actions: AppActions) {
    var section by rememberSaveable { mutableStateOf<SettingsSection?>(null) }
    val sectionStates = rememberSaveableStateHolder()
    var editingServer by remember { mutableStateOf<UiSftpServer?>(null) }
    var deletingServer by remember { mutableStateOf<UiSftpServer?>(null) }
    val validServers = settings.sftpServers.all(::validSftpServer)
    val goBack = { if (section == null) actions.onCloseSettings() else section = null }
    Dialog(onDismissRequest = goBack, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize()) {
            Scaffold(
                topBar = { TopAppBar(
                    title = { Text(stringResource(section?.title ?: R.string.native_settings)) },
                    navigationIcon = {
                        if (section == null) {
                            IconButton(onClick = actions::onCloseSettings) { Icon(Icons.Default.Close, stringResource(R.string.native_close)) }
                        } else {
                            IconButton(onClick = goBack, modifier = Modifier.testTag("settings_back")) {
                                Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.native_settings_back))
                            }
                        }
                    },
                    actions = {
                        if (section != null) IconButton(onClick = actions::onCloseSettings) {
                            Icon(Icons.Default.Close, stringResource(R.string.native_close))
                        }
                    },
                ) },
            ) { insets ->
                Box(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets).imePadding(), contentAlignment = Alignment.TopCenter) {
                    sectionStates.SaveableStateProvider(section?.id ?: "menu") {
                        Column(
                            Modifier.widthIn(max = 720.dp).fillMaxWidth().fillMaxHeight().testTag("settings_scroll")
                                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp, vertical = 16.dp),
                            verticalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            when (section) {
                                null -> SettingsSection.entries.forEach { destination ->
                                    OutlinedCard(onClick = { section = destination }, modifier = Modifier.fillMaxWidth().testTag("settings_section:${destination.id}")) {
                                        Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                                            Icon(destination.icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
                                            Column(Modifier.weight(1f).padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Text(stringResource(destination.title), style = MaterialTheme.typography.titleMedium)
                                                Text(stringResource(destination.summary), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                            }
                                            Icon(Icons.AutoMirrored.Filled.NavigateNext, null)
                                        }
                                    }
                                }
                                SettingsSection.APPEARANCE -> {
                                    ChoiceRow(stringResource(R.string.native_theme), settings.theme,
                                        listOf("system" to stringResource(R.string.native_system), "light" to stringResource(R.string.native_light), "dark" to stringResource(R.string.native_dark))) { value -> actions.onUpdateAppearanceSettings { it.copy(theme = value) } }
                                    ChoiceRow(stringResource(R.string.native_background), settings.background,
                                        listOf("cream" to stringResource(R.string.native_cream), "white" to stringResource(R.string.native_white), "sepia" to stringResource(R.string.native_sepia))) { value -> actions.onUpdateAppearanceSettings { it.copy(background = value) } }
                                    OutlinedButton(onClick = actions::onPickBackground) { Text(stringResource(R.string.native_pick_background)) }
                                    if (settings.backgroundImagePath != null) {
                                        TextButton(onClick = { actions.onUpdateAppearanceSettings { it.copy(backgroundImagePath = null) } }) { Text(stringResource(R.string.native_remove_background)) }
                                    }
                                }
                                SettingsSection.TYPOGRAPHY -> {
                                    ReadingFontSelector(settings) { value -> actions.onUpdateAppearanceSettings { it.copy(font = value) } }
                                    OutlinedButton(onClick = actions::onImportFont) { Text(stringResource(R.string.native_import_font)) }
                                    Column {
                                        Text(stringResource(R.string.native_font_size, settings.fontSize.toInt()), style = MaterialTheme.typography.bodyMedium)
                                        Slider(value = settings.fontSize.coerceIn(12f, 32f), onValueChange = { value -> actions.onUpdateAppearanceSettings { it.copy(fontSize = value) } }, valueRange = 12f..32f, steps = 19)
                                    }
                                }
                                SettingsSection.READING -> {
                                    SettingSwitch(stringResource(R.string.native_keep_screen_on), settings.keepScreenOn) { value -> actions.onUpdateAppearanceSettings { it.copy(keepScreenOn = value) } }
                                    SettingSwitch(stringResource(R.string.native_right_to_left), settings.rightToLeft) { value -> actions.onUpdateAppearanceSettings { it.copy(rightToLeft = value) } }
                                }
                                SettingsSection.SFTP -> {
                                    Text(stringResource(R.string.native_sftp_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    if (!validServers) Text(stringResource(R.string.native_invalid_servers), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                                    if (settings.sftpServers.isEmpty()) Text(stringResource(R.string.native_no_sftp_servers), color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    settings.sftpServers.forEach { server ->
                                        OutlinedCard(Modifier.fillMaxWidth().testTag("server_settings:${server.id}")) {
                                            Row(Modifier.fillMaxWidth().clickable { editingServer = server }.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                                Icon(Icons.Default.Storage, null, tint = MaterialTheme.colorScheme.primary)
                                                Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                                                    Text(server.name, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                                    Text("${server.host}:${server.port}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                    Text(server.rootPath, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                                }
                                                IconButton(onClick = { editingServer = server }, modifier = Modifier.testTag("edit_server:${server.id}")) { Icon(Icons.Default.Edit, stringResource(R.string.native_edit_sftp_server)) }
                                                IconButton(onClick = { deletingServer = server }, modifier = Modifier.testTag("delete_server:${server.id}")) { Icon(Icons.Default.DeleteOutline, stringResource(R.string.native_delete_sftp_server)) }
                                            }
                                        }
                                    }
                                    OutlinedButton(modifier = Modifier.testTag("add_server"), onClick = {
                                        editingServer = UiSftpServer(UUID.randomUUID().toString(), "", "", username = "", password = "")
                                    }) { Icon(Icons.Default.Add, null, Modifier.size(18.dp)); Spacer(Modifier.width(8.dp)); Text(stringResource(R.string.native_add_sftp_server)) }
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }
                    }
                }
            }
        }
    }
    editingServer?.let { initial ->
        SftpServerEditor(initial,
            onDismiss = { editingServer = null },
            onApply = { server ->
                actions.onUpdateSftpServers { servers ->
                    if (servers.any { it.id == server.id }) servers.map { if (it.id == server.id) server else it }
                    else servers + server
                }
                editingServer = null
            },
        )
    }
    deletingServer?.let { server ->
        AlertDialog(
            onDismissRequest = { deletingServer = null },
            title = { Text(stringResource(R.string.native_delete_sftp_server)) },
            text = { Text(stringResource(R.string.native_delete_sftp_server_message, server.name)) },
            confirmButton = { TextButton(onClick = {
                actions.onUpdateSftpServers { servers -> servers.filterNot { it.id == server.id } }
                deletingServer = null
            }) { Text(stringResource(R.string.native_delete), color = MaterialTheme.colorScheme.error) } },
            dismissButton = { TextButton(onClick = { deletingServer = null }) { Text(stringResource(R.string.native_cancel)) } },
        )
    }
}

@Composable
private fun SftpServerEditor(initial: UiSftpServer, onDismiss: () -> Unit, onApply: (UiSftpServer) -> Unit) {
    var server by remember(initial.id) { mutableStateOf(initial) }
    var revealPassword by remember { mutableStateOf(false) }
    val portValid = server.port.toIntOrNull()?.let { it in 1..65535 } == true
    val rootValid = server.rootPath.trim().startsWith('/') && !server.rootPath.contains('\u0000')
    val hostValid = validSftpHost(server.host)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.native_sftp_server)) },
        text = {
            Column(Modifier.fillMaxWidth().testTag("server_editor_scroll").verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(server.name, onValueChange = { server = server.copy(name = it) }, label = { Text(stringResource(R.string.native_server_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = server.name.isBlank(), supportingText = { if (server.name.isBlank()) Text(stringResource(R.string.native_required)) })
                OutlinedTextField(server.host, onValueChange = { server = server.copy(host = it) }, label = { Text(stringResource(R.string.native_host)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), isError = !hostValid,
                    supportingText = { if (!hostValid) Text(stringResource(if (server.host.isBlank()) R.string.native_required else R.string.native_invalid_host)) })
                OutlinedTextField(server.port, onValueChange = { if (it.length <= 5 && it.all(Char::isDigit)) server = server.copy(port = it) }, label = { Text(stringResource(R.string.native_port)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), isError = !portValid,
                    supportingText = { if (!portValid) Text(stringResource(R.string.native_invalid_port)) })
                OutlinedTextField(server.username, onValueChange = { server = server.copy(username = it) }, label = { Text(stringResource(R.string.native_username)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = server.username.isBlank(), supportingText = { if (server.username.isBlank()) Text(stringResource(R.string.native_required)) })
                OutlinedTextField(server.password, onValueChange = { server = server.copy(password = it) }, label = { Text(stringResource(R.string.native_password)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    visualTransformation = if (revealPassword) VisualTransformation.None else PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    trailingIcon = { IconButton(onClick = { revealPassword = !revealPassword }) {
                        Icon(if (revealPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, stringResource(if (revealPassword) R.string.native_hide_password else R.string.native_show_password))
                    } })
                OutlinedTextField(server.rootPath, onValueChange = { server = server.copy(rootPath = it) }, label = { Text(stringResource(R.string.native_root_folder)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    isError = !rootValid, supportingText = { if (!rootValid) Text(stringResource(R.string.native_invalid_root)) })
            }
        },
        confirmButton = { TextButton(modifier = Modifier.testTag("apply_server"), enabled = validSftpServer(server), onClick = {
            onApply(server.copy(name = server.name.trim(), host = server.host.trim(), port = server.port.toInt().toString(), username = server.username.trim(), rootPath = server.rootPath.trim()))
        }) { Text(stringResource(R.string.native_apply)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.native_cancel)) } },
    )
}

private fun validSftpHost(host: String): Boolean = host.isNotBlank() && host.trim().none { it.isWhitespace() || it == '/' || it == '\\' || it == '\u0000' }

private fun validSftpServer(server: UiSftpServer): Boolean = server.name.isNotBlank() && validSftpHost(server.host) &&
    server.username.isNotBlank() && !server.username.contains('\u0000') &&
    server.port.toIntOrNull()?.let { it in 1..65535 } == true &&
    server.rootPath.trim().startsWith('/') && !server.rootPath.contains('\u0000')

@Composable
internal fun ChoiceRow(label: String, value: String, choices: List<Pair<String, String>>, onChange: (String) -> Unit) {
    Column {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            choices.forEach { (key, title) -> FilterChip(selected = value == key, onClick = { onChange(key) }, label = { Text(title) }) }
        }
    }
}

@Composable
internal fun SettingSwitch(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 56.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange), verticalAlignment = Alignment.CenterVertically) {
        Text(label, modifier = Modifier.weight(1f).padding(end = 12.dp), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange = null)
    }
}
