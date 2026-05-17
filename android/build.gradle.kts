// Top-level build file for payload-toolkit-android
// Configures plugins used across all subprojects

plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

// Chaquopy plugin is loaded from local JAR via settings.gradle.kts
// (Not declared here because it's not on the Gradle Plugin Portal)

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
