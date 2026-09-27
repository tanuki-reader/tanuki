package app.tanuki.core.domain.settings.usecase

import app.tanuki.core.domain.common.service.CrashReporter
import app.tanuki.core.domain.common.service.LogPriority
import app.tanuki.core.domain.settings.model.AutoDownloadMode
import app.tanuki.core.domain.settings.model.DeleteAfterReading
import app.tanuki.core.domain.settings.model.LibraryUpdateInterval
import app.tanuki.core.domain.settings.model.ThemePreference
import app.tanuki.core.domain.settings.repository.SettingsRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant
import kotlin.uuid.Uuid

class ResetInstallIdTest {
    @Test
    fun `hands the new id to the crash reporter so a running reporter switches straight away`() = runTest {
        val repository = InstallIdSettingsRepository()
        val reporter = UserIdCrashReporter()

        ResetInstallId.Default(repository, reporter)()

        assertEquals(listOf(repository.id), reporter.userIds)
    }
}

private class InstallIdSettingsRepository : SettingsRepository {
    var id = "original"

    override suspend fun getOrCreateInstallId(): String = id

    override suspend fun resetInstallId(): String {
        id = "replaced"
        return id
    }

    override fun observeThemePreference(): Flow<ThemePreference> = error("unused")

    override suspend fun setThemePreference(preference: ThemePreference) = error("unused")

    override fun observeCrashReportingEnabled(): Flow<Boolean> = error("unused")

    override suspend fun setCrashReportingEnabled(enabled: Boolean) = error("unused")

    override fun observeLibraryUpdateInterval(): Flow<LibraryUpdateInterval> = error("unused")

    override suspend fun setLibraryUpdateInterval(interval: LibraryUpdateInterval) = error("unused")

    override suspend fun getLastLibraryUpdate(): Instant? = error("unused")

    override suspend fun setLastLibraryUpdate(at: Instant) = error("unused")

    override fun observeDownloadsPaused(): Flow<Boolean> = error("unused")

    override suspend fun setDownloadsPaused(paused: Boolean) = error("unused")

    override fun observeAutoDownloadMode(): Flow<AutoDownloadMode> = error("unused")

    override fun observeAutoDownloadCategories(): Flow<Set<Uuid>> = error("unused")

    override suspend fun setAutoDownload(
        mode: AutoDownloadMode,
        categories: Set<Uuid>
    ) = error("unused")

    override fun observeDownloadOnWifiOnly(): Flow<Boolean> = error("unused")

    override suspend fun setDownloadOnWifiOnly(wifiOnly: Boolean) = error("unused")

    override fun observeDeleteAfterReading(): Flow<DeleteAfterReading> = error("unused")

    override suspend fun setDeleteAfterReading(setting: DeleteAfterReading) = error("unused")

    override suspend fun getRemovedSourceIds(): Set<String> = error("unused")

    override suspend fun setSourceRemoved(
        sourceId: String,
        removed: Boolean
    ) = error("unused")
}

private class UserIdCrashReporter : CrashReporter {
    val userIds = mutableListOf<String>()

    override fun setEnabled(enabled: Boolean) = Unit

    override fun setUserId(id: String) {
        userIds += id
    }

    override fun recordBreadcrumb(
        priority: LogPriority,
        tag: String?,
        message: String?
    ) = Unit

    override fun reportError(
        tag: String?,
        message: String?,
        throwable: Throwable?,
        eventName: String?
    ) = Unit
}
