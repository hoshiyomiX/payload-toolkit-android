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
-keep class com.hoshiyomi.payloadtoolkit.PythonException { *; }

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

# Chaquopy-specific: prevent stripping Python bridge native methods
-keepclassmembers class * {
    native <methods>;
}

# Keep all classes that may be called from Python via Chaquopy reflection
-keep class * {
    @com.chaquo.python.python.* <methods>;
}
