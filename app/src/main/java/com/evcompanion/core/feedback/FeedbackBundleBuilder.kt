package com.evcompanion.core.feedback

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import com.evcompanion.AppContainer
import com.evcompanion.R
import com.evcompanion.core.debug.DebugFileLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

data class FeedbackRequest(
    val userComments: String,
    val includeDashboardJson: Boolean = true,
    val includePidsCsv: Boolean = true,
    val includeObdLogs: Boolean = false,
)

data class FeedbackBundle(
    val zipFile: File,
    val summaryText: String,
    val includedFiles: List<String>
)

object FeedbackBundleBuilder {

    suspend fun createBundle(
        context: Context,
        container: AppContainer,
        request: FeedbackRequest
    ): FeedbackBundle = createBundle(context.cacheDir, container, request, appVersion(context))

    suspend fun createBundle(
        baseCacheDir: File,
        container: AppContainer,
        request: FeedbackRequest,
        appVersion: String = "unknown",
    ): FeedbackBundle = withContext(Dispatchers.IO) {
        val feedbackDir = File(baseCacheDir, "feedback").apply { mkdirs() }
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val zipFile = File(feedbackDir, "ev_companion_feedback_$timeStamp.zip")

        // 1. Device and system metadata
        val activePresetId = container.presetRepository.activePresetId.first()
        val allPresets = container.presetRepository.presets.first()
        val activePreset = allPresets.firstOrNull { it.id == activePresetId }
        val activeTheme = container.dashboardLayoutConfig.themeId.first()
        val obdState = container.pollingEngine.connectionState.value

        val metadata = buildString {
            appendLine("=== EV Companion Diagnostics & Feedback ===")
            appendLine("Timestamp: ${SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date())}")
            appendLine("App Version: $appVersion")
            appendLine("Android OS: ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})")
            appendLine("Device Model: ${Build.MANUFACTURER} ${Build.MODEL}")
            appendLine("Active Preset: ${activePreset?.displayName ?: activePresetId ?: "None"}")
            appendLine("Active Theme: ${activeTheme.name}")
            appendLine("OBD Connection: $obdState")
            appendLine()
            appendLine("=== User Feedback Comments ===")
            appendLine(request.userComments.ifBlank { "No additional comments provided." })
        }

        // 2. Active Dashboard JSON
        val jsonText = if (request.includeDashboardJson) {
            container.dashboardConfigRepository.getActiveConfigJsonText()
        } else null

        // 3. Active PIDs in effect (base preset + custom dashboard PIDs)
        val pidsCsvText = if (request.includePidsCsv) {
            val customRows = container.dashboardConfigRepository.activeConfig.value?.customPids.orEmpty().map { it.toPidRow() }
            val baseRows = activePreset?.rows.orEmpty()
            val allRows = baseRows + customRows

            buildString {
                appendLine(""""Name","ShortName","ModeAndPID","Equation","Min Value","Max Value","Units","Header"""")
                allRows.forEach { row ->
                    appendLine(""""${row.name}","${row.shortName}","${row.modeAndPid.orEmpty()}","${row.formula.rawEquation}","${row.minValue ?: ""}","${row.maxValue ?: ""}","${row.unit}","${row.header.orEmpty()}"""")
                }
            }
        } else null

        // 4. OBD-II traffic capture log (with explicit user consent)
        val logBytes = if (request.includeObdLogs) {
            val currentLog = DebugFileLogger.currentLogFile()
            if (currentLog != null && currentLog.exists()) {
                currentLog.readBytes()
            } else {
                "No active OBD-II traffic recorded in current session. Enable debugging in settings to record full packet traces.".toByteArray(Charsets.UTF_8)
            }
        } else null

        val includedNames = buildZipArchive(
            outputZipFile = zipFile,
            metadataText = metadata,
            jsonText = jsonText,
            pidsCsvText = pidsCsvText,
            logBytes = logBytes
        )

        FeedbackBundle(
            zipFile = zipFile,
            summaryText = metadata,
            includedFiles = includedNames
        )
    }

    /**
     * Writes the given diagnostic items into [outputZipFile] and returns the list of entry names.
     */
    fun buildZipArchive(
        outputZipFile: File,
        metadataText: String,
        jsonText: String?,
        pidsCsvText: String?,
        logBytes: ByteArray?
    ): List<String> {
        val filesToZip = mutableListOf<Pair<String, ByteArray>>()
        val includedNames = mutableListOf<String>()

        filesToZip.add("device_info.txt" to metadataText.toByteArray(Charsets.UTF_8))
        includedNames.add("device_info.txt")

        if (jsonText != null) {
            filesToZip.add("dashboard_config.json" to jsonText.toByteArray(Charsets.UTF_8))
            includedNames.add("dashboard_config.json")
        }

        if (pidsCsvText != null) {
            filesToZip.add("active_pids.csv" to pidsCsvText.toByteArray(Charsets.UTF_8))
            includedNames.add("active_pids.csv")
        }

        if (logBytes != null) {
            filesToZip.add("obd_traffic.log" to logBytes)
            includedNames.add("obd_traffic.log")
        }

        ZipOutputStream(FileOutputStream(outputZipFile)).use { zos ->
            for ((name, data) in filesToZip) {
                val entry = ZipEntry(name)
                zos.putNextEntry(entry)
                zos.write(data)
                zos.closeEntry()
            }
        }

        return includedNames
    }

    /** e.g. "0.12.25 (code 45)" - read from the installed package, so it can't go stale. */
    fun appVersion(context: Context): String = runCatching {
        val info = context.packageManager.getPackageInfo(context.packageName, 0)
        "${info.versionName} (code ${androidx.core.content.pm.PackageInfoCompat.getLongVersionCode(info)})"
    }.getOrDefault("unknown")

    fun shareBundle(context: Context, bundle: FeedbackBundle) {
        val recipient = runCatching { context.getString(R.string.developer_feedback_email) }.getOrNull()
            ?: "apps@parkfour.com"
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", bundle.zipFile)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/zip"
            putExtra(Intent.EXTRA_EMAIL, arrayOf(recipient))
            putExtra(Intent.EXTRA_SUBJECT, "[EV Companion Feedback] v${appVersion(context).substringBefore(' ')} - ${Build.MODEL}")
            putExtra(Intent.EXTRA_TEXT, bundle.summaryText)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = android.content.ClipData.newRawUri(null, uri) // carries the read grant through the chooser
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // Called with the Application context (MainViewModel), which needs NEW_TASK to start activities.
        context.startActivity(
            Intent.createChooser(intent, "Submit feedback bundle").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /**
     * Opens the Google Play Store app or browser listing directly to allow testers to leave
     * native Play Console testing feedback.
     */
    fun openPlayStoreListing(context: Context) {
        val appId = context.packageName
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$appId")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$appId")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    }
}
