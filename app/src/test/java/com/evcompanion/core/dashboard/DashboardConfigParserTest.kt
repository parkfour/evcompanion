package com.evcompanion.core.dashboard

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DashboardConfigParserTest {

    /** Matches parseHexColor's packing: an RGB hex implies opaque alpha. */
    private fun opaque(rgb: Int): Int = rgb or (0xFF shl 24)

    @Test
    fun `parses a full config - pages, hero, detail, color rules, custom pids`() {
        val json = """
        {
          "customPids": [
            { "name": "My Sensor", "header": "7E4", "modeAndPid": "0x220199", "formula": "a*256+b", "unit": "kPa" }
          ],
          "detailViews": {
            "charging_detail": {
              "label": "Charging",
              "panels": [
                { "type": "dial", "pidNames": ["Requested Charge Current"], "gaugeRange": [0, 350] },
                { "type": "graph", "series": [{ "pidNames": ["Battery Min Temperature"], "color": "#5AAAFF" }] }
              ]
            }
          },
          "pages": [
            {
              "label": "Driving",
              "tiles": [
                {
                  "id": "soc",
                  "label": "STATE OF CHARGE",
                  "values": [{ "pidNames": ["State of Charge Display", "SOC"] }],
                  "gaugeRange": [0, 100],
                  "gaugeColorRules": {
                    "rules": [{ "compare": "lt", "threshold": 10, "color": "#DC3C3C" }],
                    "default": "#3CC864"
                  },
                  "hero": { "type": "dial" },
                  "detail": "charging_detail"
                }
              ]
            }
          ]
        }
        """.trimIndent()

        val result = DashboardConfigParser.parse(json)
        assertTrue("expected no warnings, got: ${result.warnings}", result.warnings.isEmpty())
        val config = assertNotNull(result.config).let { result.config!! }

        assertEquals(1, config.customPids.size)
        assertEquals("My Sensor", config.customPids[0].name)
        assertNotNull(config.customPids[0].toPidRow()) // formula parses

        assertEquals(1, config.pages.size)
        val tile = config.pages[0].tiles[0]
        assertEquals("soc", tile.id)
        assertEquals(0.0..100.0, tile.gaugeRange)
        assertEquals(HeroType.DIAL, tile.hero.type)
        assertEquals("charging_detail", tile.detail)
        assertEquals(opaque(0xDC3C3C), tile.gaugeColorRules?.rules?.get(0)?.color)
        assertEquals(opaque(0x3CC864), tile.gaugeColorRules?.defaultColor)

        val detail = config.detailViews["charging_detail"]!!
        assertEquals(2, detail.panels.size)
        assertEquals(DetailPanelType.DIAL, detail.panels[0].type)
        assertEquals(DetailPanelType.GRAPH, detail.panels[1].type)
        assertEquals(opaque(0x5AAAFF), detail.panels[1].series[0].color)
    }

    @Test
    fun `a malformed tile is skipped but the rest of the page still loads`() {
        val json = """
        {
          "pages": [
            {
              "label": "Driving",
              "tiles": [
                { "id": "bad", "label": "NO VALUES", "values": [] },
                { "id": "good", "label": "GOOD", "values": [{ "pidNames": ["Battery Power"] }] }
              ]
            }
          ]
        }
        """.trimIndent()

        val result = DashboardConfigParser.parse(json)
        assertTrue(result.warnings.isNotEmpty())
        assertEquals(1, result.config?.pages?.get(0)?.tiles?.size)
        assertEquals("good", result.config?.pages?.get(0)?.tiles?.get(0)?.id)
    }

    @Test
    fun `missing pages array fails with a null config`() {
        val result = DashboardConfigParser.parse("""{ "customPids": [] }""")
        assertNull(result.config)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `invalid json fails with a null config`() {
        val result = DashboardConfigParser.parse("not json at all")
        assertNull(result.config)
        assertTrue(result.warnings.isNotEmpty())
    }

    @Test
    fun `a customPid with an unparseable formula is skipped with a warning`() {
        val json = """
        {
          "customPids": [
            { "name": "Bad", "header": "7E4", "modeAndPid": "0x220199", "formula": "(((unbalanced", "unit": "" }
          ],
          "pages": [
            { "label": "Driving", "tiles": [{ "id": "t", "label": "T", "values": [{ "pidNames": ["X"] }] }] }
          ]
        }
        """.trimIndent()

        val result = DashboardConfigParser.parse(json)
        assertTrue(result.warnings.any { it.contains("Bad") })
        assertEquals(0, result.config?.customPids?.size)
    }

    @Test
    fun `parses page title when specified in JSON`() {
        val json = """
        {
          "pages": [
            {
              "title": "Drive Mode",
              "label": "Driving",
              "tiles": [{ "id": "t1", "label": "L1", "values": [{ "pidNames": ["P1"] }] }]
            },
            {
              "title": "Charge Mode",
              "tiles": [{ "id": "t2", "label": "L2", "values": [{ "pidNames": ["P2"] }] }]
            }
          ]
        }
        """.trimIndent()
        val result = DashboardConfigParser.parse(json)
        val config = assertNotNull(result.config).let { result.config!! }
        assertEquals(2, config.pages.size)
        assertEquals("Drive Mode", config.pages[0].title)
        assertEquals("Driving", config.pages[0].label)
        assertEquals("Charge Mode", config.pages[1].title)
        assertEquals("Charge Mode", config.pages[1].label)
    }

    @Test
    fun `a graph panel parses normalizePerSeries and a series' negate flag`() {
        val json = """
        {
          "detailViews": {
            "overlay": {
              "label": "Overlay",
              "panels": [
                { "type": "graph", "normalizePerSeries": true, "series": [
                  { "pidNames": ["SOC"], "label": "SOC" },
                  { "pidNames": ["Battery Power"], "label": "POWER", "negate": true }
                ] }
              ]
            }
          },
          "pages": [
            { "label": "Driving", "tiles": [{ "id": "t", "label": "T", "values": [{ "pidNames": ["X"] }] }] }
          ]
        }
        """.trimIndent()

        val result = DashboardConfigParser.parse(json)
        val panel = assertNotNull(result.config).let { result.config!!.detailViews["overlay"]!!.panels[0] }
        assertTrue(panel.normalizePerSeries)
        assertEquals(false, panel.series[0].negate)
        assertEquals(true, panel.series[1].negate)
    }

    @Test
    fun `a gaugeRange with equal bounds is rejected rather than dividing by zero later`() {
        val json = """
        {
          "pages": [
            {
              "label": "Driving",
              "tiles": [
                { "id": "t", "label": "T", "values": [{ "pidNames": ["X"] }], "gaugeRange": [50, 50] }
              ]
            }
          ]
        }
        """.trimIndent()

        val result = DashboardConfigParser.parse(json)
        assertTrue(result.warnings.any { it.contains("gaugeRange") })
        assertNull(result.config?.pages?.get(0)?.tiles?.get(0)?.gaugeRange)
    }

    @Test
    fun `default bundled dashboard json parses with 5 pages and zero warnings`() {
        val file = java.io.File("src/main/assets/dashboards/default.json")
        val stream = if (file.exists()) file.inputStream() else javaClass.classLoader!!.getResourceAsStream("dashboards/default.json")
        val text = stream!!.bufferedReader().use { it.readText() }
        val result = DashboardConfigParser.parse(text)
        assertTrue("Expected no warnings parsing default.json, got: ${result.warnings}", result.warnings.isEmpty())
        val config = assertNotNull(result.config).let { result.config!! }
        assertEquals(5, config.pages.size)
        assertEquals("Drive", config.pages[0].title)
        assertEquals(5, config.pages[0].tiles.size)
        assertEquals("Cabin & Body", config.pages[1].title)
        assertEquals(5, config.pages[1].tiles.size)
        assertEquals("Battery Health", config.pages[2].title)
        assertEquals(5, config.pages[2].tiles.size)
        assertEquals("Charging", config.pages[3].title)
        assertEquals(6, config.pages[3].tiles.size)
        assertEquals("Diagnostics", config.pages[4].title)
        assertEquals(4, config.pages[4].tiles.size)

        // Verify Requested Charge Power in customPids
        val reqPower = config.customPids.firstOrNull { it.name == "Requested Charge Power" }
        assertNotNull("Requested Charge Power customPid must be defined", reqPower)
        assertNotNull("Requested Charge Power formula must parse to a PidRow", reqPower?.toPidRow())
    }
}
