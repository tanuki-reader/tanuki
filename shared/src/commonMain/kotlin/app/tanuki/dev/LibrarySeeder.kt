package app.tanuki.dev

import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.category.repository.CategoryRepository
import app.tanuki.core.domain.chapter.model.Chapter
import app.tanuki.core.domain.chapter.model.ChapterNumber
import app.tanuki.core.domain.chapter.model.ReadingProgress
import app.tanuki.core.domain.chapter.repository.ChapterRepository
import app.tanuki.core.domain.common.model.Text
import app.tanuki.core.domain.common.model.Url
import app.tanuki.core.domain.common.service.DispatcherProvider
import app.tanuki.core.domain.common.service.uuidV7
import app.tanuki.core.domain.history.model.ChapterHistory
import app.tanuki.core.domain.history.repository.HistoryRepository
import app.tanuki.core.domain.manga.model.Manga
import app.tanuki.core.domain.manga.model.MangaCover
import app.tanuki.core.domain.manga.model.PublishStatus
import app.tanuki.core.domain.manga.model.PublishStatus.COMPLETED
import app.tanuki.core.domain.manga.model.PublishStatus.HIATUS
import app.tanuki.core.domain.manga.model.PublishStatus.ONGOING
import app.tanuki.core.domain.manga.model.ReadingMode
import app.tanuki.core.domain.manga.model.ReadingMode.RIGHT_TO_LEFT
import app.tanuki.core.domain.manga.model.ReadingMode.WEBTOON
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.core.domain.manga.model.ReadingStatus.ON_HOLD
import app.tanuki.core.domain.manga.model.ReadingStatus.PLANNING_TO_READ
import app.tanuki.core.domain.manga.model.ReadingStatus.READING
import app.tanuki.core.domain.manga.repository.MangaRepository
import app.tanuki.core.domain.source.model.Source
import app.tanuki.core.domain.source.repository.SourceRepository
import dev.zacsweers.metro.Inject
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant.Companion.DISTANT_PAST
import kotlin.uuid.Uuid

/**
 * Temporary development convenience: fills an empty library with a handful of sample manga so the
 * Library grid has covers to render before the real source/browse pipeline exists. Because covers fall
 * back to a deterministic per-id gradient (no image loader is wired yet), each seeded title shows as a
 * distinct coloured cover with its caption. It also seeds a spread of reading **history** so the Activity
 * screen's list and reading curve have real data before the reader records any.
 *
 * Idempotent by construction — each step is a no-op once its data exists — so it is safe to call on every
 * launch. **Remove this once sources can populate the library and the reader records history for real.**
 */
