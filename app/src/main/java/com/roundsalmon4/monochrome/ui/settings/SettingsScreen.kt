package com.roundsalmon4.monochrome.ui.settings

import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ExitToApp
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LargeTopAppBar
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.roundsalmon4.monochrome.core.api.internal.MonochromeSessionStatus
import com.roundsalmon4.monochrome.core.datastore.PreferencesUiState
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(viewModel: SettingsViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val showClearHistoryDialog by viewModel.showClearHistoryDialog.collectAsState()
    val showClearPlaylistsDialog by viewModel.showClearPlaylistsDialog.collectAsState()
    val exportResult by viewModel.exportResult.collectAsState()
    val importResult by viewModel.importResult.collectAsState()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    Scaffold(
        topBar = {
            LargeTopAppBar(title = { Text("Settings") }, scrollBehavior = scrollBehavior)
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection)
                .verticalScroll(rememberScrollState())
        ) {
            PlayerSection(uiState, viewModel)
            MonochromeSection(viewModel)
            AmazonSection(viewModel)
            AppearanceSection(uiState, viewModel)
            DataSection(viewModel, exportResult, importResult)
            AboutSection()

            val context = LocalContext.current
            val versionName = remember {
                try {
                    context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: ""
                } catch (_: Exception) { "" }
            }
            Box(modifier = Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.BottomEnd) {
                Text("v$versionName", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f))
            }
        }
    }

    if (showClearHistoryDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissClearHistoryDialog() },
            title = { Text("Clear History") },
            text = { Text("Are you sure you want to clear your listening history? This cannot be undone.") },
            confirmButton = { TextButton(onClick = { viewModel.clearHistory() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { viewModel.dismissClearHistoryDialog() }) { Text("Cancel") } }
        )
    }

    if (showClearPlaylistsDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissClearPlaylistsDialog() },
            title = { Text("Clear Playlists") },
            text = { Text("Are you sure you want to delete all playlists? This cannot be undone.") },
            confirmButton = { TextButton(onClick = { viewModel.clearPlaylists() }) { Text("Clear") } },
            dismissButton = { TextButton(onClick = { viewModel.dismissClearPlaylistsDialog() }) { Text("Cancel") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PlayerSection(uiState: PreferencesUiState, viewModel: SettingsViewModel) {
    Column {
        SettingsCategory("Player")

        var speedExpanded by remember { mutableStateOf(false) }
        val speeds = listOf("0.25x", "0.5x", "0.75x", "1.0x", "1.25x", "1.5x", "1.75x", "2.0x", "2.5x", "3.0x")
        val currentSpeed = "${uiState.playbackSpeed}x"

        ExposedDropdownMenuBox(expanded = speedExpanded, onExpandedChange = { speedExpanded = it }) {
            OutlinedTextField(
                value = currentSpeed, onValueChange = {}, readOnly = true,
                label = { Text("Default Speed") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).menuAnchor(MenuAnchorType.PrimaryNotEditable),
                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = speedExpanded) }
            )
            ExposedDropdownMenu(expanded = speedExpanded, onDismissRequest = { speedExpanded = false }) {
                speeds.forEach { speed ->
                    DropdownMenuItem(
                        text = { Text(speed) },
                        onClick = { viewModel.setPlaybackSpeed(speed.removeSuffix("x").toFloat()); speedExpanded = false }
                    )
                }
            }
        }

        SwitchItem(
            name = "Mini Player",
            description = "Show mini player bar when playback continues in background",
            checked = uiState.showMiniPlayer,
            onCheckedChange = { viewModel.setShowMiniPlayer(it) }
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            SwitchItem(
                name = "Picture-in-Picture",
                description = "Auto-enter PiP when leaving the player screen",
                checked = uiState.pipEnabled,
                onCheckedChange = { viewModel.setPiPEnabled(it) }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceSection(uiState: PreferencesUiState, viewModel: SettingsViewModel) {
    Column {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SettingsCategory("Appearance")

        val themeModeOptions = listOf("Follow System" to "SYSTEM", "Light" to "LIGHT", "Dark" to "DARK")
        val selectedThemeIndex = themeModeOptions.indexOfFirst { it.second == uiState.themeMode }.coerceAtLeast(0)

        Text("Theme Mode", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
        SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
            themeModeOptions.forEachIndexed { index, (label, _) ->
                SegmentedButton(
                    selected = selectedThemeIndex == index,
                    onClick = { viewModel.setThemeMode(themeModeOptions[index].second) },
                    shape = SegmentedButtonDefaults.itemShape(index = index, count = themeModeOptions.size),
                    icon = {
                        Icon(
                            imageVector = when (themeModeOptions[index].second) {
                                "LIGHT" -> Icons.Filled.LightMode
                                "DARK" -> Icons.Filled.DarkMode
                                else -> Icons.Filled.BrightnessAuto
                            },
                            contentDescription = null
                        )
                    }
                ) { Text(label) }
            }
        }

        val isDarkModeActive = uiState.themeMode == "DARK" || (uiState.themeMode == "SYSTEM" && isSystemInDarkTheme())
        if (isDarkModeActive) {
            SwitchItem(
                name = "AMOLED Dark",
                description = "Pure black background for OLED screens",
                checked = uiState.useAmoledTheme,
                onCheckedChange = { viewModel.setUseAmoledTheme(it) }
            )
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val colorSchemeOptions = listOf("Standard" to "STANDARD", "Dynamic Color" to "DYNAMIC_COLOR")
            val selectedColorSchemeIndex = colorSchemeOptions.indexOfFirst { it.second == uiState.colorSchemeMode }.coerceAtLeast(0)
            Text("Color Scheme", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                colorSchemeOptions.forEachIndexed { index, (label, _) ->
                    SegmentedButton(
                        selected = selectedColorSchemeIndex == index,
                        onClick = { viewModel.setColorSchemeMode(colorSchemeOptions[index].second) },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = colorSchemeOptions.size)
                    ) { Text(label) }
                }
            }
        }

        if (uiState.colorSchemeMode == "STANDARD") {
            val presetColors = listOf(
                0xFFFF0000.toInt() to "Red", 0xFF1A237E.toInt() to "Blue",
                0xFF1B5E20.toInt() to "Green", 0xFF4A148C.toInt() to "Purple",
                0xFF006064.toInt() to "Teal", 0xFFE65100.toInt() to "Orange",
                0xFF880E4F.toInt() to "Pink", 0xFF0D47A1.toInt() to "Indigo",
                0xFF33691E.toInt() to "Olive", 0xFFBF360C.toInt() to "Deep Orange",
                0xFF311B92.toInt() to "Deep Purple", 0xFF004D40.toInt() to "Dark Teal"
            )

            var showColorDialog by remember { mutableStateOf(false) }
            ListItem(
                headlineContent = { Text("Primary Color", fontWeight = FontWeight.SemiBold) },
                leadingContent = { Box(modifier = Modifier.size(40.dp).clip(CircleShape).background(Color(uiState.primaryColor))) },
                modifier = Modifier.clickable { showColorDialog = true }
            )

            if (showColorDialog) {
                AlertDialog(
                    onDismissRequest = { showColorDialog = false },
                    title = { Text("Select Color") },
                    text = {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(4), contentPadding = PaddingValues(4.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(presetColors) { (colorInt, name) ->
                                val isSelected = colorInt == uiState.primaryColor
                                Box(
                                    modifier = Modifier.size(48.dp).clip(CircleShape).background(Color(colorInt)).clickable {
                                        viewModel.setPrimaryColor(colorInt); showColorDialog = false
                                    },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSelected) {
                                        Icon(Icons.Filled.Check, contentDescription = name,
                                            tint = if (Color(colorInt).luminance() > 0.5f) Color.Black else Color.White)
                                    }
                                }
                            }
                        }
                    },
                    confirmButton = { TextButton(onClick = { showColorDialog = false }) { Text("Cancel") } }
                )
            }
        }
    }
}

private fun Color.luminance(): Float = 0.299f * red + 0.587f * green + 0.114f * blue

@Composable
private fun DataSection(viewModel: SettingsViewModel, exportResult: String?, importResult: String?) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val exportLauncher = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("application/json")) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = viewModel.buildExportJson()
                    context.contentResolver.openOutputStream(uri)?.use { it.write(json.toByteArray()) }
                    Toast.makeText(context, "Export complete", Toast.LENGTH_SHORT).show()
                } catch (e: Exception) {
                    Toast.makeText(context, "Export failed: ${e.message?.take(100)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            scope.launch {
                try {
                    val json = context.contentResolver.openInputStream(uri)?.bufferedReader()?.readText()
                    if (json != null) viewModel.importFromJson(json)
                } catch (e: Exception) {
                    Toast.makeText(context, "Import failed: ${e.message?.take(100)}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    Column {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SettingsCategory("Data")

        if (importResult != null) {
            LaunchedEffect(importResult) {
                Toast.makeText(context, importResult, Toast.LENGTH_SHORT).show()
                viewModel.clearImportResult()
            }
        }

        ListItem(
            modifier = Modifier.clickable { exportLauncher.launch("ChromePlayer_backup.json") },
            headlineContent = { Text("Export Data", fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text("Save settings, playlists, and subscriptions to a JSON file") }
        )

        ListItem(
            modifier = Modifier.clickable { importLauncher.launch(arrayOf("application/json", "*/*")) },
            headlineContent = { Text("Import Data", fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text("Restore settings, playlists, and subscriptions from a JSON file") }
        )

        ListItem(
            modifier = Modifier.clickable { viewModel.clearHistory() },
            headlineContent = { Text("Clear History", fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text("Remove all listening history") }
        )

        ListItem(
            modifier = Modifier.clickable { viewModel.clearCache() },
            headlineContent = { Text("Clear Cache", fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text("Clear image cache and temporary files") }
        )

        ListItem(
            modifier = Modifier.clickable { viewModel.showClearPlaylistsDialog() },
            headlineContent = { Text("Clear Playlists", fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text("Delete all playlists and their tracks") }
        )
    }
}

@Composable
private fun AboutSection() {
    val uriHandler = LocalUriHandler.current

    Column {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SettingsCategory("About")

        ListItem(
            modifier = Modifier.clickable { runCatching { uriHandler.openUri("https://github.com/RoundSalmon4/ChromePlayer") } },
            headlineContent = { Text("Source Code", fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text("View the project on GitHub") },
            trailingContent = { Icon(Icons.AutoMirrored.Filled.ExitToApp, contentDescription = null) }
        )
    }
}

@Composable
private fun MonochromeSection(viewModel: SettingsViewModel) {
    val monochromeStatus by viewModel.monochromeStatus.collectAsState()
    val monochromeRefreshing by viewModel.monochromeRefreshing.collectAsState()
    var jwtInput by remember { mutableStateOf("") }
    var showManualEntry by remember { mutableStateOf(false) }

    Column {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SettingsCategory("Monochrome Playback")

        val status = monochromeStatus
        val statusText = when (status) {
            is MonochromeSessionStatus.Valid -> "Session active"
            is MonochromeSessionStatus.Expired -> "Session expired"
            is MonochromeSessionStatus.Refreshing -> "Refreshing session..."
            is MonochromeSessionStatus.Failed -> "Failed: ${status.error}"
            is MonochromeSessionStatus.Unknown -> "No session yet"
        }
        ListItem(
            headlineContent = { Text("Session status", fontWeight = FontWeight.SemiBold) },
            supportingContent = { Text(statusText) }
        )

        TextButton(
            enabled = !monochromeRefreshing,
            onClick = { viewModel.refreshMonochromeSession() }
        ) {
            Text(if (monochromeRefreshing) "Refreshing..." else "Refresh token now")
        }

        TextButton(
            onClick = { showManualEntry = !showManualEntry }
        ) {
            Text(if (showManualEntry) "Hide manual entry" else "Paste token manually")
        }

        if (showManualEntry) {
            if (jwtInput.isNotEmpty()) {
                TextButton(onClick = { viewModel.setMonochromeJwt(jwtInput.trim()); jwtInput = "" }) {
                    Text("Save token")
                }
            }
            OutlinedTextField(
                value = jwtInput,
                onValueChange = { jwtInput = it },
                label = { Text("Monochrome Playback token (eyJ...)") },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                singleLine = true
            )
        }

        Text("Playback uses the in-house lossless source. The token auto-refreshes every ~45 minutes; open https://monochrome.tf in your phone browser if manual entry is needed.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AmazonSection(viewModel: SettingsViewModel) {
    var jwtInput by remember { mutableStateOf("") }
    Column {
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
        SettingsCategory("Amazon Music Streaming")

        if (jwtInput.isNotEmpty()) {
            TextButton(onClick = { viewModel.setAmazonJwt(jwtInput.trim()); jwtInput = "" }) {
                Text("Save JWT")
            }
        }

        OutlinedTextField(
            value = jwtInput,
            onValueChange = { jwtInput = it },
            label = { Text("Amazon JWT (from browser localStorage)") },
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
            singleLine = true
        )

        Text("Also check localStorage for amazon-music-turnstile-bypass-token",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp))
    }
}

@Composable
private fun SettingsCategory(title: String) {
    Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
}

@Composable
private fun SwitchItem(name: String, description: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    ListItem(
        modifier = Modifier.clickable { onCheckedChange(!checked) },
        headlineContent = { Text(name, fontWeight = FontWeight.SemiBold) },
        supportingContent = { Text(description) },
        trailingContent = { Switch(checked = checked, onCheckedChange = null) }
    )
}
