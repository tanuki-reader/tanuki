import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.io.File
import java.util.Base64

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidMultiplatformLibrary)
    alias(libs.plugins.metro)
    alias(libs.plugins.kotlinSerialization)
}

// --- Cross-engine test fixtures -----------------------------------------------------------------
// The engine tests run REAL source .wasm bundles through the actual engines: Chicory on the Android
// host test, wasmi on the iOS simulator test. Kotlin/Native tests have no classpath-resource
// mechanism, so rather than read a .wasm at runtime we compile its bytes in, as a generated base64
// fixture — one `<CRATE>_WASM` constant per source, all in the same package.
//
// Declared above the `kotlin {}` block because a source set below wires the generate tasks as srcDirs.
// Tolerant of a missing Rust toolchain: without cargo the .wasm isn't built and the fixture is empty,
// at which point each test skips itself.

/** Source crates under native/sources to compile into test fixtures (`<CRATE>_WASM` byte arrays). */
val fixtureCrates = listOf("hello", "mangadex")

/**
 * Registers the cargo-build + code-gen task pair for one fixture crate and returns the generate task
 * (so it can be added as a commonTest source dir). The generated file declares one constant,
 * `<CRATE>_WASM` in `app.tanuki.core.source.fixture`.
 */
fun registerWasmFixture(crate: String): TaskProvider<*> {
    val cap = crate.replaceFirstChar { it.uppercase() }
    val crateDir = rootProject.layout.projectDirectory.dir("native/sources/$crate")
    val wasm = crateDir.file("target/wasm32-unknown-unknown/release/tanuki_source_$crate.wasm")

    val cargoBuild = tasks.register<Exec>("cargoBuild${cap}Fixture") {
        group = "rust"
        description = "Builds native/sources/$crate for the cross-engine test fixture."

        val cargo = File(System.getProperty("user.home"), ".cargo/bin/cargo")
        onlyIf { cargo.exists() }

        workingDir = crateDir.asFile
        executable = cargo.absolutePath
        args("build", "--release", "--target", "wasm32-unknown-unknown")

        inputs.dir(crateDir.dir("src"))
        inputs.file(crateDir.file("Cargo.toml"))
        inputs.dir(rootProject.layout.projectDirectory.dir("native/source-api/src"))
        outputs.file(wasm)
    }

    return tasks.register("generate${cap}Fixture") {
        group = "tanuki"
        description = "Generates ${cap}Wasm.kt (base64 of $crate.wasm) for the engine tests."

        inputs.files(cargoBuild)

        // Locals, so the doLast closure serialized into the config cache captures plain values, not
        // the script object.
        val wasmFile = wasm.asFile
        val constName = "${crate.uppercase()}_WASM"
        val className = "${cap}Wasm"
        val outDir = layout.buildDirectory.dir("generated/fixtures/$crate/kotlin").get().asFile
        outputs.dir(outDir)

        doLast {
            // Base64 is [A-Za-z0-9+/=] — none of it needs escaping in a Kotlin "..." literal. Chunk it:
            // a single string literal can't exceed the JVM 64 KB constant-pool limit.
            val chunks: List<String> =
                if (wasmFile.exists()) {
                    Base64.getEncoder().encodeToString(wasmFile.readBytes()).chunked(50_000)
                } else {
                    emptyList()
                }

            val text = buildString {
                appendLine("package app.tanuki.core.source.fixture")
                appendLine()
                appendLine("import kotlin.io.encoding.Base64")
                appendLine("import kotlin.io.encoding.ExperimentalEncodingApi")
                appendLine()
                appendLine("// Generated from native/sources/$crate. Do not edit.")
                appendLine("// Empty when the Rust toolchain was absent at build time.")
                appendLine("private val CHUNKS: Array<String> = arrayOf(")
                chunks.forEach { appendLine("    \"$it\",") }
                appendLine(")")
                appendLine()
                appendLine("@OptIn(ExperimentalEncodingApi::class)")
                appendLine("internal val $constName: ByteArray =")
                appendLine("    if (CHUNKS.isEmpty()) ByteArray(0) else Base64.decode(CHUNKS.joinToString(\"\"))")
            }

            outDir.resolve("app/tanuki/core/source/fixture")
                .apply { mkdirs() }
                .resolve("$className.kt")
                .writeText(text)
        }
    }
}

