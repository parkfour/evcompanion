package com.evcompanion.phone.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.evcompanion.core.feedback.FeedbackRequest
import com.evcompanion.phone.viewmodel.MainViewModel
import kotlinx.coroutines.launch

@Composable
fun FeedbackDialog(
    viewModel: MainViewModel,
    onDismiss: () -> Unit
) {
    var comments by remember { mutableStateOf("") }
    var includeDashboardJson by remember { mutableStateOf(true) }
    var includePidsCsv by remember { mutableStateOf(true) }
    var includeObdLogs by remember { mutableStateOf(false) }
    var isPackaging by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = { if (!isPackaging) onDismiss() },
        title = { Text("Submit Feedback & Diagnostics") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    "Share your experience, report gauge formula issues, or suggest enhancements. You can optionally attach active configurations to assist debugging.",
                    style = MaterialTheme.typography.bodySmall
                )

                OutlinedTextField(
                    value = comments,
                    onValueChange = { comments = it },
                    label = { Text("Comments or issue description") },
                    placeholder = { Text("What happened? What were you expecting?") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 100.dp),
                    maxLines = 6
                )

                Text(
                    "Diagnostic Attachments",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp)
                )

                // Dashboard JSON
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = includeDashboardJson,
                        onCheckedChange = { includeDashboardJson = it }
                    )
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text("Active dashboard layout (JSON)", style = MaterialTheme.typography.bodyMedium)
                        Text("Shares tile layout, color rules, and hero styles", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // PIDs CSV
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = includePidsCsv,
                        onCheckedChange = { includePidsCsv = it }
                    )
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text("Active PID configuration (CSV)", style = MaterialTheme.typography.bodyMedium)
                        Text("Shares ECU headers, mode/PID commands, and formulas", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                // OBD-II Traffic Logs (Requires explicit consent)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Checkbox(
                        checked = includeObdLogs,
                        onCheckedChange = { includeObdLogs = it }
                    )
                    Column(modifier = Modifier.padding(start = 4.dp)) {
                        Text("Include OBD-II capture logs (Consent required)", style = MaterialTheme.typography.bodyMedium)
                        Text("Raw vehicle ECU request/response hex packets. Contains no personal information.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                if (includeObdLogs) {
                    Text(
                        "Note: OBD-II logs record diagnostic communication between your adapter and vehicle modules. They help fix decoding bugs.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Or leave text feedback on Google Play:", style = MaterialTheme.typography.bodySmall)
                    TextButton(onClick = {
                        viewModel.openPlayStoreListing()
                        onDismiss()
                    }) {
                        Text("Open Store Page")
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    isPackaging = true
                    scope.launch {
                        try {
                            val request = FeedbackRequest(
                                userComments = comments,
                                includeDashboardJson = includeDashboardJson,
                                includePidsCsv = includePidsCsv,
                                includeObdLogs = includeObdLogs
                            )
                            val bundle = viewModel.createFeedbackBundle(request)
                            viewModel.shareFeedbackBundle(bundle)
                            onDismiss()
                        } finally {
                            isPackaging = false
                        }
                    }
                },
                enabled = !isPackaging
            ) {
                if (isPackaging) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp
                    )
                    Text("Packaging...")
                } else {
                    Text("Package & Share")
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                enabled = !isPackaging
            ) {
                Text("Cancel")
            }
        }
    )
}
