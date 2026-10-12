import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.metro)
}

kotlin {
    androidLibrary {
        namespace = "app.tanuki.features.source"
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
            api(projects.features.source.logic)
            implementation(projects.core.presentation)
            implementation(projects.core.navigation)
            implementation(projects.core.domain)
            implementation(projects.assets.designSystem)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.metrox.viewmodel.compose)
            implementation(libs.compose.ui)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
        }
    }
}

compose.resources {
    packageOfResClass = "app.tanuki.features.source.resources"
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}
