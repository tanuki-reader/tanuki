package app.tanuki.core.data.repository.settings

import app.tanuki.core.domain.common.model.TriState
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.library.model.CategoryMatchMode
import app.tanuki.core.domain.library.model.LibraryFilter
import app.tanuki.core.domain.library.model.LibraryPreferences
import app.tanuki.core.domain.library.model.LibrarySort
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.settings.model.LibraryGridSize
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.uuid.Uuid

/** The library's filter, sort and cover size survive a round trip through the real preferences file. */
class SettingsRepositoryImpLibraryTest {
    private val path: Path =
        FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "library-prefs-test-${Uuid.random()}.preferences_pb"
    private val repository = SettingsRepositoryImp({ path }, LibraryDispatchers(UnconfinedTestDispatcher()))

    @AfterTest
    fun deleteStore() {
        FileSystem.SYSTEM.delete(path, mustExist = false)
    }

    @Test
    fun `an empty store reads as the plain library`() = runTest {
        assertEquals(LibraryPreferences(), repository.observeLibraryPreferences().first())
    }

    @Test
    fun `every field is read back as written`() = runTest {
        val written =
            LibraryPreferences(
                filter =
                    LibraryFilter(
                        readingStatus = ReadingStatus.ON_HOLD,
                        publicationCompleted = TriState.EXCLUDED,
                        downloaded = TriState.REQUIRED,
                        categoryIds = setOf(Uuid.random(), Uuid.random()),
                        categoryMatchMode = CategoryMatchMode.All
                    ),
                sort = LibrarySort(LibrarySort.Mode.LastRead, LibrarySort.Direction.Descending)
            )

        repository.updateLibraryPreferences { written }

        assertEquals(written, repository.observeLibraryPreferences().first())
    }

    @Test
    fun `an update applies to the stored value and clearing a status removes it`() = runTest {
        repository.updateLibraryPreferences { it.copy(filter = it.filter.copy(readingStatus = ReadingStatus.READING)) }
        repository.updateLibraryPreferences { it.copy(sort = it.sort.copy(direction = LibrarySort.Direction.Descending)) }
        repository.updateLibraryPreferences { it.copy(filter = it.filter.copy(readingStatus = null)) }

        val stored = repository.observeLibraryPreferences().first()
        assertEquals(null, stored.filter.readingStatus)
        assertEquals(LibrarySort.Direction.Descending, stored.sort.direction)
    }

    @Test
    fun `the cover size defaults to medium and reads back as set`() = runTest {
        assertEquals(LibraryGridSize.Medium, repository.observeLibraryGridSize().first())

        repository.setLibraryGridSize(LibraryGridSize.Small)

        assertEquals(LibraryGridSize.Small, repository.observeLibraryGridSize().first())
    }

    @Test
    fun `sensitive content is hidden until allowed`() = runTest {
        assertEquals(false, repository.observeShowSensitiveContent().first())

        repository.setShowSensitiveContent(true)

        assertEquals(true, repository.observeShowSensitiveContent().first())
    }
}

private class LibraryDispatchers(
    dispatcher: CoroutineDispatcher
) : DispatcherProvider {
    override val default: CoroutineDispatcher = dispatcher
    override val io: CoroutineDispatcher = dispatcher
    override val main: CoroutineDispatcher = dispatcher
}
