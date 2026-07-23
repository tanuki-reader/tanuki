import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

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
            // Ktor engine backing Coil's image fetches on Android (see setSingletonImageLoaderFactory
            // in App.kt).
            implementation(libs.ktor.client.okhttp)
        }
        iosMain.dependencies {
            // Ktor Darwin engine backing Coil's image fetches on iOS.
            implementation(libs.ktor.client.darwin)
        }
        commonMain.dependencies {
            implementation(projects.core.navigation)
            // Coil 3 image loading — the singleton loader is configured in App.kt; features render
            // covers with AsyncImage against it.
            implementation(libs.coil.compose)
            implementation(libs.coil.network.ktor3)
            // Explicit dep for the okio Path the disk cache directory is expressed as (newTanukiImageLoader).
            implementation(libs.okio)
            // Exposed as `api` so the Metro `@ContributesBinding` hints from these modules reach the
            // `AndroidAppGraph` compilation in `:androidApp` (which only depends on `:shared`). The iOS
            // graph lives inside `:shared`, so it already sees them; the Android graph is a separate
            // module and needs them on its compile classpath to aggregate the contributions.
            api(projects.core.data)
            api(projects.core.domain)
            api(projects.core.source)
            api(projects.features.library)
            api(projects.features.manga)
            api(projects.features.activity)
            api(projects.features.home)
            api(projects.features.source)
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

// --- Dev source bundles -------------------------------------------------------------------------
// Builds the first-party `.wasm` sources and drops them into composeResources so DevSourceInstaller
// can copy them into the SourceBundleStore on launch. Temporary: replaced by the remote source repo
// + installer once that exists.
//
// Deliberately tolerant of a missing Rust toolchain — Android-only contributors shouldn't be forced
// to install cargo to build the app. If the toolchain is absent the task is skipped and the checked-in
// `.wasm` (if any) is used as-is.

// Built-in Exec + Copy tasks rather than a custom task class: a task type declared inside a build
// script is a nested class of the script and holds a reference the configuration cache can't
// serialize. Exec and Copy are CC-safe — BUT their execution-time callbacks (onlyIf, rename) must
// not capture script-level state either, or they drag the same unserializable script reference in.
// So each such callback closes over a plain local (a File / String) declared inside the task's own
// config block, never over a top-level script `val`.

/** Source crates under native/sources to build. Add a directory name here and it gets bundled. */
val devSourceCrates = listOf("hello", "mangadex")

val wasmTarget = "wasm32-unknown-unknown"
val nativeSourcesDir = rootProject.layout.projectDirectory.dir("native/sources")
// A cargo target dir of our own, NOT the crate's default `native/sources/<crate>/target`. :core:source
// builds the very same crates there for its test fixtures, and two tasks in two projects declaring the
// same output file makes Gradle fail any build that contains both ("uses this output without declaring
// a dependency"). Separate target dirs decouple them for the price of a second ~1s compile.
val cargoTargetDir = layout.buildDirectory.dir("wasmSources")
val sourceApiSrc = rootProject.layout.projectDirectory.dir("native/source-api/src")
val bundledSourcesDir = layout.projectDirectory.dir("src/commonMain/composeResources/files/sources")

// One cargo build per crate. Each declares its src (and the shared SDK src) as inputs and its .wasm
// as output, so Gradle skips the build when nothing changed and reruns it when a source is edited.
val cargoTasks = devSourceCrates.map { crate ->
    tasks.register<Exec>("cargoBuild${crate.replaceFirstChar { it.uppercase() }}") {
        group = "tanuki"
        description = "cargo build native/sources/$crate → wasm"

        // Local, so the onlyIf spec (serialized into the CC) captures a File, not the script.
        val cargo = File(System.getProperty("user.home"), ".cargo/bin/cargo")
        onlyIf { cargo.exists() }

        // Local, so the CC serializes a String rather than the script's providers.
        val targetDir = cargoTargetDir.get().asFile.absolutePath

        workingDir = nativeSourcesDir.dir(crate).asFile
        executable = cargo.absolutePath
        args("build", "--release", "--target", wasmTarget, "--target-dir", targetDir)

        inputs.dir(nativeSourcesDir.dir(crate).dir("src"))
        inputs.file(nativeSourcesDir.dir(crate).file("Cargo.toml"))
        inputs.dir(sourceApiSrc)
        // cargo replaces dashes with underscores in the artifact name.
        outputs.file(cargoTargetDir.map { it.file("$wasmTarget/release/tanuki_source_$crate.wasm") })
    }
}

// Warn once at configuration time (not in a task callback) if the toolchain is missing, so the skip
// is visible without a serialized closure referencing the script logger.
if (!File(System.getProperty("user.home"), ".cargo/bin/cargo").exists()) {
    logger.lifecycle(
        "[tanuki] cargo not found — skipping dev source bundles. " +
            "Install Rust and run: rustup target add $wasmTarget",
    )
}

// Copies each freshly built .wasm into composeResources under its source id. Depending on the Exec
// tasks' outputs makes the ordering explicit, so no CC or implicit-dependency warning.
val buildDevSources = tasks.register<Copy>("buildDevSources") {
    group = "tanuki"
    description = "Compiles native/sources/* to .wasm and copies them into composeResources."

    val cargo = File(System.getProperty("user.home"), ".cargo/bin/cargo")
    onlyIf { cargo.exists() }

    devSourceCrates.forEachIndexed { index, crate ->
        // Local `name`, so the rename closure captures a String rather than the script.
        val name = "$crate.wasm"
        from(cargoTasks[index]) { rename { name } }
    }
    into(bundledSourcesDir)
}

// Every Compose resource task consumes the composeResources dir buildDevSources writes into, so each
// must run after it. Matched broadly on "Resource" because the plugin registers several per source
// set and platform (copyNonXmlValueResources…, generateResourceAccessors…, prepareComposeResources…)
// and the exact set varies by Compose version. buildDevSources itself doesn't match, so no cycle.
tasks.matching { it.name.contains("Resource") && it.name != "buildDevSources" }
    .configureEach { dependsOn(buildDevSources) }