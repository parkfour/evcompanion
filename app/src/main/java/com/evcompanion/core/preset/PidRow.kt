package com.evcompanion.core.preset

import com.evcompanion.core.preset.formula.Formula

/** One row of a Torque custom-PID preset: a named value, how to fetch/derive it, and how to display it. */
data class PidRow(
    val name: String,
    val shortName: String,
    val modeAndPid: String?,
    val header: String?,
    val formula: Formula,
    val minValue: Double?,
    val maxValue: Double?,
    val unit: String,
) {
    val category: PidCategory = categorize(name)
}
