package com.evcompanion.core.dashboard

import org.json.JSONArray
import org.json.JSONObject

/**
 * Parses the dashboard JSON config format (see `assets/dashboards/default.json` for a worked
 * example). Lenient like com.evcompanion.core.preset.TorquePresetParser: a malformed individual
 * tile/panel/customPid is skipped (reported in [ParseResult.warnings]) rather than failing the
 * whole file - a user hand-editing JSON will make typos, and one bad tile shouldn't cost them
 * every other tile they got right. Only genuinely unparseable JSON, or a file with no usable
 * "pages" array at all, fails outright (returns a null [ParseResult.config]).
 */
object DashboardConfigParser {

    data class ParseResult(val config: DashboardConfig?, val warnings: List<String>)

    fun parse(jsonText: String): ParseResult {
        val warnings = mutableListOf<String>()
        val root = try {
            JSONObject(jsonText)
        } catch (e: Exception) {
            return ParseResult(null, listOf("Invalid JSON: ${e.message}"))
        }

        val customPids = root.optJSONArray("customPids")?.let { array ->
            (0 until array.length()).mapNotNull { i -> parseCustomPid(array.getJSONObject(i), warnings) }
        } ?: emptyList()

        val detailViews = root.optJSONObject("detailViews")?.let { obj ->
            obj.keys().asSequence().mapNotNull { id ->
                parseDetailView(obj.getJSONObject(id), warnings)?.let { id to it }
            }.toMap()
        } ?: emptyMap()

        val pagesArray = root.optJSONArray("pages")
        if (pagesArray == null) {
            warnings += "Missing required \"pages\" array"
            return ParseResult(null, warnings)
        }
        val pages = (0 until pagesArray.length()).mapNotNull { i -> parsePage(pagesArray.getJSONObject(i), warnings) }
        if (pages.isEmpty()) {
            warnings += "No usable pages found"
            return ParseResult(null, warnings)
        }

        return ParseResult(DashboardConfig(customPids, detailViews, pages), warnings)
    }

    private fun parseCustomPid(obj: JSONObject, warnings: MutableList<String>): CustomPidDef? {
        val name = obj.optString("name").takeIf { it.isNotBlank() }
        val modeAndPid = obj.optString("modeAndPid").takeIf { it.isNotBlank() }
        val formula = obj.optString("formula").takeIf { it.isNotBlank() }
        if (name == null || modeAndPid == null || formula == null) {
            warnings += "customPids entry missing name/modeAndPid/formula - skipped: $obj"
            return null
        }
        return try {
            CustomPidDef(
                name = name,
                shortName = obj.optString("shortName", name),
                header = obj.optString("header").takeIf { it.isNotBlank() },
                modeAndPid = modeAndPid,
                formula = formula,
                unit = obj.optString("unit", ""),
                minValue = obj.optDoubleOrNull("min"),
                maxValue = obj.optDoubleOrNull("max"),
            ).also { it.toPidRow() } // validates the formula parses
        } catch (e: Exception) {
            warnings += "customPids entry '$name': ${e.message} - skipped"
            null
        }
    }

    private fun parsePage(obj: JSONObject, warnings: MutableList<String>): DashboardPageDef? {
        val title = obj.optString("title").takeIf { it.isNotBlank() }
        val label = obj.optString("label").takeIf { it.isNotBlank() } ?: title ?: run {
            warnings += "Page missing label/title - skipped"
            return null
        }
        val effectiveTitle = title ?: label
        val tilesArray = obj.optJSONArray("tiles") ?: JSONArray()
        val tiles = (0 until tilesArray.length()).mapNotNull { i -> parseTile(tilesArray.getJSONObject(i), warnings) }
        return DashboardPageDef(label, effectiveTitle, tiles)
    }

