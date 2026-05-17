buildscript {
    repositories {
        maven { url = uri("https://chaquo.com/maven") }
    }
    dependencies {
        classpath("com.chaquo.python:gradle:15.0.1")
    }
}

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

apply(plugin = "com.chaquo.python")

android {
    namespace = "com.hoshiyomi.payloadtoolkit"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hoshiyomi.payloadtoolkit"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Chaquopy: embedded Python configuration
        ndk {
            // Build for common ARM64 devices
            abiFilters += listOf("arm64-v8a")
        }
    }

    // CI signing config — activated via -PciSigning=true
    signingConfigs {
        if (project.hasProperty("ciSigning") && project.property("ciSigning") == "true") {
            create("ci") {
                storeFile = file(findProperty("ciKeystoreFile") ?: "ci-keystore.jks")
                storePassword = findProperty("ciKeystorePass") as String? ?: System.getenv("CI_SIGNING_STORE_PASSWORD") ?: ""
                keyAlias = findProperty("ciKeyAlias") as String? ?: System.getenv("CI_SIGNING_KEY_ALIAS") ?: "ci-key"
                keyPassword = findProperty("ciKeyPass") as String? ?: System.getenv("CI_SIGNING_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (project.hasProperty("ciSigning") && project.property("ciSigning") == "true") {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-DEBUG"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    // Python source is in app/src/main/python/ (Chaquopy default location).
    // No python.srcDir() needed — Chaquopy auto-discovers from there.

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

// Chaquopy Python configuration
chaquopy {
    defaultConfig {
        // Python version embedded in the APK
        version = "3.11"

        // Python packages to pip install (none needed — stdlib only)
        pip {
            // No external pip packages required.
            // payload_toolkit uses only Python stdlib modules:
            // gzip, bz2, lzma, zipfile, hashlib, struct, tempfile, os, sys, time, argparse
        }
    }

}

dependencies {
    // AndroidX Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // Material Design 3
    implementation("com.google.android.material:material:1.11.0")

    // Lifecycle components (ViewModel + LiveData for async operations)
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // Coroutines (for async Python execution)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Activity Result API (modern file picker)
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")

    // DocumentFile for SAF (Storage Access Framework)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Preference (for settings)
    implementation("androidx.preference:preference-ktx:1.2.1")

    // Testing
    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
