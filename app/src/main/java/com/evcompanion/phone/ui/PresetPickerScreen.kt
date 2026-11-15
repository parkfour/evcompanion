package com.evcompanion.phone.ui

import android.content.Intent
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
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
    val autoScanDtc by viewModel.autoScanDtcOnHero.collectAsState()
    val unitPrefs by viewModel.unitPreferences.collectAsState()
    var unitsExpanded by remember { mutableStateOf(false) }
    val vehicleProfileName by viewModel.vehicleProfileName.collectAsState()
    val vehicleProfileImported by viewModel.vehicleProfileImported.collectAsState()
    val vehicleProfileWarnings by viewModel.vehicleProfileWarnings.collectAsState()
    val socThreshold by viewModel.socFastChargeThreshold.collectAsState()
    val minPowerKw by viewModel.minFastChargePowerKw.collectAsState()
    val context = LocalContext.current
    val uriHandler = LocalUriHandler.current
    val scope = rememberCoroutineScope()
    var showFeedbackDialog by remember { mutableStateOf(false) }

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

    val pickVehicleJson = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val text = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } ?: return@launch
            viewModel.importVehicleProfile(text)
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

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Vehicle preset") },
                actions = {
                    TextButton(onClick = { showFeedbackDialog = true }) {
                        Text("Feedback")
                    }
                }
            )
        }
    ) { padding ->
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { pickPidCsv.launch(arrayOf("text/csv", "text/comma-separated-values", "text/plain")) }) {
                            Text("Import PID file (CSV)")
                        }
                        TextButton(onClick = { uriHandler.openUri("https://github.com/parkfour/evcompanion/blob/main/PID_CONFIG.md") }) {
                            Text("(how to)")
                        }
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
                                        com.evcompanion.core.theme.DashboardThemeId.EV_DASHBOARD ->
                                            "Luminous CarPlay style with smooth curved graphs, gradient glow, and illuminated dials"
                                        com.evcompanion.core.theme.DashboardThemeId.IONIQ5_COMPANION ->
                                            "Minimalist flat slate cards with cyan accents and swept dials"
                                        com.evcompanion.core.theme.DashboardThemeId.CLASSIC_DARK ->
                                            "Minimalist flat dark cards with standard circular dials"
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
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { pickDashboardJson.launch(arrayOf("application/json", "text/plain")) }) {
                            Text("Import dashboard JSON")
                        }
                        TextButton(onClick = { uriHandler.openUri("https://github.com/parkfour/evcompanion/blob/main/DASHBOARD_CONFIG.md") }) {
                            Text("(how to)")
                        }
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
                    "Vehicle profile",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                LaunchedEffect(Unit) { viewModel.refreshVehicleProfileState() }
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        if (vehicleProfileImported) "Using your imported profile: $vehicleProfileName"
                        else "Using the bundled profile: $vehicleProfileName",
                    )
                    Text(
                        "Car-specific behaviour beyond the PIDs: which PID is speed/odometer/pack current, " +
                            "ECU polling limits, charging detection. Applies to the selected preset.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        OutlinedButton(onClick = { pickVehicleJson.launch(arrayOf("application/json", "text/plain")) }) {
                            Text("Import vehicle profile JSON")
                        }
                        TextButton(onClick = { uriHandler.openUri("https://github.com/parkfour/evcompanion/blob/main/VEHICLE_CONFIG.md") }) {
                            Text("(how to)")
                        }
                    }
                    if (vehicleProfileImported) {
                        OutlinedButton(onClick = { viewModel.resetVehicleProfile() }) {
                            Text("Reset to default")
                        }
                    }
                    vehicleProfileWarnings.forEach { warning ->
                        Text("Warning: $warning", style = androidx.compose.material3.MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item {
                Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)) {
                    ListItem(
                        headlineContent = { Text("Units") },
                        supportingContent = {
                            val summary = when {
                                unitPrefs.isMetric -> "Metric (km, °C, kPa, kWh/100km)"
                                unitPrefs.isImperial -> "Imperial (mi, °F, psi, mi/kWh)"
                                else -> "Custom (${unitPrefs.distance.label}, ${unitPrefs.temperature.label}, ${unitPrefs.pressure.label}, ${unitPrefs.efficiency.label})"
                            }
                            Text(summary)
                        },
                        trailingContent = {
                            Switch(
                                checked = unitPrefs.isMetric,
                                onCheckedChange = { metric ->
                                    viewModel.setUnitSystem(
                                        if (metric) com.evcompanion.core.units.UnitSystem.METRIC else com.evcompanion.core.units.UnitSystem.IMPERIAL,
                                    )
                                },
                            )
                        },
                        modifier = Modifier.clickable { unitsExpanded = !unitsExpanded },
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = if (unitsExpanded) "Hide individual unit selections" else "Customize individual units...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.clickable { unitsExpanded = !unitsExpanded }.padding(vertical = 4.dp),
                        )
                    }

                    if (unitsExpanded) {
                        Card(
                            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                            ),
                        ) {
                            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                // Distance
                                Column {
                                    Text("Distance & Speed", style = MaterialTheme.typography.labelMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                        com.evcompanion.core.units.DistanceUnit.values().forEach { dist ->
                                            FilterChip(
                                                selected = unitPrefs.distance == dist,
                                                onClick = { viewModel.setDistanceUnit(dist) },
                                                label = {
                                                    Text(if (dist == com.evcompanion.core.units.DistanceUnit.MILES) "Miles (mi, mph)" else "Kilometers (km, km/h)")
                                                },
                                            )
                                        }
                                    }
                                }

                                // Temperature
                                Column {
                                    Text("Temperature", style = MaterialTheme.typography.labelMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                        com.evcompanion.core.units.TemperatureUnit.values().forEach { temp ->
                                            FilterChip(
                                                selected = unitPrefs.temperature == temp,
                                                onClick = { viewModel.setTemperatureUnit(temp) },
                                                label = {
                                                    Text(if (temp == com.evcompanion.core.units.TemperatureUnit.FAHRENHEIT) "Fahrenheit (°F)" else "Celsius (°C)")
                                                },
                                            )
                                        }
                                    }
                                }

                                // Pressure
                                Column {
                                    Text("Tire Pressure", style = MaterialTheme.typography.labelMedium)
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                                        com.evcompanion.core.units.PressureUnit.values().forEach { press ->
                                            FilterChip(
                                                selected = unitPrefs.pressure == press,
                                                onClick = { viewModel.setPressureUnit(press) },
                                                label = { Text(press.label) },
                                            )
                                        }
                                    }
                                }

                                // Efficiency
                                Column {
                                    Text("Efficiency Metric", style = MaterialTheme.typography.labelMedium)
                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                                        modifier = Modifier.padding(top = 4.dp).horizontalScroll(rememberScrollState()),
                                    ) {
                                        com.evcompanion.core.units.EfficiencyUnit.values().forEach { eff ->
                                            FilterChip(
                                                selected = unitPrefs.efficiency == eff,
                                                onClick = { viewModel.setEfficiencyUnit(eff) },
                                                label = { Text(eff.label) },
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
            item {
                Text(
                    "Diagnostics",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                ListItem(
                    headlineContent = { Text("Auto-scan DTCs on hero promotion") },
                    supportingContent = { Text("Automatically scans EV modules when the Diagnostics tile is promoted to hero.") },
                    trailingContent = { Switch(checked = autoScanDtc, onCheckedChange = viewModel::setAutoScanDtcOnHero) },
                )
            }
            item {
                Text(
                    "Charging & Navigation",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "When State of Charge is promoted to hero on Android Auto, EV Companion displays nearby charging stations. When battery SoC falls below the threshold, it automatically filters to high-power DC fast chargers. Tapping a station opens it in your navigation app (Google Maps or Waze). Charger data comes from OpenStreetMap; your location is sent to its public Overpass API to find nearby stations.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                    ListItem(
                        headlineContent = { Text("Low-SoC filter threshold: $socThreshold%") },
                        supportingContent = { Text("Filters to fast chargers when battery is below this level") },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = { if (socThreshold > 10) viewModel.setSocFastChargeThreshold(socThreshold - 5) },
                                    enabled = socThreshold > 10
                                ) { Text("-5%") }
                                OutlinedButton(
                                    onClick = { if (socThreshold < 60) viewModel.setSocFastChargeThreshold(socThreshold + 5) },
                                    enabled = socThreshold < 60
                                ) { Text("+5%") }
                            }
                        }
                    )
                    ListItem(
                        headlineContent = { Text("Minimum fast charger power: $minPowerKw kW") },
                        supportingContent = { Text("Only chargers offering at least this power are shown during low SoC") },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                OutlinedButton(
                                    onClick = { if (minPowerKw > 50) viewModel.setMinFastChargePowerKw(minPowerKw - 25) },
                                    enabled = minPowerKw > 50
                                ) { Text("-25") }
                                OutlinedButton(
                                    onClick = { if (minPowerKw < 350) viewModel.setMinFastChargePowerKw(minPowerKw + 25) },
                                    enabled = minPowerKw < 350
                                ) { Text("+25") }
                            }
                        }
                    )
                }
            }
            item {
                Text(
                    "Support & Feedback",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            item {
                Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Submit feedback, report PID formula issues, or share configuration bundles with developers.",
                        style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    )
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedButton(onClick = { showFeedbackDialog = true }) {
                            Text("Submit diagnostic bundle")
                        }
                        TextButton(onClick = { viewModel.openPlayStoreListing() }) {
                            Text("Play Store feedback")
                        }
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
            item {
                Text(
                    "EV Companion v${com.evcompanion.BuildConfig.VERSION_NAME} (${com.evcompanion.BuildConfig.VERSION_CODE})",
                    style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 16.dp),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }

    if (showFeedbackDialog) {
        FeedbackDialog(
            viewModel = viewModel,
            onDismiss = { showFeedbackDialog = false }
        )
    }
}