    private fun parseTile(obj: JSONObject, warnings: MutableList<String>): TileDef? {
        val id = obj.optString("id").takeIf { it.isNotBlank() }
        val label = obj.optString("label").takeIf { it.isNotBlank() }
        val valuesArray = obj.optJSONArray("values")
        if (id == null || label == null || valuesArray == null || valuesArray.length() == 0) {
            warnings += "Tile missing id/label/values - skipped: $obj"
            return null
        }
        val values = (0 until valuesArray.length()).mapNotNull { i -> parseTileValue(valuesArray.getJSONObject(i), warnings) }
        if (values.isEmpty()) {
            warnings += "Tile '$id' has no usable values - skipped"
            return null
        }
        return TileDef(
            id = id,
            label = label,
            values = values,
            gaugeRange = obj.optRange("gaugeRange", warnings),
            gaugeColorRules = obj.optJSONObject("gaugeColorRules")?.let { parseColorRules(it, warnings) },
            backgroundColorRules = obj.optJSONObject("backgroundColorRules")?.let { parseColorRules(it, warnings) },
            showUnits = obj.optBoolean("showUnits", true),
            extraNeedles = obj.optJSONArray("extraNeedles")?.toStringList() ?: emptyList(),
            hero = obj.optJSONObject("hero")?.let { parseHero(it, warnings) } ?: HeroDef(),
            detail = obj.optString("detail").takeIf { it.isNotBlank() },
        )
    }

    private fun parseTileValue(obj: JSONObject, warnings: MutableList<String>): TileValueDef? {
        val pidNames = obj.optJSONArray("pidNames")?.toStringList() ?: run {
            warnings += "Tile value missing pidNames - skipped: $obj"
            return null
        }
        if (pidNames.isEmpty()) return null
        return TileValueDef(
            pidNames = pidNames,
            sublabel = obj.optString("sublabel").takeIf { it.isNotBlank() },
            negate = obj.optBoolean("negate", false),
            showIfPidName = obj.optString("showIfPidName").takeIf { it.isNotBlank() },
            showIfEquals = obj.optDouble("showIfEquals", 1.0),
        )
    }

    private fun parseHero(obj: JSONObject, warnings: MutableList<String>): HeroDef {
        val type = when (obj.optString("type", "value").lowercase()) {
            "dial" -> HeroType.DIAL
            "graph" -> HeroType.GRAPH
            else -> HeroType.VALUE
        }
        val seriesArray = obj.optJSONArray("series")
        val series = seriesArray?.let { array ->
            (0 until array.length()).mapNotNull { i -> parseSeries(array.getJSONObject(i), warnings) }
        } ?: emptyList()
        return HeroDef(type, series, obj.optInt("windowSeconds", 300))
    }

    private fun parseSeries(obj: JSONObject, warnings: MutableList<String>): GraphSeriesDef? {
        val pidNames = obj.optJSONArray("pidNames")?.toStringList() ?: run {
            warnings += "Graph series missing pidNames - skipped: $obj"
            return null
        }
        if (pidNames.isEmpty()) return null
        return GraphSeriesDef(
            pidNames,
            obj.optString("label").takeIf { it.isNotBlank() },
            obj.optColorOrNull("color"),
            obj.optBoolean("negate", false),
        )
    }

    private fun parseDetailView(obj: JSONObject, warnings: MutableList<String>): DetailViewDef? {
        val label = obj.optString("label").takeIf { it.isNotBlank() } ?: ""
        val panelsArray = obj.optJSONArray("panels") ?: return null
        val panels = (0 until panelsArray.length()).mapNotNull { i -> parsePanel(panelsArray.getJSONObject(i), warnings) }
        if (panels.isEmpty()) return null
        return DetailViewDef(label, panels)
    }

