package com.evcompanion.car.screens

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import android.view.Surface
import androidx.car.app.AppManager
import androidx.car.app.CarContext
import androidx.car.app.Screen
import androidx.car.app.SurfaceCallback
import androidx.car.app.SurfaceContainer
import androidx.car.app.model.Action
import androidx.car.app.model.ActionStrip
import androidx.car.app.model.CarIcon
import androidx.car.app.model.Template
import androidx.car.app.navigation.model.NavigationTemplate
import androidx.core.graphics.drawable.IconCompat
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.evcompanion.AppContainer
import com.evcompanion.R
import com.evcompanion.car.screens.dashboard.GaugeDrawing
import com.evcompanion.car.screens.dashboard.GraphHistoryTracker
import com.evcompanion.car.screens.dashboard.MotorDriveTypeTracker
import com.evcompanion.core.dashboard.DashboardConfig
import com.evcompanion.core.debug.DebugFileLogger
import com.evcompanion.core.dashboard.DetailPanelDef
import com.evcompanion.core.dashboard.DetailPanelType
import com.evcompanion.core.dashboard.DetailViewDef
import com.evcompanion.core.dashboard.HeroType
import com.evcompanion.core.dashboard.TileDef
import com.evcompanion.core.dashboard.findValue
import com.evcompanion.core.polling.DecodedValue
import com.evcompanion.core.settings.MotorDriveType
import com.evcompanion.core.theme.DashboardTheme
import com.evcompanion.core.theme.DefaultDashboardTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Promote the tile at [slot] (0-based index into the secondary grid, row-major) to the big slot.
 * The tile currently in the big slot swaps back into the grid slot that was tapped, so every tile
 * of the page stays visible. A tap outside the grid's slots is a no-op.
 */
fun promoteAt(order: MutableList<Int>, slot: Int) {
    val pos = slot + 1
    if (pos in 1 until order.size) {
        val promoted = order[pos]
        order[pos] = order[0]
        order[0] = promoted
    }
}

/**
 * EXPERIMENTAL hand-drawn dashboard, rendered directly onto the Car App Library's drawing
 * surface - which the host only grants to apps declaring the NAVIGATION category (see
 * AndroidManifest.xml). This app does no real turn-by-turn navigation; NAVIGATION was declared
 * specifically to unlock this surface, a deliberate personal-use-only stretch of the category
 * (see project notes on the tradeoffs before doing this). If that ever causes trouble with the
 * Play developer account, this screen and the category declaration are the first things to
 * revert - the ListTemplate-based screens (HomeScreen, LiveDataScreen, etc.) are unaffected and
 * work under any category.
 *
 * Modeled on the real IONIQ 5 Companion iOS app's CarPlay screens: one big primary tile sitting
 * on the driver's side of the screen, plus a static 2-row grid of smaller tiles filling the rest -
 * no swiping needed, every tile of the page is always on screen at once, tap one to promote it and
 * the previous big tile swaps back into that slot. Tapping the *already-promoted* big tile again
 * pushes a combined detail screen if it defines one - see [TileDef.detail].
 * [com.evcompanion.core.settings.DashboardLayoutConfig] remembers which side the driver's seat is
 * on so the big tile sits under the driver's eye either way; the action-strip's swap icon flips it.
 *
 * The whole layout - pages, tiles, gauge ranges/colors, detail views - comes from
 * [com.evcompanion.core.dashboard.DashboardConfigRepository] instead of being hardcoded here; see
 * `assets/dashboards/default.json` for the bundled layout and
 * [com.evcompanion.core.dashboard.DashboardConfigParser] for the file format.
 *
 * Visual appearance is styled by [DashboardTheme] (e.g. Classic Dark vs. IONIQ 5 Companion
 * CarPlay style), configurable via the action strip, home screen, or phone UI.
 *
 * Taps ([SurfaceCallback.onClick]) report surface-pixel coordinates, and every rect here is laid
 * out in the same absolute surface space as it is drawn, so hit-testing aligns with what's on
 * screen by construction.
 */
class DashboardScreen(carContext: CarContext, private val container: AppContainer) : Screen(carContext) {

    /** Fixed layout: a big tile on the driver's side, a static 2-row grid filling the rest. */
    private class PageLayout(
        val bigRect: Rect,
        val gridLeft: Int,
        val gridTop: Int,
        val cardW: Int,
        val cardH: Int,
        val gapX: Int,
        val gapY: Int,
        val cols: Int,
    )

    private var surface: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0
    private var visibleArea: Rect? = null
    private var isStarted = false

    private var config: DashboardConfig? = null
    private var activeTheme: DashboardTheme = DefaultDashboardTheme
    private var page = 0
    private var driverOnRight = false
    private var motorDriveType: MotorDriveType = MotorDriveType.UNKNOWN
    private val motorDriveTracker = MotorDriveTypeTracker()
    private var motorFrontFadeProgress = 1f
    private var motorFadeJob: Job? = null
    private var subscribedNames: Set<String> = emptySet()
    private var subscribedMediumNames: Set<String> = emptySet()
    private var subscribedSlowNames: Set<String> = emptySet()
    private var latestValues: Map<String, DecodedValue> = emptyMap()

    // Per-page display order of catalog indices into that page's tiles: index 0 is the promoted
    // big value, the rest fill the secondary grid row-major. Keyed by page index (not a fixed enum
    // any more, since pages/tiles now come from config) and reset whenever the config reloads.
    private val orderByPage = mutableMapOf<Int, MutableList<Int>>()

    // Every GRAPH-hero tile across every page - recorded regardless of which page is showing (so
    // promoting one isn't an empty graph). Recomputed only when the config reloads (see the
    // activeConfig collector below), not on every values tick.
    private var graphTiles: List<TileDef> = emptyList()
    private val graphHistory = GraphHistoryTracker()

    // Detail-view mode: tapping an already-promoted hero tile a second time used to push a
    // separate TileDetailScreen - but this host (confirmed on both headlessunit and a real head
    // unit over aawireless2+) never hands a pushed Screen a lockable surface: lockCanvas() threw
    // on every single poll tick for the screen's entire life, with no recovery no matter how long
    // it was left running. Rendering the detail view as a mode switch on this screen's own
    // surface - which has always worked - sidesteps the host limitation entirely instead of
    // working around it.
    private var activeDetail: DetailViewDef? = null
    private val detailGraphHistory = GraphHistoryTracker()
    private var detailSubscribedNames: Set<String> = emptySet()

