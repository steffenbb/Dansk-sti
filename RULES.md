# Dansk Sti - Project Rules & Guidelines

You are working on the Android app "Dansk Sti" (Kotlin, Jetpack Compose, Room, osmdroid, Retrofit/Moshi).

## Rules that override anything else:

1. **Never hand-type, guess or generate coordinates, IDs, opening hours, capacities or any other place data.** All place and route data must be downloaded from a named source (OpenStreetMap via Overpass, GeoFA, Open-Meteo) and stored with its source name and a fetched-at timestamp.
2. **Never simulate a feature.** If real data is missing, show an honest empty state in Danish ("Ingen data") instead of a placeholder value.
3. **Delete code you replace.** Do not leave unused functions, fake seed data or dummy defaults behind.
4. **Do not change screens, files or features that the current phase does not mention.**
5. **Keep all user-facing text in Danish.** Keep package `com.example` and the existing `applicationId`.
6. **Add Room migrations for every schema change.** Remove `fallbackToDestructiveMigration` and set `exportSchema = true`.
7. **Write unit tests for every new calculation** (distance, snapping, stage planning) and make them pass.
8. **When finished, list every file you changed and anything you could not complete.**
