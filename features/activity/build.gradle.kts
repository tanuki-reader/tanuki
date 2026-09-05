import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.metro)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    androidLibrary {
        namespace = "app.tanuki.features.activity"
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

        // Required so this module's Compose `composeResources/` (the string catalogs) are assembled and
        // packaged into the APK. The new AGP KMP library plugin disables Android resource processing by
        // default; without this the generated `.cvr` files never reach the app and `stringResource(...)`
        // throws MissingResourceException at runtime. Mirrors the `:shared` module's config.
        androidResources {
            enable = true
        }
    }

    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.metrox.android)
        }
        commonMain.dependencies {
            implementation(projects.core.presentation)
            implementation(projects.core.navigation)
            implementation(projects.core.domain)
            implementation(projects.assets.designSystem)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.metrox.viewmodel.compose)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

compose.resources {
    // Deterministic package for the generated `Res` so imports don't depend on module-path derivation.
    packageOfResClass = "app.tanuki.features.activity.resources"
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}