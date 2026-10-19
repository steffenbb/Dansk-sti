package com.example.ui.viewmodel

import android.app.Application
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.DanskStiApp
import com.example.data.local.SettingsRepository
import com.example.data.model.CustomRouteEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.PlannedRoute
import com.example.data.model.RoutePoint
import com.example.data.repository.DanskStiRepository
import com.example.util.GpxImporter
import com.example.util.OvernightCandidate
import com.example.util.StagePlanner
import com.example.util.routing.BRouterOnlineEngine
import com.example.util.routing.LatLng
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteGeometryStorageManager
import com.example.util.routing.RouteLegResult
import com.example.util.routing.RouteOptions
import android.content.Context
import com.example.data.model.NearestPoiCategory
import com.example.data.model.RundturCandidate
import com.example.data.model.SupplyGapWarning
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.model.WaypointWeatherEta
import com.example.data.remote.WeatherApiService
import com.example.util.routing.RouteSmartTools
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteStats
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.RoutingEngine
import com.example.util.routing.WaypointRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.InputStream
import java.util.Locale
import java.util.UUID
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class RouteAlternativeInfo(
    val index: Int,
    val lengthKm: Double,
    val ascentM: Double,
    val pathSharePct: Double,
    val points: List<RoutePoint3D>
)

data class RouteBuilderState(
    val routeId: Long? = null,
    val routeName: String = "",
    val waypoints: List<RouteWaypoint> = emptyList(),
    val legs: Map<String, LegGeometryData> = emptyMap(),
    val loadingLegKeys: Set<String> = emptySet(),
    val options: RouteOptions = RouteOptions(),
    val stats: RouteStats = RouteStats(0.0, 0.0, 0.0, 100.0, 0.0),
    val timeEstimateFormatted: String = "0 min",
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
    val gpxOriginalPoints: List<RoutePoint3D> = emptyList(),
    val alternatives: List<RouteAlternativeInfo> = emptyList(),
    val isComparingAlternatives: Boolean = false,
    val selectedWaypointId: String? = null,
    val scrubbedPointOnMap: RoutePoint3D? = null,
    val accessPolygons: List<com.example.data.model.access.AccessPolygon> = emptyList(),
    val accessWarnings: List<com.example.util.access.RouteAccessWarning> = emptyList(),
    val isAccessLayerVisible: Boolean = true,
    val selectedAccessPolygonForDetails: com.example.data.model.access.AccessPolygon? = null,
    val overnightCandidatesByDay: Map<Int, List<com.example.util.OvernightCandidate>> = emptyMap(),
    val isFindingOvernight: Boolean = false,
    val showOvernightDialog: Boolean = false,
    val supplyGapWarnings: List<com.example.data.model.SupplyGapWarning> = emptyList(),
    val maxWaterGapKm: Double = 12.0,
    val maxShopGapKm: Double = 25.0,
    val rundturCandidates: List<com.example.data.model.RundturCandidate> = emptyList(),
    val isGeneratingRundtur: Boolean = false,
    val showRundturDialog: Boolean = false,
    val rundturTargetKm: Double = 15.0,
    val waypointWeatherEtas: List<com.example.data.model.WaypointWeatherEta> = emptyList(),
    val isLoadingWeather: Boolean = false,
    val isRoutingToNearest: Boolean = false,
    val showNearestPoiDialog: Boolean = false,
    val routeQuality: com.example.data.model.RouteQualityBreakdown = com.example.data.model.RouteQualityBreakdown(),
    val customUserWaypoints: List<com.example.data.model.UserCustomWaypointEntity> = emptyList(),
    val showAddCustomWaypointDialog: Boolean = false,
    val pendingCustomWaypointLocation: LatLng? = null,
    val selectedCustomWaypointForDetails: com.example.data.model.UserCustomWaypointEntity? = null
)

