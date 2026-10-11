import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Source browse's Compose-free half: the Source home and catalog ViewModels. Exported to the iOS framework, so
// SwiftUI and Compose drive the same ViewModels. Never add a Compose dependency here.
plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.metro)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    androidLibrary {
        namespace = "app.tanuki.features.source.logic"
        compileSdk =
            libs.versions.android.compileSdk
                .get()
                .toInt()
        minSdk =
            libs.versions.android.minSdk
                .get()
                .toInt()

        compilerOptions {
            jvmTarget = JvmTarget.JVM_11
        }

        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            api(projects.core.presentation)
            implementation(libs.metrox.viewmodel)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
