package app.tanuki.core.data.repository.settings

import app.tanuki.core.domain.common.service.DispatcherProvider
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.async
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.uuid.Uuid

/**
 * The install id is only worth sending if it is stable: a crash-free rate and a "users affected" count
 * both assume one install reports under one id until the user resets it.
 *
 * Runs against a real preferences file in the temp directory — DataStore owns its own file IO, so there
 * is no fake filesystem to hand it. Each test gets its own file, since DataStore allows one store per file.
 */
class SettingsRepositoryImpInstallIdTest {
    private val path: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "install-id-test-${Uuid.random()}.preferences_pb"
    private val repository = SettingsRepositoryImp({ path }, InstallIdDispatchers(UnconfinedTestDispatcher()))

    @AfterTest
    fun deleteStore() {
        FileSystem.SYSTEM.delete(path, mustExist = false)
    }

    @Test
    fun `the first read creates a random id`() = runTest {
        val id = repository.getOrCreateInstallId()

        // Throws if the stored value isn't a UUID.
        Uuid.parse(id)
    }

    @Test
    fun `later reads return the same id`() = runTest {
        val first = repository.getOrCreateInstallId()

        assertEquals(first, repository.getOrCreateInstallId())
    }

    @Test
    fun `two racing first reads settle on one id`() = runTest {
        val a = async { repository.getOrCreateInstallId() }
        val b = async { repository.getOrCreateInstallId() }

        assertEquals(a.await(), b.await())
    }

    @Test
    fun `a reset replaces the id and later reads return the new one`() = runTest {
        val before = repository.getOrCreateInstallId()

        val reset = repository.resetInstallId()

        assertNotEquals(before, reset)
        assertEquals(reset, repository.getOrCreateInstallId())
    }
}

private class InstallIdDispatchers(
    dispatcher: CoroutineDispatcher
) : DispatcherProvider {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}
