# Phase B – path details from OSM way tags and waymark badges

Requires Phase A. Follow RULES.md. Only change what this phase names. Danish UI text, with the same keys in `values-en` and `values-de`.

## B1. Keep the useful way tags

**data/model/GeometryModels.kt**
- Add `val pathTags: Map<String, String>? = null` to `WayTagRangeDto`. Keep it as the last parameter so the generated Moshi adapter can still read old cached JSON.
- Add `val pathTagsVersion: Int = 0` to `TrailGeometryDto`.

**New `util/PathTags.kt`** with `val PATH_TAG_KEYS` = `sac_scale, trail_visibility, smoothness, tracktype, lit, foot, access, access:conditional, foot:conditional, ford, step_count, bridge, informal, bicycle, horse, tidal, flood_prone, incline, seasonal, name`, and `fun filterPathTags(tags: Map<String,String>): Map<String,String>?`. The function keeps only those keys with non-blank values and returns null if none remain.

**data/repository/TrailGeometryRepository.kt**
- Add `pathTags: Map<String,String>? = null` to the private `WayData`. Fill it with `filterPathTags(tags)` where `WayData` is built in the member loop (next to `highway = tags["highway"]`).
- In `stitchWays`, pass `pathTags = w.pathTags` in all four places where a `WayTagRangeDto` is created.
- The Douglas-Peucker rescaling already uses `r.copy(...)`, so it keeps the tags. Check every other place in this file where a range is copied.
- Set `pathTagsVersion = 1` on the `TrailGeometryDto` returned by `fetchAndProcessTrail` (the newly built geometry).

**util/routing/TrailSlicer.kt**
- In the clipped ranges and the reversed ranges, build the new range with `r.copy(fromKm = …, toKm = …)` instead of positional `WayTagRangeDto(...)`, so `pathTags` survives.
- Move `normalizeSegmentWayRanges` into a shared internal helper (for example `util/routing/WayRangeUtils.kt`) and use it from both `TrailSlicer` and B2.

**util/routing/RouteGeometryStorageManager.kt**
- Write `pathTags` as a JSON object `"pt"` on each serialized range, and read it back where `rangesList` is rebuilt.

## B2. Path features calculation (new util/PathFeatureAnalyzer.kt, pure Kotlin)

**Input**
- A list of `PathRange(fromKm, toKm, highway, surface, tags: Map<String,String>)` in **global walk km**, plus a function `pointAtKm(km): GeoPointDto?`.

**Helpers**
- `PathRangeBuilder.fromTrailSegments(segments)`: walks the WALK segments, adds each segment's walk-km offset, and uses the shared normalize helper from B1.
- `PathRangeBuilder.fromLegs(orderedLegs)`: for each leg, uses its `trailSegments` ranges when present; otherwise builds per-point ranges from `leg.wayInfo` (`highway`, `surface`, and `filterPathTags(rawTags)`).

**Output**
- `PathFeatureSummary`, with km or counts for:
  - `stepsCount`, `fordCount`;
  - `privateKm`, `horseKm`, `bikeKm`, `litKm`, `roughOrUnclearKm`, `sandKm`, `informalKm`, `tidalKm`.
- `List<PathFeatureWarning(type, startKm, endKm, lat, lng, title, detail)>`.

**Rules**
| Type | Detection | Title |
|---|---|---|
| STEPS | `highway=steps`; detail from `step_count` when present | "Trapper" / "Trapper (%1$d trin)" |
| FORD | `ford=yes` or `ford=stepping_stones` | "Vadested – du kan få våde fødder" |
| PRIVATE | `access` or `foot` is `private` or `no`, unless `foot` is `yes`, `designated` or `permissive` | "Privat vej/sti – adgang kan være begrænset" |
| SEASONAL_CLOSURE | `access:conditional` or `foot:conditional` with a closed value and a date period (B3) | "Lukket %1$s" (e.g. "Lukket 1. mar – 15. jul") |
| NIGHT_CLOSURE | conditional with `sunset-sunrise` or a clock range | "Lukket om natten (%1$s)" |
| TIDAL | `tidal=yes` or `flood_prone=yes` | "Kan stå under vand ved højvande eller oversvømmelse" |
| UNCLEAR | `trail_visibility` is `bad`, `horrible` or `no` | "Utydelig sti" |
| ROUGH | `smoothness` is `very_bad`, `horrible`, `very_horrible` or `impassable`, or `sac_scale` is `mountain_hiking` or harder | "Ujævnt underlag – godt fodtøj anbefales" |
| INFORMAL | `informal=yes` | "Uofficiel sti" |
| BOARDWALK | `bridge=boardwalk` | "Spang – kan være glat" |

