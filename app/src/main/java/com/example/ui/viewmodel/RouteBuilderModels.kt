package com.example.ui.viewmodel

import com.example.data.model.FriTeltningEntity
import com.example.data.model.GeoPointDto
import com.example.data.model.OvernightType
import com.example.data.model.PoiEntity
import com.example.data.model.RundturCandidate
import com.example.data.model.ShelterEntity
import com.example.data.model.SupplyGapWarning
import com.example.data.model.TrailEntity
import com.example.data.model.TrailSegmentDto
import com.example.data.model.TripStageEntity
import com.example.data.model.UserCustomWaypointEntity
import com.example.data.model.WaypointWeatherEta
import com.example.util.OvernightCandidate
import com.example.util.routing.BRouterOnlineEngine
import com.example.util.routing.LatLng
import com.example.util.routing.LegGeometryData
import com.example.util.routing.LegMode
import com.example.util.routing.RouteOptions
import com.example.util.routing.RoutePoint3D
import com.example.util.routing.RouteStats
import com.example.util.routing.RouteWaypoint
import com.example.util.routing.TrailKmMarker
import com.example.util.routing.TrailProjection
import com.example.util.routing.WayInfo
import com.example.util.routing.WaypointRole
import kotlin.math.max

enum class PlannerMode {
    TRAIL, // Downloaded trail is the base line
    FREE   // Free route builder
}

enum class PlannerInteractionMode {
    KIG,
    TEGN
}

enum class SheetHeightState {
    PEEK,
    HALF,
    FULL
}

enum class PlannerSheetTab {
    RUTE,
    DAGE,
    DETALJER
}

data class SnapCandidateResult(
    val lat: Double,
    val lng: Double,
    val snapped: Boolean,
    val snapLabel: String? = null
)

data class LegMidpointHandle(
    val legKey: String,
    val legIndex: Int,
    val fromWaypointId: String,
    val toWaypointId: String,
    val lat: Double,
    val lng: Double
)

data class PlannerDaySummary(
    val dayNumber: Int,
    val startKm: Double,
    val endKm: Double,
    val distanceKm: Double,
    val ascentM: Int,
    val descentM: Int,
    val fromName: String,
    val toName: String,
    val overnightWaypointId: String?,
    val overnightName: String,
    val overnightTypeLabel: String,
    val detourLabel: String?,
    val detourKm: Double,
    val detourAscentM: Int,
    val bookingLabel: String?,
    val sunsetTime: String,
    val arrivalTime: String,
    val isAfterSunset: Boolean,
    val warnings: List<String>,
    val nightWeatherSummary: String,
    val campLat: Double,
    val campLng: Double,
    val estimatedHours: Double = 0.0,
    val overnightType: String = "SHELTER",
    val bookingRequired: Boolean = false
) {
    val detourDistanceKm: Double?
        get() = detourKm.takeIf { it > 0.0 }
    val arrivalTimeFormatted: String?
        get() = arrivalTime
    val arrivesAfterSunset: Boolean
        get() = isAfterSunset
    val sunsetFormatted: String?
        get() = sunsetTime
}

data class PlannerMapPoi(
    val id: String,
    val name: String,
    val type: String, // "SHELTER", "FRI_TELTNING", "WATER", "TOILET", "SHOP"
    val lat: Double,
    val lng: Double,
    val distanceToRouteMeters: Double = 0.0,
    val bookingRequired: Boolean = false,
    val hasWater: Boolean = false,
    val hasToilet: Boolean = false
)

data class PlannerMapPoiSelection(
    val id: String,
    val name: String,
    val typeLabel: String,
    val lat: Double,
    val lng: Double,
    val isOvernightCapable: Boolean,
    val bookingRequired: Boolean = false
)

data class RouteAlternativeInfo(
    val index: Int,
    val lengthKm: Double,
    val ascentM: Double,
    val pathSharePct: Double?,
    val roadKm: Double?,
    val points: List<RoutePoint3D>,
    val wayInfo: List<WayInfo> = emptyList()
)

typealias RouteBuilderUiState = RouteBuilderState

