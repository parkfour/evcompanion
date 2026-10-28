package com.evcompanion.core.preset

import com.evcompanion.core.preset.formula.Formula

/** Turns raw OBD responses and previously-resolved values into named PID values. */
object PresetEvaluator {

    /** Decodes every row in [group] against its shared response [bytes]; failures are reported, not thrown. */
    fun decodeGroup(
        group: QueryGroup,
        bytes: ByteArray,
        onError: (PidRow, Throwable) -> Unit = { _, _ -> },
    ): Map<String, Double> {
        val result = mutableMapOf<String, Double>()
        for (row in group.rows) {
            try {
                result[row.name] = row.formula.evaluate(bytes = bytes)
            } catch (e: Exception) {
                onError(row, e)
            }
        }
        return result
    }

    /**
     * Evaluates `val{}`-only rows against [resolvedSoFar], repeating until no more rows can be
     * resolved - this lets a calculated row depend on another calculated row without needing a
     * formal topological sort (preset sizes are small enough that this is cheap). A row whose
     * reference never resolves (e.g. a typo in a hand-maintained CSV) is reported via [onUnresolved]
     * and left out of the result.
     */
    fun evaluateCalculated(
        calculatedRows: List<PidRow>,
        resolvedSoFar: Map<String, Double>,
        onUnresolved: (PidRow) -> Unit = {},
    ): Map<String, Double> {
        val resolved = resolvedSoFar.toMutableMap()
        val remaining = calculatedRows.filterNot { it.name in resolved }.toMutableList()
        var progress = true
        while (progress && remaining.isNotEmpty()) {
            progress = false
            val iterator = remaining.iterator()
            while (iterator.hasNext()) {
                val row = iterator.next()
                try {
                    resolved[row.name] = row.formula.evaluate(resolved = resolved)
                    iterator.remove()
                    progress = true
                } catch (e: Formula.UnresolvedReferenceException) {
                    // may resolve on a later pass once its dependency is computed
                }
            }
        }
        remaining.forEach(onUnresolved)
        return resolved
    }
}
