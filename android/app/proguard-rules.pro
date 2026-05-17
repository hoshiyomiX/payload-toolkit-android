# Additional ProGuard rules for Payload Toolkit
# ================================

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep bridge classes (called from UI)
-keep class com.hoshiyomi.payloadtoolkit.PayloadBridge { *; }
-keep class com.hoshiyomi.payloadtoolkit.PythonBridge { *; }
-keep class com.hoshiyomi.payloadtoolkit.PayloadResult { *; }
-keep class com.hoshiyomi.payloadtoolkit.ExecResult { *; }

# Keep PayloadToolkitApp
-keep class com.hoshiyomi.payloadtoolkit.PayloadToolkitApp { *; }

# Keep service classes (declared in AndroidManifest)
-keep class com.hoshiyomi.payloadtoolkit.service.** { *; }

# Keep data classes (used for serialization and backup)
-keep class com.hoshiyomi.payloadtoolkit.data.** { *; }

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

# Material Design
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**
