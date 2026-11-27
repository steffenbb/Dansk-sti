# Phase A – GeoFA fields, tap water, bus stops, re-sync (Room 28 → 29)

Follow RULES.md. Only change what this phase names. All user-facing text is Danish in `values/strings.xml`; add the same keys translated in `values-en` and `values-de`.

## A1. Room migration 28 → 29

**`ShelterEntity` (data/model/Models.kt): add nullable columns with default null**
- `hasOwnTap: Boolean?`
- `hasOwnToilet: Boolean?`
- `hasShower: Boolean?`
- `seasonal: String?`: OSM-style season text that `SeasonalWaterEvaluator` understands, e.g. `"Apr-Oct"`, `"yes"`, `"no"`.
- `seasonInfo: String?`: a Danish display text.
- `open24h: Boolean?`
- `accessNote: String?`

**`PoiEntity`: add**
- `subtype: String? = null`
- `detailsJson: String? = null`: a JSON object of whitelisted extra details.

**Migration and tests**
- Add `MIGRATION_28_29` in `DanskStiDatabase` using `ALTER TABLE … ADD COLUMN` (INTEGER for nullable Booleans, TEXT for strings).
- Set `version = 29`, register the migration in `addMigrations(...)` and keep `exportSchema = true`.
- Add a migration unit test in the style of the existing 27→28 test in `Round21VerificationTest`. It should insert a v28 shelter and POI row, migrate, and check that old values survive and the new columns are null.

## A2. GeoFA parsing (data/repository/GeoFaRepository.kt)

Create the pure Kotlin object `GeoFaFieldParser` (no Android types), with unit tests:

- **`isActiveStatus(status: String?)`:** returns false when the text contains "Kladde", "Forslag", "Aflyst" or "Ikke gældende" (case-insensitive). Otherwise true, including null and "Ukendt".
- **`yesNo(v: String?)`:** "Ja" → true, "Nej" → false, anything else (Ukendt, Blandet, null) → null.
- **`toPlainText(html: String?)`:**
  - turn `<br>`, `</p>`, `</div>`, `</li>`, `</h1-6>` into line breaks and `<li>` into "• ";
  - remove all other tags, including `<img …>`, and keep link text;
  - decode `&amp; &nbsp; &quot; &lt; &gt; &#39;`, numeric entities, and `&aelig; &oslash; &aring; &AElig; &Oslash; &Aring;`;
  - trim each line, collapse 3+ line breaks to 2 and repeated spaces to one;
  - return null when empty.
- **`seasonalFromGeoFa(saeson, saesonStart, saesonSlut)`:** the dates look like `0001-04-01`.
  - "Helårsåbent" → `"no"`;
  - "Sæsonåbent" with both dates, unless they are 01-01 and 12-31 → English month range such as `"Apr-Oct"` (Jan Feb Mar Apr May Jun Jul Aug Sep Oct Nov Dec; the evaluator already maps these);
  - "Sæsonåbent" without usable dates → `"yes"`;
  - anything else → null.
- **`seasonDisplayText(saeson, start, slut, bemaerkning)`:**
  - "Helårsåbent" → "Åbent hele året";
  - seasonal with dates → "Sæsonåbent 1. apr – 31. okt";
  - seasonal without dates → "Sæsonåbent";
  - append the cleaned `saeson_bem` note after " · " when present;
  - otherwise null.
- **`accessibilityText(handicap)`:**
  - "Handicapegnet" or "Handicapvenligt" → "Handicapegnet";
  - "Delvist handicapegnet" → "Delvist handicapegnet";
  - "Ikke handicapegnet" → "Ikke handicapegnet";
  - otherwise null.
- **`accessNote(tilgaengOplJson: String?)`:** parse the JSON object and take `shelter_tilgaengelighed_beskrivelse` or `toiletforhold_beskrivelse`, run it through `toPlainText`, and return null if empty or invalid.

