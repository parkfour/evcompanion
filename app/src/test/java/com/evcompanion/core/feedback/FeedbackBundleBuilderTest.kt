package com.evcompanion.core.feedback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipFile

class FeedbackBundleBuilderTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun testBuildZipArchive_allFilesIncluded() {
        val zipFile = File(tempFolder.root, "feedback_test.zip")
        val metadata = "App Version: 0.13.0\nUser: Testing feedback"
        val json = """{"pages": []}"""
        val pidsCsv = """"Name","ShortName","ModeAndPID"
"SOC","SOC","220101""""
        val logBytes = "2026-10-30 TX: 220101\n2026-10-30 RX: 620101".toByteArray(Charsets.UTF_8)

        val included = FeedbackBundleBuilder.buildZipArchive(
            outputZipFile = zipFile,
            metadataText = metadata,
            jsonText = json,
            pidsCsvText = pidsCsv,
            logBytes = logBytes
        )

        assertTrue(zipFile.exists())
        assertEquals(4, included.size)
        assertTrue(included.contains("device_info.txt"))
        assertTrue(included.contains("dashboard_config.json"))
        assertTrue(included.contains("active_pids.csv"))
        assertTrue(included.contains("obd_traffic.log"))

        // Read and verify zip contents
        ZipFile(zipFile).use { zf ->
            assertEquals(4, zf.size())

            val metaEntry = zf.getEntry("device_info.txt")
            val metaContent = zf.getInputStream(metaEntry).bufferedReader().readText()
            assertTrue(metaContent.contains("App Version: 0.13.0"))
            assertTrue(metaContent.contains("Testing feedback"))

            val jsonEntry = zf.getEntry("dashboard_config.json")
            val jsonContent = zf.getInputStream(jsonEntry).bufferedReader().readText()
            assertEquals(json, jsonContent)

            val pidsEntry = zf.getEntry("active_pids.csv")
            val pidsContent = zf.getInputStream(pidsEntry).bufferedReader().readText()
            assertEquals(pidsCsv, pidsContent)

            val logEntry = zf.getEntry("obd_traffic.log")
            val logContent = zf.getInputStream(logEntry).bufferedReader().readText()
            assertTrue(logContent.contains("TX: 220101"))
        }
    }

    @Test
    fun testBuildZipArchive_withoutObdConsent_excludesLog() {
        val zipFile = File(tempFolder.root, "feedback_no_log.zip")
        val metadata = "App Version: 0.13.0"
        val json = """{"pages": []}"""
        val pidsCsv = """"Name","ShortName""""

        val included = FeedbackBundleBuilder.buildZipArchive(
            outputZipFile = zipFile,
            metadataText = metadata,
            jsonText = json,
            pidsCsvText = pidsCsv,
            logBytes = null // User did NOT consent to OBD logs
        )

        assertTrue(zipFile.exists())
        assertEquals(3, included.size)
        assertFalse(included.contains("obd_traffic.log"))

        ZipFile(zipFile).use { zf ->
            assertEquals(3, zf.size())
            val logEntry = zf.getEntry("obd_traffic.log")
            assertTrue("obd_traffic.log must NOT be present in zip without consent", logEntry == null)
        }
    }

    @Test
    fun testBuildZipArchive_onlyCommentsAndMetadata() {
        val zipFile = File(tempFolder.root, "feedback_minimal.zip")
        val metadata = "General inquiry"

        val included = FeedbackBundleBuilder.buildZipArchive(
            outputZipFile = zipFile,
            metadataText = metadata,
            jsonText = null,
            pidsCsvText = null,
            logBytes = null
        )

        assertTrue(zipFile.exists())
        assertEquals(1, included.size)
        assertEquals("device_info.txt", included[0])

        ZipFile(zipFile).use { zf ->
            assertEquals(1, zf.size())
            val entry = zf.getEntry("device_info.txt")
            val content = zf.getInputStream(entry).bufferedReader().readText()
            assertEquals("General inquiry", content)
        }
    }
}