- Horse, bike, lit and sand only go into the summary, not the warning list:
  - horse: `horse=designated`;
  - bike: `bicycle=designated` on a path or footway, or `highway=cycleway`;
  - lit: `lit=yes`;
  - sand: `surface=sand`.
- **Merging:** merge ranges of the same type when they are less than 50 m apart. Drop warnings shorter than 10 m, except STEPS and FORD.
- **Coordinates:** set lat/lng from `pointAtKm(startKm)`.

## B3. Conditional access parser (new util/ConditionalAccessParser.kt, pure Kotlin)

**What to parse**
- `access:conditional` / `foot:conditional` values such as:
  - `no @ (Mar 01-Jul 15)`, `no @ (Mar-Jul)`, `no @ (Apr 1-Aug 31)`;
  - `no @ (sunset-sunrise)`, `no @ (21:00-06:00)`;
  - several rules separated by `;` outside the parentheses.
- Only `no` and `private` count as closed.

**Output**
- `ClosureRule(dateRange: (startMonth, startDay, endMonth, endDay)?, timeText: String?, raw: String)`.
- `isClosedOn(epochMs)`, using Europe/Copenhagen; a range like Nov–Feb crosses the new year.
- `displayPeriod()` in Danish, e.g. "1. mar – 15. jul".

**When it can't be parsed**
- Keep the warning with its raw condition text: "Adgang begrænset: %1$s".

## B4. Trail page (TrailViewModel + TrailDetailContentSection / TrailDetailExtraCards)

**`TrailViewModel`**
- Compute `PathFeatureAnalyzer` from the WALK segments next to the surface mix, exposing `pathFeatureSummary` and `pathFeatureWarnings`.
- Add a "Sand" share to the surface mix (`surface=sand`); the existing order then becomes Sti, Grus, Asfalt, Sand, Ukendt. Add the string `trail_surface_sand_pct`.

**New card "Stien undervejs" in the Oversigt tab, under the surface bar**
- **Summary rows**, only for non-zero values: "Trapper: %1$d steder", "Vadesteder: %1$d", "Privat vej/sti: %1$s km", "Delt med ryttere: %1$s km", "Delt med cyklister: %1$s km", "Belyst: %1$s km", "Utydelig eller ujævn sti: %1$s km", "Sand og strand: %1$s km".
- **Warning list**, sorted by km: "km 12,4 · Trapper (40 trin)".
  - SEASONAL_CLOSURE always shows the period.
  - If `TrailDetailScreen` already has a way to open the map at a point, use it on tap; otherwise show the list without tap.
  - Show 8 rows, then "Vis alle (%1$d)".
- **Empty states:**
  - geometry with `pathTagsVersion < 1`: "Detaljer om stien hentes, næste gang ruten opdateres";
  - otherwise: "Ingen trapper, vadesteder eller adgangsbegrænsninger registreret i OpenStreetMap".
- **Source line:** "Kilde: OpenStreetMap".

## B5. Upgrade old cached geometry (data/repository/DanskStiRepository.kt)

In `getTrailGeometry` and `getTrailPoints`, when the cached geometry for a catalogue trail (not `route_`) has `pathTagsVersion < 1`, the device is online, and the trail has **no unfinished trip**:
- start a **non-blocking** upgrade in a repository-level `CoroutineScope(SupervisorJob() + Dispatchers.IO)`, at most once per trail per app session;
- call `geometryRepository.fetchAndProcessTrail(...)` with **no timeout**;
- save the result and update the `TrailEntity` the same way the existing refresh does;
- return the cached geometry immediately;
- on failure, keep the cache.

**Checking for an unfinished trip**
- A trip is unfinished when the latest `trip_plans` row for the trail exists, its `trip_progress` rows are not all `isCompleted`, and it was active in the last 60 days.
- Add the DAO queries you need.

**Do not** reuse the existing 3 s `withTimeoutOrNull`. That path stays only for geometry that has no way-tag ranges at all.

## B6. Planner (RouteBuilder)

