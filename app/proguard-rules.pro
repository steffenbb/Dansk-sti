# Add project specific ProGuard rules here.

# Keep osmdroid classes and MapTileProviderArray.mTileProviderList (accessed via reflection)
-keep class org.osmdroid.** { *; }
-keep interface org.osmdroid.** { *; }
-keepclassmembers class org.osmdroid.tileprovider.MapTileProviderArray {
    protected java.util.List mTileProviderList;
}
-dontwarn org.osmdroid.**

# Keep BRouter offline routing engine classes (btools.**)
-keep class btools.** { *; }
-keep interface btools.** { *; }
-dontwarn btools.**

# Keep Room entities, DAOs, and database classes
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }

# Keep application data models, DTOs, and routing models serialized via Moshi/JSON
-keep class com.example.data.model.** { *; }
-keep class com.example.data.remote.** { *; }
-keep class com.example.data.local.** { *; }
-keep class com.example.util.routing.** { *; }

# Keep Moshi & Kotlin reflection metadata
-keep class kotlin.Metadata { *; }
-keep @com.squareup.moshi.JsonClass class * { *; }
-keep class **JsonAdapter { *; }
-dontwarn com.squareup.moshi.**

# Keep Retrofit interfaces & annotations
-keepattributes Signature, InnerClasses, EnclosingMethod, RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-keepclassmembers,allowshrinking,allowobfuscation interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn retrofit2.**
-dontwarn okhttp3.**
-dontwarn okio.**
