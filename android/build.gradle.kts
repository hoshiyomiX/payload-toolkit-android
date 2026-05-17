// Top-level build file for payload-toolkit-android
// Configures plugins used across all subprojects
// NOTE: Chaquopy removed — uses ProcessBuilder + external Python (e.g. Termux)

buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("com.android.tools.build:gradle:8.2.2")
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22")
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
