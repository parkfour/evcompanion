package com.evcompanion.core.dashboard

import com.evcompanion.core.polling.DecodedValue
import com.evcompanion.core.preset.PidRow
import com.evcompanion.core.preset.formula.Formula

/**
 * A declarative dashboard layout loaded from JSON instead of hardcoded in [DashboardScreen] - see
 * [DashboardConfigParser] for the file format and [DashboardConfigRepository] for how one gets
 * loaded (bundled default, or a user-imported override).
 */
data class DashboardConfig(
    val customPids: List<CustomPidDef>,
    val detailViews: Map<String, DetailViewDef>,
    val pages: List<DashboardPageDef>,
)

/**
 * A PID this config defines itself rather than referencing one already in the active vehicle
 * preset - e.g. a signal the bundled preset doesn't expose yet. Reuses the exact same Torque-style
 * formula syntax ([Formula.parse]) the CSV preset parser already uses, so "a*256+b" works
 * identically here. Merged into the active preset's own rows (see AppContainer) rather than
 * queried separately - from PollingEngine's point of view it's just another row.
 */
data class CustomPidDef(
    val name: String,
    val shortName: String,
    val header: String?,
    val modeAndPid: String,
    val formula: String,
    val unit: String,
    val minValue: Double?,
    val maxValue: Double?,
) {
    fun toPidRow(): PidRow = PidRow(name, shortName, modeAndPid, header, Formula.parse(formula), minValue, maxValue, unit)
}

data class DashboardPageDef(
    val label: String,
    val title: String = label,
    val tiles: List<TileDef>,
)

/**
 * One tile. [values] is what the small/grid card shows (one value = plain readout or, if
 * [gaugeRange] is set, a ring gauge; several values = a small label+value grid - same rule
 * DashboardScreen already used for hand-written tiles). [hero] controls what happens once this
 * tile is promoted to the big slot: VALUE/DIAL render the same single-value content just bigger,
 * GRAPH switches to a time-series line graph. [detail] names a [DetailViewDef] to push when the
 * *already-promoted* hero tile itself is tapped - several tiles can share the same [detail] id,
 * so tapping any of them converges on one combined screen.
 */
data class TileDef(
    val id: String,
    val label: String,
    val values: List<TileValueDef>,
    val gaugeRange: ClosedFloatingPointRange<Double>? = null,
    val gaugeColorRules: ColorRules? = null,
    val backgroundColorRules: ColorRules? = null,
    val showUnits: Boolean = true,
    val extraNeedles: List<String> = emptyList(),
    val hero: HeroDef = HeroDef(),
    val detail: String? = null,
)

enum class HeroType { VALUE, DIAL, GRAPH }

/** [series]/[windowSeconds] only apply when [type] is GRAPH - see DashboardScreen's rendering of it. */
data class HeroDef(
    val type: HeroType = HeroType.VALUE,
    val series: List<GraphSeriesDef> = emptyList(),
    val windowSeconds: Int = 300,
)

/**
 * One line of a GRAPH hero/detail panel - a value tracked over time, recorded in-memory only
 * (lost on restart). [negate] flips the sign for display only, same convention as [TileValueDef.negate].
 */
data class GraphSeriesDef(
    val pidNames: List<String>,
    val label: String? = null,
    val color: Int? = null,
    val negate: Boolean = false,
)

/**
 * One value inside a tile or detail panel. [negate] flips the sign for display only (e.g. charge
 * current shown positive on the charging page). [showIfPidName]/[showIfEquals], when set, hide
 * this value unless that other PID currently equals [showIfEquals] - e.g. a heater temperature
 * that's only meaningful while preconditioning is actually active.
 */
data class TileValueDef(
    val pidNames: List<String>,
    val sublabel: String? = null,
    val negate: Boolean = false,
    val showIfPidName: String? = null,
    val showIfEquals: Double = 1.0,
) {
    fun resolve(values: Map<String, DecodedValue>): DecodedValue? {
        if (showIfPidName != null && findValue(values, listOf(showIfPidName))?.value != showIfEquals) return null
        return findValue(values, pidNames)?.let { if (negate) it.copy(value = -it.value) else it }
    }
}

/** First-match lookup across [names] by (case-insensitive, substring) PID name - same convention DashboardScreen always used. */
fun findValue(values: Map<String, DecodedValue>, names: List<String>): DecodedValue? =
    names.firstNotNullOfOrNull { name -> values.values.firstOrNull { it.pidName.contains(name, ignoreCase = true) } }

/** A combined, freeform detail screen - several tiles can route to the same one via [TileDef.detail]. */
data class DetailViewDef(val label: String, val panels: List<DetailPanelDef>)

enum class DetailPanelType { VALUE, DIAL, GRAPH }

data class DetailPanelDef(
    val type: DetailPanelType,
    val pidNames: List<String> = emptyList(),
    val label: String? = null,
    val negate: Boolean = false,
    val showUnits: Boolean = true,
    val gaugeRange: ClosedFloatingPointRange<Double>? = null,
    val gaugeColorRules: ColorRules? = null,
    val series: List<GraphSeriesDef> = emptyList(),
    val windowSeconds: Int = 300,
    // Only meaningful when type is GRAPH. False (default) scales every series against one shared
    // min/max - correct when they share a unit (e.g. MIN/MAX pack temp). True scales each series
    // against its own min/max instead - for overlaying differently-scaled metrics (e.g. SoC %,
    // power kW, current A, temp C) so none of them get flattened by the others' range.
    val normalizePerSeries: Boolean = false,
)

/**
 * A conditional color: the first [rules] whose comparison is satisfied wins, [defaultColor]
 * otherwise. A rule with empty [ColorRule.pidNames] compares the subject's own bound value (e.g. a
 * gauge coloring itself off the value it's displaying); non-empty names compare *other* PIDs
 * instead (e.g. a card tinting itself off a status flag it isn't otherwise showing) - matches any
 * ONE of them satisfying the comparison, same "any of these" semantics a multi-tire-pressure
 * warning needs.
 */
data class ColorRules(val rules: List<ColorRule>, val defaultColor: Int) {
    fun resolve(ownValue: Double?, values: Map<String, DecodedValue>): Int =
        rules.firstOrNull { it.matches(ownValue, values) }?.color ?: defaultColor
}

data class ColorRule(val pidNames: List<String>, val compare: Compare, val threshold: Double, val color: Int) {
    enum class Compare { LT, GT, EQ }

    fun matches(ownValue: Double?, values: Map<String, DecodedValue>): Boolean {
        val candidates = if (pidNames.isEmpty()) listOfNotNull(ownValue) else pidNames.mapNotNull { findValue(values, listOf(it))?.value }
        return candidates.any { v ->
            when (compare) {
                Compare.LT -> v < threshold
                Compare.GT -> v > threshold
                Compare.EQ -> v == threshold
            }
        }
    }
}