- **`RouteBuilderViewModelLegs`:** where `analyzeRouteQuality(orderedLegs)` runs, also compute `PathFeatureAnalyzer` from `PathRangeBuilder.fromLegs(orderedLegs)`. Store `pathFeatureSummary` and `pathFeatureWarnings` in `RouteBuilderState` (ui/viewmodel/RouteBuilderModels.kt).
- **`PlannerSheetDetaljerTab`:** add a "Stien undervejs" section next to the road-section warnings, with the same rows and empty states as B4 and tap-to-zoom like `RoadSectionWarningCard`.
- **`RouteBuilderViewModelDays`:**
  - For each day, add the path warnings whose km range overlaps `[stage.startKm, stage.endKm]` to `dayWarnings`, as "km %1$s: %2$s".
  - Evaluate SEASONAL_CLOSURE with `ClosureRule.isClosedOn(state.startDateMillis + idx days)`. Add it only when closed that day, as "km %1$s: Stien er lukket på denne dato (%2$s)". Otherwise skip it.
  - **Bug fix:** add each access warning only to the day whose km range contains it. Project `RouteAccessWarning.location` onto the combined route to get its km, instead of adding every warning to every day.

## B7. Navigation "Foran dig"

- **`NavigationViewModel`:** when a route starts, load the trail or route geometry, build `pathFeatureWarnings`, and project each warning's lat/lng onto the `PlannedRoute` with `route.projectPoint`. Keep warnings within 50 m of the route.
- **`computeRouteStopsAndAhead`:** add a parameter `pathWarnings`. Add items of type `"WARNING"` for warnings starting in the next 5 km.
- **`NavigationAheadAndProgressComponents`:** WARNING uses `Icons.Default.Warning` and the warning colour.

## B8. Waymark badge

**New util/OsmcSymbolParser.kt (pure Kotlin)**
- Parse `osmc:symbol` in the form `waycolor:background[:foreground[:foreground2]][:text[:textcolor]]`.
- Background values: a colour, or `<colour>_circle|_frame|_round`.
- Foregrounds `<colour>_bar|_stripe|_dot|_circle|_cross|_triangle|_diamond|_lower|_corner|_x|_rectangle|_pointer`.
- If the symbol is missing, fall back to the relation's `colour` tag.

**Danish wording**
- Map the colours red, blue, green, yellow, white, black, orange, purple, brown and gray to both forms: definite (røde, blå, grønne, gule, hvide, sorte, orange, lilla, brune, grå) and indefinite (rød, blå, grøn, gul, hvid, sort, orange, lilla, brun, grå).
- Text examples:
  - "Følg det røde mærke på hvid baggrund";
  - with text: "Følg mærket «E1»";
  - colour only: "Rutens farve: rød".
- Unknown parts are left out, never guessed.

**New composable `WaymarkBadge(symbol, colour)`**
- Draws a 28 dp rounded square with the background colour, the foreground shape in its colour (simple Canvas shapes) and the text.
- Shows the sentence next to it.

**Where it's used**
- At the top of `OsmRelationTagsCard`, with a network line from `network`: iwn → "International rute", nwn → "National rute", rwn → "Regional rute", lwn → "Lokal rute".
- In `MarkedRouteDetailSheet`, replacing the raw symbol text.

## Tests
- `PathFeatureAnalyzer`: every rule, merging under 50 m, the 10 m minimum, and the summary sums.
- `ConditionalAccessParser`: the listed formats, a period across the new year, unparseable values, and `yes @ (...)` ignored.
- `PathRangeBuilder.fromTrailSegments`: local versus global km ranges.
- `TrailSlicer`: `pathTags` survives clipping and reversing.
- `RouteGeometryStorageManager`: a round trip with `pathTags`.
- `OsmcSymbolParser` plus the Danish sentence for `red:white:red_bar`, `blue:white:blue_dot`, `:::E1:black` and the colour-only case.
- Planner: an access warning only appears on its own day.

## Done when
- A catalogue trail fetched after this phase shows real steps, fords, private stretches and seasonal closures, with km.
- Older cached trails upgrade in the background without blocking the screen, and never while a trip on that trail is unfinished.
- Planner days list only their own warnings, and seasonal closures appear only on the days they apply.
- Navigation warns about a path feature within 5 km ahead.
- Waymarks show as a badge plus a Danish sentence instead of raw codes.
- List every file you changed and anything you could not complete.
