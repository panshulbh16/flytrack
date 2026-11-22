# Release keep rules. The libraries also ship consumer rules; these cover the app's own
# serialized models and the reflection Hilt, Room and kotlinx.serialization still do.

# kotlinx.serialization (app DTOs and supabase-kt models)
-keepattributes *Annotation*,InnerClasses,Signature,EnclosingMethod
-keep,includedescriptorclasses class app.flytrack.**$$serializer { *; }
-keepclassmembers class app.flytrack.** {
    *** Companion;
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}
-keepclasseswithmembers class **$$serializer {
    *** INSTANCE;
}

# supabase-kt and Ktor. R8 must not strip the plugin serializers or the engine.
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**
-dontwarn io.github.jan.supabase.**
-dontwarn kotlinx.serialization.**

# Room
-keep class * extends androidx.room.RoomDatabase { *; }
-keep @androidx.room.Entity class * { *; }
-keep @androidx.room.Dao class * { *; }
-dontwarn androidx.room.paging.**

# Hilt / Dagger. Entry points and workers are looked up by name.
-keep class dagger.hilt.** { *; }
-keep class javax.inject.** { *; }
-keep class * extends dagger.hilt.android.internal.managers.ViewComponentManager$FragmentContextWrapper { *; }
-keepclasseswithmembers class * {
    @dagger.hilt.android.AndroidEntryPoint <init>(...);
}
-keep class * extends androidx.hilt.work.HiltWorkerFactory { *; }
-keep class app.flytrack.push.FlightSyncWorker { *; }

# Firebase messaging is optional (no google-services.json). Don't fail the build on its optional bits.
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
-keep class com.google.android.gms.** { *; }
-dontwarn com.google.android.gms.**

# ML Kit text recognition (bundled Latin model)
-keep class com.google.mlkit.** { *; }
-dontwarn com.google.mlkit.**

# Credential Manager and the Google ID token credential
-keep class androidx.credentials.** { *; }
-keep class com.google.android.libraries.identity.googleid.** { *; }
-dontwarn androidx.credentials.**