    private fun parsePanel(obj: JSONObject, warnings: MutableList<String>): DetailPanelDef? {
        val type = when (obj.optString("type").lowercase()) {
            "dial" -> DetailPanelType.DIAL
            "graph" -> DetailPanelType.GRAPH
            "value" -> DetailPanelType.VALUE
            else -> {
                warnings += "Detail panel has unknown/missing type - skipped: $obj"
                return null
            }
        }
        val seriesArray = obj.optJSONArray("series")
        val series = seriesArray?.let { array ->
            (0 until array.length()).mapNotNull { i -> parseSeries(array.getJSONObject(i), warnings) }
        } ?: emptyList()
        val pidNames = obj.optJSONArray("pidNames")?.toStringList() ?: emptyList()
        if (type != DetailPanelType.GRAPH && pidNames.isEmpty()) {
            warnings += "Detail panel of type '$type' missing pidNames - skipped: $obj"
            return null
        }
        if (type == DetailPanelType.GRAPH && series.isEmpty()) {
            warnings += "Detail panel of type GRAPH missing series - skipped: $obj"
            return null
        }
        return DetailPanelDef(
            type = type,
            pidNames = pidNames,
            label = obj.optString("label").takeIf { it.isNotBlank() },
            negate = obj.optBoolean("negate", false),
            showUnits = obj.optBoolean("showUnits", true),
            gaugeRange = obj.optRange("gaugeRange", warnings),
            gaugeColorRules = obj.optJSONObject("gaugeColorRules")?.let { parseColorRules(it, warnings) },
            series = series,
            windowSeconds = obj.optInt("windowSeconds", 300),
            normalizePerSeries = obj.optBoolean("normalizePerSeries", false),
        )
    }

    private fun parseColorRules(obj: JSONObject, warnings: MutableList<String>): ColorRules? {
        val defaultColor = obj.optColorOrNull("default") ?: run {
            warnings += "colorRules missing \"default\" color - skipped: $obj"
            return null
        }
        val rulesArray = obj.optJSONArray("rules") ?: JSONArray()
        val rules = (0 until rulesArray.length()).mapNotNull { i -> parseColorRule(rulesArray.getJSONObject(i), warnings) }
        return ColorRules(rules, defaultColor)
    }

    private fun parseColorRule(obj: JSONObject, warnings: MutableList<String>): ColorRule? {
        val compare = when (obj.optString("compare").lowercase()) {
            "lt" -> ColorRule.Compare.LT
            "gt" -> ColorRule.Compare.GT
            "eq" -> ColorRule.Compare.EQ
            else -> {
                warnings += "Color rule has unknown/missing compare - skipped: $obj"
                return null
            }
        }
        if (!obj.has("threshold")) {
            warnings += "Color rule missing threshold - skipped: $obj"
            return null
        }
        val color = obj.optColorOrNull("color") ?: run {
            warnings += "Color rule missing/invalid color - skipped: $obj"
            return null
        }
        return ColorRule(obj.optJSONArray("pidNames")?.toStringList() ?: emptyList(), compare, obj.optDouble("threshold"), color)
    }

    // ---- org.json helpers ----

    private fun JSONArray.toStringList(): List<String> = (0 until length()).map { getString(it) }

    private fun JSONObject.optDoubleOrNull(key: String): Double? = if (has(key)) optDouble(key) else null

    private fun JSONObject.optColorOrNull(key: String): Int? =
        optString(key).takeIf { it.isNotBlank() }?.let { parseHexColor(it) }

    /**
     * Parses "#RRGGBB" or "#AARRGGBB" into a packed ARGB int (opaque, alpha=0xFF, when no alpha
     * is given) - the same packing android.graphics.Color.parseColor/rgb produce, but a plain
     * Kotlin implementation so this stays unit-testable without an Android stub/Robolectric.
     */
    private fun parseHexColor(hex: String): Int? {
        val clean = hex.removePrefix("#")
        return when (clean.length) {
            6 -> clean.toIntOrNull(16)?.let { it or (0xFF shl 24) }
            8 -> clean.toLongOrNull(16)?.toInt()
            else -> null
        }
    }

    /**
     * A two-element [min, max] JSON array, or null if absent/malformed. Rejects (with a warning)
     * a range whose bounds are equal - DashboardScreen divides by `end - start` to compute a
     * gauge's fill fraction, so an equal-bounds range would otherwise silently become a NaN
     * fraction instead of failing fast at parse time.
     */
    private fun JSONObject.optRange(key: String, warnings: MutableList<String>): ClosedFloatingPointRange<Double>? {
        val array = optJSONArray(key) ?: return null
        if (array.length() != 2) return null
        val start = array.getDouble(0)
        val end = array.getDouble(1)
        if (start == end) {
            warnings += "\"$key\" has equal bounds ($start) - skipped: $this"
            return null
        }
        return start..end
    }
}
