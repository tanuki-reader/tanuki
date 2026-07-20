import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.metro)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }
    
    androidLibrary {
       namespace = "app.tanuki.shared"
       compileSdk = libs.versions.android.compileSdk.get().toInt()
       minSdk = libs.versions.android.minSdk.get().toInt()
    
       compilerOptions {
           jvmTarget = JvmTarget.JVM_11
       }
       androidResources {
           enable = true
       }
       withHostTest {
           isIncludeAndroidResources = true
       }
    }
    
    sourceSets {
        androidMain.dependencies {
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.metrox.android)
        }
        commonMain.dependencies {
            implementation(projects.core.navigation)
            // Exposed as `api` so the Metro `@ContributesBinding` hints from these modules reach the
            // `AndroidAppGraph` compilation in `:androidApp` (which only depends on `:shared`). The iOS
            // graph lives inside `:shared`, so it already sees them; the Android graph is a separate
            // module and needs them on its compile classpath to aggregate the contributions.
            api(projects.core.data)
            api(projects.core.domain)
            api(projects.features.library)
            api(projects.features.manga)
            api(projects.features.activity)
            implementation(projects.assets.designSystem)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.metrox.viewmodel.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

compose.resources {
    // Deterministic package for the generated `Res` (bottom-bar tab labels), mirroring the feature modules.
    packageOfResClass = "app.tanuki.shared.resources"
}

dependencies {
    androidRuntimeClasspath(libs.compose.uiTooling)
}