data class RouteBuilderState(
    val mode: PlannerMode = PlannerMode.FREE,
    val interactionMode: PlannerInteractionMode = PlannerInteractionMode.TEGN,
    val sheetHeightState: SheetHeightState = SheetHeightState.PEEK,
    val existingPlanId: Long? = null,
    val baseTrailId: String? = null,
    val baseTrail: TrailEntity? = null,
    val baseTrailSegments: List<TrailSegmentDto> = emptyList(),
    val baseTrailKmMarkers: List<TrailKmMarker> = emptyList(),
    val isReverse: Boolean = false,
    val draggingWaypointId: String? = null,
    val draggingPinKmLabel: String? = null,
    val tappedTrailPoint: TrailProjection? = null,
    val showTripSettingsSheet: Boolean = false,
    val paceKmh: Double = 4.5,
    val dailyTargetKm: Double = 15.0,
    val startDateMillis: Long = System.currentTimeMillis(),
    val startTimeStr: String = "09:00",
    val maxDetourKm: Double = 3.0,
    val overnightPreference: OvernightType = OvernightType.BOTH,
    val plannedStages: List<TripStageEntity> = emptyList(),
    val daySummaries: List<PlannerDaySummary> = emptyList(),
    val dayBoundaryWalkKms: List<Double> = emptyList(),
    val nearbyShelters: List<ShelterEntity> = emptyList(),
    val nearbyFriTeltning: List<FriTeltningEntity> = emptyList(),
    val nearbyPois: List<PoiEntity> = emptyList(),
    val nearbyPoisForPlan: List<PlannerMapPoi> = emptyList(),
    val selectedNearbyPoiForSheet: PlannerMapPoi? = null,
    val selectedMapPoiForSheet: PlannerMapPoiSelection? = null,
    val replaceOvernightWaypointId: String? = null,
    val replaceOvernightCandidates: List<OvernightCandidate> = emptyList(),
    val routeId: Long? = null,
    val routeName: String = "",
    val waypoints: List<RouteWaypoint> = emptyList(),
    val legs: Map<String, LegGeometryData> = emptyMap(),
    val loadingLegKeys: Set<String> = emptySet(),
    val failedLegs: Map<String, String> = emptyMap(),
    val waitingForStartPointTap: Boolean = false,
    val movingWaypointId: String? = null,
    val isBuiltinFallbackInUse: Boolean = BRouterOnlineEngine.isBuiltinFallbackInUse(),
    val isLoadingCorridorPois: Boolean = false,
    val corridorPoisOfflineNotice: String? = null,
    val isDownloadingTrailGeometry: Boolean = false,
    val trailGeometryDownloadFailed: Boolean = false,
    val showResetProgressDialog: Boolean = false,
    val options: RouteOptions = RouteOptions(),
    val stats: RouteStats = RouteStats(0.0, 0.0, 0.0, null, 0.0, hasWayInfo = false),
    val timeEstimateFormatted: String = "0 min",
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val isSaving: Boolean = false,
    val isStarting: Boolean = false,
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
    val supplyGapWarnings: List<SupplyGapWarning> = emptyList(),
    val maxWaterGapKm: Double = 12.0,
    val maxShopGapKm: Double = 25.0,
    val rundturCandidates: List<RundturCandidate> = emptyList(),
    val isGeneratingRundtur: Boolean = false,
    val showRundturDialog: Boolean = false,
    val rundturTargetKm: Double = 15.0,
    val waypointWeatherEtas: List<WaypointWeatherEta> = emptyList(),
    val isLoadingWeather: Boolean = false,
    val isRoutingToNearest: Boolean = false,
    val showNearestPoiDialog: Boolean = false,
    val routeQuality: com.example.data.model.RouteQualityBreakdown = com.example.data.model.RouteQualityBreakdown(),
    val customUserWaypoints: List<UserCustomWaypointEntity> = emptyList(),
    val showAddCustomWaypointDialog: Boolean = false,
    val pendingCustomWaypointLocation: LatLng? = null,
    val selectedCustomWaypointForDetails: UserCustomWaypointEntity? = null,
    val isOfflineRoute: Boolean = false,
    val routeEngineModeLabel: String = "Online rute",
    val nextLegMode: LegMode = LegMode.SNAP,
    val isOvernightToolActive: Boolean = false,
    val isCrosshairMode: Boolean = false,
    val selectedLegKey: String? = null,
    val legAlternatives: Map<String, List<RouteAlternativeInfo>> = emptyMap(),
    val isFetchingLegAlternatives: Boolean = false,
    val deletedPinSnackbarMessage: String? = null,
    val selectedSheetTab: PlannerSheetTab = PlannerSheetTab.RUTE,
    val sheetHeightFraction: Float = 0.22f,
    val selectedDayNumber: Int? = null,
    val mapViewportTarget: LatLng? = null,
    val plannerSearchQuery: String = "",
    val plannerSearchResults: com.example.data.remote.GroupedSearchResults = com.example.data.remote.GroupedSearchResults(),
    val currentGpsPosition: LatLng? = null,
    val showCoachMarks: Boolean = false,
    val coachMarkStep: Int = 0
) {
    val visibleWaypoints: List<RouteWaypoint>
        get() = waypoints.filter { !it.isHelper }
    val numDays: Int
        get() = max(1, visibleWaypoints.count { it.role == WaypointRole.OVERNIGHT } + 1)
    val baseTrailPoints: List<GeoPointDto>
        get() = baseTrailSegments.flatMap { it.points }
    val draggingTrailKmLabel: String?
        get() = draggingPinKmLabel
    val trailLineTapTarget: TrailProjection?
        get() = tappedTrailPoint
    val replacingOvernightWaypointId: String?
        get() = replaceOvernightWaypointId
    val startHour: Int
        get() = startTimeStr.substringBefore(":").toIntOrNull() ?: 9
    val startDateEpochMs: Long
        get() = startDateMillis
    val plannerMode: PlannerMode
        get() = mode
    val totalKm: Double
        get() = stats.totalKm
    val isRoutingLegCount: Int
        get() = loadingLegKeys.size
}
