rootProject.name = "Tanuki"
enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

pluginManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        google {
            mavenContent {
                includeGroupAndSubgroups("androidx")
                includeGroupAndSubgroups("com.android")
                includeGroupAndSubgroups("com.google")
            }
        }
        mavenCentral()
    }
}

include(":androidApp")
include("assets:designSystem")
include(":shared")
include(":core:data")
include(":core:domain")
include(":core:navigation")
include(":core:presentation")
include(":core:source")
include(":features:library")
include(":features:manga")
include(":features:reader")
include(":features:activity")
include(":features:downloads")
include(":features:home")
include(":features:home:logic")
include(":features:library:logic")
include(":features:source")
include(":features:settings")
include(":features:settings:logic")