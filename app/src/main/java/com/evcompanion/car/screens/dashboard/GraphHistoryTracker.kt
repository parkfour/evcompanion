package com.evcompanion.car.screens.dashboard

import com.evcompanion.core.dashboard.GraphSeriesDef
import com.evcompanion.core.dashboard.findValue
import com.evcompanion.core.polling.DecodedValue

/**
 * (timestamp, value) sample bookkeeping for a GRAPH hero/panel's series. [record] is cheap to
 * call on every PollingEngine values tick; samples for each "<key>:<seriesIndex>" are trimmed to
 * that series' own [windowSeconds] as they're recorded. In-memory only - DashboardScreen keeps one
 * instance for hero-tile graphs and a separate one for the detail-view graph panels, both lost on
 * restart.
 */
class GraphHistoryTracker(private val clock: () -> Long = System::currentTimeMillis) {
    private val history = mutableMapOf<String, MutableList<Pair<Long, Double>>>()
    private val units = mutableMapOf<String, String>()

    fun record(key: String, series: List<GraphSeriesDef>, windowSeconds: Int, values: Map<String, DecodedValue>) {
        val now = clock()
        val windowMillis = windowSeconds * 1000L
        series.forEachIndexed { i, s ->
            val decoded = findValue(values, s.pidNames) ?: return@forEachIndexed
            val v = if (s.negate) -decoded.value else decoded.value
            val list = history.getOrPut("$key:$i") { mutableListOf() }
            list.add(now to v)
            list.removeAll { now - it.first > windowMillis }
            units["$key:$i"] = decoded.unit
        }
    }

    fun pointsFor(key: String, seriesIndex: Int): List<Float> =
        history["$key:$seriesIndex"]?.map { it.second.toFloat() } ?: emptyList()

    /** (epoch millis, value) samples, oldest first - for charts with a real time axis. */
    fun samplesFor(key: String, seriesIndex: Int): List<Pair<Long, Double>> =
        history["$key:$seriesIndex"]?.toList() ?: emptyList()

    /** The unit of the series' latest sample (e.g. "kW"), or null before the first one. */
    fun unitFor(key: String, seriesIndex: Int): String? = units["$key:$seriesIndex"]

    fun clear() {
        history.clear()
        units.clear()
    }
}
