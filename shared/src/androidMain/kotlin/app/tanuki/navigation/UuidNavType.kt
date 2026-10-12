package app.tanuki.navigation

import androidx.navigation.NavType
import androidx.savedstate.SavedState
import androidx.savedstate.read
import androidx.savedstate.write
import kotlin.uuid.Uuid

internal const val UUID_NAV_TYPE_NAME = "Uuid"

/**
 * [NavType] for [Uuid] so type-safe routes that carry an id — `MangaDetail(mangaId: Uuid)`,
 * `Reader(mangaId, chapterId)` — can be declared without falling back to raw strings.
 *
 * `Uuid` is not one of Navigation Compose's built-in [NavType]s, so every `composable<T>()` whose
 * route holds a `Uuid` must supply it via `typeMap`:
 *
 * ```
 * composable<MangaDetail>(typeMap = mapOf(typeOf<Uuid>() to UuidNavType)) { … }
 * ```
 *
 * The value is carried as its canonical string form ([Uuid.toString]/[Uuid.parse]).
 *
 * Lives here, `internal`, rather than in `:core:navigation`: it is a Navigation Compose detail of the
 * Android/Compose NavHost. Keeping it out keeps `:core:navigation` Compose-free and out of the iOS
 * framework's Swift API, where SwiftUI renders `Destination` with its own `NavigationStack`.
 */
internal val UuidNavType: NavType<Uuid> =
    object : NavType<Uuid>(isNullableAllowed = false) {
        override fun get(
            bundle: SavedState,
            key: String
        ): Uuid? = bundle.read { if (contains(key)) Uuid.parse(getString(key)) else null }

        override fun put(
            bundle: SavedState,
            key: String,
            value: Uuid
        ): Unit = bundle.write { putString(key, value.toString()) }

        override fun parseValue(value: String): Uuid = Uuid.parse(value)

        override fun serializeAsValue(value: Uuid): String = value.toString()

        override val name: String = UUID_NAV_TYPE_NAME
    }