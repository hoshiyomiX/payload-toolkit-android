# Additional ProGuard rules for Payload Toolkit
# ================================

# Chaquopy — keep Python bridge classes
-keep class com.chaquo.python.** { *; }
-dontwarn com.chaquo.python.**

# Keep Kotlin coroutines
-keepnames class kotlinx.coroutines.internal.MainDispatcherFactory {}
-keepnames class kotlinx.coroutines.CoroutineExceptionHandler {}

# Keep PayloadBridge and PythonBridge (called via reflection from Python)
-keep class com.hoshiyomi.payloadtoolkit.PayloadBridge { *; }
-keep class com.hoshiyomi.payloadtoolkit.PythonBridge { *; }
-keep class com.hoshiyomi.payloadtoolkit.PayloadResult { *; }

# Keep PayloadToolkitApp
-keep class com.hoshiyomi.payloadtoolkit.PayloadToolkitApp { *; }

# Keep data classes (used for serialization)
-keep class com.hoshiyomi.payloadtoolkit.data.** { *; }

# AndroidX
-keep class androidx.** { *; }
-dontwarn androidx.**

# Material Design
-keep class com.google.android.material.** { *; }
-dontwarn com.google.android.material.**