class RouteBuilderViewModel(
    application: Application
) : AndroidViewModel(application) {

    private val TAG = "RouteBuilderVM"
    private val app = application as DanskStiApp
    private val repository: DanskStiRepository = app.repository
    private val settingsRepository = SettingsRepository(application)
    private val routingEngine: RoutingEngine = BRouterOnlineEngine(application)
    private val weatherService: WeatherApiService = WeatherApiService.create()

    private val _uiState = MutableStateFlow(RouteBuilderState())
    val uiState: StateFlow<RouteBuilderState> = _uiState.asStateFlow()

    private val _userMessage = MutableSharedFlow<String>()
    val userMessage: SharedFlow<String> = _userMessage.asSharedFlow()

    // Undo / Redo stacks (max 50)
    private data class HistorySnapshot(
        val waypoints: List<RouteWaypoint>,
        val legs: Map<String, LegGeometryData>,
        val options: RouteOptions
    )
    private val undoStack = mutableListOf<HistorySnapshot>()
    private val redoStack = mutableListOf<HistorySnapshot>()
    private val MAX_HISTORY = 50

    private var activeRoutingJob: Job? = null
    private var geometryFileName: String = "route_${UUID.randomUUID()}.json"
    private var pacingSpeedKmh: Double = 4.5
    private var startTimeStr: String = "09:00"
    private var sunsetTimeStr: String = "20:30"

    init {
        viewModelScope.launch {
            val settings = settingsRepository.settingsFlow.first()
            pacingSpeedKmh = settings.mitTempoKmh
            startTimeStr = settings.startTime
        }
        loadAccessPolygonsForBounds(54.5, 57.8, 8.0, 15.2)
    }

    /**
     * Initializes RouteBuilder for editing an existing custom route or starting a new one.
     */
    fun initialize(
        existingRouteId: Long? = null,
        initialDestLat: Double? = null,
        initialDestLng: Double? = null,
        initialDestName: String? = null
    ) {
        if (existingRouteId != null && existingRouteId > 0) {
            loadExistingRoute(existingRouteId)
        } else if (initialDestLat != null && initialDestLng != null) {
            if (_uiState.value.waypoints.isEmpty()) {
                // Add the initial destination as a waypoint
                val wp = RouteWaypoint(
                    lat = initialDestLat,
                    lng = initialDestLng,
                    role = WaypointRole.END,
                    name = initialDestName ?: "Destination"
                )
                _uiState.value = _uiState.value.copy(
                    routeName = initialDestName?.let { "Rute til $it" } ?: "Ny rute",
                    waypoints = listOf(wp)
                )
            }
        }
    }

    private fun loadExistingRoute(id: Long) {
        viewModelScope.launch {
            val route = repository.getCustomRoute(id) ?: return@launch
            geometryFileName = route.geometryFile.ifBlank { "route_${id}.json" }

            val waypoints = RouteWaypoint.listFromJson(route.waypointsJson)
            val legs = withContext(Dispatchers.IO) {
                RouteGeometryStorageManager.loadLegs(getApplication(), geometryFileName)
            }

            val options = parseOptionsJson(route.optionsJson)

            _uiState.value = _uiState.value.copy(
                routeId = route.id,
                routeName = route.name,
                waypoints = waypoints,
                legs = legs,
                options = options
            )

            updateRouteStats()
        }
    }

    private fun pushHistory() {
        val current = HistorySnapshot(
            waypoints = _uiState.value.waypoints,
            legs = _uiState.value.legs,
            options = _uiState.value.options
        )
        undoStack.add(current)
        if (undoStack.size > MAX_HISTORY) {
            undoStack.removeAt(0)
        }
        redoStack.clear()
        updateUndoRedoAvailability()
    }

    private fun updateUndoRedoAvailability() {
        _uiState.value = _uiState.value.copy(
            canUndo = undoStack.isNotEmpty(),
            canRedo = redoStack.isNotEmpty()
        )
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        val previous = undoStack.removeAt(undoStack.lastIndex)
        val current = HistorySnapshot(
            waypoints = _uiState.value.waypoints,
            legs = _uiState.value.legs,
            options = _uiState.value.options
        )
        redoStack.add(current)
        if (redoStack.size > MAX_HISTORY) {
            redoStack.removeAt(0)
        }

        _uiState.value = _uiState.value.copy(
            waypoints = previous.waypoints,
            legs = previous.legs,
            options = previous.options
        )
        updateUndoRedoAvailability()
        updateRouteStats()
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        val next = redoStack.removeAt(redoStack.lastIndex)
        val current = HistorySnapshot(
            waypoints = _uiState.value.waypoints,
            legs = _uiState.value.legs,
            options = _uiState.value.options
        )
        undoStack.add(current)

        _uiState.value = _uiState.value.copy(
            waypoints = next.waypoints,
            legs = next.legs,
            options = next.options
        )
        updateUndoRedoAvailability()
        updateRouteStats()
    }

    /**
     * Map tap: Add a new waypoint at the end.
     */
    fun addPointAtEnd(lat: Double, lng: Double, name: String? = null) {
        pushHistory()
        val currentWps = _uiState.value.waypoints.toMutableList()

        val role = when (currentWps.size) {
            0 -> WaypointRole.START
            else -> WaypointRole.END
        }

        // If there was an existing END waypoint, change it to VIA
        if (currentWps.isNotEmpty() && currentWps.last().role == WaypointRole.END) {
            val lastIdx = currentWps.lastIndex
            currentWps[lastIdx] = currentWps[lastIdx].copy(role = WaypointRole.VIA)
        }

        val newWp = RouteWaypoint(
            lat = lat,
            lng = lng,
            role = role,
            legModeToNext = LegMode.SNAP,
            name = name
        )
        currentWps.add(newWp)

        _uiState.value = _uiState.value.copy(waypoints = currentWps)

        // Route ONLY the new leg from previous waypoint to new waypoint
        if (currentWps.size >= 2) {
            val prevWp = currentWps[currentWps.size - 2]
            computeSingleLeg(prevWp, newWp)
        } else {
            fetchSunsetForRoute(lat, lng)
            updateRouteStats()
        }
    }

    /**
     * Long-press on the line: Insert a via point at the tapped location.
     */
    fun insertViaPointOnLine(lat: Double, lng: Double) {
        val wps = _uiState.value.waypoints
        if (wps.size < 2) {
            addPointAtEnd(lat, lng)
            return
        }

        // Find the closest leg segment to insert into
        var bestIndex = -1
        var minDistanceM = Double.MAX_VALUE

        for (i in 0 until wps.size - 1) {
            val legKey = "${wps[i].id}_to_${wps[i + 1].id}"
            val leg = _uiState.value.legs[legKey]
            val pts = leg?.points ?: listOf(RoutePoint3D(wps[i].lat, wps[i].lng), RoutePoint3D(wps[i + 1].lat, wps[i + 1].lng))

            for (pt in pts) {
                val d = haversineM(lat, lng, pt.lat, pt.lng)
                if (d < minDistanceM) {
                    minDistanceM = d
                    bestIndex = i + 1
                }
            }
        }

        if (bestIndex != -1) {
            insertPointAtIndex(bestIndex, lat, lng)
        } else {
            addPointAtEnd(lat, lng)
        }
    }

    /**
     * Inserts a via point at a specific index, replacing leg (i-1 -> i) with two legs.
     */
    fun insertPointAtIndex(index: Int, lat: Double, lng: Double) {
        pushHistory()
        val currentWps = _uiState.value.waypoints.toMutableList()
        val newWp = RouteWaypoint(
            lat = lat,
            lng = lng,
            role = WaypointRole.VIA,
            legModeToNext = LegMode.SNAP,
            name = "Via ${currentWps.size}"
        )

        currentWps.add(index, newWp)
        normalizeRoles(currentWps)
        _uiState.value = _uiState.value.copy(waypoints = currentWps)

        // Re-route ONLY the two affected legs: (index-1 -> index) and (index -> index+1)
        val prevWp = currentWps[index - 1]
        val nextWp = currentWps[index + 1]

        // Remove old replaced leg
        val oldKey = "${prevWp.id}_to_${nextWp.id}"
        val updatedLegs = _uiState.value.legs.toMutableMap()
        updatedLegs.remove(oldKey)
        _uiState.value = _uiState.value.copy(legs = updatedLegs)

        computeSingleLeg(prevWp, newWp)
        computeSingleLeg(newWp, nextWp)
    }

    /**
     * Waypoint dragged/moved: re-routes ONLY the legs touching this changed point.
     */
    fun moveWaypoint(waypointId: String, newLat: Double, newLng: Double) {
        val wps = _uiState.value.waypoints.toMutableList()
        val index = wps.indexOfFirst { it.id == waypointId }
        if (index == -1) return

        pushHistory()
        val oldWp = wps[index]
        val updatedWp = oldWp.copy(lat = newLat, lng = newLng)
        wps[index] = updatedWp
        _uiState.value = _uiState.value.copy(waypoints = wps)

        // Only re-route legs touching index:
        // 1. Previous leg (index - 1 to index)
        if (index > 0) {
            val prevWp = wps[index - 1]
            computeSingleLeg(prevWp, updatedWp)
        }
        // 2. Next leg (index to index + 1)
        if (index < wps.size - 1) {
            val nextWp = wps[index + 1]
            computeSingleLeg(updatedWp, nextWp)
        }
    }

    /**
     * Delete waypoint at index.
     */
    fun deleteWaypoint(waypointId: String) {
        val wps = _uiState.value.waypoints.toMutableList()
        val index = wps.indexOfFirst { it.id == waypointId }
        if (index == -1) return

        pushHistory()
        val removed = wps.removeAt(index)
        normalizeRoles(wps)

        // Remove legs touching the removed waypoint
        val currentLegs = _uiState.value.legs.toMutableMap()
        val keysToRemove = currentLegs.keys.filter { it.contains(removed.id) }
        for (k in keysToRemove) currentLegs.remove(k)

        _uiState.value = _uiState.value.copy(
            waypoints = wps,
            legs = currentLegs,
            selectedWaypointId = null
        )

        // If between two waypoints, route the new connection (index-1 to index)
        if (index > 0 && index < wps.size) {
            computeSingleLeg(wps[index - 1], wps[index])
        } else {
            updateRouteStats()
        }
    }

    /**
     * Toggle role: VIA <-> OVERNIGHT
     */
    fun toggleWaypointOvernight(waypointId: String) {
        val wps = _uiState.value.waypoints.toMutableList()
        val index = wps.indexOfFirst { it.id == waypointId }
        if (index == -1) return

        pushHistory()
        val current = wps[index]
        val newRole = if (current.role == WaypointRole.OVERNIGHT) WaypointRole.VIA else WaypointRole.OVERNIGHT
        wps[index] = current.copy(role = newRole)
        _uiState.value = _uiState.value.copy(waypoints = wps)
    }

    /**
     * Toggle leg mode from this waypoint to the next: SNAP <-> STRAIGHT
     */
    fun toggleLegMode(waypointId: String) {
        val wps = _uiState.value.waypoints.toMutableList()
        val index = wps.indexOfFirst { it.id == waypointId }
        if (index == -1 || index >= wps.size - 1) return

        pushHistory()
        val current = wps[index]
        val newMode = if (current.legModeToNext == LegMode.SNAP) LegMode.STRAIGHT else LegMode.SNAP
        wps[index] = current.copy(legModeToNext = newMode)
        _uiState.value = _uiState.value.copy(waypoints = wps)

        // Re-route this single leg
        computeSingleLeg(wps[index], wps[index + 1])
    }

    /**
     * Rename waypoint.
     */
    fun renameWaypoint(waypointId: String, newName: String) {
        val wps = _uiState.value.waypoints.toMutableList()
        val index = wps.indexOfFirst { it.id == waypointId }
        if (index == -1) return

        pushHistory()
        wps[index] = wps[index].copy(name = newName.ifBlank { null })
        _uiState.value = _uiState.value.copy(waypoints = wps)
    }

    /**
     * "Vend rute" - Reverses the route and re-routes legs.
     */
    fun reverseRoute() {
        val wps = _uiState.value.waypoints
        if (wps.size < 2) return

        pushHistory()
        val reversed = wps.asReversed().map { it.copy() }.toMutableList()
        normalizeRoles(reversed)

        _uiState.value = _uiState.value.copy(
            waypoints = reversed,
            legs = emptyMap()
        )

        recomputeAllLegs()
    }

    /**
     * "Ryd" - Clears all waypoints and legs.
     */
    fun clearRoute() {
        pushHistory()
        _uiState.value = _uiState.value.copy(
            waypoints = emptyList(),
            legs = emptyMap(),
            loadingLegKeys = emptySet(),
            stats = RouteStats(0.0, 0.0, 0.0, 100.0, 0.0),
            timeEstimateFormatted = "0 min",
            gpxOriginalPoints = emptyList(),
            alternatives = emptyList(),
            selectedWaypointId = null
        )
    }

    /**
     * "Fra min position" - Sets START at current GPS fix.
     */
    fun setStartAtGps(lat: Double, lng: Double) {
        pushHistory()
        val wps = _uiState.value.waypoints.toMutableList()
        val startWp = RouteWaypoint(
            lat = lat,
            lng = lng,
            role = WaypointRole.START,
            name = "Min position"
        )

        if (wps.isEmpty()) {
            wps.add(startWp)
        } else {
            // Replace first or prepend
            if (wps.first().role == WaypointRole.START) {
                wps[0] = startWp
            } else {
                wps.add(0, startWp)
            }
        }
        normalizeRoles(wps)
        _uiState.value = _uiState.value.copy(waypoints = wps)

        if (wps.size >= 2) {
            computeSingleLeg(wps[0], wps[1])
        } else {
            updateRouteStats()
        }
    }

    fun selectWaypoint(waypointId: String?) {
        _uiState.value = _uiState.value.copy(selectedWaypointId = waypointId)
    }

    fun setScrubbedPoint(point: RoutePoint3D?) {
        _uiState.value = _uiState.value.copy(scrubbedPointOnMap = point)
    }

    fun updateRouteName(name: String) {
        _uiState.value = _uiState.value.copy(routeName = name)
    }

    fun updateOptions(newOptions: RouteOptions) {
        if (_uiState.value.options == newOptions) return
        pushHistory()
        _uiState.value = _uiState.value.copy(options = newOptions)
        // When options change, recompute all legs that use SNAP
        recomputeAllLegs()
    }

    /**
     * Computes a single leg between two waypoints.
     */
    private fun computeSingleLeg(from: RouteWaypoint, to: RouteWaypoint) {
        val legKey = "${from.id}_to_${to.id}"
        val legMode = from.legModeToNext

        if (legMode == LegMode.STRAIGHT) {
            // Compute direct straight line
            val pts = listOf(
                RoutePoint3D(from.lat, from.lng, 15.0),
                RoutePoint3D(to.lat, to.lng, 15.0)
            )
            val distKm = haversineM(from.lat, from.lng, to.lat, to.lng) / 1000.0
            val straightLeg = LegGeometryData(
                fromWaypointId = from.id,
                toWaypointId = to.id,
                legMode = LegMode.STRAIGHT,
                points = pts,
                lengthKm = distKm,
                ascentM = 0.0,
                descentM = 0.0,
                pathSharePct = 0.0,
                roadKm = 0.0,
                hasAccessWarning = false,
                sourceName = "Straight"
            )

            val updatedLegs = _uiState.value.legs.toMutableMap()
            updatedLegs[legKey] = straightLeg
            _uiState.value = _uiState.value.copy(legs = updatedLegs)
            updateRouteStats()
            return
        }

        // Snap mode: Route with RoutingEngine
        val currentLoading = _uiState.value.loadingLegKeys.toMutableSet()
        currentLoading.add(legKey)
        _uiState.value = _uiState.value.copy(loadingLegKeys = currentLoading)

        viewModelScope.launch {
            try {
                val fromCoord = LatLng(from.lat, from.lng)
                val toCoord = LatLng(to.lat, to.lng)
                val options = _uiState.value.options

                val result = routingEngine.routeLeg(fromCoord, toCoord, options, alternative = 0)

                val updatedLoading = _uiState.value.loadingLegKeys.toMutableSet()
                updatedLoading.remove(legKey)

                when (result) {
                    is RouteLegResult.Success -> {
                        // Calculate path share % from perPointWayInfo
                        var pathCount = 0
                        var roadCount = 0
                        for (w in result.perPointWayInfo) {
                            val hw = w.highway ?: ""
                            if (hw == "path" || hw == "footway" || hw == "bridleway" || hw == "track") {
                                pathCount++
                            } else if (hw == "residential" || hw == "unclassified" || hw == "tertiary" || hw == "secondary" || hw == "primary") {
                                roadCount++
                            }
                        }
                        val totalCount = result.perPointWayInfo.size
                        val pathShare = if (totalCount > 0) (pathCount.toDouble() / totalCount * 100.0).coerceIn(0.0, 100.0) else 80.0
                        val roadKm = if (totalCount > 0) (roadCount.toDouble() / totalCount * result.lengthKm) else 0.0

                        val legData = LegGeometryData(
                            fromWaypointId = from.id,
                            toWaypointId = to.id,
                            legMode = LegMode.SNAP,
                            points = result.points,
                            lengthKm = result.lengthKm,
                            ascentM = result.ascentM,
                            descentM = result.descentM,
                            pathSharePct = pathShare,
                            roadKm = roadKm,
                            hasAccessWarning = false,
                            sourceName = result.sourceName,
                            fetchedAt = result.fetchedAt
                        )

                        val updatedLegs = _uiState.value.legs.toMutableMap()
                        updatedLegs[legKey] = legData

                        _uiState.value = _uiState.value.copy(
                            legs = updatedLegs,
                            loadingLegKeys = updatedLoading,
                            errorMessage = null
                        )

                        // Save isolated leg in storage
                        withContext(Dispatchers.IO) {
                            RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, legData)
                        }

                        updateRouteStats()
                    }
                    is RouteLegResult.ServerBusy -> {
                        _uiState.value = _uiState.value.copy(
                            loadingLegKeys = updatedLoading,
                            errorMessage = result.message
                        )
                        _userMessage.emit(result.message)
                    }
                    is RouteLegResult.Offline -> {
                        _uiState.value = _uiState.value.copy(
                            loadingLegKeys = updatedLoading,
                            errorMessage = result.message
                        )
                        _userMessage.emit(result.message)
                    }
                    is RouteLegResult.NoRoute -> {
                        _uiState.value = _uiState.value.copy(
                            loadingLegKeys = updatedLoading,
                            errorMessage = "Ingen rute fundet på stinetværket for denne etape"
                        )
                        _userMessage.emit("Ingen rute fundet - prøv at flytte punktet eller skift til lige linje")
                    }
                    is RouteLegResult.Failure -> {
                        _uiState.value = _uiState.value.copy(
                            loadingLegKeys = updatedLoading,
                            errorMessage = result.message
                        )
                        _userMessage.emit("Ruteberegningsfejl: ${result.message}")
                    }
                }
            } catch (e: Exception) {
                val updatedLoading = _uiState.value.loadingLegKeys.toMutableSet()
                updatedLoading.remove(legKey)
                _uiState.value = _uiState.value.copy(
                    loadingLegKeys = updatedLoading,
                    errorMessage = e.message
                )
            }
        }
    }

    private fun recomputeAllLegs() {
        val wps = _uiState.value.waypoints
        if (wps.size < 2) return

        for (i in 0 until wps.size - 1) {
            computeSingleLeg(wps[i], wps[i + 1])
        }
    }

    private fun updateRouteStats() {
        val wps = _uiState.value.waypoints
        val orderedLegs = mutableListOf<LegGeometryData>()
        for (i in 0 until wps.size - 1) {
            val key = "${wps[i].id}_to_${wps[i + 1].id}"
            _uiState.value.legs[key]?.let { orderedLegs.add(it) }
        }

        val stats = RouteGeometryStorageManager.calculateStats(orderedLegs)

        // Time estimate formula (Requirement D):
        // hours = (totalKm / mitTempo) + (ascentM / 10.0 / 60.0)
        val speed = if (pacingSpeedKmh > 0.5) pacingSpeedKmh else 4.5
        val travelHours = (stats.totalKm / speed) + (stats.ascentM / 10.0 / 60.0)
        val totalMinutes = (travelHours * 60).roundToInt()

        val timeStr = if (totalMinutes >= 60) {
            val h = totalMinutes / 60
            val m = totalMinutes % 60
            "ca. $h t $m min"
        } else {
            "ca. $totalMinutes min"
        }

        val quality = RouteSmartTools.analyzeRouteQuality(orderedLegs)
        _uiState.value = _uiState.value.copy(
            stats = stats,
            timeEstimateFormatted = timeStr,
            routeQuality = quality
        )
        evaluateAccessRules()
        analyzeSupplyGaps()
    }

    /**
     * "Sammenlign ruter": Fetches alternative routes 1-3.
     */
    fun fetchAlternatives() {
        val wps = _uiState.value.waypoints
        if (wps.size < 2) return

        _uiState.value = _uiState.value.copy(isComparingAlternatives = true)

        viewModelScope.launch {
            val altList = mutableListOf<RouteAlternativeInfo>()
            val fromCoord = LatLng(wps.first().lat, wps.first().lng)
            val toCoord = LatLng(wps.last().lat, wps.last().lng)

            for (altIdx in 1..3) {
                when (val res = routingEngine.routeLeg(fromCoord, toCoord, _uiState.value.options, alternative = altIdx)) {
                    is RouteLegResult.Success -> {
                        var pathCount = 0
                        for (w in res.perPointWayInfo) {
                            val hw = w.highway ?: ""
                            if (hw == "path" || hw == "footway" || hw == "bridleway" || hw == "track") pathCount++
                        }
                        val share = if (res.perPointWayInfo.isNotEmpty()) {
                            (pathCount.toDouble() / res.perPointWayInfo.size * 100.0).coerceIn(0.0, 100.0)
                        } else 75.0

                        altList.add(
                            RouteAlternativeInfo(
                                index = altIdx,
                                lengthKm = res.lengthKm,
                                ascentM = res.ascentM,
                                pathSharePct = share,
                                points = res.points
                            )
                        )
                    }
                    else -> {
                        // Alternative not available, skip
                    }
                }
            }

            _uiState.value = _uiState.value.copy(
                alternatives = altList,
                isComparingAlternatives = false
            )
        }
    }

    /**
     * User picks an alternative route to replace the current route.
     */
    fun selectAlternative(alt: RouteAlternativeInfo) {
        pushHistory()
        // Simplify alternative points to start, intermediate via, end
        val wps = _uiState.value.waypoints
        if (wps.size < 2) return

        val startWp = wps.first()
        val endWp = wps.last()

        val newLeg = LegGeometryData(
            fromWaypointId = startWp.id,
            toWaypointId = endWp.id,
            legMode = LegMode.SNAP,
            points = alt.points,
            lengthKm = alt.lengthKm,
            ascentM = alt.ascentM,
            descentM = 0.0,
            pathSharePct = alt.pathSharePct,
            roadKm = alt.lengthKm * (1.0 - alt.pathSharePct / 100.0),
            sourceName = "BRouter Alt ${alt.index}"
        )

        val updatedWps = listOf(startWp, endWp)
        val updatedLegs = mapOf(newLeg.legKey to newLeg)

        _uiState.value = _uiState.value.copy(
            waypoints = updatedWps,
            legs = updatedLegs,
            alternatives = emptyList()
        )

        viewModelScope.launch(Dispatchers.IO) {
            RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, newLeg)
        }
        updateRouteStats()
    }

    /**
     * GPX Import (Requirement F):
     * Parses .gpx, simplifies to ~1 point per 500m, sets original points for comparison, routes with SNAP.
     */
    fun importGpx(inputStream: InputStream) {
        viewModelScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    GpxImporter.parseAndSimplifyGpx(inputStream, targetSpacingMeters = 500.0)
                }

                if (result.waypoints.size < 2) {
                    _userMessage.emit("Kunne ikke indlæse rute fra GPX: For få punkter")
                    return@launch
                }

                pushHistory()
                val suggestedName = result.name ?: "Importeret rute (${String.format(Locale.US, "%.1f", result.totalOriginalKm)} km)"

                _uiState.value = _uiState.value.copy(
                    routeName = suggestedName,
                    waypoints = result.waypoints,
                    legs = emptyMap(),
                    gpxOriginalPoints = result.originalPoints
                )

                _userMessage.emit("GPX indlæst: ${result.waypoints.size} ruteelementer. Beregner stier...")
                recomputeAllLegs()
            } catch (e: Exception) {
                Log.e(TAG, "Error importing GPX: ${e.message}")
                _userMessage.emit("Fejl ved GPX-import: ${e.message}")
            }
        }
    }

    /**
     * Save custom route to Room (Requirement E).
     */
    fun saveRoute(onSuccess: (Long) -> Unit) {
        val currentState = _uiState.value
        val wps = currentState.waypoints
        if (wps.size < 2) {
            viewModelScope.launch { _userMessage.emit("Tilføj mindst to punkter for at gemme ruten") }
            return
        }

        _uiState.value = _uiState.value.copy(isSaving = true)

        viewModelScope.launch {
            try {
                // Determine default name if empty: "<start place> - <end place>"
                val startName = wps.first().name ?: "Start"
                val endName = wps.last().name ?: "Slut"
                val finalName = if (currentState.routeName.isNotBlank()) {
                    currentState.routeName
                } else {
                    "$startName - $endName"
                }

                val optionsJson = JSONObject().apply {
                    put("avoidRoads", currentState.options.avoidRoads)
                    put("preferNature", currentState.options.preferNature)
                    put("allowFerries", currentState.options.allowFerries)
                }.toString()

                val waypointsJson = RouteWaypoint.listToJson(wps)
                val now = System.currentTimeMillis()

                val entity = CustomRouteEntity(
                    id = currentState.routeId ?: 0L,
                    name = finalName,
                    createdAt = now,
                    updatedAt = now,
                    optionsJson = optionsJson,
                    waypointsJson = waypointsJson,
                    totalKm = currentState.stats.totalKm,
                    ascentM = currentState.stats.ascentM,
                    descentM = currentState.stats.descentM,
                    pathSharePct = currentState.stats.pathSharePct,
                    roadKm = currentState.stats.roadKm,
                    geometryFile = geometryFileName,
                    sourceName = "Rutebygger",
                    fetchedAt = now
                )

                val savedId = repository.saveCustomRoute(entity)
                _uiState.value = _uiState.value.copy(isSaving = false, routeId = savedId)
                _userMessage.emit("Ruten er gemt under Gemte")
                onSuccess(savedId)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to save custom route: ${e.message}")
                _uiState.value = _uiState.value.copy(isSaving = false)
                _userMessage.emit("Fejl ved gemning af rute: ${e.message}")
            }
        }
    }

    /**
     * Requirement C: "Find overnatning" button in the route builder:
     * for each day (daily target from the planner), list overnight candidates near that day's end
     * within max detour, using the Phase 5 scoring.
     */
    fun findOvernightCandidates(dailyTargetKm: Double = 15.0) {
        val currentState = _uiState.value
        val wps = currentState.waypoints
        if (wps.size < 2) {
            viewModelScope.launch { _userMessage.emit("Tilføj mindst to punkter for at finde overnatningssteder") }
            return
        }

        _uiState.value = _uiState.value.copy(isFindingOvernight = true)

        viewModelScope.launch {
            try {
                val orderedLegs = mutableListOf<LegGeometryData>()
                for (i in 0 until wps.size - 1) {
                    val key = "${wps[i].id}_to_${wps[i + 1].id}"
                    currentState.legs[key]?.let { orderedLegs.add(it) }
                }
                val points = RouteGeometryStorageManager.getCombinedPoints(orderedLegs)
                val totalKm = currentState.stats.totalKm

                val plannedPoints = points.mapIndexed { idx, pt ->
                    com.example.data.model.RoutePoint(
                        lat = pt.lat,
                        lng = pt.lng,
                        ele = pt.ele,
                        walkKm = (idx.toDouble() / kotlin.math.max(1, points.size - 1)) * totalKm,
                        trailKm = (idx.toDouble() / kotlin.math.max(1, points.size - 1)) * totalKm
                    )
                }

                val plannedRoute = com.example.data.model.PlannedRoute(
                    planId = 0L,
                    trailId = "route_preview",
                    trailName = currentState.routeName.ifBlank { "Rute" },
                    isReverse = false,
                    startTrailKm = 0.0,
                    endTrailKm = totalKm,
                    totalWalkKm = totalKm,
                    points = plannedPoints,
                    stages = emptyList()
                )

                val shelters = repository.getAllSheltersList()
                val friTeltning = repository.getAllFriTeltningList()
                val water = repository.getPoisByType("water")
                val toilet = repository.getPoisByType("toilet")

                val candidatesMap = com.example.util.StagePlanner.findCandidatesForDayTargets(
                    plannedRoute = plannedRoute,
                    dailyTargetKm = dailyTargetKm,
                    maxDetourKm = 3.0,
                    shelters = shelters,
                    friTeltnings = friTeltning,
                    waterPois = water,
                    toiletPois = toilet
                )

                _uiState.value = _uiState.value.copy(
                    isFindingOvernight = false,
                    overnightCandidatesByDay = candidatesMap,
                    showOvernightDialog = true
                )
            } catch (e: Exception) {
                Log.e(TAG, "Failed finding overnight candidates: ${e.message}")
                _uiState.value = _uiState.value.copy(isFindingOvernight = false)
                _userMessage.emit("Fejl ved søgning efter overnatning: ${e.message}")
            }
        }
    }

    fun dismissOvernightDialog() {
        _uiState.value = _uiState.value.copy(showOvernightDialog = false)
    }

    /**
     * Requirement C: Tap one candidate = insert it as an OVERNIGHT waypoint and re-route the two legs.
     */
    fun insertOvernightCandidate(cand: com.example.util.OvernightCandidate) {
        dismissOvernightDialog()
        pushHistory()
        val wps = _uiState.value.waypoints.toMutableList()
        if (wps.size < 2) return

        // Find the closest leg segment to insert into based on candidate's lat/lng
        var bestIndex = -1
        var minDistanceM = Double.MAX_VALUE

        for (i in 0 until wps.size - 1) {
            val legKey = "${wps[i].id}_to_${wps[i + 1].id}"
            val leg = _uiState.value.legs[legKey]
            val pts = leg?.points ?: listOf(RoutePoint3D(wps[i].lat, wps[i].lng), RoutePoint3D(wps[i + 1].lat, wps[i + 1].lng))

            for (pt in pts) {
                val d = haversineM(cand.lat, cand.lng, pt.lat, pt.lng)
                if (d < minDistanceM) {
                    minDistanceM = d
                    bestIndex = i + 1
                }
            }
        }

        if (bestIndex == -1) {
            bestIndex = wps.size - 1
        }

        val newWp = RouteWaypoint(
            lat = cand.lat,
            lng = cand.lng,
            role = WaypointRole.OVERNIGHT,
            legModeToNext = LegMode.SNAP,
            name = cand.name,
            linkedPoiId = cand.id
        )

        wps.add(bestIndex, newWp)
        normalizeRoles(wps)
        _uiState.value = _uiState.value.copy(waypoints = wps)

        val prevWp = wps[bestIndex - 1]
        val nextWp = wps[bestIndex + 1]

        val oldKey = "${prevWp.id}_to_${nextWp.id}"
        val updatedLegs = _uiState.value.legs.toMutableMap()
        updatedLegs.remove(oldKey)
        _uiState.value = _uiState.value.copy(legs = updatedLegs)

        computeSingleLeg(prevWp, newWp)
        computeSingleLeg(newWp, nextWp)

        viewModelScope.launch {
            _userMessage.emit("Overnatning indsat: ${cand.name}")
        }
    }

    /**
     * Requirement D: "Planlæg dage" turns a custom route into a trip plan with the existing stage planner.
     */
    fun planStages(onNavigateToPlanner: (String) -> Unit) {
        saveRoute { savedId ->
            viewModelScope.launch {
                try {
                    val cr = repository.getCustomRoute(savedId) ?: return@launch
                    repository.ensureTripPlanForCustomRoute(cr, forceRecompute = true)
                    onNavigateToPlanner("route_$savedId")
                } catch (e: Exception) {
                    Log.e(TAG, "Error planning stages for custom route: ${e.message}")
                }
            }
        }
    }

    private fun normalizeRoles(wps: MutableList<RouteWaypoint>) {
        if (wps.isEmpty()) return
        if (wps.size == 1) {
            wps[0] = wps[0].copy(role = WaypointRole.START)
            return
        }
        wps[0] = wps[0].copy(role = WaypointRole.START)
        wps[wps.lastIndex] = wps.last().copy(role = WaypointRole.END)
        for (i in 1 until wps.size - 1) {
            if (wps[i].role == WaypointRole.START || wps[i].role == WaypointRole.END) {
                wps[i] = wps[i].copy(role = WaypointRole.VIA)
            }
        }
    }

    private fun parseOptionsJson(json: String): RouteOptions {
        if (json.isBlank()) return RouteOptions()
        return try {
            val obj = JSONObject(json)
            RouteOptions(
                avoidRoads = obj.optDouble("avoidRoads", 0.5),
                preferNature = obj.optBoolean("preferNature", true),
                allowFerries = obj.optBoolean("allowFerries", false)
            )
        } catch (_: Exception) {
            RouteOptions()
        }
    }

    private fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371000.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    // Phase 10: Access rules & layer methods (Naturbeskyttelsesloven kapitel 4)

    fun toggleAccessLayer() {
        _uiState.value = _uiState.value.copy(isAccessLayerVisible = !_uiState.value.isAccessLayerVisible)
    }

    fun selectAccessPolygon(poly: com.example.data.model.access.AccessPolygon?) {
        _uiState.value = _uiState.value.copy(selectedAccessPolygonForDetails = poly)
    }

    fun loadAccessPolygonsForBounds(minLat: Double, maxLat: Double, minLng: Double, maxLng: Double) {
        viewModelScope.launch {
            try {
                val polys = repository.getAccessPolygons(minLat, maxLat, minLng, maxLng)
                _uiState.value = _uiState.value.copy(accessPolygons = polys)
                evaluateAccessRules()
            } catch (e: Exception) {
                Log.w(TAG, "Failed loading access polygons: ${e.message}")
            }
        }
    }

    private fun fetchSunsetForRoute(lat: Double, lng: Double) {
        viewModelScope.launch {
            try {
                val weather = repository.getTrailWeather(lat, lng)
                val sunset = weather?.sunset
                if (!sunset.isNullOrBlank()) {
                    sunsetTimeStr = sunset
                    evaluateAccessRules()
                }
            } catch (_: Exception) {}
        }
    }

    fun evaluateAccessRules() {
        val wps = _uiState.value.waypoints
        val legs = _uiState.value.legs
        val polygons = _uiState.value.accessPolygons

        val warnings = com.example.util.access.AccessGeometryUtils.evaluateRouteAccess(
            waypoints = wps,
            legs = legs,
            accessPolygons = polygons,
            startTimeStr = startTimeStr,
            pacingSpeedKmh = pacingSpeedKmh,
            sunsetTimeStr = sunsetTimeStr
        )

        // Mark legs that have access warnings so they render in amber (Requirement C)
        val updatedLegs = legs.toMutableMap()
        for ((key, leg) in legs) {
            val hasWarning = warnings.any { w ->
                val loc = w.location
                leg.points.any { pt -> kotlin.math.abs(pt.lat - loc.lat) < 0.005 && kotlin.math.abs(pt.lng - loc.lng) < 0.005 }
            }
            if (leg.hasAccessWarning != hasWarning) {
                updatedLegs[key] = leg.copy(hasAccessWarning = hasWarning)
            }
        }

        _uiState.value = _uiState.value.copy(
            accessWarnings = warnings,
            legs = updatedLegs
        )
    }

    /**
     * "Rut uden om": Re-routes the affected leg with the problem polygon sent as a no-go polygon.
     */
    fun rerouteAroundPolygon(warning: com.example.util.access.RouteAccessWarning) {
        val poly = warning.polygon ?: return
        val wps = _uiState.value.waypoints
        val legIdx = warning.legIndex ?: return
        if (legIdx < 0 || legIdx >= wps.size - 1) return

        val fromWp = wps[legIdx]
        val toWp = wps[legIdx + 1]
        val legKey = "${fromWp.id}_to_${toWp.id}"

        viewModelScope.launch {
            _userMessage.emit("Beregner rute uden om ${poly.name ?: "området"}...")
            val newNoGo = _uiState.value.options.noGoAreas + listOf(poly.coordinates)
            val detourOptions = _uiState.value.options.copy(noGoAreas = newNoGo)

            val fromCoord = LatLng(fromWp.lat, fromWp.lng)
            val toCoord = LatLng(toWp.lat, toWp.lng)

            val result = routingEngine.routeLeg(fromCoord, toCoord, detourOptions, alternative = 0)
            when (result) {
                is RouteLegResult.Success -> {
                    var pathCount = 0
                    for (w in result.perPointWayInfo) {
                        val hw = w.highway ?: ""
                        if (hw in listOf("path", "footway", "bridleway", "track")) pathCount++
                    }
                    val pathShare = if (result.perPointWayInfo.isNotEmpty()) {
                        (pathCount.toDouble() / result.perPointWayInfo.size * 100.0).coerceIn(0.0, 100.0)
                    } else 80.0

                    val newLeg = LegGeometryData(
                        fromWaypointId = fromWp.id,
                        toWaypointId = toWp.id,
                        legMode = LegMode.SNAP,
                        points = result.points,
                        lengthKm = result.lengthKm,
                        ascentM = result.ascentM,
                        descentM = result.descentM,
                        pathSharePct = pathShare,
                        roadKm = result.lengthKm * (1.0 - pathShare / 100.0),
                        hasAccessWarning = false,
                        sourceName = "BRouter Detour"
                    )

                    val updatedLegs = _uiState.value.legs.toMutableMap()
                    updatedLegs[legKey] = newLeg
                    _uiState.value = _uiState.value.copy(
                        legs = updatedLegs,
                        options = detourOptions
                    )
                    withContext(Dispatchers.IO) {
                        RouteGeometryStorageManager.saveLeg(getApplication(), geometryFileName, newLeg)
                    }
                    updateRouteStats()
                    _userMessage.emit("Ruten er omlagt uden om ${poly.name ?: "området"}")
                }
                else -> {
                    _userMessage.emit("Kunne ikke finde en rute uden om ${poly.name ?: "området"}")
                }
            }
        }
    }

    // --- Phase 12 Feature 1: Vand og mad ---
    fun analyzeSupplyGaps() {
        val wps = _uiState.value.waypoints
        val orderedLegs = mutableListOf<LegGeometryData>()
        for (i in 0 until wps.size - 1) {
            val key = "${wps[i].id}_to_${wps[i + 1].id}"
            _uiState.value.legs[key]?.let { orderedLegs.add(it) }
        }
        val points = orderedLegs.flatMap { it.points }
        if (points.size < 2) {
            _uiState.value = _uiState.value.copy(supplyGapWarnings = emptyList())
            return
        }

        viewModelScope.launch {
            try {
                val rId = _uiState.value.routeId?.toString() ?: "temp"
                val corridorPois = repository.getPoisForRoute(rId)
                val allDbPois = repository.getAllPoisList()
                val warnings = RouteSmartTools.analyzeSupplyGaps(
                    routePoints = points,
                    corridorPois = if (corridorPois.isNotEmpty()) corridorPois else allDbPois,
                    maxWaterGapKm = _uiState.value.maxWaterGapKm,
                    maxShopGapKm = _uiState.value.maxShopGapKm,
                    allDbWaterPois = allDbPois.filter { it.type.equals("water", ignoreCase = true) },
                    allDbShopPois = allDbPois.filter { it.type.equals("shop", ignoreCase = true) }
                )
                _uiState.value = _uiState.value.copy(supplyGapWarnings = warnings)
            } catch (e: Exception) {
                Log.w(TAG, "Failed analyzing supply gaps: ${e.message}")
            }
        }
    }

    fun setMaxWaterGapKm(km: Double) {
        _uiState.value = _uiState.value.copy(maxWaterGapKm = km)
        analyzeSupplyGaps()
    }

    fun setMaxShopGapKm(km: Double) {
        _uiState.value = _uiState.value.copy(maxShopGapKm = km)
        analyzeSupplyGaps()
    }

    // --- Phase 12 Feature 2: Rundtur ---
    fun openRundturDialog() {
        _uiState.value = _uiState.value.copy(showRundturDialog = true)
    }

    fun dismissRundturDialog() {
        _uiState.value = _uiState.value.copy(showRundturDialog = false)
    }

    fun setRundturTargetKm(km: Double) {
        _uiState.value = _uiState.value.copy(rundturTargetKm = km.coerceIn(5.0, 60.0))
    }

    fun generateRundtur(startLat: Double? = null, startLng: Double? = null) {
        val startPt = if (startLat != null && startLng != null) {
            LatLng(startLat, startLng)
        } else {
            val firstWp = _uiState.value.waypoints.firstOrNull()
            if (firstWp != null) LatLng(firstWp.lat, firstWp.lng) else LatLng(56.15, 10.20)
        }

        _uiState.value = _uiState.value.copy(isGeneratingRundtur = true, showRundturDialog = true)
        viewModelScope.launch {
            try {
                val shelters = repository.getAllSheltersList()
                val friTeltning = repository.getAllFriTeltningDirect()
                val candidates = RouteSmartTools.generateRundturCandidates(
                    start = startPt,
                    targetKm = _uiState.value.rundturTargetKm,
                    routingEngine = routingEngine,
                    shelters = shelters,
                    friTeltning = friTeltning
                )
                _uiState.value = _uiState.value.copy(
                    isGeneratingRundtur = false,
                    rundturCandidates = candidates
                )
                if (candidates.isEmpty()) {
                    _userMessage.emit("Ingen rundture kunne dannes inden for 20% af målet (${_uiState.value.rundturTargetKm.toInt()} km)")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error generating rundtur: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isGeneratingRundtur = false)
                _userMessage.emit("Kunne ikke danne rundtur: ${e.message}")
            }
        }
    }

    fun applyRundtur(candidate: RundturCandidate) {
        pushHistory()
        _uiState.value = _uiState.value.copy(
            waypoints = candidate.waypoints,
            legs = candidate.legs,
            showRundturDialog = false,
            routeName = candidate.title
        )
        updateRouteStats()
        viewModelScope.launch {
            _userMessage.emit("Rundtur valgt: ${String.format(Locale.US, "%.1f", candidate.actualKm)} km")
        }
    }

    // --- Phase 12 Feature 3: Vejr og dagslys ---
    fun fetchWeatherAndSunset() {
        val wps = _uiState.value.waypoints
        val orderedLegs = mutableListOf<LegGeometryData>()
        for (i in 0 until wps.size - 1) {
            val key = "${wps[i].id}_to_${wps[i + 1].id}"
            _uiState.value.legs[key]?.let { orderedLegs.add(it) }
        }
        if (wps.size < 2) return

        _uiState.value = _uiState.value.copy(isLoadingWeather = true)
        viewModelScope.launch {
            try {
                val etas = RouteSmartTools.calculateWaypointWeatherEtas(
                    waypoints = wps,
                    orderedLegs = orderedLegs,
                    startTimeStr = startTimeStr,
                    pacingSpeedKmh = pacingSpeedKmh,
                    weatherService = weatherService
                )
                _uiState.value = _uiState.value.copy(
                    isLoadingWeather = false,
                    waypointWeatherEtas = etas
                )
            } catch (e: Exception) {
                Log.w(TAG, "Error fetching weather ETA: ${e.message}")
                _uiState.value = _uiState.value.copy(isLoadingWeather = false)
            }
        }
    }

    // --- Phase 12 Feature 4: Rute til nærmeste ---
    fun openNearestPoiDialog() {
        _uiState.value = _uiState.value.copy(showNearestPoiDialog = true)
    }

    fun dismissNearestPoiDialog() {
        _uiState.value = _uiState.value.copy(showNearestPoiDialog = false)
    }

    fun routeToNearest(category: NearestPoiCategory, startLat: Double? = null, startLng: Double? = null) {
        val startPt = if (startLat != null && startLng != null) {
            LatLng(startLat, startLng)
        } else {
            val firstWp = _uiState.value.waypoints.firstOrNull()
            if (firstWp != null) LatLng(firstWp.lat, firstWp.lng) else LatLng(56.15, 10.20)
        }

        _uiState.value = _uiState.value.copy(isRoutingToNearest = true)
        dismissNearestPoiDialog()
        viewModelScope.launch {
            try {
                val shelters = repository.getAllSheltersList()
                val friTeltning = repository.getAllFriTeltningDirect()
                val pois = repository.getAllPoisList()

                val match = RouteSmartTools.routeToNearestPoi(
                    start = startPt,
                    category = category,
                    routingEngine = routingEngine,
                    shelters = shelters,
                    friTeltning = friTeltning,
                    pois = pois
                )

                if (match != null) {
                    val (poi, legResult) = match
                    pushHistory()
                    val wpStart = RouteWaypoint(id = "start_nearest", lat = startPt.lat, lng = startPt.lng, role = WaypointRole.START, name = "Min position")
                    val wpDest = RouteWaypoint(id = "dest_${poi.id}", lat = poi.lat, lng = poi.lng, role = WaypointRole.END, name = poi.name)

                    val leg = LegGeometryData(
                        fromWaypointId = wpStart.id,
                        toWaypointId = wpDest.id,
                        legMode = LegMode.SNAP,
                        points = legResult.points,
                        lengthKm = legResult.lengthKm,
                        ascentM = legResult.ascentM,
                        descentM = legResult.descentM,
                        pathSharePct = 85.0,
                        roadKm = 0.2,
                        wayInfo = legResult.perPointWayInfo
                    )

                    val legsMap = mapOf("${wpStart.id}_to_${wpDest.id}" to leg)
                    _uiState.value = _uiState.value.copy(
                        isRoutingToNearest = false,
                        waypoints = listOf(wpStart, wpDest),
                        legs = legsMap,
                        routeName = "Rute til ${poi.name}"
                    )
                    updateRouteStats()
                    _userMessage.emit("Rute fundet til ${poi.name}: ${String.format(Locale.US, "%.1f", legResult.lengthKm)} km via rute")
                } else {
                    _uiState.value = _uiState.value.copy(isRoutingToNearest = false)
                    _userMessage.emit("Ingen ${category.label.lowercase()} fundet i nærheden.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error routing to nearest: ${e.message}", e)
                _uiState.value = _uiState.value.copy(isRoutingToNearest = false)
                _userMessage.emit("Fejl ved rutesøgning: ${e.message}")
            }
        }
    }

    // --- Phase 12 Feature 6: Mine punkter ---
    fun openAddCustomWaypointDialog(lat: Double, lng: Double) {
        _uiState.value = _uiState.value.copy(
            showAddCustomWaypointDialog = true,
            pendingCustomWaypointLocation = LatLng(lat, lng)
        )
    }

    fun dismissAddCustomWaypointDialog() {
        _uiState.value = _uiState.value.copy(
            showAddCustomWaypointDialog = false,
            pendingCustomWaypointLocation = null
        )
    }

    fun saveCustomWaypoint(name: String, note: String, photoPath: String?) {
        val loc = _uiState.value.pendingCustomWaypointLocation ?: return
        val entity = UserCustomWaypointEntity(
            routeId = _uiState.value.routeId,
            lat = loc.lat,
            lng = loc.lng,
            name = name.ifBlank { "Mit punkt" },
            note = note,
            photoPath = photoPath
        )
        dismissAddCustomWaypointDialog()
        viewModelScope.launch {
            repository.saveCustomWaypoint(entity)
            val updated = repository.getCustomWaypointsForRoute(_uiState.value.routeId ?: 0L)
            _uiState.value = _uiState.value.copy(customUserWaypoints = updated)
            _userMessage.emit("Punkt tilføjet: ${entity.name}")
        }
    }

    fun deleteCustomWaypoint(id: String) {
        viewModelScope.launch {
            repository.deleteCustomWaypoint(id)
            val updated = repository.getCustomWaypointsForRoute(_uiState.value.routeId ?: 0L)
            _uiState.value = _uiState.value.copy(
                customUserWaypoints = updated,
                selectedCustomWaypointForDetails = null
            )
            _userMessage.emit("Punkt slettet")
        }
    }

    fun selectCustomWaypointForDetails(wp: UserCustomWaypointEntity?) {
        _uiState.value = _uiState.value.copy(selectedCustomWaypointForDetails = wp)
    }

    // --- Phase 12 Feature 7: Del rute ---
    fun shareRoute(context: Context) {
        val wps = _uiState.value.waypoints
        val orderedLegs = mutableListOf<LegGeometryData>()
        for (i in 0 until wps.size - 1) {
            val key = "${wps[i].id}_to_${wps[i + 1].id}"
            _uiState.value.legs[key]?.let { orderedLegs.add(it) }
        }
        val points = orderedLegs.flatMap { it.points }.map { GeoPointDto(it.lat, it.lng, it.ele) }
        val name = _uiState.value.routeName.ifBlank { "Min rute" }
        val stats = _uiState.value.stats
        val quality = _uiState.value.routeQuality
        val overnights = wps.count { it.role == WaypointRole.OVERNIGHT }

        val dummyTrail = com.example.data.model.TrailEntity(
            id = "route_${_uiState.value.routeId ?: 0L}",
            name = name,
            description = "Brugerskabt rute (${String.format(Locale.US, "%.1f", stats.totalKm)} km)",
            region = "Danmark",
            parentRegion = "Danmark",
            computedWalkingKm = stats.totalKm
        )

        val gpxStr = com.example.util.GpxExporter.generateMultiSegmentGpx(
            trail = dummyTrail,
            plan = null,
            stages = emptyList(),
            trailPoints = points,
            customWaypoints = _uiState.value.customUserWaypoints
        )

        val summary = buildString {
            append("🥾 Rute: $name\n")
            append("📏 Distance: ${String.format(Locale.US, "%.1f", stats.totalKm)} km\n")
            append("⛰️ Højdemeter: +${stats.ascentM.toInt()} m / -${stats.descentM.toInt()} m\n")
            append("⏱️ Estimeret tid: ${_uiState.value.timeEstimateFormatted}\n")
            if (overnights > 0) {
                append("⛺ Planlagte overnatninger: $overnights\n")
            }
            append("🌲 Underlag: ${quality.trailPct.toInt()}% sti, ${quality.trackPct.toInt()}% skov-/markvej, ${quality.asphaltPct.toInt()}% asfalt\n")
            append("Del med Dansk Sti")
        }

        com.example.util.GpxExporter.exportAndShareGpxWithSummary(
            context = context,
            fileName = "${name.replace(" ", "_")}.gpx",
            gpxContent = gpxStr,
            shareTitle = "Del rute: $name",
            textSummary = summary
        )
    }
}