**`parseSheltersGeoJson`**
- Skip features where `!isActiveStatus(props["status"])`.
- `description` = `toPlainText(beskrivels) ?: toPlainText(lang_beskr)`.
- `fee = yesNo(betaling)` and `accessibility = accessibilityText(handicap)`.
- `hasOwnTap = yesNo(vandhane)` and `open24h = yesNo(doegnaab)`.
- `seasonal` and `seasonInfo` come from `saeson`, `saeson_st`, `saeson_sl`, `saeson_bem`.
- `accessNote = accessNote(tilgaeng_opl)`.
- Keep the booking and info-link logic unchanged.

**`parseFriTeltningGeoJson`**
- Skip inactive entries.
- `rulesSummary` uses the cleaned text, or the existing default.

**`parsePoisGeoJson`**
- Skip inactive entries.
- Set `subtype` to "geofa_toilet" or "geofa_water".
- `seasonal = seasonalFromGeoFa(...)`; keep the existing 24/7 access label and `fee = betaling == "Ja"`.
- Store `detailsJson` with keys `accessibility` and `accessNote` when present.
- For every **toilet** with `vandhane = "Ja"`, also emit a water POI:
  - id `"<objekt_id>_vandhane"`, type `"water"`, subtype `"geofa_toilet_tap"`;
  - name from the new string `geofa_toilet_tap_name` = "Vandhane ved toilet";
  - the same coordinates, `seasonal`, source `"geofa"` and fetchedAt.

**New `GeoFaRepository.tapPoisFor(shelters: List<ShelterEntity>): List<PoiEntity>`**
- For shelters with `hasOwnTap == true`, create a water POI:
  - id `"<shelterId>_vandhane"`, type `"water"`, subtype `"geofa_shelter_tap"`;
  - name `geofa_shelter_tap_name_fmt` = "Vandhane ved %1$s" (the shelter name);
  - the shelter's `seasonal`, source "geofa".

## A3. Use taps everywhere water is counted (data/repository/DanskStiRepository.kt)

1. **Wherever GeoFA shelters are inserted**, also insert `geoFaRepository.tapPoisFor(shelters)` into `pois`. That is:
   - the first-run seeding in `initializePreseededDataIfEmpty`;
   - step 1 of `syncCorridorPois`;
   - step 1 of `syncCorridorPoisForRouteInternal`;
   - the new backfill below.
2. **In all three shelter recompute blocks** (seeding, `syncCorridorPois` step 4, the route sync equivalent):
   - `hasWater = sh.hasOwnTap == true || <existing 500 m check>`;
   - `hasToilet = sh.hasOwnToilet == true || <existing 500 m check>`.
3. **`mergeGeoFaAndOsmShelter`:** fill each new field GeoFA-first, then OSM (`gf.x ?: osm.x`).

**One-time backfill for existing installs**
- Add `runGeoFaFieldsBackfillIfNeeded(force: Boolean = false)` next to `runGeoFaBookingBackfillIfNeeded`, with the pref key `geofa_fields_backfill_v29_done`. Call it in the same `else` branch.
- **Source:** for each dataset, use the live cache file in `filesDir/geofa` (`shelters.json`, `fri_teltning.json`, `toilets_water.json`) when it exists and parses; otherwise use the bundled asset.
- **Update** existing rows by id with `source = 'geofa'`, using new DAO `@Query` methods:
  - shelters: `description`, `fee`, `accessibility`, `hasOwnTap`, `seasonal`, `seasonInfo`, `open24h`, `accessNote`;
  - fri teltning: `rulesSummary`;
  - POIs: `seasonal`, `subtype`, `detailsJson`, `fee`.
- **Never touch** `hasWaterNearby`, `trailCorridorId`, booking fields or `trail_pois`.
- **Delete** GeoFA rows (shelters, fri_teltning, pois) whose status is inactive. Add a parser helper that returns the inactive `objekt_id`s.
- **Insert** both kinds of tap POI.
- Then run `UPDATE shelters SET hasWaterNearby = 1 WHERE hasOwnTap = 1`.
- Set the flag only after success, and log counts.

## A4. Shelter, toilet and water sheet (ui/components/PoiInfoSheetModels.kt)

**`fromShelter`**
- **Water:**
  - `hasOwnTap == true` → "Vandhane ved shelteret" (`poi_fac_own_tap`), instead of "Vand i nærheden";
  - `hasOwnTap == false && hasWaterNearby` → "Vand i nærheden (ikke ved shelteret)" (`poi_fac_water_nearby_not_at_shelter`);
  - otherwise keep the current behaviour.
