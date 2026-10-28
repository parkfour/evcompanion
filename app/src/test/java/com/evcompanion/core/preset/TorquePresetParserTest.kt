package com.evcompanion.core.preset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class TorquePresetParserTest {

    @Test
    fun `parses header, comments, and data rows`() {
        val csv = """
            Name,ShortName,ModeAndPID,Equation,Min Value,Max Value,Units,Header
            ~ this is a changelog comment and should be skipped
            ~
            000_Cell Voltage 001,C001,0x220102,e/50,2.70,4.30,V,7E4
            000_State of Health,SOH,0x220105,((z<8)+aa)/10,0,100,%,7E4
        """.trimIndent()

        val result = TorquePresetParser.parse(csv)

        assertEquals(2, result.rows.size)
        assertTrue(result.warnings.isEmpty())

        val cell = result.rows[0]
        assertEquals("000_Cell Voltage 001", cell.name)
        assertEquals("220102", cell.modeAndPid)
        assertEquals("7E4", cell.header)
        assertEquals(2.70, cell.minValue!!, 1e-9)
        assertEquals(4.30, cell.maxValue!!, 1e-9)
        assertEquals("V", cell.unit)
    }

    @Test
    fun `rows with no ModeAndPID or Header are parsed as calculated rows`() {
        val csv = """
            Name,ShortName,ModeAndPID,Equation,Min Value,Max Value,Units,Header
            !_ABRP_Battery Current,Batt Current,,val{000_Battery Current},-200,200,A,
        """.trimIndent()

        val result = TorquePresetParser.parse(csv)
        assertEquals(1, result.rows.size)
        assertNull(result.rows[0].modeAndPid)
        assertNull(result.rows[0].header)
        assertEquals(false, result.rows[0].formula.usesRawBytes)
    }

    @Test
    fun `a malformed formula is skipped with a warning, not a crash`() {
        val csv = """
            Name,ShortName,ModeAndPID,Equation,Min Value,Max Value,Units,Header
            000_Good Row,Good,0x220101,a/2,0,100,%,7E4
            000_Bad Row,Bad,0x220101,(Signed(BD)*256))+BE,-10100,10100,rpm,7E4
        """.trimIndent()

        val result = TorquePresetParser.parse(csv)
        assertEquals(1, result.rows.size)
        assertEquals("000_Good Row", result.rows[0].name)
        assertTrue(result.warnings.any { it.contains("000_Bad Row") })
    }

    @Test
    fun `loads the real bundled IONIQ 5 preset without crashing`() {
        val file = File("src/main/assets/presets/ioniq5_awd_77kwh.csv")
        assertTrue("expected ${file.absolutePath} to exist", file.exists())

        val result = TorquePresetParser.parse(file.readText())

        // The file has ~296 data rows; a couple are known-bad in the source CSV itself
        // (see below) and should be skipped, not crash the whole load.
        assertTrue("expected at least 250 rows, got ${result.rows.size}", result.rows.size >= 250)

        // The RPMF row's unbalanced-parens typo in the upstream community CSV was corrected in
        // our bundled copy, so it must now parse and the whole file is typo-free from our side.
        assertTrue("expected no parse-time warnings, got ${result.warnings}", result.warnings.isEmpty())

        val preset = Preset("ioniq5", "IONIQ 5", result.rows)
        assertTrue(preset.queryGroups.isNotEmpty())
        assertTrue(preset.calculatedRows.isNotEmpty())

        // val{000_Maximum Deterioration} doesn't match any row name in the file - this should
        // surface as an unresolved reference at evaluation time, not a parse-time failure.
        val soh = result.rows.first { it.name == "000_State of Health" }
        val decoded = PresetEvaluator.decodeGroup(
            preset.queryGroups.first { it.rows.contains(soh) },
            ByteArray(40) { 10 },
        )
        val unresolved = mutableListOf<PidRow>()
        PresetEvaluator.evaluateCalculated(preset.calculatedRows, decoded, onUnresolved = { unresolved += it })
        assertTrue(unresolved.any { it.name.contains("Max Deterioration", ignoreCase = true) })
    }
}
