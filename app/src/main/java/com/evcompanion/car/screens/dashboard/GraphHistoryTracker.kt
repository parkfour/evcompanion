package com.evcompanion.car.screens.dashboard

import com.evcompanion.core.dashboard.GraphSeriesDef
import com.evcompanion.core.dashboard.findValue
import com.evcompanion.core.polling.DecodedValue

/**
 * (timestamp, value) sample bookkeeping for a GRAPH hero/panel's series. [record] is cheap to
 * call on every PollingEngine values tick; samples for each "<key>:<seriesIndex>" are trimmed to
 * that series' own [windowSeconds] as they're recorded. In-memory only - DashboardScreen keeps one
 * instance for hero-tile graphs and a separate one for the detail-view graph panels (cleared each
 * time the detail view is opened), both lost on restart.
 */
class GraphHistoryTracker {
    private val history = mutableMapOf<String, MutableList<Pair<Long, Double>>>()

    fun record(key: String, series: List<GraphSeriesDef>, windowSeconds: Int, values: Map<String, DecodedValue>) {
        val now = System.currentTimeMillis()
        val windowMillis = windowSeconds * 1000L
        series.forEachIndexed { i, s ->
            val raw = findValue(values, s.pidNames)?.value ?: return@forEachIndexed
            val v = if (s.negate) -raw else raw
            val list = history.getOrPut("$key:$i") { mutableListOf() }
            list.add(now to v)
            list.removeAll { now - it.first > windowMillis }
        }
    }

    fun pointsFor(key: String, seriesIndex: Int): List<Float> =
        history["$key:$seriesIndex"]?.map { it.second.toFloat() } ?: emptyList()

    fun clear() = history.clear()
}
