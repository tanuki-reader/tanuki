import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.metro)
    alias(libs.plugins.sentryKmp)
    alias(libs.plugins.skie)
}

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "Shared"
            isStatic = true
            // Kotlin/Native infers a framework bundle id from the package names it finds and warns when it
            // can't ("Cannot infer a bundle ID … use the bundle name instead: Shared"), then falls back to
            // the bare base name. It can't infer one here because the framework re-exports several modules
            // and no single package dominates. Set explicitly: `Shared` is not a bundle identifier, and the
            // value ends up in the framework's Info.plist, where the crash reporter's image list reads it.
            // Distinct from the app's own id (iosApp/Configuration/Config.xcconfig) — this identifies the
            // Kotlin framework inside it, and mirrors the module's Android namespace.
            binaryOption("bundleId", "app.tanuki.shared")
            // What Swift sees. Only Compose-free modules belong here (CLAUDE.md → boundary rule 5); a
            // feature's ViewModels get exported once they move into their `:features:<x>:logic` module
            // (docs/swiftui-migration.md). Each exported module must also be an `api` dependency below.
            export(projects.core.domain)
            export(projects.core.navigation)
            export(projects.core.presentation)
            export(projects.features.home.logic)
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
            // WorkManager backs the Android LibraryUpdateScheduler. `api` because :androidApp has to
            // see WorkerFactory/Configuration to hand TanukiWorkerFactory to WorkManager at startup.
            api(libs.androidx.work.runtime)
        }
        iosMain.dependencies {
            // Ktor Darwin engine backing Coil's image fetches on iOS.
            implementation(libs.ktor.client.darwin)
        }
        commonMain.dependencies {
            api(projects.core.navigation)
            api(projects.core.presentation)
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
            api(projects.features.reader)
            api(projects.features.activity)
            api(projects.features.downloads)
            api(projects.features.home)
            api(projects.features.home.logic)
            api(projects.features.source)
            api(projects.features.settings)
            implementation(projects.assets.designSystem)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.androidx.navigation.compose)
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            // Multiplatform predictive-back handling, so the site-session layer (a modal over the
            // NavHost) can take the back gesture instead of letting it drive the screen underneath.
            // Compose's own `ui-backhandler` is deprecated in favour of this.
            implementation(libs.androidx.navigationevent.compose)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.uiToolingPreview)
            implementation(libs.androidx.lifecycle.viewmodelCompose)
            implementation(libs.androidx.lifecycle.runtimeCompose)
            implementation(libs.metrox.viewmodel.compose)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
        iosTest.dependencies {
            // ImageBridgeTest drives Coil's fetch and disk cache against a fake network.
            implementation(libs.ktor.client.mock)
            implementation(libs.kotlinx.coroutines.test)
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

// --- Sentry DSN ---------------------------------------------------------------------------------
// The DSN is kept OUT of the repository and generated into a source file at build time, so a fork
// building from source gets an empty DSN and reports nothing to this project's Sentry.
//
// It is not a secret in the cryptographic sense — a DSN is a write-only ingest key and it ships inside
// every released binary, where anyone can extract it. What this arrangement actually buys is: the DSN
// never enters public git history, and forks/CI of forks are inert by default. Abuse from an extracted
// DSN is handled server-side by the rate limit on the key, not here.
//
// Resolution order, first non-absent wins:
//   1. TANUKI_SENTRY_DSN         environment variable  (CI)
//   2. tanuki.sentry.dsn         Gradle property       (~/.gradle/gradle.properties, or -P)
//   3. sentry.dsn                local.properties      (git-ignored; the usual local choice)
// Read through `providers` rather than by reading the file directly at configuration time, so the
// configuration cache treats the value as an input and re-runs when it changes.
val SENTRY_DSN_PROPERTY = "sentry.dsn"

val sentryDsn: Provider<String> =
    providers
        .environmentVariable("TANUKI_SENTRY_DSN")
        .orElse(providers.gradleProperty("tanuki.sentry.dsn"))
        .orElse(
            providers
                .fileContents(rootProject.layout.projectDirectory.file("local.properties"))
                .asText
                .map { text ->
                    text
                        .lineSequence()
                        .map(String::trim)
                        .firstOrNull { it.startsWith("$SENTRY_DSN_PROPERTY=") }
                        ?.substringAfter('=')
                        ?.trim()
                        .orEmpty()
                },
        ).orElse("")

// Generated rather than hand-written so the DSN has exactly one definition and cannot drift, and so an
// absent DSN is a compile-time constant the reporter can check — not a missing file that breaks the build.
val generateSentryConfig =
    tasks.register("generateSentryConfig") {
        group = "tanuki"
        description = "Generates SentryConfig.kt carrying the build's Sentry DSN (empty when unset)."

        // Locals only inside the task config/execution, per the configuration-cache note below: a
        // Provider serializes fine, the enclosing build script does not.
        val dsn = sentryDsn
        val version = libs.versions.tanuki.get()
        val outDir = layout.buildDirectory.dir("generated/sentry/commonMain/kotlin")

        inputs.property("dsn", dsn)
        inputs.property("version", version)
        outputs.dir(outDir)

        doLast {
            val packageDir = outDir.get().asFile.resolve("app/tanuki/platform/observability")
            packageDir.mkdirs()
            packageDir.resolve("SentryConfig.kt").writeText(
                """
                |// Generated by :shared:generateSentryConfig — do not edit, and do not commit a real DSN here.
                |package app.tanuki.platform.observability
                |
                |/**
                | * The Sentry DSN for this build, or empty when none was configured (see shared/build.gradle.kts).
                | * Empty means crash reporting stays inert no matter what the user's preference says, which is
                | * what makes a fork's build report nothing.
                | */
                |internal const val SENTRY_DSN: String = "${dsn.get()}"
                |
                |/**
                | * The app's marketing version, from the `tanuki` entry in the version catalog. Sentry groups
                | * crashes by release, so this is what makes "was this fixed in 1.1?" answerable.
                | */
                |internal const val APP_VERSION: String = "$version"
                |
                """.trimMargin(),
            )
        }
    }

kotlin.sourceSets.commonMain { kotlin.srcDir(generateSentryConfig) }

// Warn once at configuration time (not in a task callback), mirroring the cargo warning below, so a
// contributor understands why crash reporting does nothing locally.
if (sentryDsn.get().isBlank()) {
    logger.lifecycle(
        "[tanuki] no Sentry DSN configured — crash reporting is inert. " +
            "Set $SENTRY_DSN_PROPERTY in local.properties to enable it locally.",
    )
}

// --- iOS string catalogs ------------------------------------------------------------------------
// The SwiftUI app reads the same copy as Compose: scripts/generate-ios-strings.py turns every module's
// composeResources strings.xml into an .xcstrings table under iosApp/iosApp/Strings (checked in), and
// fails if a language lacks a key. Hooked to the Xcode build's framework step, so iOS builds can't run
// on stale strings. Skipped where there is no python3 (Android-only machines never run this task).
val generateIosStrings =
    tasks.register<Exec>("generateIosStrings") {
        group = "tanuki"
        description = "Generates iosApp's .xcstrings catalogs from the modules' Compose strings.xml."

        // Locals only, as with the cargo tasks below, so the configuration cache never captures the script.
        val python = File("/usr/bin/python3")
        onlyIf { python.exists() }

        val root = rootProject.layout.projectDirectory
        workingDir = root.asFile
        commandLine(python.absolutePath, "-I", "scripts/generate-ios-strings.py", ".", "iosApp/iosApp/Strings")

        inputs.file(root.file("scripts/generate-ios-strings.py"))
        inputs.files(
            root.asFileTree.matching {
                include("shared/src/commonMain/composeResources/values*/strings.xml")
                include("features/*/src/commonMain/composeResources/values*/strings.xml")
            }
        )
        outputs.dir(root.dir("iosApp/iosApp/Strings"))
    }

tasks.matching { it.name == "embedAndSignAppleFrameworkForXcode" }.configureEach { dependsOn(generateIosStrings) }

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

/**
 * The `.wasm` bundles to build, as `bundleId to (crate to cargoFeature)`.
 *
 * Usually one bundle per crate. A **multi-language site** is the exception: the ABI gives a source one
 * `lang`, so MangaDex and MangaFire each compile twice from the same crate — once per language — behind
 * a cargo feature, exactly as Mihon ships one extension per language. The bundle id is the source id
 * `meta()` reports, and the file name `DevSourceInstaller` looks for; they must agree.
 */
val devSourceBundles: List<Triple<String, String, String?>> = listOf(
    Triple("hello", "hello", null),
    Triple("mangadex", "mangadex", "lang-en"),
    Triple("mangadex-pt-br", "mangadex", "lang-pt-br"),
    Triple("mangafire", "mangafire", "lang-en"),
    Triple("mangafire-pt-br", "mangafire", "lang-pt-br"),
    Triple("manhastro", "manhastro", null),
    Triple("hipercool", "hipercool", null),
)

val wasmTarget = "wasm32-unknown-unknown"
val nativeSourcesDir = rootProject.layout.projectDirectory.dir("native/sources")
// A cargo target dir of our own, NOT the crate's default `native/sources/<crate>/target`. :core:source
// builds the very same crates there for its test fixtures, and two tasks in two projects declaring the
// same output file makes Gradle fail any build that contains both ("uses this output without declaring
// a dependency"). Separate target dirs decouple them for the price of a second ~1s compile.
val cargoTargetDir = layout.buildDirectory.dir("wasmSources")
val sourceApiSrc = rootProject.layout.projectDirectory.dir("native/source-api/src")
val bundledSourcesDir = layout.projectDirectory.dir("src/commonMain/composeResources/files/sources")

// One cargo build per BUNDLE, not per crate: two language bundles of one crate differ only by feature,
// and cargo would otherwise overwrite the same artifact each time. Each gets its own --target-dir, so
// the two builds are independent and Gradle can cache them separately. Each declares its src (and the
// shared SDK src) as inputs and its .wasm as output, so Gradle skips the build when nothing changed
// and reruns it when a source is edited.
val cargoTasks = devSourceBundles.map { (bundleId, crate, feature) ->
    val taskSuffix = bundleId.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }
    tasks.register<Exec>("cargoBuild$taskSuffix") {
        group = "tanuki"
        description = "cargo build native/sources/$crate → $bundleId.wasm"

        // Local, so the onlyIf spec (serialized into the CC) captures a File, not the script.
        val cargo = File(System.getProperty("user.home"), ".cargo/bin/cargo")
        onlyIf { cargo.exists() }

        // Local, so the CC serializes a String rather than the script's providers.
        val targetDir = cargoTargetDir.get().dir(bundleId).asFile.absolutePath

        workingDir = nativeSourcesDir.dir(crate).asFile
        executable = cargo.absolutePath
        args("build", "--release", "--target", wasmTarget, "--target-dir", targetDir)
        // --no-default-features, so a crate's default language can't leak into the other bundle.
        feature?.let { args("--no-default-features", "--features", it) }

        inputs.dir(nativeSourcesDir.dir(crate).dir("src"))
        inputs.file(nativeSourcesDir.dir(crate).file("Cargo.toml"))
        inputs.dir(sourceApiSrc)
        // cargo replaces dashes with underscores in the artifact name.
        outputs.file(
            cargoTargetDir.map { it.dir(bundleId).file("$wasmTarget/release/tanuki_source_$crate.wasm") },
        )
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

    devSourceBundles.forEachIndexed { index, (bundleId, _, _) ->
        // Local `name`, so the rename closure captures a String rather than the script.
        val name = "$bundleId.wasm"
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