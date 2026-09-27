import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
    alias(libs.plugins.metro)
    alias(libs.plugins.sentryAndroid)
}

// The build number (`versionCode`, Sentry's `dist`): what separates two builds of the same version. CI
// passes its own in TANUKI_BUILD_NUMBER; otherwise it is the commit count, which only ever grows on
// main. Falls back to 1 where there is no git (a source tarball), which is still a valid versionCode.
val buildNumber: Int =
    providers.environmentVariable("TANUKI_BUILD_NUMBER")
        .orElse(
            providers.exec {
                commandLine("git", "rev-list", "--count", "HEAD")
                isIgnoreExitValue = true
            }.standardOutput.asText.map { it.trim() }
        )
        .get()
        .toIntOrNull() ?: 1

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}
dependencies {
    implementation(projects.shared)

    implementation(libs.androidx.activity.compose)
    // WorkManager's Configuration/WorkerFactory, handed the graph's factory in TanukiApplication.
    implementation(libs.androidx.work.runtime)
    implementation(libs.metrox.android)
    implementation(libs.metrox.viewmodel.compose)

    implementation(libs.compose.uiToolingPreview)
    debugImplementation(libs.compose.uiTooling)
}

android {
    namespace = "app.tanuki"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.tanuki"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = buildNumber
        // From the version catalog, so the Sentry release string matches what iOS reports.
        versionName = libs.versions.tanuki.get()
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("release") {
            // Off by decision, not by accident (observability backlog 2.2): stack traces stay readable
            // without a mapping upload, and R8 would need keep rules for Metro, Room, Compose and the WASM
            // runtime that have to be proven on a device first. Turning it on also turns on the Sentry
            // plugin's mapping upload below, with nothing else to change.
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}
// Whether this build can talk to Sentry at all: gates the mapping upload and the release below.
val hasAuthToken = providers.environmentVariable("SENTRY_AUTH_TOKEN").isPresent

// Build tooling only. The SDK comes from the KMP SDK in :shared, and starts only when the user opts in
// (SentryCrashReporter) — the manifest's `io.sentry.auto-init=false` keeps the SDK's own ContentProvider
// from starting it at process start.
sentry {
    org = "tanuki-jc"
    projectName = "tanuki"

    // The SDK version is the KMP SDK's to pick; letting this plugin add its own would put two
    // versions of sentry-android on the classpath.
    autoInstallation.enabled = false

    // Matches `tracesSampleRate = null` in SentryCrashReporter: no performance tracing.
    tracingInstrumentation.enabled = false

    // Uploads source snippets to Sentry — a separate privacy call, not made yet.
    includeSourceContext = false

    // Only with a token, so a contributor's release build never fails for want of one. A no-op while
    // minify is off: there is no mapping to upload.
    includeProguardMapping = hasAuthToken
    autoUploadProguardMapping = hasAuthToken

    ignoredBuildTypes = setOf("debug")
    telemetry = false
}

// Records the release and its commits in Sentry after a release build (scripts/sentry-release.sh, which
// also does it for iOS). Token-gated like the mapping upload, and it never fails the build.
val sentryRelease =
    tasks.register<Exec>("sentryRelease") {
        description = "Creates this version's Android release in Sentry and associates its commits."
        commandLine(rootProject.layout.projectDirectory.file("scripts/sentry-release.sh").asFile.path, "android")
        isIgnoreExitValue = true
    }
if (hasAuthToken) {
    tasks.matching { it.name == "assembleRelease" }.configureEach { finalizedBy(sentryRelease) }
}
