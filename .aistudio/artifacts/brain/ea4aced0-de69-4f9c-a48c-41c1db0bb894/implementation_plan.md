# Fix Trail Navigation and Trip Planner Initialization

Resolve the issue where tapping **Navigér** or **Planlæg tur** on a trail detail screen displays an empty state with "Ingen sti valgt". Directly initialize the trail in `PlannerViewModel`, provide a clear loading state with status feedback while the trail and stages are being calculated, and ensure smooth transitions to live navigation.

## User Review & Critical Decisions

> [!IMPORTANT]
> The following decisions were confirmed based on your requirements:
> - **Navigation Flow without Existing Plan**: When tapping **Navigér** on a trail without a previously saved trip plan, the app opens the **Turplanlægger** with the trail pre-loaded and stages ready, allowing immediate review or 1-tap start via **Start vandring**.
> - **Trip Planner Loading State**: While the trail entity, GPS coordinates, elevation, shelters, and weather data are being loaded from the local database, the screen displays a centered loading spinner with the message *"Indlæser rute og beregner etaper..."* instead of prematurely showing "Ingen sti valgt".

---

## 1. Overview & Core Concept

When a user views a hiking route in `TrailDetailScreen` and taps either:
1. **Planlæg tur** (the primary bottom action button)
2. **Navigér** (the action row button when no previous plan exists for this trail)

The app routes to `TripPlannerScreen(trailId = trailId)`. Previously, `TripPlannerScreen` synchronously checked `viewModel.allTrails.value.find { it.id == trailId }` inside a `LaunchedEffect`. Because `allTrails` is an asynchronous `StateFlow` from Room, its value was initially `emptyList()`. The trail was never selected, remaining `null`, and the screen immediately exited and rendered `"Ingen sti valgt"`.

This implementation plan resolves this by:
1. Triggering `viewModel.initTrail(trailId)` directly in `TripPlannerScreen` via `LaunchedEffect(trailId)`.
2. Providing a dedicated loading state in `TripPlannerScreen` while `trail == null` and `isCalculating` is true.
3. Providing a graceful fallback error state with a "Gå tilbage" button only if the trail ID does not exist in the database.
4. Ensuring `TrailViewModel.onNavigateClicked` seamlessly routes to either existing plans or the pre-loaded planner.

---

## 2. User Experience & Visual Design

### User Flows

```
[ Trail Detail Screen ]
   │
   ├── Taps "Planlæg tur" ───────────────┐
   │                                     ▼
   └── Taps "Navigér" (no saved plan) ──> [ Turplanlægger Screen ]
                                           │
                                           ├─ 1. Shows "Indlæser rute og beregner etaper..." with spinner
                                           ├─ 2. Displays trail name, total length, day splits & shelters
                                           └─ 3. User taps "Start vandring" ──> [ Live Navigation Screen ]
```

### Visual Styling & Components
- **Container**: `ForestDarkBg` (`0xFF121A16`) with modern Material Design 3 cards.
- **Loading State**:
  - `CircularProgressIndicator` with `ForestGreenPrimary` (`0xFF2E7D32`).
  - Heading: *"Indlæser rute"* (18sp, Bold, White).
  - Subtitle: *"Beregner etaper, overnatningssteder og vejr..."* (14sp, Gray).
- **Error State**:
  - Icon: `Icons.Default.Route` or `Warning`.
  - Message: *"Stien kunne ikke findes"* with a prominent button to return to the trail overview.

---

## 3. Key Product Decisions & Trade-Offs

- **Direct Repository Query vs. StateFlow Snapshot**:
  - *Decision*: In `TripPlannerScreen`, call `viewModel.initTrail(trailId)` directly rather than querying `viewModel.allTrails.value`.
  - *Rationale*: `initTrail` fetches the trail asynchronously from `repository.getTrailById(trailId)` and coordinates all stage computations, preventing race conditions.
- **Loading State UI vs Instant Null Check**:
  - *Decision*: Replace the synchronous `if (trail == null) return Text("Ingen sti valgt")` block with an active loading card whenever `trail == null || isCalculating`.
  - *Rationale*: Eliminates flickering and provides clear visual feedback to the user while route calculations execute.

---

## 4. Technical Architecture & Data Strategy

### Component Interaction Diagram

```
┌─────────────────────────────────┐
│       TrailDetailScreen         │
│  - "Navigér"                    │
│  - "Planlæg tur"                │
└────────────────┬────────────────┘
                 │
                 ▼
┌─────────────────────────────────┐
│       TripPlannerScreen         │
│  LaunchedEffect(trailId)        │
│  └─ viewModel.initTrail(trailId)│
└────────────────┬────────────────┘
                 │
                 ▼
┌─────────────────────────────────┐
│        PlannerViewModel         │
│  - repository.getTrailById()    │
│  - calculateStagesInternal()    │
│  - _trail.value = t             │
└─────────────────────────────────┘
```

### Specific Implementation Steps

1. **`TripPlannerScreen.kt`** [COMPLETED]:
   - Updated `LaunchedEffect(trailId)`: Calls `viewModel.initTrail(trailId)` whenever `trailId.isNotBlank()`.
   - Replaced early `if (trail == null)` exit:
     - When `trail == null` and route is loading / calculating: Centered `CircularProgressIndicator` with *"Indlæser rute"* and *"Beregner etaper, overnatningssteder og vejr..."*.
     - When `isTrailNotFound`: Shows *"Stien kunne ikke findes"* with return action.
     - When trail is loaded: Renders complete planner interface with stages, gear checklist, and *"Start vandring"*.
2. **`TrailViewModel.kt` & `TrailDetailScreen.kt`** [COMPLETED]:
   - Verified `onNavigateClicked` invokes `onOpenPlanner()` with `trailId` when no saved plan exists, ensuring seamless routing via `Screen.TripPlanner.createRoute(trailId)`.
   - Verified bottom *"Planlæg tur"* button passes `trailId` to `onPlanTrip(trailId)`.
3. **Verification** [COMPLETED]:
   - Cleared conflicting background JVM processes and gradle locks.
   - Successfully executed `compile_applet` with clean zero-error build.