val fixtureTasks: List<TaskProvider<*>> = fixtureCrates.map(::registerWasmFixture)

kotlin {
    listOf(
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        // The iOS WASM engine is wasmi, reached through the `native/wasmi-cabi` C shim (cinterop). Each
        // iOS target maps to a Rust target triple; we build its libwasmi_cabi.a with cargo, then bundle
        // that static lib INTO the cinterop klib (-staticLibrary/-libraryPath) so every dependent binary
        // — this module's iOS test executable AND the `:shared` framework — links it automatically, with
        // no per-binary linkerOpts anywhere.
        val rustTriple = when (iosTarget.name) {
            "iosArm64" -> "aarch64-apple-ios"
            "iosSimulatorArm64" -> "aarch64-apple-ios-sim"
            else -> error("Unexpected iOS target ${iosTarget.name}; add its Rust triple here.")
        }
        val wasmiDir = rootProject.layout.projectDirectory.dir("native/wasmi-cabi")
        val wasmiLibDir = wasmiDir.dir("target/$rustTriple/release")
        val suffix = iosTarget.name.replaceFirstChar(Char::uppercase)

        val cargoBuild = tasks.register<Exec>("cargoBuildWasmi$suffix") {
            group = "rust"
            description = "Builds libwasmi_cabi.a for $rustTriple"
            workingDir = wasmiDir.asFile
            // cargo/rustc aren't on the daemon's PATH; invoke the toolchain from ~/.cargo/bin explicitly
            // and put it on PATH so cargo can find rustc (mirrors the Tanuki reference project).
            val cargoBin = "${System.getProperty("user.home")}/.cargo/bin"
            executable = "$cargoBin/cargo"
            args("build", "--release", "--target", rustTriple)
            environment("PATH", "$cargoBin:${System.getenv("PATH")}")
            inputs.files(
                wasmiDir.file("Cargo.toml"),
                wasmiDir.file("src/lib.rs"),
                wasmiDir.file("include/tanuki_wasmi.h"),
            )
            outputs.file(wasmiLibDir.file("libwasmi_cabi.a"))
        }

        iosTarget.compilations.getByName("main").cinterops.create("wasmi") {
            defFile(layout.projectDirectory.file("src/nativeInterop/cinterop/wasmi.def"))
            includeDirs(wasmiDir.dir("include"))
            extraOpts("-staticLibrary", "libwasmi_cabi.a")
            extraOpts("-libraryPath", wasmiLibDir.asFile.absolutePath)
        }
        tasks.matching { it.name == "cinteropWasmi$suffix" }.configureEach { dependsOn(cargoBuild) }
    }

    androidLibrary {
        namespace = "app.tanuki.core.source"
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

        // Enable JVM host unit tests so the Chicory engine is exercised (mirrors :core:navigation).
        // Without this only the iOS/wasmi side of commonTest runs, leaving Chicory untested.
        withHostTest {}
    }

    sourceSets {
        commonMain.dependencies {
            implementation(projects.core.domain)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.okio)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.chicory.runtime)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.okio.fakefilesystem)
            implementation(libs.ktor.client.mock)
        }
    }
}

// The generated base64 .wasm fixtures (see registerWasmFixture above) are added as commonTest source
// dirs here rather than inside `sourceSets {}`: the accessor form `commonTest.kotlin.srcDir`
// mis-resolves `.kotlin` to the project extension, so use getByName for an unambiguous receiver.
fixtureTasks.forEach { kotlin.sourceSets.getByName("commonTest").kotlin.srcDir(it) }