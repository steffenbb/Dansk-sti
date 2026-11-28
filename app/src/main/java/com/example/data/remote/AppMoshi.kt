package com.example.data.remote

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

/**
 * The app's one Moshi instance (audit fix batch 4). It used to be built in 11 places, each putting
 * the reflection factory first, so even classes with generated adapters were read by reflection,
 * which R8 can break in release builds (the "No JsonAdapter for class java.util.ArrayList" crash).
 *
 * Generated adapters (@JsonClass(generateAdapter = true)) win here; reflection is only a last
 * resort. MoshiCodegenCoverageTest requires every serialised class to have a generated adapter,
 * so the fallback can be removed (with the kotlin-reflect dependency) once that is proven in use.
 */
object AppMoshi {
    val instance: Moshi by lazy { Moshi.Builder().addLast(KotlinJsonAdapterFactory()).build() }
}