    private var titleVisibilityProgress = 1.0f
    private var titleHideJob: Job? = null
    private var titleAnimJob: Job? = null

    private fun showTitle(autoHideDelayMillis: Long = 4000L) {
        titleHideJob?.cancel()
        titleAnimJob?.cancel()

        if (titleVisibilityProgress < 1.0f) {
            titleAnimJob = lifecycleScope.launch {
                val startProgress = titleVisibilityProgress
                val animDuration = 180L
                val startTime = System.currentTimeMillis()
                while (true) {
                    val elapsed = System.currentTimeMillis() - startTime
                    val frac = (elapsed.toFloat() / animDuration).coerceIn(0f, 1f)
                    val eased = frac * frac * (3f - 2f * frac)
                    titleVisibilityProgress = startProgress + (1.0f - startProgress) * eased
                    redraw()
                    if (frac >= 1f) break
                    delay(16L)
                }
                titleVisibilityProgress = 1.0f
                redraw()
                scheduleTitleHide(autoHideDelayMillis)
            }
        } else {
            redraw()
            scheduleTitleHide(autoHideDelayMillis)
        }
    }

    private fun scheduleTitleHide(autoHideDelayMillis: Long) {
        titleHideJob?.cancel()
        titleHideJob = lifecycleScope.launch {
            delay(autoHideDelayMillis)
            val startProgress = titleVisibilityProgress
            val animDuration = 350L
            val startTime = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                val frac = (elapsed.toFloat() / animDuration).coerceIn(0f, 1f)
                val eased = frac * frac * (3f - 2f * frac)
                titleVisibilityProgress = startProgress * (1.0f - eased)
                redraw()
                if (frac >= 1f) break
                delay(16L)
            }
            titleVisibilityProgress = 0.0f
            redraw()
        }
    }

    private val surfaceCallback = object : SurfaceCallback {
        override fun onSurfaceAvailable(surfaceContainer: SurfaceContainer) {
            surface = surfaceContainer.surface
            surfaceWidth = surfaceContainer.width
            surfaceHeight = surfaceContainer.height
            DebugFileLogger.i(TAG, "onSurfaceAvailable surfaceWidth=$surfaceWidth surfaceHeight=$surfaceHeight")
            showTitle(4000L)
            redraw()
        }

        override fun onVisibleAreaChanged(visibleArea: Rect) {
            this@DashboardScreen.visibleArea = visibleArea
            DebugFileLogger.i(TAG, "onVisibleAreaChanged visibleArea=$visibleArea titleRowHeight=${titleRowHeight(visibleArea)}")
            redraw()
        }

        override fun onClick(x: Float, y: Float) {
            super.onClick(x, y)
            DebugFileLogger.i(TAG, "onClick x=$x y=$y")

            if (activeDetail != null) {
                DebugFileLogger.i(TAG, "onClick -> exit detail view")
                activeDetail = null
                updateDetailSubscriptions()
                invalidate()
                redraw()
                return
            }

            val area = currentArea() ?: return
            val cfg = config ?: return

            val maxTitleH = titleRowHeight(area)
            val currentTitleH = (maxTitleH * titleVisibilityProgress).toInt()
            val isTitleVisible = titleVisibilityProgress > 0.2f

            // Left cell is ~70% width of the top row (buttons occupy the right 30%). The hit box
            // tracks currentTitleH (not maxTitleH) so it never grows past the title's actual drawn
            // height - otherwise, mid-fade, a tap on the tile grid underneath (which starts right
            // at currentTitleH, same as the content area below) would be wrongly caught here instead.
            val leftCellRight = area.left + area.width() * 0.70f
            val hitTop = area.top.toFloat()
            val hitBottom = area.top + currentTitleH + 12f

            if (isTitleVisible && x in area.left.toFloat()..leftCellRight && y in hitTop..hitBottom) {
                DebugFileLogger.i(TAG, "onClick -> cyclePage (title hit zone)")
                cyclePage()
                return
            }

            // Any user tap shows/resets the title timer
            showTitle(4000L)

            val tiles = tilesForPage(cfg, page)
            if (tiles.isEmpty()) return
            val order = currentOrder(tiles)
            val secondaryCount = order.size - 1

            val contentArea = Rect(area.left, area.top + currentTitleH, area.right, area.bottom)
            val layout = pageLayout(contentArea, secondaryCount)
            DebugFileLogger.i(
                TAG,
                "onClick area=$area currentTitleH=$currentTitleH bigRect=${layout.bigRect} " +
                    "gridLeft=${layout.gridLeft} gridTop=${layout.gridTop} cardW=${layout.cardW} cardH=${layout.cardH} cols=${layout.cols}",
            )

            if (layout.bigRect.contains(x.toInt(), y.toInt())) {
                val detailId = tiles[order[0]].detail
                if (detailId == null) {
                    DebugFileLogger.w(TAG, "onClick -> hero tap but tile ${tiles[order[0]].id} has no detail set")
                    return
                }
                val detail = cfg.detailViews[detailId]
                if (detail == null) {
                    DebugFileLogger.w(TAG, "onClick -> hero tap but detailViews has no entry for '$detailId'")
                    return
                }
                DebugFileLogger.i(TAG, "onClick -> show detail view for '$detailId'")
                activeDetail = detail
                detailGraphHistory.clear()
                updateDetailSubscriptions()
                invalidate()
                redraw()
                return
            }

            if (secondaryCount < 1) {
                DebugFileLogger.i(TAG, "onClick -> no-op (secondaryCount < 1)")
                return
            }
            val col = ((x - layout.gridLeft) / (layout.cardW + layout.gapX)).toInt()
            val row = ((y - layout.gridTop) / (layout.cardH + layout.gapY)).toInt()
            if (col !in 0 until layout.cols || row !in 0..1) {
                DebugFileLogger.i(TAG, "onClick -> no-op (col=$col row=$row out of range)")
                return
            }
            val slot = row * layout.cols + col
            if (slot >= secondaryCount) {
                DebugFileLogger.i(TAG, "onClick -> no-op (slot=$slot >= secondaryCount=$secondaryCount)")
                return
            }
            DebugFileLogger.i(TAG, "onClick -> promoteAt(slot=$slot)")
            promoteAt(order, slot)
            redraw()
        }

        override fun onSurfaceDestroyed(surfaceContainer: SurfaceContainer) {
            titleHideJob?.cancel()
            titleAnimJob?.cancel()
            surface = null
        }
    }

    init {
        // Registered synchronously here too, not only in onStart below - onStart is dispatched
        // via the lifecycle event queue, but the host can deliver its very first
        // onSurfaceAvailable immediately on this screen's creation, before that dispatch runs.
        // A surface only reports "available" once, so missing it here would leave this screen
        // with no content ever drawn.
        isStarted = true
        carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)

        lifecycle.addObserver(object : DefaultLifecycleObserver {
            // AppManager's surface callback is shared across every Screen in this CarContext, not
            // per-screen - onStart/onStop (not onCreate/onDestroy) are what actually track this
            // screen's real visibility, since it's only ever stopped (covered by HomeScreen when
            // pushed via the menu action), never destroyed. HomeScreen itself never touches the
            // surface callback (it's a plain ListTemplate screen), so re-registering here on
            // return is just reclaiming whatever the host did with the shared slot in the
            // meantime - no symmetric partner screen needed.
            override fun onStart(owner: LifecycleOwner) {
                DebugFileLogger.i(TAG, "onStart - re-registering surfaceCallback")
                isStarted = true
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
                redraw()
            }

            override fun onResume(owner: LifecycleOwner) {
                // STARTED (visible, surfaces connected) isn't the same as RESUMED (actually
                // focused for input) - re-registering again here in case this host only hands
                // back click/input routing at resume, not at start, after a child screen pops.
                DebugFileLogger.i(TAG, "onResume - re-registering surfaceCallback")
                carContext.getCarService(AppManager::class.java).setSurfaceCallback(surfaceCallback)
            }

            override fun onPause(owner: LifecycleOwner) {
                DebugFileLogger.i(TAG, "onPause")
            }

            override fun onStop(owner: LifecycleOwner) {
                // Deliberately NOT nulling the surface callback here - whichever screen becomes
                // visible always re-registers its own callback in its own onStart, so nulling
                // here was never load-bearing. An earlier design that pushed a second
                // NavigationTemplate screen for the tile-detail view showed it was harmful:
                // nulling in onStop here raced that other screen's own registration and could wipe
                // it out before it ever got an onSurfaceAvailable.
                //
                // isStarted = false IS load-bearing, though: this screen's poll-tick collectors
                // (see init) run for its whole lifetime, not just while visible, so without this
                // gate redraw() would keep calling surface.lockCanvas() every tick even while
                // genuinely not visible.
                DebugFileLogger.i(TAG, "onStop")
                isStarted = false
            }

            override fun onDestroy(owner: LifecycleOwner) {
                container.pollingEngine.unsubscribeNames(subscribedNames)
                container.pollingEngine.unsubscribeMediumNames(subscribedMediumNames)
                container.pollingEngine.unsubscribeSlowNames(subscribedSlowNames)
                container.pollingEngine.unsubscribeNames(detailSubscribedNames)
                subscribedNames = emptySet()
                subscribedMediumNames = emptySet()
                subscribedSlowNames = emptySet()
                detailSubscribedNames = emptySet()
            }
        })

        lifecycleScope.launch {
            container.dashboardLayoutConfig.driverOnRight.collect {
                driverOnRight = it
                redraw()
            }
        }

        lifecycleScope.launch {
            container.dashboardLayoutConfig.activeTheme.collect { theme ->
                activeTheme = theme
                redraw()
            }
        }

        lifecycleScope.launch {
            // Seeds the already-known AWD/RWD-only determination once, so a re-pushed screen
            // doesn't have to re-run the 30s detection from scratch - see MotorDriveTypeTracker.
            val persisted = container.dashboardLayoutConfig.motorDriveType.first()
            motorDriveType = persisted
            motorDriveTracker.seed(persisted)
            motorFrontFadeProgress = if (persisted == MotorDriveType.RWD_ONLY) 0f else 1f
            redraw()
        }

        lifecycleScope.launch {
            // A reloaded config can have a different page/tile count than before - stale order
            // indices would be out of range, so start fresh rather than trying to carry them over.
            container.dashboardConfigRepository.activeConfig.collect { newConfig ->
                config = newConfig
                orderByPage.clear()
                page = 0
                graphTiles = newConfig?.pages.orEmpty().flatMap { it.tiles }.filter { it.hero.type == HeroType.GRAPH }
                graphHistory.clear()
                updateSubscriptionsForPage()
                showTitle(4000L)
                redraw()
            }
        }

        lifecycleScope.launch {
            // Re-derives which PIDs to poll whenever the active preset (re)loads, since tile
            // values are looked up by name against it - see neededPidNames().
            container.pollingEngine.activePresetState.collect { updateSubscriptionsForPage() }
        }

        lifecycleScope.launch {
            container.pollingEngine.values.collect { values ->
                latestValues = values
                recordGraphHistory(values)
                activeDetail?.let { recordDetailGraphHistory(it, values) }
                updateMotorDriveType(values)
                redraw()
            }
        }
    }

    private fun updateMotorDriveType(values: Map<String, DecodedValue>) {
        val motorTile = config?.pages.orEmpty().flatMap { it.tiles }.firstOrNull { it.id == "motor_rpm" } ?: return
        val frontValue = motorTile.values.getOrNull(0)?.resolve(values)?.value
        val previous = motorDriveTracker.driveType
        val updated = motorDriveTracker.update(frontValue, System.currentTimeMillis())
        if (updated == previous) return

        motorDriveType = updated
        lifecycleScope.launch { container.dashboardLayoutConfig.setMotorDriveType(updated) }
        animateMotorFrontFade(target = if (updated == MotorDriveType.RWD_ONLY) 0f else 1f)
    }

    private fun animateMotorFrontFade(target: Float) {
        motorFadeJob?.cancel()
        motorFadeJob = lifecycleScope.launch {
            val start = motorFrontFadeProgress
            val animDuration = 800L
            val startTime = System.currentTimeMillis()
            while (true) {
                val elapsed = System.currentTimeMillis() - startTime
                val frac = (elapsed.toFloat() / animDuration).coerceIn(0f, 1f)
                val eased = frac * frac * (3f - 2f * frac)
                motorFrontFadeProgress = start + (target - start) * eased
                redraw()
                if (frac >= 1f) break
                delay(16L)
            }
            motorFrontFadeProgress = target
            redraw()
        }
    }

    override fun onGetTemplate(): Template {
        activeDetail?.let {
            return NavigationTemplate.Builder()
                .setActionStrip(
                    ActionStrip.Builder()
                        .addAction(
                            Action.Builder()
                                .setIcon(CarIcon.BACK)
                                .setOnClickListener {
                                    activeDetail = null
                                    updateDetailSubscriptions()
                                    invalidate()
                                    redraw()
                                }
                                .build()
                        )
                        .build()
                )
                .build()
        }

        val cycleAction = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_dashboard_switch)).build())
            .setOnClickListener { cyclePage() }
            .build()
        val themeAction = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_theme)).build())
            .setOnClickListener {
                lifecycleScope.launch { container.dashboardLayoutConfig.cycleTheme() }
            }
            .build()
        val flipSideAction = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_swap_horiz)).build())
            .setOnClickListener {
                lifecycleScope.launch { container.dashboardLayoutConfig.setDriverOnRight(!driverOnRight) }
            }
            .build()
        val menuAction = Action.Builder()
            .setIcon(CarIcon.Builder(IconCompat.createWithResource(carContext, R.drawable.ic_menu)).build())
            .setOnClickListener { screenManager.push(HomeScreen(carContext, container)) }
            .build()

        return NavigationTemplate.Builder()
            .setActionStrip(
                ActionStrip.Builder()
                    .addAction(cycleAction)
                    .addAction(themeAction)
                    .addAction(flipSideAction)
                    .addAction(menuAction)
                    .build()
            )
            .build()
    }

    private fun cyclePage() {
        val pageCount = config?.pages?.size ?: return
        if (pageCount == 0) return
        page = (page + 1) % pageCount
        updateSubscriptionsForPage()
        showTitle(4000L)
        redraw()
    }

    /**
     * Subscribes the current page's tiles on the fast tier - see [neededPidNames] - the next page
     * in the cycle order on the medium tier, and every other page combined on the slow tier. Not
     * just a fixed three pages any more (a loaded config can define any number) - graduated rates
     * instead of blasting every PID on the fast tier keep the active page snappy while still
     * avoiding a "--" moment when the driver switches to the next page, without crowding out the
     * vehicle's own CAN bus traffic the way querying everything at once would.
     */
    private fun updateSubscriptionsForPage() {
        val cfg = config
        val pageCount = cfg?.pages?.size ?: 0
        val needed = if (cfg != null) neededPidNames(cfg, page) else emptySet()
        val toRemove = subscribedNames - needed
        val toAdd = needed - subscribedNames
        if (toRemove.isNotEmpty()) container.pollingEngine.unsubscribeNames(toRemove)
        if (toAdd.isNotEmpty()) container.pollingEngine.subscribeNames(toAdd)
        subscribedNames = needed

        if (cfg == null || pageCount < 2) {
            if (subscribedMediumNames.isNotEmpty()) container.pollingEngine.unsubscribeMediumNames(subscribedMediumNames)
            if (subscribedSlowNames.isNotEmpty()) container.pollingEngine.unsubscribeSlowNames(subscribedSlowNames)
            subscribedMediumNames = emptySet()
            subscribedSlowNames = emptySet()
            return
        }

        val nextPage = (page + 1) % pageCount
        val neededMedium = neededPidNames(cfg, nextPage) - needed
        val toRemoveMedium = subscribedMediumNames - neededMedium
        val toAddMedium = neededMedium - subscribedMediumNames
        if (toRemoveMedium.isNotEmpty()) container.pollingEngine.unsubscribeMediumNames(toRemoveMedium)
        if (toAddMedium.isNotEmpty()) container.pollingEngine.subscribeMediumNames(toAddMedium)
        subscribedMediumNames = neededMedium

        val otherPages = (0 until pageCount).filter { it != page && it != nextPage }
        val neededSlow = otherPages.flatMap { neededPidNames(cfg, it) }.toSet() - needed - neededMedium
        val toRemoveSlow = subscribedSlowNames - neededSlow
        val toAddSlow = neededSlow - subscribedSlowNames
        if (toRemoveSlow.isNotEmpty()) container.pollingEngine.unsubscribeSlowNames(toRemoveSlow)
        if (toAddSlow.isNotEmpty()) container.pollingEngine.subscribeSlowNames(toAddSlow)
        subscribedSlowNames = neededSlow
    }

    /**
     * Derived from the tile definitions themselves rather than hand-listed: every tile value names
     * the PIDs it reads, so the exact rows to poll fall out of looking those names up in the
     * active preset's rows. Adding or moving a tile can never leave its subscription out of sync
     * with what it actually displays.
     */
    private fun neededPidNames(cfg: DashboardConfig, pageIndex: Int): Set<String> {
        val preset = container.pollingEngine.activePresetState.value ?: return emptySet()
        val tiles = tilesForPage(cfg, pageIndex)
        val needles = tiles.flatMap { it.values }.flatMap { it.pidNames } + tiles.flatMap { it.extraNeedles }
        return preset.rows
            .filter { row -> needles.any { needle -> row.name.contains(needle, ignoreCase = true) } }
            .map { it.name }
            .toSet()
    }

    private fun tilesForPage(cfg: DashboardConfig, pageIndex: Int): List<TileDef> =
        cfg.pages.getOrNull(pageIndex)?.tiles ?: emptyList()

    private fun currentOrder(tiles: List<TileDef>): MutableList<Int> =
        orderByPage.getOrPut(page) { tiles.indices.toMutableList() }

    // ---- Layout ----

    private fun currentArea(): Rect? =
        visibleArea ?: if (surfaceWidth > 0 && surfaceHeight > 0) Rect(0, 0, surfaceWidth, surfaceHeight) else null

    /**
     * Both the drawing and hit-testing derive their rects from this, so they agree. The big tile
     * sits on [driverOnRight]'s side; the rest of the width holds a static 2-row grid sized to fit
     * every one of [secondaryCount] tiles at once - no scrolling.
     */
    private fun pageLayout(area: Rect, secondaryCount: Int): PageLayout {
        val bigW = (area.width() * 0.38f).toInt()
        val bigGap = (area.width() * 0.015f).toInt().coerceAtLeast(6)
        val bigRect = if (driverOnRight) {
            Rect(area.right - bigW, area.top, area.right, area.bottom)
        } else {
            Rect(area.left, area.top, area.left + bigW, area.bottom)
        }
        val gridLeft = if (driverOnRight) area.left else bigRect.right + bigGap
        val gridRight = if (driverOnRight) bigRect.left - bigGap else area.right
        val gridWidth = (gridRight - gridLeft).coerceAtLeast(1)

        val cols = ((secondaryCount + 1) / 2).coerceAtLeast(1)
        val gapX = (gridWidth * 0.02f).toInt().coerceAtLeast(4)
        val gapY = (area.height() * 0.03f).toInt().coerceAtLeast(4)
        val cardW = ((gridWidth - (cols - 1) * gapX) / cols).coerceAtLeast(2)
        val cardH = ((area.height() - gapY) / 2).coerceAtLeast(2)
        return PageLayout(bigRect, gridLeft, area.top, cardW, cardH, gapX, gapY, cols)
    }

    private fun redraw() {
        if (!isStarted) return
        val s = surface ?: return
        if (!s.isValid) return
        val canvas = try {
            s.lockCanvas(null)
        } catch (e: Exception) {
            // This surface can report itself as non-null and valid yet still throw here; dropping
            // our reference stops subsequent redraw() calls from retrying lockCanvas every poll
            // tick until a fresh onSurfaceAvailable actually arrives.
            surface = null
            null
        } ?: return
        try {
            canvas.drawColor(activeTheme.backgroundColor)
            val area = currentArea()
            val cfg = config
            val detail = activeDetail
            if (area != null && area.width() > 0 && area.height() > 0) {
                if (detail != null) {
                    drawDetailPanels(canvas, area, detail)
                } else if (cfg != null) {
                    drawMasterDetail(canvas, area, cfg)
                }
            }
        } finally {
            // The surface can be torn down concurrently (e.g. the host tearing down for a config
            // change right as a poll-tick redraw was in flight, see onSurfaceDestroyed) between
            // lockCanvas and here - losing this one frame is harmless, crashing isn't.
            try {
                s.unlockCanvasAndPost(canvas)
            } catch (e: Exception) {
                // Ignored - surface already gone.
            }
        }
    }

    // Sized to visually match the host's own ActionStrip button row above it (its exact height
    // isn't exposed to the surface callback, so this is a best estimate from on-device screenshots
    // - confirm/adjust next time this ships, since AA only shows a NAVIGATION-category app
    // through a Play Store build, not a quick adb install). Capped to a fraction of the actual
    // available height so a host granting a smaller usable area than assumed here can never have
    // this crowd out most of the content area below it - a fixed pixel floor alone can't do that.
    private fun titleRowHeight(area: Rect): Float =
        (area.height() * 0.22f).coerceIn(90f, 140f).coerceAtMost(area.height() * 0.35f)

    // ---- Rendering: 1 big value on the driver's side + a static 2-row grid of secondary tiles ----

    private fun drawMasterDetail(canvas: Canvas, area: Rect, cfg: DashboardConfig) {
        val tiles = tilesForPage(cfg, page)
        if (tiles.isEmpty()) return

        val maxTitleH = titleRowHeight(area)
        val currentTitleH = (maxTitleH * titleVisibilityProgress).toInt()

        val contentArea = Rect(area.left, area.top + currentTitleH, area.right, area.bottom)

        val order = currentOrder(tiles)
        val layout = pageLayout(contentArea, order.size - 1)
        drawPrimary(canvas, layout, tiles[order[0]])
        drawSecondaryGrid(canvas, layout, order.drop(1).map { tiles[it] })

        if (titleVisibilityProgress > 0f) {
            val titleRect = Rect(area.left, area.top, area.right, (area.top + maxTitleH).toInt())
            val pageDef = cfg.pages.getOrNull(page)
            val labelText = pageDef?.title?.takeIf { it.isNotBlank() } ?: pageDef?.label ?: ""
            drawDashboardTitle(canvas, titleRect, labelText, titleVisibilityProgress, cfg.pages.size)
        }
    }

    // ---- Detail view: ported from the former TileDetailScreen, now rendered in-place on this
    // screen's own surface instead of a pushed Screen - see activeDetail's doc for why. ----

    private fun recordDetailGraphHistory(detail: DetailViewDef, values: Map<String, DecodedValue>) {
        detail.panels.forEachIndexed { panelIndex, panel ->
            if (panel.type != DetailPanelType.GRAPH) return@forEachIndexed
            detailGraphHistory.record("$panelIndex", panel.series, panel.windowSeconds, values)
        }
    }

    private fun neededDetailPidNames(detail: DetailViewDef): Set<String> {
        val preset = container.pollingEngine.activePresetState.value ?: return emptySet()
        val needles = detail.panels.flatMap { it.pidNames } + detail.panels.flatMap { it.series }.flatMap { it.pidNames }
        return preset.rows
            .filter { row -> needles.any { needle -> row.name.contains(needle, ignoreCase = true) } }
            .map { it.name }
            .toSet()
    }

    private fun updateDetailSubscriptions() {
        val needed = activeDetail?.let { neededDetailPidNames(it) } ?: emptySet()
        if (needed == detailSubscribedNames) return
        container.pollingEngine.unsubscribeNames(detailSubscribedNames - needed)
        container.pollingEngine.subscribeNames(needed - detailSubscribedNames)
        detailSubscribedNames = needed
    }

    private fun drawDetailPanels(canvas: Canvas, area: Rect, detail: DetailViewDef) {
        val pad = (minOf(area.width(), area.height()) * 0.02f).toInt().coerceAtLeast(4)
        val inset = Rect(area.left + pad, area.top + pad, area.right - pad, area.bottom - pad)
        GaugeDrawing.gridCells(inset, detail.panels.size).forEachIndexed { i, cell ->
            val panel = detail.panels[i]
            val cardPad = (minOf(cell.rect.width(), cell.rect.height()) * 0.04f).toInt()
            val cardRect = Rect(cell.rect.left + cardPad, cell.rect.top + cardPad, cell.rect.right - cardPad, cell.rect.bottom - cardPad)
            GaugeDrawing.drawCard(canvas, cardRect, activeTheme.defaultCardColor, theme = activeTheme)
            drawDetailPanel(canvas, cardRect, panel, i)
        }
    }

    private fun drawDetailPanel(canvas: Canvas, rect: Rect, panel: DetailPanelDef, panelIndex: Int) {
        when (panel.type) {
            DetailPanelType.GRAPH -> drawDetailGraphPanel(canvas, rect, panel, panelIndex)
            DetailPanelType.DIAL -> drawDetailDialPanel(canvas, rect, panel)
            DetailPanelType.VALUE -> drawDetailValuePanel(canvas, rect, panel)
        }
    }

    private fun detailResolvedValue(panel: DetailPanelDef): DecodedValue? =
        findValue(latestValues, panel.pidNames)?.let { if (panel.negate) it.copy(value = -it.value) else it }

    private fun drawDetailDialPanel(canvas: Canvas, rect: Rect, panel: DetailPanelDef) {
        val value = detailResolvedValue(panel)
        val gauge = panel.gaugeRange
        val fraction = gauge?.let { r -> value?.value?.let { v -> ((v - r.start) / (r.endInclusive - r.start)).coerceIn(0.0, 1.0) } }
        val radius = minOf(rect.width(), rect.height()) * 0.4f
        GaugeDrawing.drawBigGauge(
            canvas, rect.exactCenterX(), rect.exactCenterY(), radius, fraction,
            valueText = value?.value?.let { "${it.toInt()}${if (panel.showUnits) value.unit else ""}" } ?: "--",
            label = panel.label ?: "",
            ringColor = { frac -> panel.gaugeColorRules?.resolve(value?.value, latestValues) ?: activeTheme.resolveGaugeColor(frac) },
            theme = activeTheme,
        )
    }

    private fun drawDetailValuePanel(canvas: Canvas, rect: Rect, panel: DetailPanelDef) {
        val value = detailResolvedValue(panel)
        val unit = if (panel.showUnits) value?.unit ?: "" else ""
        val decimals = if (unit.isBlank() || unit == "%") 0 else 1
        val textSize = (rect.width() * 0.16f).coerceIn(16f, 48f).coerceAtMost(rect.height() * 0.3f)
        GaugeDrawing.drawReadout(
            canvas, rect.exactCenterX(), rect.exactCenterY().toFloat(), textSize,
            panel.label ?: "", GaugeDrawing.formatValue(value?.value, unit, decimals),
            maxWidth = rect.width() * 0.9f,
            theme = activeTheme,
        )
    }

    private fun drawDetailGraphPanel(canvas: Canvas, rect: Rect, panel: DetailPanelDef, panelIndex: Int) {
        val seriesData = panel.series.mapIndexed { i, s ->
            detailGraphHistory.pointsFor("$panelIndex", i) to (s.color ?: activeTheme.defaultGraphColor)
        }
        if (seriesData.all { it.first.size < 2 }) {
            GaugeDrawing.drawReadout(
                canvas, rect.exactCenterX(), rect.exactCenterY().toFloat(), rect.width() * 0.08f,
                panel.label ?: "", "--", theme = activeTheme,
            )
            return
        }
        GaugeDrawing.drawLineGraph(canvas, rect, panel.label ?: "", seriesData, theme = activeTheme, perSeriesNormalize = panel.normalizePerSeries)
    }

    private fun drawDashboardTitle(
        canvas: Canvas,
        rect: Rect,
        label: String,
        visibilityProgress: Float,
        pageCount: Int,
    ) {
        if (visibilityProgress <= 0f || label.isBlank()) return

        val alphaFrac = visibilityProgress.coerceIn(0f, 1f)
        val textC = activeTheme.textPrimaryColor

        // Top row layout: left cell is ~70% width, right cell is ~30% width (ActionStrip buttons sit there).
        val leftCellW = rect.width() * 0.70f
        val padX = (rect.width() * 0.025f).coerceIn(16f, 28f)
        val labelLeft = rect.left + padX
        val cellRight = rect.left + leftCellW - padX
        val maxUsableW = (cellRight - labelLeft).coerceAtLeast(40f)

        // Reserve space for page indicator dots if multiple dashboards exist
        val dotRadius = (rect.height() * 0.055f).coerceIn(3.5f, 9.0f)
        val dotSpacing = dotRadius * 3.5f
        val totalDotsW = if (pageCount > 1) (pageCount - 1) * dotSpacing + dotRadius * 2 else 0f
        val dotsReserve = if (pageCount > 1) totalDotsW + padX else 0f
        val availableTextW = (maxUsableW - dotsReserve).coerceAtLeast(40f)

        // Left-justified title typography scaled to fit cell height and width
        val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = activeTheme.valueTypeface ?: Typeface.DEFAULT_BOLD
            textAlign = Paint.Align.LEFT
        }

        var textSize = (rect.height() * 0.65f).coerceIn(28f, 96f)
        textPaint.textSize = textSize
        val measuredW = textPaint.measureText(label)
        if (measuredW > availableTextW && measuredW > 0f) {
            textSize = (textSize * (availableTextW / measuredW)).coerceAtLeast(14f)
            textPaint.textSize = textSize
        }

        textPaint.color = Color.argb(
            (255 * alphaFrac).toInt(),
            Color.red(textC),
            Color.green(textC),
            Color.blue(textC),
        )

        val centerY = rect.exactCenterY()
        val fontMetrics = textPaint.fontMetrics
        val textBaseline = centerY - (fontMetrics.descent + fontMetrics.ascent) / 2f
        canvas.drawText(label, labelLeft, textBaseline, textPaint)

        // Page indicator dots placed right after the label if multiple dashboards exist
        if (pageCount > 1) {
            val finalLabelW = textPaint.measureText(label)
            val dotStartX = labelLeft + finalLabelW + padX
            val activeDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(
                    (240 * alphaFrac).toInt(),
                    Color.red(textC),
                    Color.green(textC),
                    Color.blue(textC),
                )
            }
            val inactiveDotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.FILL
                color = Color.argb(
                    (70 * alphaFrac).toInt(),
                    Color.red(textC),
                    Color.green(textC),
                    Color.blue(textC),
                )
            }

            for (p in 0 until pageCount) {
                val dx = dotStartX + p * dotSpacing
                val r = if (p == page) dotRadius * 1.15f else dotRadius
                canvas.drawCircle(dx, centerY, r, if (p == page) activeDotPaint else inactiveDotPaint)
            }
        }
    }

    private fun drawPrimary(canvas: Canvas, layout: PageLayout, tile: TileDef) {
        val pad = minOf(layout.bigRect.width(), layout.bigRect.height()) * 0.05f
        val inner = Rect(
            (layout.bigRect.left + pad).toInt(),
            (layout.bigRect.top + pad).toInt(),
            (layout.bigRect.right - pad).toInt(),
            (layout.bigRect.bottom - pad).toInt(),
        )
        val cardColor = tile.backgroundColorRules?.resolve(null, latestValues) ?: activeTheme.defaultCardColor
        GaugeDrawing.drawCard(canvas, inner, cardColor, theme = activeTheme)
        when {
            tile.hero.type == HeroType.GRAPH -> drawGraphHero(canvas, inner, tile)
            tile.hero.type == HeroType.DIAL && tile.values.size > 1 -> {
                // Motor RPM shows the front motor (index 0) by default - once this vehicle is
                // found to be RWD-only, the front reading is never meaningful, so show rear
                // (index 1) instead. See MotorDriveTypeTracker.
                val valueIndex = if (tile.id == "motor_rpm" && motorDriveType == MotorDriveType.RWD_ONLY) 1 else 0
                drawPrimaryValueDialHero(canvas, inner, tile, valueIndex)
            }
            else -> drawTileContent(canvas, inner, tile, big = true)
        }
    }

    private fun drawSecondaryGrid(canvas: Canvas, layout: PageLayout, tiles: List<TileDef>) {
        tiles.forEachIndexed { i, tile ->
            val row = i / layout.cols
            val col = i % layout.cols
            val left = layout.gridLeft + col * (layout.cardW + layout.gapX)
            val top = layout.gridTop + row * (layout.cardH + layout.gapY)
            val rect = Rect(left, top, left + layout.cardW, top + layout.cardH)
            val cardColor = tile.backgroundColorRules?.resolve(null, latestValues) ?: activeTheme.defaultCardColor
            GaugeDrawing.drawCard(canvas, rect, cardColor, theme = activeTheme)
            drawTileContent(canvas, rect, tile, big = false)
        }
    }

    /**
     * A HeroType.DIAL tile with more than one value (e.g. MOTOR RPM, VOLTAGE / CURRENT) shows only
     * its first value as one big ring gauge when promoted - the rest are still visible in the
     * small/grid card, and in full via [TileDef.detail] if set. A single-value DIAL tile never
     * reaches here - [drawTileContent]'s own size==1 branch already draws it as a gauge.
     */
    private fun drawPrimaryValueDialHero(canvas: Canvas, inner: Rect, tile: TileDef, valueIndex: Int = 0) {
        val value = tile.values.getOrNull(valueIndex)?.resolve(latestValues)
        val gauge = tile.gaugeRange
        val fraction = gauge?.let { r -> value?.value?.let { v -> ((v - r.start) / (r.endInclusive - r.start)).coerceIn(0.0, 1.0) } }
        val radius = minOf(inner.width(), inner.height()) * 0.42f
        GaugeDrawing.drawBigGauge(
            canvas, inner.exactCenterX(), inner.exactCenterY(), radius, fraction,
            valueText = value?.value?.let { "${it.toInt()}${if (tile.showUnits) value.unit else ""}" } ?: "--",
            label = tile.label,
            ringColor = { frac -> tile.gaugeColorRules?.resolve(value?.value, latestValues) ?: activeTheme.resolveGaugeColor(frac) },
            theme = activeTheme,
        )
    }

    /** A time-series line graph instead of the usual grid - see [graphHistory]. */
    private fun drawGraphHero(canvas: Canvas, inner: Rect, tile: TileDef) {
        val seriesData = tile.hero.series.mapIndexed { i, s ->
            graphHistory.pointsFor(tile.id, i) to (s.color ?: activeTheme.defaultGraphColor)
        }
        if (seriesData.all { it.first.size < 2 }) {
            drawTileContent(canvas, inner, tile, big = true)
            return
        }
        GaugeDrawing.drawLineGraph(canvas, inner, tile.label, seriesData, theme = activeTheme)
    }

    private fun recordGraphHistory(values: Map<String, DecodedValue>) {
        graphTiles.forEach { tile -> graphHistory.record(tile.id, tile.hero.series, tile.hero.windowSeconds, values) }
    }

    /**
     * A single-value tile with [TileDef.gaugeRange] always draws as a ring gauge (sized to fit
     * whichever slot it's in); other single-value tiles render as one readout; multi-value ones
     * get a heading plus a small grid.
     */
    private fun drawTileContent(canvas: Canvas, rect: Rect, tile: TileDef, big: Boolean) {
        if (tile.id == "lights") {
            drawLightsIndicator(canvas, rect, tile)
            return
        }
        if (tile.id == "motor_rpm" && motorDriveType == MotorDriveType.RWD_ONLY) {
            // Front motor is never meaningful once this vehicle is known to be RWD-only (see
            // MotorDriveTypeTracker) - instead of a half-faded two-cell grid, show just the rear
            // value centered in the whole tile, same readout style a grid cell would use.
            val rear = tile.values.getOrNull(1)
            val value = rear?.resolve(latestValues)
            val formatted = GaugeDrawing.formatValue(value?.value, if (tile.showUnits) value?.unit ?: "" else "", decimals(value))
            val textSize = (rect.width() * (if (big) 0.12f else 0.20f)).coerceIn(14f, 60f).coerceAtMost(rect.height() * 0.34f)
            GaugeDrawing.drawReadout(
                canvas, rect.exactCenterX(), rect.exactCenterY().toFloat(), textSize,
                rear?.sublabel ?: tile.label, formatted,
                maxWidth = rect.width() * 0.9f, theme = activeTheme,
            )
            return
        }
        if (tile.values.size == 1) {
            val value = tile.values[0].resolve(latestValues)
            val gauge = tile.gaugeRange
            if (gauge != null) {
                val fraction = value?.value?.let { v -> ((v - gauge.start) / (gauge.endInclusive - gauge.start)).coerceIn(0.0, 1.0) }
                val radius = minOf(rect.width(), rect.height()) * (if (big) 0.42f else 0.36f)
                GaugeDrawing.drawBigGauge(
                    canvas, rect.exactCenterX(), rect.exactCenterY(), radius, fraction,
                    valueText = value?.value?.let { "${it.toInt()}${if (tile.showUnits) value.unit else ""}" } ?: "--",
                    label = tile.label,
                    ringColor = { frac -> tile.gaugeColorRules?.resolve(value?.value, latestValues) ?: activeTheme.resolveGaugeColor(frac) },
                    theme = activeTheme,
                )
                return
            }
            val text = GaugeDrawing.formatValue(value?.value, if (tile.showUnits) value?.unit ?: "" else "", decimals(value))
            if (big) {
                GaugeDrawing.drawBigReadout(canvas, rect.exactCenterX(), rect.exactCenterY(), rect.width().toFloat(), tile.label, text, theme = activeTheme)
            } else {
                val textSize = (rect.width() * 0.20f).coerceIn(14f, 44f).coerceAtMost(rect.height() * 0.34f)
                GaugeDrawing.drawReadout(
                    canvas, rect.exactCenterX(), rect.exactCenterY().toFloat() + rect.height() * 0.03f, textSize,
                    tile.label, text, maxWidth = rect.width() * 0.9f, theme = activeTheme,
                )
            }
            return
        }
        val headingSize = (rect.height() * (if (big) 0.06f else 0.13f)).coerceAtMost(rect.width() * 0.08f)
        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = activeTheme.headingColor
            textAlign = Paint.Align.CENTER
            textSize = headingSize
            typeface = activeTheme.labelTypeface
        }
        canvas.drawText(tile.label, rect.exactCenterX(), rect.top + headingSize * 1.4f, headingPaint)
        val gridArea = Rect(rect.left, (rect.top + headingSize * 2f).toInt(), rect.right, rect.bottom)
        GaugeDrawing.gridCells(gridArea, tile.values.size).forEachIndexed { i, cell ->
            val sub = tile.values[i]
            val value = sub.resolve(latestValues)
            val cellTextSize = (cell.rect.height() * (if (big) 0.34f else 0.42f)).coerceIn(10f, 60f)
            val formatted = GaugeDrawing.formatValue(value?.value, if (tile.showUnits) value?.unit ?: "" else "", decimals(value))
            // The Motor RPM tile's front cell (index 0) fades out in place once this vehicle is
            // found to be RWD-only, instead of relayouting - see MotorDriveTypeTracker.
            val alpha = if (tile.id == "motor_rpm" && i == 0) motorFrontFadeProgress else 1f
            GaugeDrawing.drawReadout(
                canvas, cell.cx, cell.cy, cellTextSize, sub.sublabel ?: "",
                formatted,
                maxWidth = cell.rect.width() * 0.9f,
                theme = activeTheme,
                alpha = alpha,
            )
        }
    }

    /**
     * A two-segment vertical indicator in place of the lights tile's usual value grid - mirrors
     * the real car's stacked headlight cluster rather than showing HEAD/HIGH/BRAKE as text.
     * Bottom segment fills white when the headlights are on (red instead if the brake lights are
     * also on, since that's the same physical light position seen from behind); the top segment
     * only fills - also white - once the high beams are on too. An off segment draws nothing,
     * same as the lights being physically unlit.
     */
    /**
     * In place of the lights tile's usual value grid - a fixed four-light cluster mirroring the
     * real car's layout: a headlight pair up top, a tail/brake-light pair below, each pair's two
     * lights spaced apart so they read as distinct left/right fixtures. Always fully drawn (even
     * fully "off"), rather than fading in/out, since this is meant to be read at a glance like the
     * physical lights themselves - a dark fixture is still a visible fixture.
     */
    private fun drawLightsIndicator(canvas: Canvas, rect: Rect, tile: TileDef) {
        val head = (tile.values.getOrNull(0)?.resolve(latestValues)?.value ?: 0.0) > 0.0
        val high = (tile.values.getOrNull(1)?.resolve(latestValues)?.value ?: 0.0) > 0.0
        val brake = (tile.values.getOrNull(2)?.resolve(latestValues)?.value ?: 0.0) > 0.0

        val headingSize = (rect.height() * 0.1f).coerceAtMost(rect.width() * 0.1f)
        val headingPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = activeTheme.headingColor
            textAlign = Paint.Align.CENTER
            textSize = headingSize
            typeface = activeTheme.labelTypeface
        }
        canvas.drawText(tile.label, rect.exactCenterX(), rect.top + headingSize * 1.4f, headingPaint)

        // The light boxes ran flush to the card's left/right/bottom edges with zero margin,
        // looking oversized next to every other tile's natural text padding - insetting them
        // here matches that same breathing room.
        val hPad = rect.width() * 0.1f
        val bodyLeft = (rect.left + hPad).toInt()
        val bodyRight = (rect.right - hPad).toInt()
        val bodyTop = rect.top + headingSize * 2f
        val bodyBottom = rect.bottom - rect.height() * 0.06f
        val bodyHeight = bodyBottom - bodyTop
        val halfGap = bodyHeight * 0.08f
        val halfHeight = (bodyHeight - halfGap) / 2f

        val headlightHalf = Rect(bodyLeft, bodyTop.toInt(), bodyRight, (bodyTop + halfHeight).toInt())
        val taillightHalf = Rect(bodyLeft, (bodyTop + halfHeight + halfGap).toInt(), bodyRight, bodyBottom.toInt())

        drawHeadlightPair(canvas, headlightHalf, head, high)
        drawTaillightPair(canvas, taillightHalf, brake)
    }

    /** Two side-by-side lights, each itself split bottom (low beam, white when on) / top (high beam, blue when on). */
    private fun drawHeadlightPair(canvas: Canvas, rect: Rect, head: Boolean, high: Boolean) {
        val bottomColor = if (head) Color.WHITE else Color.BLACK
        val topColor = if (high) Color.rgb(0x40, 0x90, 0xFF) else Color.BLACK
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        forEachLightSlot(rect) { lightRect ->
            val segGap = lightRect.height() * 0.08f
            val segHeight = (lightRect.height() - segGap) / 2f
            val topSeg = RectF(lightRect.left, lightRect.top, lightRect.right, lightRect.top + segHeight)
            val bottomSeg = RectF(lightRect.left, lightRect.top + segHeight + segGap, lightRect.right, lightRect.bottom)
            val radius = minOf(lightRect.width(), segHeight) * 0.18f
            canvas.drawRoundRect(topSeg, radius, radius, paint.apply { color = topColor })
            canvas.drawRoundRect(bottomSeg, radius, radius, paint.apply { color = bottomColor })
        }
    }

    /** Two side-by-side lights, solid red when the brake lights are on, otherwise unlit. */
    private fun drawTaillightPair(canvas: Canvas, rect: Rect, brake: Boolean) {
        val color = if (brake) Color.rgb(0xDC, 0x28, 0x28) else Color.BLACK
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }

        forEachLightSlot(rect) { lightRect ->
            val radius = minOf(lightRect.width(), lightRect.height()) * 0.18f
            canvas.drawRoundRect(lightRect, radius, radius, paint)
        }
    }

    /** Splits [rect] into a left and right light slot with a margin between them. */
    private fun forEachLightSlot(rect: Rect, draw: (RectF) -> Unit) {
        val pairGap = rect.width() * 0.1f
        val lightW = (rect.width() - pairGap) / 2f
        draw(RectF(rect.left.toFloat(), rect.top.toFloat(), rect.left + lightW, rect.bottom.toFloat()))
        draw(RectF(rect.left + lightW + pairGap, rect.top.toFloat(), rect.right.toFloat(), rect.bottom.toFloat()))
    }

    private fun decimals(value: DecodedValue?): Int {
        val unit = value?.unit
        return if (unit.isNullOrBlank() || unit == "%") 0 else 1
    }

    companion object {
        private const val TAG = "DashboardScreen"
    }
}
