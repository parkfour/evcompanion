package com.evcompanion.core.preset

/** One OBD request that serves every [rows] entry reading from its response (see [PidRow]). */
data class QueryGroup(
    val header: String?,
    val modeAndPid: String,
    val rows: List<PidRow>,
)

class Preset(
    val id: String,
    val displayName: String,
    val rows: List<PidRow>,
) {
    /** Byte-backed rows, grouped so one OBD request per group serves every row that reads it. */
    val queryGroups: List<QueryGroup> = rows
        .filter { it.formula.usesRawBytes && it.modeAndPid != null }
        .groupBy { (it.header ?: "") to it.modeAndPid!! }
        .map { (key, groupRows) -> QueryGroup(key.first.ifEmpty { null }, key.second, groupRows) }

    /** Pure `val{}`-derived rows, evaluated after the query groups they depend on resolve. */
    val calculatedRows: List<PidRow> = rows.filterNot { it.formula.usesRawBytes }

    val categories: List<PidCategory> = rows.map { it.category }.distinct()

    fun rowsInCategory(category: PidCategory): List<PidRow> = rows.filter { it.category == category }

    /** Query groups needed to populate every row in [category], directly or via calc-row dependencies. */
    fun queryGroupsForCategory(category: PidCategory): List<QueryGroup> {
        val namesInCategory = rowsInCategory(category).map { it.name }.toSet()
        val neededNames = namesInCategory + calculatedRows
            .filter { it.category == category }
            .flatMap { it.formula.referencedPidNames }
        return queryGroups.filter { group -> group.rows.any { it.name in neededNames } }
    }

    /**
     * Query groups needed to populate exactly [names], directly or via calc-row dependencies -
     * unlike [queryGroupsForCategory], this doesn't pull in every other row that happens to share
     * a category (e.g. subscribing to a couple of aggregate cell-voltage rows shouldn't also
     * query the other ~190 raw per-cell rows in the same CELL_VOLTAGES category).
     */
    fun queryGroupsForNames(names: Set<String>): List<QueryGroup> {
        val neededNames = names + calculatedRows
            .filter { it.name in names }
            .flatMap { it.formula.referencedPidNames }
        return queryGroups.filter { group -> group.rows.any { it.name in neededNames } }
    }
}
