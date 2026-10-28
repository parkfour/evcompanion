package com.evcompanion.phone.ui

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.evcompanion.core.debug.DebugFileLogger
import com.evcompanion.phone.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun PresetPickerScreen(viewModel: MainViewModel) {
    val presets by viewModel.presets.collectAsState()
    val activeId by viewModel.activePresetId.collectAsState()
    val dashboardOverridden by viewModel.dashboardConfigOverridden.collectAsState()
    val dashboardWarnings by viewModel.dashboardConfigWarnings.collectAsState()
    val presetImportError by viewModel.presetImportError.collectAsState()
    val debugEnabled by viewModel.debugFeaturesEnabled.collectAsState()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val pickDashboardJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        // The activity-result callback runs on the main thread - read off it (a content:// URI
        // can mean real, possibly slow I/O, e.g. a cloud-backed document provider) rather than
        // blocking the UI thread here.
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } ?: return@launch
            viewModel.importDashboardConfig(text)
        }
    }

    val pickPidCsv = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val (name, text) = withContext(Dispatchers.IO) {
                val name = context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                    ?.use { if (it.moveToFirst()) it.getString(0) else null }
                    ?.substringBeforeLast('.')
                    ?: "imported"
                name to context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            }
            text ?: return@launch
            viewModel.importPreset(name, text)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Vehicle preset") }) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            items(presets) { preset ->
                ListItem(
                    headlineContent = { Text(preset.displayName) },
                    supportingContent = { Text("${preset.rows.size} PIDs · ${preset.queryGroups.size} queries/cycle") },
                    leadingContent = {
                        RadioButton(
                            selected = preset.id == activeId,
                            onClick = { viewModel.selectPreset(preset) },
                        )
                    },
                    trailingContent = if (preset.id.startsWith("user_")) {
                        { TextButton(onClick = { viewModel.deleteUserPreset(preset) }) { Text("Delete") } }
                    } else {
                        null
                    },
                )
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    // Torque-format custom-PID CSV - lets someone with a different car bring their
                    // own PIDs. Saved and selected until another preset is chosen.
                    OutlinedButton(onClick = { pickPidCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) }) {
                        Text("Import PID file (CSV)")
                    }
                    presetImportError?.let { Text(it, style = androidx.compose.material3.MaterialTheme.typography.bodySmall) }
                }
            }
            item {
                Text(
                    "Android Auto Display Theme",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                val currentThemeId by viewModel.activeThemeId.collectAsState()
                Column(modifier = Modifier.padding(horizontal = 8.dp)) {
                    com.evcompanion.core.theme.DashboardThemeRegistry.allThemes.forEach { theme ->
                        ListItem(
                            headlineContent = { Text(theme.displayName) },
                            supportingContent = {
                                Text(
                                    when (theme.id) {
                                        com.evcompanion.core.theme.DashboardThemeId.DEFAULT -> "Classic dark card grid with standard circular dials"
                                        com.evcompanion.core.theme.DashboardThemeId.IONIQ5_COMPANION -> "CarPlay style with luminous cyan cards and swept dials"
                                    }
                                )
                            },
                            leadingContent = {
                                RadioButton(
                                    selected = theme.id == currentThemeId,
                                    onClick = { viewModel.selectTheme(theme.id) },
                                )
                            },
                        )
                    }
                }
            }
            item {
                Text(
                    "Dashboard layout",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (dashboardOverridden) {
                            "Using your imported dashboard layout."
                        } else {
                            "Using the bundled default dashboard layout."
                        },
                    )
                    OutlinedButton(onClick = { pickDashboardJson.launch(arrayOf("application/json", "text/plain")) }) {
                        Text("Import dashboard JSON")
                    }
                    if (dashboardOverridden) {
                        OutlinedButton(onClick = { viewModel.resetDashboardConfig() }) {
                            Text("Reset to default")
                        }
                    }
                    dashboardWarnings.forEach { warning ->
                        Text("Warning: $warning", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Text(
                    "Debugging",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Enable debugging features") },
                    supportingContent = { Text("Captures OBD traffic to a log file and shows the share and replay tools.") },
                    trailingContent = { Switch(checked = debugEnabled, onCheckedChange = viewModel::setDebugFeaturesEnabled) },
                )
            }
            if (debugEnabled) item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    OutlinedButton(
                        onClick = {
                            val file = DebugFileLogger.currentLogFile() ?: return@OutlinedButton
                            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
                            val intent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_STREAM, uri)
                                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                            }
                            context.startActivity(Intent.createChooser(intent, "Share debug log"))
                        },
                    ) { Text("Share debug log") }
                }
            }
        }
    }
}
