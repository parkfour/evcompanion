package com.evcompanion.core.polling

import com.evcompanion.core.bluetooth.LastObdDeviceRepository
import com.evcompanion.core.debug.DebugFileLogger
import com.evcompanion.core.efficiency.TripEfficiencyTracker
import com.evcompanion.core.obd.Elm327Driver
import com.evcompanion.core.obd.ObdConnectionState
import com.evcompanion.core.obd.ObdTransport
import com.evcompanion.core.obd.ObdTransportException
import com.evcompanion.core.obd.ObdTransportFactory
import com.evcompanion.core.obd.dtc.DtcCode
import com.evcompanion.core.obd.dtc.DtcClearResult
import com.evcompanion.core.obd.dtc.DtcScanResult
import com.evcompanion.core.obd.dtc.DtcScanState
import com.evcompanion.core.obd.dtc.DtcScanner
import com.evcompanion.core.preset.PidCategory
import com.evcompanion.core.preset.Preset
import com.evcompanion.core.preset.PresetEvaluator
import com.evcompanion.core.preset.QueryGroup
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Single source of truth for live vehicle data, shared by the phone UI and the Car App Library
 * screens (same process, see the plan's "single process" decision - no IPC needed).
 *
 * Implements tiered adaptive polling modeled after professional automotive scan tools
 * (Torque Pro, Car Scanner ELM OBD2):
 * 1. High-frequency polling (FAST tier ~150ms) for dynamic powertrain telemetry (power,
 *    current, voltage, motor RPM, accelerator pedal position) to provide smooth, real-time gauges.
 * 2. Medium tier (~2000ms) for moderate-frequency telemetry (min/max cell voltages, lighting).
 * 3. Slow tier (~8000ms) for slow-changing indicators (aux 12V battery, climate temperatures, SOH).
 * 4. Automatic ECU safety throttling: low-speed body ECUs (TPMS, Climate FATC, Cluster Odometer)
 *    are strictly rate-limited regardless of subscription tier, preventing Central Gateway congestion
 *    and CAN bus flooding that previously caused the car's own instrument cluster to lose readings.
 * 5. Sticky header batching: queries sharing the current header are executed first, eliminating
 *    wasteful ATSH round-trips.
 */
class PollingEngine(
    private val transportFactory: ObdTransportFactory,
    private val scope: CoroutineScope,
    private val lastDeviceRepository: LastObdDeviceRepository? = null,
    private val pollIntervalMillis: Long = FAST_POLL_INTERVAL_MILLIS,
    private val timeProvider: () -> Long = { System.currentTimeMillis() },
) {
    private val _connectionState = MutableStateFlow<ObdConnectionState>(ObdConnectionState.Disconnected)
    val connectionState: StateFlow<ObdConnectionState> = _connectionState.asStateFlow()

    private val _values = MutableStateFlow<Map<String, DecodedValue>>(emptyMap())
    val values: StateFlow<Map<String, DecodedValue>> = _values.asStateFlow()

    private val _activePresetState = MutableStateFlow<Preset?>(null)
    /** Public read of the active preset - e.g. so a screen can look up which category a
     * user-picked PID name belongs to before subscribing. */
    val activePresetState: StateFlow<Preset?> = _activePresetState.asStateFlow()

    private var transport: ObdTransport? = null
    private var driver: Elm327Driver? = null
    private var connectionForwardingJob: Job? = null

    private val connectionLock = Mutex()
    private val lock = Mutex()
    private var activePreset: Preset? = null
    private val lastKnownRaw = mutableMapOf<String, Double>()
    private var pollJob: Job? = null
    private var connectJob: Job? = null
    private var lastDeviceAddress: String? = null

    // Group-level next poll timestamp tracking (epoch ms)
    private val nextPollAt = mutableMapOf<QueryGroup, Long>()

    // Subscriptions use a plain synchronized block so Car App Library Screen onDestroy can call synchronously
    private val subscribersLock = Any()
    private val subscriberCounts = mutableMapOf<PidCategory, Int>()
    private val nameSubscriberCounts = mutableMapOf<String, Int>()
    private val slowNameSubscriberCounts = mutableMapOf<String, Int>()
    private val mediumNameSubscriberCounts = mutableMapOf<String, Int>()

    val dtcScanner = DtcScanner()
    private val efficiencyTracker = TripEfficiencyTracker()

    private val _dtcScanState = MutableStateFlow<DtcScanState>(DtcScanState.Idle)
    val dtcScanState: StateFlow<DtcScanState> = _dtcScanState.asStateFlow()

    private val diagnosticMutex = Mutex()
    @Volatile
    private var isDiagnosticRunning = false
    private var dtcScanJob: Job? = null

    suspend fun <T> runDiagnosticRoutine(action: suspend (Elm327Driver) -> T): T = diagnosticMutex.withLock {
        isDiagnosticRunning = true
        try {
            val currentDriver = driver ?: throw ObdTransportException("Not connected to vehicle")
            action(currentDriver)
        } finally {
            isDiagnosticRunning = false
            driver?.resetHeaderTracking()
        }
    }

    fun startDtcScan() {
        val currentJob = dtcScanJob
        if (currentJob?.isActive == true) return
        dtcScanJob = scope.launch {
            val currentDriver = driver
            if (currentDriver == null) {
                _dtcScanState.value = DtcScanState.Error("Not connected to vehicle")
                return@launch
            }
            val headers = DtcScanner.discoverTargetHeaders(activePreset)
            val firstH = headers.firstOrNull()
            _dtcScanState.value = DtcScanState.Scanning(firstH, 0f, DtcScanner.resolveEcuName(firstH))
            try {
                val result = runDiagnosticRoutine { drv ->
                    dtcScanner.scan(drv, headers) { header, scanned, total, ecuName ->
                        _dtcScanState.value = DtcScanState.Scanning(header, scanned.toFloat() / total.toFloat(), ecuName)
                    }
                }
                _dtcScanState.value = DtcScanState.Completed(result)
            } catch (e: CancellationException) {
                _dtcScanState.value = DtcScanState.Idle
                throw e
            } catch (e: Exception) {
                _dtcScanState.value = DtcScanState.Error(e.message ?: "Scan failed")
            }
        }
    }

    fun clearDtc(isCarParkedOrStopped: Boolean) {
        val currentJob = dtcScanJob
        if (currentJob?.isActive == true) return
        dtcScanJob = scope.launch {
            val currentDriver = driver
            if (currentDriver == null) {
                _dtcScanState.value = DtcScanState.Error("Not connected to vehicle")
                return@launch
            }
            val headers = DtcScanner.discoverTargetHeaders(activePreset)
            _dtcScanState.value = DtcScanState.Clearing(0f)
            try {
                val clearResult = runDiagnosticRoutine { drv ->
                    dtcScanner.clear(drv, headers, isCarParkedOrStopped) { _, cleared, total ->
                        _dtcScanState.value = DtcScanState.Clearing(cleared.toFloat() / total.toFloat())
                    }
                }
                if (!clearResult.success) {
                    _dtcScanState.value = DtcScanState.Error(clearResult.errorMessage ?: "Failed to clear codes")
                } else {
                    // Re-scan to verify
                    val verifyResult = runDiagnosticRoutine { drv ->
                        dtcScanner.scan(drv, headers) { header, scanned, total, ecuName ->
                            _dtcScanState.value = DtcScanState.Scanning(header, scanned.toFloat() / total.toFloat(), ecuName)
                        }
                    }
                    _dtcScanState.value = DtcScanState.Completed(verifyResult)
                }
            } catch (e: CancellationException) {
                _dtcScanState.value = DtcScanState.Idle
                throw e
            } catch (e: Exception) {
                _dtcScanState.value = DtcScanState.Error(e.message ?: "Clear failed")
            }
        }
    }

    fun connectAsync(deviceAddress: String) {
        val previous = connectJob
        connectJob = scope.launch {
            previous?.cancelAndJoin()
            connect(deviceAddress)
        }
    }

    suspend fun connect(deviceAddress: String) {
        if (!connectionLock.tryLock()) {
            DebugFileLogger.w(TAG, "connect($deviceAddress) ignored - a connection attempt is already in progress")
            return
        }
        try {
            lastDeviceAddress = deviceAddress
            _values.value = emptyMap()
            repeat(MAX_CONNECT_ATTEMPTS) { attempt ->
                try {
                    transport?.disconnect()
                    val newTransport = transportFactory.create(deviceAddress)
                    transport = newTransport
                    driver = Elm327Driver(newTransport)

                    connectionForwardingJob?.cancel()
                    connectionForwardingJob = scope.launch {
                        newTransport.connectionState.collect { _connectionState.value = it }
                    }

                    newTransport.connect(deviceAddress)
                    driver?.initialize()
                    ensurePolling()

                    val gotData = withTimeoutOrNull(DATA_TIMEOUT_MILLIS) { values.first { it.isNotEmpty() } } != null
                    if (gotData) {
                        // A replay isn't a real adapter - don't auto-"reconnect" to it next launch.
                        if (!deviceAddress.startsWith(com.evcompanion.core.obd.ReplayObdTransport.ADDRESS_PREFIX)) {
                            lastDeviceRepository?.save(deviceAddress)
                        }
                        return
                    }
                    DebugFileLogger.w(TAG, "connect($deviceAddress): no data within ${DATA_TIMEOUT_MILLIS}ms (attempt ${attempt + 1}/$MAX_CONNECT_ATTEMPTS) - retrying")
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugFileLogger.w(TAG, "connect($deviceAddress) attempt ${attempt + 1}/$MAX_CONNECT_ATTEMPTS failed: ${e.message}")
                }
            }
            connectionForwardingJob?.cancel()
            transport?.disconnect()
            transport = null
            driver = null
            _connectionState.value = ObdConnectionState.Error("No data after $MAX_CONNECT_ATTEMPTS attempts")
            DebugFileLogger.e(TAG, "connect($deviceAddress): giving up after $MAX_CONNECT_ATTEMPTS attempts with no data")
        } finally {
            connectionLock.unlock()
        }
    }

    suspend fun disconnect() {
        connectJob?.cancelAndJoin()
        connectJob = null
        dtcScanJob?.cancel()
        dtcScanJob = null
        _dtcScanState.value = DtcScanState.Idle
        efficiencyTracker.reset()
        pollJob?.cancel()
        pollJob = null
        connectionForwardingJob?.cancel()
        connectionForwardingJob = null
        transport?.disconnect()
        transport = null
        driver = null
        _connectionState.value = ObdConnectionState.Disconnected
        lock.withLock {
            lastKnownRaw.clear()
            nextPollAt.clear()
            _values.value = emptyMap()
        }
    }

    suspend fun setActivePreset(preset: Preset) = lock.withLock {
        activePreset = preset
        _activePresetState.value = preset
        lastKnownRaw.clear()
        nextPollAt.clear()
        _values.value = emptyMap()
    }

    fun subscribe(category: PidCategory) {
        synchronized(subscribersLock) { subscriberCounts[category] = (subscriberCounts[category] ?: 0) + 1 }
        ensurePolling()
    }

    fun unsubscribe(category: PidCategory) = synchronized(subscribersLock) {
        val count = (subscriberCounts[category] ?: 0) - 1
        if (count <= 0) subscriberCounts.remove(category) else subscriberCounts[category] = count
        Unit
    }

    fun subscribeNames(names: Set<String>) {
        synchronized(subscribersLock) {
            names.forEach { nameSubscriberCounts[it] = (nameSubscriberCounts[it] ?: 0) + 1 }
        }
        ensurePolling()
    }

    fun unsubscribeNames(names: Set<String>) = synchronized(subscribersLock) {
        names.forEach { name ->
            val count = (nameSubscriberCounts[name] ?: 0) - 1
            if (count <= 0) nameSubscriberCounts.remove(name) else nameSubscriberCounts[name] = count
        }
    }

    fun subscribeSlowNames(names: Set<String>) {
        synchronized(subscribersLock) {
            names.forEach { slowNameSubscriberCounts[it] = (slowNameSubscriberCounts[it] ?: 0) + 1 }
        }
        ensurePolling()
    }

    fun unsubscribeSlowNames(names: Set<String>) = synchronized(subscribersLock) {
        names.forEach { name ->
            val count = (slowNameSubscriberCounts[name] ?: 0) - 1
            if (count <= 0) slowNameSubscriberCounts.remove(name) else slowNameSubscriberCounts[name] = count
        }
    }

    fun subscribeMediumNames(names: Set<String>) {
        synchronized(subscribersLock) {
            names.forEach { mediumNameSubscriberCounts[it] = (mediumNameSubscriberCounts[it] ?: 0) + 1 }
        }
        ensurePolling()
    }

    fun unsubscribeMediumNames(names: Set<String>) = synchronized(subscribersLock) {
        names.forEach { name ->
            val count = (mediumNameSubscriberCounts[name] ?: 0) - 1
            if (count <= 0) mediumNameSubscriberCounts.remove(name) else mediumNameSubscriberCounts[name] = count
        }
    }

    private fun ensurePolling() {
        if (pollJob?.isActive == true) return
        pollJob = scope.launch { pollLoop() }
    }

    /**
     * Determines safe minimum polling intervals per ECU header and PID.
     * Slow body ECUs (TPMS, Climate FATC, Cluster CLU) are strictly rate-limited
     * regardless of requested subscription tier to protect them from bus flooding,
     * which previously caused the car's own instrument cluster to lose readings.
     */
    fun minimumSafeInterval(header: String?, modeAndPid: String): Long {
        val h = header?.uppercase() ?: ""
        val pid = modeAndPid.removePrefix("0x").removePrefix("0X").uppercase()
        return when {
            // TPMS ECU is low-speed and easily congested; causes cluster dropouts if polled fast
            h == "7A0" -> 25_000L
            // Cluster (CLU) / Odometer
            h == "730" -> 30_000L
            // Climate FATC: cabin/outdoor temperatures change slowly
            h == "7B3" -> 8_000L
            // Body control module & Integrated body unit (lights/switches)
            h in listOf("770", "7C6") -> 1_500L
            // BMS Aux 12V battery & SOH
            h == "7E4" && pid == "220105" -> 5_000L
            // BMS bulk individual cell voltages (lots of CAN frames)
            h == "7E4" && pid in listOf("220102", "220103", "220104", "22010A", "22010B", "22010C") -> 3_000L
            // Powertrain BMS live data & MCU motor RPM / accelerator
            h in listOf("7E4", "7E2") -> 100L
            // Standard OBD-II Mode 01
            h.isEmpty() -> when {
                pid.startsWith("010D") -> 100L // Speed
                pid.startsWith("01A6") -> 30_000L // Odometer
                else -> 1_000L
            }
            else -> 500L
        }
    }

    /** Maps all active subscriptions to their query groups and effective poll intervals in ms. */
    private fun getActiveQueryGroups(
        preset: Preset,
        categories: Set<PidCategory>,
        fastNames: Set<String>,
        mediumNames: Set<String>,
        slowNames: Set<String>,
    ): Map<QueryGroup, Long> {
        val categoryNames = categories.flatMap { cat -> preset.rowsInCategory(cat).map { it.name } }.toSet()

        // A calc row subscribed at a tier needs its raw dependencies queried at that same tier -
        // matched by the calc row's own name, not by whether a dependency's name happens to
        // already be separately subscribed (which would silently skip any raw PID that isn't -
        // e.g. a tile subscribing only to a calculated/derived name, with no direct subscription
        // to the raw rows it depends on, would otherwise never actually query them).
        fun expand(names: Set<String>): Set<String> =
            names + preset.calculatedRows.filter { it.name in names }.flatMap { it.formula.referencedPidNames }

        val fullFastNames = expand(fastNames + categoryNames)
        val fullMediumNames = expand(mediumNames)
        val fullSlowNames = expand(slowNames)
        if (fullFastNames.isEmpty() && fullMediumNames.isEmpty() && fullSlowNames.isEmpty()) return emptyMap()

        val activeMap = mutableMapOf<QueryGroup, Long>()
        for (group in preset.queryGroups) {
            val groupNames = group.rows.map { it.name }
            val isFast = groupNames.any { it in fullFastNames }
            val isMedium = !isFast && groupNames.any { it in fullMediumNames }
            val isSlow = !isFast && !isMedium && groupNames.any { it in fullSlowNames }

            if (!isFast && !isMedium && !isSlow) continue

            val requested = when {
                isFast -> FAST_POLL_INTERVAL_MILLIS
                isMedium -> MEDIUM_POLL_INTERVAL_MILLIS
                else -> SLOW_POLL_INTERVAL_MILLIS
            }
            val minSafe = minimumSafeInterval(group.header, group.modeAndPid)
            activeMap[group] = maxOf(requested, minSafe)
        }
        return activeMap
    }

    private suspend fun pollLoop() {
        var consecutiveFailedCycles = 0
        while (true) {
            if (isDiagnosticRunning) {
                delay(80L)
                continue
            }
            val preset = activePreset
            val currentDriver = driver
            val categories: Set<PidCategory>
            val names: Set<String>
            val mediumNames: Set<String>
            val slowNames: Set<String>
            synchronized(subscribersLock) {
                categories = subscriberCounts.keys.toSet()
                names = nameSubscriberCounts.keys.toSet()
                mediumNames = mediumNameSubscriberCounts.keys.toSet()
                slowNames = slowNameSubscriberCounts.keys.toSet()
            }
            if (preset == null || currentDriver == null ||
                (categories.isEmpty() && names.isEmpty() && mediumNames.isEmpty() && slowNames.isEmpty())
            ) {
                delay(pollIntervalMillis)
                continue
            }

            val activeGroups = getActiveQueryGroups(preset, categories, names, mediumNames, slowNames)
            if (activeGroups.isEmpty()) {
                delay(pollIntervalMillis)
                continue
            }

            val now = timeProvider()
            val dueGroups = activeGroups.filter { (group, _) -> now >= (nextPollAt[group] ?: 0L) }.keys

            if (dueGroups.isEmpty()) {
                val nextDueTime = activeGroups.keys.minOfOrNull { nextPollAt[it] ?: 0L } ?: (now + 50L)
                val sleepMs = (nextDueTime - now).coerceIn(10L, 200L)
                delay(sleepMs)
                continue
            }

            // Sticky header sorting: prioritize groups matching the current header to avoid ATSH switching,
            // then cluster the rest by header.
            val currentHeader = currentDriver.currentHeader ?: ""
            val sortedDueGroups = dueGroups.sortedWith(
                compareBy(
                    { if ((it.header ?: "") == currentHeader) 0 else 1 },
                    { it.header ?: "" },
                ),
            )

            val cycleStartMillis = timeProvider()
            var anySuccess = false
            for (group in sortedDueGroups) {
                try {
                    // Same mutex as runDiagnosticRoutine: the isDiagnosticRunning check above is
                    // only per-cycle, so without this a scan/clear started mid-cycle would
                    // interleave ELM327 commands (and ATSH header state) with this request.
                    val bytes = diagnosticMutex.withLock { currentDriver.requestPid(group.modeAndPid, group.header) }
                    val decoded = PresetEvaluator.decodeGroup(group, bytes) { row, e ->
                        DebugFileLogger.w(TAG, "Failed to decode '${row.name}': ${e.message}")
                    }
                    val hex = bytes.joinToString("") { "%02X".format(it) }
                    DebugFileLogger.i(TAG, "DATA [${group.header} ${group.modeAndPid}]: $hex -> ${decoded.entries.joinToString { "${it.key}=${it.value}" }}")
                    lock.withLock { lastKnownRaw.putAll(decoded) }
                    val interval = activeGroups[group] ?: FAST_POLL_INTERVAL_MILLIS
                    nextPollAt[group] = timeProvider() + interval
                    anySuccess = true
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    DebugFileLogger.w(TAG, "Query failed for header=${group.header} pid=${group.modeAndPid}: ${e.message}")
                    currentDriver.resetHeaderTracking()
                    // Back off failing group temporarily so it doesn't repeatedly stall the loop
                    nextPollAt[group] = timeProvider() + 2500L
                }
            }

            if (anySuccess) {
                consecutiveFailedCycles = 0
                val (rawSnapshot, calculated) = lock.withLock {
                    val calc = PresetEvaluator.evaluateCalculated(preset.calculatedRows, lastKnownRaw)
                    lastKnownRaw.toMap() to calc
                }

                val rowsByName = preset.rows.associateBy { it.name }
                val updatedNow = timeProvider()
                val merged = (rawSnapshot + calculated).mapNotNull { (name, value) ->
                    val row = rowsByName[name] ?: return@mapNotNull null
                    name to DecodedValue(name, row.shortName.ifBlank { row.name }, row.category, value, row.unit, updatedNow)
                }.toMap().toMutableMap()

                // Synthesize efficiency metrics
                val speedEntry = findSnapshotEntry(rawSnapshot, listOf("Real Vehicle Speed", "001_Real Vehicle Speed", "Vehicle Speed", "000_Vehicle Speed"))
                val speedUnit = speedEntry?.let { rowsByName[it.key]?.unit?.replace("/", "")?.lowercase() }
                val speedMph = speedEntry?.value?.let { if (speedUnit == "kmh" || speedUnit == "kph") it * 0.621371 else it }

                val powerKw = findSnapshotValue(rawSnapshot, listOf("Battery Power", "000_Battery Power"))
                val cedKwh = findSnapshotValue(rawSnapshot, listOf("Cumulative Energy Discharged", "000_Cumulative Energy Discharged"))
                val cecKwh = findSnapshotValue(rawSnapshot, listOf("Cumulative Energy Charged", "000_Cumulative Energy Charged"))
                val odoMiles = findSnapshotValue(rawSnapshot, listOf("002_Odometer", "Odometer", "001_Odometer", "000_Odometer"))

                val eff = efficiencyTracker.update(speedMph, powerKw, cedKwh, cecKwh, odoMiles)

                if (eff.tripAverageMiPerKwh != null) {
                    val dv = DecodedValue("Trip Efficiency", "TRIP AVG", PidCategory.CALCULATED, eff.tripAverageMiPerKwh, "mi/kWh", updatedNow)
                    merged["Trip Efficiency"] = dv
                    merged["004_CALC Trip Efficiency"] = dv
                }
                if (eff.instantMiPerKwh != null) {
                    val dv = DecodedValue("Instant Efficiency", "INSTANT", PidCategory.CALCULATED, eff.instantMiPerKwh, "mi/kWh", updatedNow)
                    merged["Instant Efficiency"] = dv
                    merged["004_CALC Instant Efficiency"] = dv
                } else if (eff.isRegen) {
                    val dv = DecodedValue("Instant Efficiency", "INSTANT", PidCategory.CALCULATED, -1.0, "REGEN", updatedNow)
                    merged["Instant Efficiency"] = dv
                    merged["004_CALC Instant Efficiency"] = dv
                }
                if (eff.tripDistanceMiles != null) {
                    val dv = DecodedValue("Trip Distance", "TRIP MI", PidCategory.CALCULATED, eff.tripDistanceMiles, "mi", updatedNow)
                    merged["Trip Distance"] = dv
                    merged["004_CALC Trip Distance"] = dv
                }
                if (eff.tripNetKwh != null) {
                    val dv = DecodedValue("Trip Energy", "NET kWh", PidCategory.CALCULATED, eff.tripNetKwh, "kWh", updatedNow)
                    merged["Trip Energy"] = dv
                    merged["004_CALC Trip Energy"] = dv
                }

                // Synthesize DTC metrics
                val dtcState = _dtcScanState.value
                val dtcCount = if (dtcState is DtcScanState.Completed) dtcState.result.codeCount.toDouble() else 0.0
                val dtcClean = if (dtcState is DtcScanState.Completed && dtcState.result.isClean) 1.0 else 0.0
                val dtcCountDv = DecodedValue("DTC Code Count", "CODES", PidCategory.CALCULATED, dtcCount, "", updatedNow)
                merged["DTC Code Count"] = dtcCountDv
                merged["004_CALC DTC Code Count"] = dtcCountDv

                val dtcCleanDv = DecodedValue("DTC Scanned Clean", "CLEAN", PidCategory.CALCULATED, dtcClean, "", updatedNow)
                merged["DTC Scanned Clean"] = dtcCleanDv
                merged["004_CALC DTC Scanned Clean"] = dtcCleanDv

                _values.value = merged
                DebugFileLogger.i(TAG, "poll cycle: ${sortedDueGroups.size} groups in ${timeProvider() - cycleStartMillis}ms")
            } else {
                consecutiveFailedCycles++
                val address = lastDeviceAddress
                if (consecutiveFailedCycles >= RECONNECT_AFTER_FAILED_CYCLES && connectJob?.isActive != true && address != null) {
                    DebugFileLogger.w(TAG, "$consecutiveFailedCycles consecutive fully-failed poll cycles - reconnecting to $address")
                    consecutiveFailedCycles = 0
                    connectAsync(address)
                }
            }

            val nowAfter = timeProvider()
            val nextDueTime = activeGroups.keys.minOfOrNull { nextPollAt[it] ?: 0L } ?: (nowAfter + 50L)
            val sleepMs = (nextDueTime - nowAfter).coerceIn(10L, 100L)
            delay(sleepMs)
        }
    }

    private fun findSnapshotValue(snapshot: Map<String, Double>, candidates: List<String>): Double? =
        findSnapshotEntry(snapshot, candidates)?.value

    private fun findSnapshotEntry(snapshot: Map<String, Double>, candidates: List<String>): Map.Entry<String, Double>? =
        candidates.firstNotNullOfOrNull { name -> snapshot.entries.firstOrNull { it.key == name } }
            ?: candidates.firstNotNullOfOrNull { name -> snapshot.entries.firstOrNull { it.key.contains(name, ignoreCase = true) } }

    companion object {
        private const val TAG = "PollingEngine"
        private const val MAX_CONNECT_ATTEMPTS = 5
        private const val DATA_TIMEOUT_MILLIS = 8000L
        const val FAST_POLL_INTERVAL_MILLIS = 150L
        const val MEDIUM_POLL_INTERVAL_MILLIS = 2000L
        const val SLOW_POLL_INTERVAL_MILLIS = 8000L
        private const val RECONNECT_AFTER_FAILED_CYCLES = 5
    }
}
