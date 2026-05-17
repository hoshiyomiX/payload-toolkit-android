pluginManagement {
    // Chaquopy plugin JAR location
    val chaquopyPluginPath = file("chaquopy_plugin/chaquopy-gradle-plugin.jar")

    if (chaquopyPluginPath.exists()) {
        // Load Chaquopy from local JAR
        pluginManagement {
            resolutionStrategy {
                eachPlugin {
                    if (requested.id.id == "com.chaquo.python") {
                        useModule("com.chaquo.python:gradle:${requested.version}")
                    }
                }
            }
        }
    }

    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()

        // Chaquopy Maven repository (fallback if local JAR not found)
        maven {
            url = uri("https://chaquo.com/maven")
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()

        // Chaquopy Maven repository for Python packages
        maven {
            url = uri("https://chaquo.com/maven")
        }
    }
}

rootProject.name = "payload-toolkit-android"
include(":app")