@Inject
class LibrarySeeder(
    private val sourceRepository: SourceRepository,
    private val mangaRepository: MangaRepository,
    private val categoryRepository: CategoryRepository,
    private val chapterRepository: ChapterRepository,
    private val historyRepository: HistoryRepository,
    private val dispatchers: DispatcherProvider,
) {
    /**
     * Seeds the sample library (source + manga + categories) when it is empty, ensures every library manga
     * has a sample chapter list, then seeds reading history. Each step is idempotent independently — the
     * chapter step skips any manga that already has chapters, the history step skips once any history
     * exists — so a library seeded before chapters/history existed is back-filled without a wipe.
     */
    suspend fun seedIfEmpty() =
        withContext(dispatchers.io) {
            seedLibraryIfEmpty()
            seedChaptersIfMissing()
            seedHistoryIfMissing()
        }

    private suspend fun seedLibraryIfEmpty() {
        if (mangaRepository.observeLibrary().first().isNotEmpty()) return

            // Reuse the sample source if a prior seed left it behind (unique on native id), else add it.
            val sourceId =
                sourceRepository.getSourceByNativeId(SAMPLE_SOURCE_ID)?.id
                    ?: uuidV7().also { id ->
                        sourceRepository.addSource(
                            Source(
                                id = id,
                                sourceId = SAMPLE_SOURCE_ID,
                                lang = Text("en"),
                                name = Text("Sample"),
                                url = Url("https://example.com"),
                            ),
                        )
                    }

            // Create the categories up front so their ids exist before we assign memberships.
            val categoryIds =
                CATEGORIES.associateWith { name ->
                    uuidV7().also { id -> categoryRepository.upsertCategory(Category(id = id, name = Text(name))) }
                }

            SAMPLES.forEach { sample ->
                val manga = sample.toManga(sourceId)
                mangaRepository.upsertManga(manga)
                // The manga now has a `library` row (setMangaCategories' FK target); assign its shelves.
                val shelves = CATEGORY_ASSIGNMENTS[sample.title].orEmpty().mapNotNull(categoryIds::get).toSet()
                if (shelves.isNotEmpty()) categoryRepository.setMangaCategories(manga.id, shelves)
            }
        }

    /** Gives every library manga a sample chapter list; skips any manga that already has chapters. */
    private suspend fun seedChaptersIfMissing() {
        // The library rows are narrow (see `MangaRepository.observeLibrary`) and a sample chapter's url
        // is built from its series' permalink, so resolve the full manga for the ids they carry.
        val library = mangaRepository.getMangaByIds(mangaRepository.observeLibrary().first().map { it.id })
        library.forEach { manga ->
            if (chapterRepository.observeChapters(manga.id).first().isNotEmpty()) return@forEach
            buildSampleChapters(manga).forEach { chapterRepository.upsertChapter(it) }
        }
    }

    /**
     * Seeds reading history so the Activity screen has real data: the History list is populated and the
     * reading curve shows a realistic shape in every period — dense in the recent days, tapering to
     * occasional reads across the year. A no-op once any history exists.
     *
     * History carries a **unique constraint on `chapter_id`** — it records the last time each chapter was
     * read, one row per chapter — so this assigns **each chapter exactly one** read time (reusing a
     * chapter would just overwrite its row). Chapters are consumed across days weighted toward the present
     * ([readsForDay]) and interleaved across manga (round-robin), so recent history spans several titles
     * rather than a single one. Every entry references a real seeded chapter/manga, so the FKs are valid.
     */
    private suspend fun seedHistoryIfMissing() {
        if (historyRepository.observeHistory(DISTANT_PAST).first().isNotEmpty()) return

        // One (mangaId, chapterId) per chapter, interleaved across manga so consuming them in order spreads
        // recent reads over multiple titles instead of exhausting one manga before the next.
        val perManga =
            mangaRepository.observeLibrary().first().map { manga ->
                chapterRepository.observeChapters(manga.id).first().map { manga.id to it.id }
            }
        val chapters =
            buildList {
                val deepest = perManga.maxOfOrNull { it.size } ?: 0
                for (depth in 0 until deepest) {
                    for (manga in perManga) manga.getOrNull(depth)?.let(::add)
                }
            }
        if (chapters.isEmpty()) return

        // Walk days outward from today, giving each day its target number of reads ([readsForDay]) drawn
        // from the not-yet-read chapters, until every chapter has exactly one read time.
        val now = Clock.System.now()
        var index = 0
        var dayOffset = 0
        while (index < chapters.size) {
            repeat(readsForDay(dayOffset)) { readOfDay ->
                if (index >= chapters.size) return@repeat
                val (mangaId, chapterId) = chapters[index]
                // Scatter reads within the day (hours) so entries on the same day still order sensibly.
                val readAt = now - dayOffset.days - (readOfDay * 3 + index % 5).hours
                historyRepository.upsertHistory(
                    ChapterHistory(
                        id = uuidV7(),
                        chapterId = chapterId,
                        mangaId = mangaId,
                        readAt = readAt,
                    ),
                )
                index++
            }
            dayOffset++
        }
    }

    /**
     * How many chapters were "read" on the day [dayOffset] days ago — the shape of the seeded reading
     * curve. Heavy in the last week, moderate through the last month, then a sparse tail so the Year view
     * still has scattered activity. Consumed until the library's chapters run out, so the curve's length
     * scales with the sample size.
     */
    private fun readsForDay(dayOffset: Int): Int =
        when {
            dayOffset < 7 -> 3 + dayOffset % 3 // recent week: 3–5/day
            dayOffset < 30 -> if (dayOffset % 2 == 0) 2 else 1 // rest of the month: 1–2/day
            dayOffset % 4 == 0 -> 1 // older tail: a read every fourth day
            else -> 0
        }

    /**
     * A deterministic sample chapter list for [manga]: numbered newest-first, with the older ~third
     * marked read so the detail screen shows a mix of read (dimmed) and unread (accent-dot) rows.
     */
    private fun buildSampleChapters(manga: Manga): List<Chapter> {
        val now = Clock.System.now()
        val count = CHAPTER_MIN + (manga.title.value.length % (CHAPTER_MAX - CHAPTER_MIN + 1))
        val readThrough = count / 3
        return (count downTo 1).map { n ->
            Chapter(
                id = uuidV7(),
                mangaId = manga.id,
                chapterNumber = ChapterNumber(n),
                name = Text(CHAPTER_TITLES[(n - 1) % CHAPTER_TITLES.size]),
                scanlator = Text(SCANLATORS[n % SCANLATORS.size]),
                // Seeded rows stand in for synced ones, so they need the key a real sync would match on
                // — without it the unique (manga_id, source_chapter_key) index sees them all as NULL.
                sourceChapterKey = Text("seed-${manga.id}-$n"),
                url = Url("${manga.url.value}/chapter-$n"),
                dateUpload = now,
                dateFetch = now,
                progress =
                    if (n <= readThrough) {
                        ReadingProgress(hasBookmark = false, lastPageRead = 0u, alreadyRead = true)
                    } else {
                        null
                    },
            )
        }
    }

    private fun Sample.toManga(sourceId: Uuid): Manga {
        val id = uuidV7()
        val now = Clock.System.now()
        return Manga(
            id = id,
            source = sourceId,
            title = Text(title),
            author = Text(author),
            description = Text(description),
            cover = MangaCover(mangaId = id, url = null, lastModified = now),
            url = Url("https://example.com/manga/${title.lowercase().replace(' ', '-')}"),
            dateAdded = now,
            readingMode = readingMode,
            publishStatus = publishStatus,
            readingStatus = readingStatus,
        )
    }

    private data class Sample(
        val title: String,
        val author: String,
        val description: String,
        val publishStatus: PublishStatus,
        val readingMode: ReadingMode,
        val readingStatus: ReadingStatus,
    )

    private companion object {
        const val SAMPLE_SOURCE_ID = "sample"

        // Chapter counts vary per manga (by title length) for a less uniform sample list.
        const val CHAPTER_MIN = 8
        const val CHAPTER_MAX = 15

        /** Cycled chapter names, so each row's subtitle reads like a real chapter title. */
        val CHAPTER_TITLES =
            listOf(
                "The Beginning", "Reunion", "The Duel", "Nightfall", "Homecoming",
                "Betrayal", "The Long Road", "Ashes", "Bonds", "The Descent",
                "Resolve", "Dawn", "The Gathering", "Crossroads", "Awakening",
            )

        /**
         * Cycled scanlation groups. Seeded rows stand in for synced ones, and on a real aggregator a
         * chapter almost always carries a group credit — so the sample list exercises the subtitle the
         * way MangaDex data does.
         */
        val SCANLATORS = listOf("Tanuki Scans", "Midnight Group", "Lantern Translations")

        val SAMPLES =
            listOf(
                Sample(
                    "One Piece", "Eiichiro Oda",
                    "Monkey D. Luffy sails the Grand Line with his ragtag crew, chasing the legendary " +
                        "treasure that will crown him King of the Pirates.",
                    ONGOING, RIGHT_TO_LEFT, READING,
                ),
                Sample(
                    "Berserk", "Kentaro Miura",
                    "Branded for death and hunted by demons, the lone mercenary Guts wages a brutal war " +
                        "against a fate that will not let him rest.",
                    HIATUS, RIGHT_TO_LEFT, ON_HOLD,
                ),
                Sample(
                    "Vinland Saga", "Makoto Yukimura",
                    "A young warrior driven by revenge is swept into the wars of Viking-age Europe, " +
                        "searching for a land untouched by slaughter.",
                    ONGOING, RIGHT_TO_LEFT, READING,
                ),
                Sample(
                    "Chainsaw Man", "Tatsuki Fujimoto",
                    "Denji fuses with his chainsaw devil to survive, becoming a hunter caught between " +
                        "monsters and the people who wield them.",
                    ONGOING, RIGHT_TO_LEFT, READING,
                ),
                Sample(
                    "Fullmetal Alchemist", "Hiromu Arakawa",
                    "Two brothers pay a terrible price for forbidden alchemy and journey to reclaim what " +
                        "their transgression cost them.",
                    COMPLETED, RIGHT_TO_LEFT, ReadingStatus.COMPLETED,
                ),
                Sample(
                    "Solo Leveling", "Chugong",
                    "The world's weakest hunter gains the power to level up without limit, rising alone " +
                        "toward the summit of the strong.",
                    COMPLETED, WEBTOON, ReadingStatus.COMPLETED,
                ),
                Sample(
                    "Vagabond", "Takehiko Inoue",
                    "A fictionalized retelling of swordsman Miyamoto Musashi's violent path from wild " +
                        "youth to living legend.",
                    HIATUS, RIGHT_TO_LEFT, PLANNING_TO_READ,
                ),
                Sample(
                    "Death Note", "Tsugumi Ohba",
                    "A gifted student finds a notebook that kills anyone whose name is written in it, and " +
                        "sets out to remake the world in his image.",
                    COMPLETED, RIGHT_TO_LEFT, ReadingStatus.COMPLETED,
                ),
                Sample(
                    "Jujutsu Kaisen", "Gege Akutami",
                    "To save his friends, Yuji Itadori swallows a cursed relic and is pulled into the " +
                        "hidden war of jujutsu sorcerers.",
                    COMPLETED, RIGHT_TO_LEFT, READING,
                ),
                Sample(
                    "Tower of God", "SIU",
                    "A boy enters a mysterious tower to chase the only person he ever cared about, facing " +
                        "deadly tests on every floor.",
                    ONGOING, WEBTOON, PLANNING_TO_READ,
                ),
            )

        /**
         * Sample library categories. "Isekai" is intentionally assigned to no title so the sheet has a
         * zero-count (dimmed) chip to show; the rest spread across the samples below for varied counts.
         */
        val CATEGORIES = listOf("Favorites", "Shounen", "Seinen", "Webtoons", "Dark Fantasy", "Isekai")

        /** Which categories each sample belongs to, keyed by [Sample.title]. */
        val CATEGORY_ASSIGNMENTS =
            mapOf(
                "One Piece" to listOf("Favorites", "Shounen"),
                "Berserk" to listOf("Seinen", "Dark Fantasy"),
                "Vinland Saga" to listOf("Seinen"),
                "Chainsaw Man" to listOf("Shounen", "Dark Fantasy"),
                "Fullmetal Alchemist" to listOf("Favorites", "Shounen"),
                "Solo Leveling" to listOf("Webtoons"),
                "Vagabond" to listOf("Seinen"),
                "Death Note" to listOf("Shounen"),
                "Jujutsu Kaisen" to listOf("Shounen", "Dark Fantasy"),
                "Tower of God" to listOf("Webtoons"),
            )
    }
}