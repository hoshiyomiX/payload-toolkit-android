// Top-level build file for payload-toolkit-android
// Configures plugins used across all subprojects

buildscript {
    repositories {
        google()
        mavenCentral()
        maven { url 'https://chaquo.com/maven' }
    }
    dependencies {
        classpath 'com.android.tools.build:gradle:8.2.2'
        classpath 'org.jetbrains.kotlin:kotlin-gradle-plugin:1.9.22'
        classpath 'com.chaquo.python:gradle:15.0.1'
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
