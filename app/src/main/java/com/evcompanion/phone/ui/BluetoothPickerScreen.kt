package com.evcompanion.phone.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.evcompanion.core.bluetooth.BondedDevice
import com.evcompanion.core.debug.DebugFileLogger
import com.evcompanion.core.obd.ObdConnectionState
import com.evcompanion.phone.viewmodel.MainViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun BluetoothPickerScreen(viewModel: MainViewModel) {
    val bondedDevices by viewModel.bondedDevices.collectAsState()
    val scannedDevices by viewModel.scannedBleDevices.collectAsState()
    val isScanning by viewModel.isScanning.collectAsState()
    val debugEnabled by viewModel.debugFeaturesEnabled.collectAsState()
    val connectionState by viewModel.connectionState.collectAsState()
    val isConnecting = connectionState is ObdConnectionState.Connecting || connectionState is ObdConnectionState.Initializing
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var logFiles by remember { mutableStateOf(DebugFileLogger.listLogFiles(context)) }

    // Logs captured on a different device, or pulled onto a computer and shared back - e.g. one
    // already saved via "Share debug log" below - aren't in this app's own storage yet. This
    // copies the picked file in (see DebugFileLogger.importLogFile) so it can be replayed below
    // like any log captured on this device.
    val pickLogFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
            val lines = withContext(Dispatchers.IO) {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readLines() }
            } ?: return@launch
            withContext(Dispatchers.IO) { DebugFileLogger.importLogFile(context, lines) }
            logFiles = DebugFileLogger.listLogFiles(context)
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("Connect to OBD adapter") }) }) { padding ->
        LazyColumn(modifier = Modifier.fillMaxSize().padding(padding)) {
            item { ConnectionStatusRow(connectionState, onDisconnect = viewModel::disconnect) }
            if (debugEnabled) item {
                // Logcat's ring buffer rotates away in minutes - this is the only way to get a
                // usable trace back from a drive far from a computer. No USB/adb needed: it goes
                // straight through the standard Android share sheet (email, Drive, Files, etc.).
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
                    modifier = Modifier.padding(horizontal = 16.dp),
                ) { Text("Share debug log") }
            }

            item {
                Text(
                    "Classic Bluetooth adapters",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                Text(
                    "Pair the adapter in Android's system Bluetooth settings first, then refresh here.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                OutlinedButton(
                    onClick = viewModel::refreshBondedDevices,
                    modifier = Modifier.padding(16.dp),
                ) { Text("Refresh paired devices") }
            }
            if (bondedDevices.isEmpty()) {
                item { Text("No paired Bluetooth devices found.", modifier = Modifier.padding(horizontal = 16.dp)) }
            }
            items(bondedDevices) { device: BondedDevice -> DeviceRow(device, isConnecting, onConnect = viewModel::connect) }

            item {
                Text(
                    "BLE adapters",
                    style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            item {
                Text(
                    "Many BLE ELM327 adapters don't support pairing at all - scan to find them instead.",
                    modifier = Modifier.padding(horizontal = 16.dp),
                )
            }
            item {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = viewModel::scanForBleDevices, enabled = !isScanning) {
                        Text(if (isScanning) "Scanning..." else "Scan for BLE devices")
                    }
                    if (isScanning) CircularProgressIndicator()
                }
            }
            items(scannedDevices) { device: BondedDevice -> DeviceRow(device, isConnecting, onConnect = viewModel::connect) }

            if (debugEnabled) {
                item {
                    Text(
                        "Replay a captured drive",
                        style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                item {
                    Text(
                        "Runs the whole app against a past drive's debug log instead of a live adapter - no car needed.",
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                item {
                    OutlinedButton(
                        onClick = { pickLogFile.launch(arrayOf("text/plain")) },
                        modifier = Modifier.padding(horizontal = 16.dp),
                    ) { Text("Import a log file...") }
                }
                if (logFiles.isEmpty()) {
                    item { Text("No captured logs found on this device yet.", modifier = Modifier.padding(horizontal = 16.dp)) }
                }
                items(logFiles) { file ->
                    ListItem(
                        headlineContent = { Text(file.name) },
                        trailingContent = {
                            Button(onClick = { viewModel.connect("${com.evcompanion.core.obd.ReplayObdTransport.ADDRESS_PREFIX}${file.absolutePath}") }) {
                                Text(if (isConnecting) "Connecting..." else "Replay")
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceRow(device: BondedDevice, isConnecting: Boolean, onConnect: (String) -> Unit) {
    ListItem(
        headlineContent = { Text(device.name) },
        supportingContent = { Text(device.address) },
        trailingContent = {
            // Always enabled, even mid-attempt - tapping a different device cancels whatever was
            // connecting and starts fresh instead of being locked out until it finishes/times out.
            Button(onClick = { onConnect(device.address) }) {
                Text(if (isConnecting) "Connecting..." else "Connect")
            }
        },
    )
}

@Composable
private fun ConnectionStatusRow(state: ObdConnectionState, onDisconnect: () -> Unit) {
    Column(
        modifier = Modifier.padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(
            text = when (state) {
                ObdConnectionState.Disconnected -> "Not connected"
                ObdConnectionState.Connecting -> "Connecting..."
                ObdConnectionState.Initializing -> "Initializing adapter..."
                ObdConnectionState.Connected -> "Connected"
                is ObdConnectionState.Error -> "Error: ${state.message}"
            },
        )
        // Shown for an in-progress attempt too (not just once connected) so an auto-reconnect
        // started at app launch - see AppContainer - can be stopped from here.
        if (state is ObdConnectionState.Connected || state is ObdConnectionState.Connecting || state is ObdConnectionState.Initializing) {
            OutlinedButton(onClick = onDisconnect) {
                Text(if (state is ObdConnectionState.Connected) "Disconnect" else "Stop connecting")
            }
        }
    }
}
