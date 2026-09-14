# RelayFS ProGuard / R8 Rules

# Preserve line numbers and source files for readable stack traces
-keepattributes SourceFile,LineNumberTable
-keepattributes *Annotation*,Signature,InnerClasses,EnclosingMethod

# ---------------------------------------------------------
# Room SQLite Database
# ---------------------------------------------------------
-keep class * extends androidx.room.RoomDatabase
-keep class io.relayfs.android.core.database.AppDatabase
-keep class io.relayfs.android.core.database.entity.** { *; }
-keep interface io.relayfs.android.core.database.dao.** { *; }
-dontwarn androidx.room.paging.**

# ---------------------------------------------------------
# Kotlinx Serialization
# ---------------------------------------------------------
-keepclassmembers class * {
    *** Companion;
}
-keepclasseswithmembers class * {
    kotlinx.serialization.KSerializer serializer(...);
}
-keep,allowobfuscation,allowshrinking @kotlinx.serialization.Serializable class * { *; }
-keepclassmembers @kotlinx.serialization.Serializable class * {
    *** Companion;
}
-keepclassmembers class * {
    @kotlinx.serialization.SerialName <fields>;
}

# Keep project data models and responses
-keep class io.relayfs.android.core.model.** { *; }
-keep class io.relayfs.android.core.provider.pcloud.api.** { *; }
-keep class io.relayfs.android.core.provider.webdav.** { *; }
-keep class io.relayfs.android.ui.diff.** { *; }

# ---------------------------------------------------------
# Retrofit & OkHttp
# ---------------------------------------------------------
-dontwarn retrofit2.**
-keep class retrofit2.** { *; }
-keepclasseswithmembers interface * {
    @retrofit2.http.* <methods>;
}
-dontwarn okhttp3.**
-dontwarn okio.**
-keep class okhttp3.** { *; }
-keep interface okhttp3.** { *; }

# ---------------------------------------------------------
# WorkManager
# ---------------------------------------------------------
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class io.relayfs.android.core.work.** { *; }

# ---------------------------------------------------------
# Android Storage Access Framework & Services
# ---------------------------------------------------------
-keep class io.relayfs.android.core.saf.CloudDocumentsProvider { *; }
-keep class io.relayfs.android.core.service.SyncForegroundService { *; }
-keep class io.relayfs.android.core.provider.pcloud.auth.OAuthCallbackActivity { *; }

# ---------------------------------------------------------
# AndroidX Security Crypto & Google Tink
# ---------------------------------------------------------
-dontwarn com.google.errorprone.annotations.**
-dontwarn org.conscrypt.**
-keepclassmembers class * extends com.google.crypto.tink.shaded.protobuf.GeneratedMessageLite {
  <fields>;
}