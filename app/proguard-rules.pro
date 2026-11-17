# Vigilo – regole R8/ProGuard per la build minificata (firmata con il keystore debug).

# --- Kotlinx Serialization (serializer generati e Companion) ---
-keepattributes *Annotation*, InnerClasses, EnclosingMethod, Signature
-keepattributes RuntimeVisibleAnnotations, RuntimeVisibleParameterAnnotations
-dontnote kotlinx.serialization.**
-keep,includedescriptorclasses class io.github.redeyelabs.vigilo.**$$serializer { *; }
-keepclassmembers class io.github.redeyelabs.vigilo.** {
    *** Companion;
}
-keepclasseswithmembers class io.github.redeyelabs.vigilo.** {
    kotlinx.serialization.KSerializer serializer(...);
}
-if @kotlinx.serialization.Serializable class **
-keepclassmembers class <1> {
    static <1>$Companion Companion;
}

# --- Room ---
-keep class io.github.redeyelabs.vigilo.data.** { *; }
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# --- osmdroid (classi caricate per riflessione / tile providers) ---
-keep class org.osmdroid.** { *; }
-dontwarn org.osmdroid.**

# --- App: entry point, worker, widget, servizi ---
-keep class io.github.redeyelabs.vigilo.** { *; }

# EncryptedSharedPreferences / security-crypto: le regole consumer della libreria bastano.
# OkHttp, WorkManager, Compose, Glance: idem, regole consumer incluse.

# Tink (security-crypto) riferisce annotazioni errorprone non presenti a runtime.
-dontwarn com.google.errorprone.annotations.CanIgnoreReturnValue
-dontwarn com.google.errorprone.annotations.CheckReturnValue
-dontwarn com.google.errorprone.annotations.Immutable
-dontwarn com.google.errorprone.annotations.RestrictedApi