- **Toilet and shower:** `hasOwnToilet == true` → "Toilet ved shelteret"; `hasShower == true` → "Bad".
- **Season:** `seasonInfo` as its own facility line.
- **24 hours:** `open24h == true` → "Døgnåbent".
- **Description:** already cleaned. Show `accessNote` under it with the label "Adgang:" (`poi_access_note_label`).

**`fromPoiEntity`, for toilet and water**
- When `seasonal` gives explicit months via `SeasonalWaterEvaluator.parseOpenMonths`, show "Sæsonåbent (apr–okt)" with Danish month abbreviations, instead of plain "Sæsonåbent".
- Show `accessibility` and `accessNote` from `detailsJson`.
- Tap POIs show "Vandhane" as the type label.

## A5. Map: avoid a duplicate icon on top of shelters

In `MapLayersPoiBuilder`, skip POIs with `subtype == "geofa_shelter_tap"` when `state.showShelters` is true. The shelter sheet already says it has a tap. Keep them in every calculation and in the trail's "Vand & mad" list.

## A6. Bus stops and halts (data/repository/OsmPoiRepository.kt)

**In `buildOverpassPolylineQuery`, add**
```
nwr["highway"="bus_stop"](around:1500,$coordStr);
nwr["public_transport"="platform"]["bus"="yes"](around:1500,$coordStr);
```
and change the station line to `nwr["railway"~"^(station|halt)$"](around:3000,$coordStr);`.

**In `parseOverpassJson`, before the station branch**
- Map `highway=bus_stop`, or `public_transport=platform` + `bus=yes`, to type `"bus_stop"`.
- Name fallback: `osm_default_bus_stop_name` = "Busstoppested".
- After parsing, merge bus stops with the same name within 60 m (the two sides of a road) into one.

**`TrailViewModel` Transport tab**
- Show all stations and ferries, as today.
- Show bus stops only within 600 m of the trail, de-duplicated by name, sorted by km.
- After 8 stops, show a "Vis alle %1$d stoppesteder" row that expands.
- F3 (`findNearestTransportWithin3Km`) keeps using both types.

## A7. Re-sync corridor POIs once after an update

- Add `const val CORRIDOR_POI_VERSION = 2` and store the synced version per id in SharedPreferences (`corridor_poi_version_<trailId>` / `corridor_poi_version_route_<routeId>`).
- After every successful `syncCorridorPois` / `syncCorridorPoisForRoute`, write the current version.
- **`TrailViewModel`:** when POIs are not empty but the stored version is lower and the device is online, start a **background** re-sync that doesn't block the screen, then reload the lists. Keep the existing blocking sync for the empty case.
- **`RouteBuilderViewModelSmartTools.analyzeSupplyGaps`:** apply the same rule for saved routes.
- `OfflineDownloadWorker` always syncs, so it just writes the version.

## Tests (RULES.md 7)
- `GeoFaFieldParser`: every function, including the HTML samples `<p>Shelter kan bruges…</p>`, `<ul><li>A</li><li>B</li></ul>`, `&amp;` / `&oslash;`, empty `<p></p>`, and the seasons 01-01/12-31 versus 04-01/10-31.
- `parsePoisGeoJson`: a toilet with `vandhane = Ja` yields a toilet plus a tap POI; an inactive status yields nothing.
- `tapPoisFor`: ids, names, season carried over.
- Recompute: a shelter with `hasOwnTap = true` and no water POIs nearby still gets `hasWaterNearby = true`.
- Bus stop parsing and the 60 m same-name merge.
- The migration test.

## Done when
- Shelters with their own tap say "Vandhane ved shelteret". They no longer cause false "no water" gaps, and they appear as water in the planner and in navigation.
- No GeoFA description shows HTML tags.
- Draft, proposed and cancelled GeoFA entries are gone, also on devices that already had data.
- Bus stops show in the Transport tab, on the Transport map layer, and as start/end transport.
- Opening a trail synced before this update re-syncs its POIs once, in the background.
- List every file you changed and anything you could not complete.
