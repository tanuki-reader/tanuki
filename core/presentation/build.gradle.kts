import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
}

kotlin {
    iosArm64()
    iosSimulatorArm64()

    androidLibrary {
        namespace = "app.tanuki.core.presentation"
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
            // `api`, not `implementation`: `TanukiViewModel`'s own signature is made of these — it *is* an
            // `androidx` ViewModel, it exposes `Flow<NavEvent>`, and its constructor takes a
            // `DispatcherProvider`/`Logger`. Every subclass therefore needs them on its compile classpath.
            api(projects.core.navigation)
            api(projects.core.domain)
            api(libs.androidx.lifecycle.viewmodel)
            api(libs.kotlinx.coroutines.core)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}