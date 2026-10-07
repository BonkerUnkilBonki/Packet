pluginManagement {
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
    }
    // NOTE (fork): the protobuf Gradle plugin marker is not resolvable from plugins.gradle.org in
    // this build environment, so map the plugin id straight at the Maven Central module.
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "com.google.protobuf") {
                useModule("com.google.protobuf:protobuf-gradle-plugin:${requested.version}")
            }
        }
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") } // libadb-android (self-ADB Wi-Fi)
    }
}

rootProject.name = "Bada"

include(":app")
include(":radio-helper")
include(":service-android")
include(":discovery-android")
include(":core-protocol")
include(":core-protocol-test")
