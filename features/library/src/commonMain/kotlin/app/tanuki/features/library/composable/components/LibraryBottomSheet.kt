package app.tanuki.features.library.composable.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.ArrowDownwardIcon
import app.tanuki.assets.designSystem.component.ArrowUpwardIcon
import app.tanuki.assets.designSystem.component.Segment
import app.tanuki.assets.designSystem.component.TanukiButton
import app.tanuki.assets.designSystem.component.TanukiButtonVariant
import app.tanuki.assets.designSystem.component.TanukiFilterChip
import app.tanuki.assets.designSystem.component.TanukiSegmentedControl
import app.tanuki.core.domain.category.model.Category
import app.tanuki.core.domain.common.model.TriState
import app.tanuki.core.domain.library.model.CategoryMatchMode
import app.tanuki.core.domain.library.model.LibraryFilter
import app.tanuki.core.domain.library.model.LibrarySort
import app.tanuki.core.domain.library.model.LibrarySort.Direction
import app.tanuki.core.domain.library.model.LibrarySort.Direction.Ascending
import app.tanuki.core.domain.library.model.LibrarySort.Mode
import app.tanuki.core.domain.manga.model.ReadingStatus
import app.tanuki.features.library.resources.Res
import app.tanuki.features.library.resources.library_category_badge_and
import app.tanuki.features.library.resources.library_category_badge_or
import app.tanuki.features.library.resources.library_category_help_all
import app.tanuki.features.library.resources.library_category_help_any
import app.tanuki.features.library.resources.library_category_match_all
import app.tanuki.features.library.resources.library_category_match_any
import app.tanuki.features.library.resources.library_downloaded_any
import app.tanuki.features.library.resources.library_downloaded_no
import app.tanuki.features.library.resources.library_downloaded_yes
import app.tanuki.features.library.resources.library_publication_any
import app.tanuki.features.library.resources.library_publication_completed
import app.tanuki.features.library.resources.library_publication_ongoing
import app.tanuki.features.library.resources.library_sheet_reset
import app.tanuki.features.library.resources.library_sheet_section_categories
import app.tanuki.features.library.resources.library_sheet_section_downloaded
import app.tanuki.features.library.resources.library_sheet_section_publication
import app.tanuki.features.library.resources.library_sheet_section_status
import app.tanuki.features.library.resources.library_sheet_show_count
import app.tanuki.features.library.resources.library_sheet_tab_filter
import app.tanuki.features.library.resources.library_sheet_tab_sort
import app.tanuki.features.library.resources.library_sheet_title
import app.tanuki.features.library.resources.library_sort_alphabetical
import app.tanuki.features.library.resources.library_sort_alphabetical_asc
import app.tanuki.features.library.resources.library_sort_alphabetical_desc
import app.tanuki.features.library.resources.library_sort_date_added
import app.tanuki.features.library.resources.library_sort_date_added_asc
import app.tanuki.features.library.resources.library_sort_date_added_desc
import app.tanuki.features.library.resources.library_sort_hint
import app.tanuki.features.library.resources.library_sort_last_read
import app.tanuki.features.library.resources.library_sort_last_read_asc
import app.tanuki.features.library.resources.library_sort_last_read_desc
import app.tanuki.features.library.resources.library_status_all
import app.tanuki.features.library.resources.library_status_completed
import app.tanuki.features.library.resources.library_status_dropped
import app.tanuki.features.library.resources.library_status_on_hold
import app.tanuki.features.library.resources.library_status_planning
import app.tanuki.features.library.resources.library_status_reading
import app.tanuki.features.library.viewmodel.LibraryState.ShowingBottomSheet.CategoryChip
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** The two panes of the tabbed sort & filter sheet. */
private enum class SheetTab { Sort, Filter }

/**
 * Filter & sort sheet shown over the library grid (the `ShowingBottomSheet` library state), in the
 * design system's **tabbed** layout (Direction B): a Sort / Filter segmented control swaps between a
 * tap-to-order sort list and the filter controls, so neither pane needs much scrolling. A Reset / Show
 * footer closes the sheet. The grid beneath re-derives live as each control changes (the ViewModel feeds
 * the selections into `GetLibrary`); the footer's "Show N" simply dismisses.
 *
 * The composable is stateless — the only local state is the active tab. Selections are driven by
 * [filter]/[sort] and reported through the callbacks, keeping the ViewModel the single source of truth.
 * All labels are resolved here (not in the ViewModel) per the localization boundary in CLAUDE.md.
 *
 * @param filter the active filter, reflected by the status/publication/category controls.
 * @param sort the active ordering, reflected by the sort list.
 * @param categories the library's categories as [CategoryChip]s (each pairing a [Category] with its
 *   selected state), rendered as chips with a per-category count; empty hides the category section.
 * @param itemCount titles currently passing the filter — shown on the footer's "Show N" action.
 * @param onDismiss the sheet was swiped away, its scrim tapped, or "Show N" pressed — close it.
 * @param onSortChange a new [LibrarySort] was chosen (tap a row to select, tap it again to flip).
 * @param onReadingStatusChange a reading-status chip was picked (`null` = Any / no status filter).
 * @param onPublicationChange the publication tri-state was changed.
 * @param onDownloadedChange the downloaded tri-state was changed.
 * @param onCategoryToggle a category chip was tapped — add/remove it from the selection.
 * @param onCategoryMatchModeChange the category match mode (ANY/ALL) was switched.
 * @param onReset the reset control was tapped — clear all filters and restore default sort.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun LibraryBottomSheet(
    filter: LibraryFilter,
    sort: LibrarySort,
    categories: List<CategoryChip>,
    itemCount: Int,
    onDismiss: () -> Unit,
    onSortChange: (LibrarySort) -> Unit,
    onReadingStatusChange: (ReadingStatus?) -> Unit,
    onPublicationChange: (TriState) -> Unit,
    onDownloadedChange: (TriState) -> Unit,
    onCategoryToggle: (Category) -> Unit,
    onCategoryMatchModeChange: (CategoryMatchMode) -> Unit,
    onReset: () -> Unit,
    modifier: Modifier = Modifier
) {
    val spacing = TanukiTheme.spacing
    var tab by remember { mutableStateOf(SheetTab.Sort) }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        modifier = modifier,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        shape = TanukiRadii.sheetShape,
        containerColor = MaterialTheme.colorScheme.surface,
        scrimColor = TanukiTheme.colors.scrim
    ) {
        Text(
            text = stringResource(Res.string.library_sheet_title),
            style = TanukiTheme.typography.title,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.padding(horizontal = spacing.gutter, vertical = spacing.xs)
        )

        // Sort / Filter tabs — Filter gains a dot when any filter criterion is set.
        TanukiSegmentedControl(
            segments =
                listOf(
                    Segment(SheetTab.Sort, stringResource(Res.string.library_sheet_tab_sort)),
                    Segment(SheetTab.Filter, filterTabLabel(filter))
                ),
            selected = tab,
            onSelect = { tab = it },
            modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.gutter, vertical = spacing.sm)
        )

        Column(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = SHEET_CONTENT_MAX_HEIGHT)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = spacing.gutter)
                    .padding(top = spacing.sm, bottom = spacing.md)
        ) {
            AnimatedContent(
                targetState = tab,
                transitionSpec = {
                    val forward = targetState.ordinal > initialState.ordinal
                    val width = { full: Int -> if (forward) full else -full }
                    (slideInHorizontally(initialOffsetX = width) + fadeIn())
                        .togetherWith(slideOutHorizontally(targetOffsetX = { -width(it) }) + fadeOut())
                },
                label = "sheetTab"
            ) { current ->
                when (current) {
                    SheetTab.Sort -> {
                        SortList(sort = sort, onSortChange = onSortChange)
                    }

                    SheetTab.Filter -> {
                        FilterBlock(
                            filter = filter,
                            categories = categories,
                            onReadingStatusChange = onReadingStatusChange,
                            onPublicationChange = onPublicationChange,
                            onDownloadedChange = onDownloadedChange,
                            onCategoryToggle = onCategoryToggle,
                            onCategoryMatchModeChange = onCategoryMatchModeChange
                        )
                    }
                }
            }
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            modifier =
                Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = spacing.gutter, vertical = spacing.md),
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TanukiButton(
                text = stringResource(Res.string.library_sheet_reset),
                onClick = onReset,
                variant = TanukiButtonVariant.Text
            )
            TanukiButton(
                text = pluralStringResource(Res.plurals.library_sheet_show_count, itemCount, itemCount),
                onClick = onDismiss,
                variant = TanukiButtonVariant.Filled,
                block = true,
                modifier = Modifier.weight(1f)
            )
        }
    }
}

/**
 * The sort pane: one tappable row per [Mode]. Tapping a row selects that mode ascending; tapping the
 * already-selected row flips its [Direction]. The active row carries a contextual direction label
 * ("A → Z", "Newest first", …) and an up/down arrow.
 */
@Composable
private fun SortList(
    sort: LibrarySort,
    onSortChange: (LibrarySort) -> Unit
) {
    val spacing = TanukiTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
        Mode.entries.forEach { mode ->
            SortRow(
                mode = mode,
                sort = sort,
                onClick = {
                    onSortChange(
                        if (sort.mode == mode) {
                            sort.copy(direction = sort.direction.flipped())
                        } else {
                            LibrarySort(mode, Ascending)
                        }
                    )
                }
            )
        }
        Text(
            text = stringResource(Res.string.library_sort_hint),
            style = TanukiTheme.typography.caption,
            color = TanukiTheme.colors.textFaint,
            modifier = Modifier.padding(horizontal = spacing.xs, vertical = spacing.sm)
        )
    }
}

@Composable
private fun SortRow(
    mode: Mode,
    sort: LibrarySort,
    onClick: () -> Unit
) {
    val spacing = TanukiTheme.spacing
    val selected = sort.mode == mode
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        modifier =
            Modifier
                .fillMaxWidth()
                .clip(TanukiRadii.containerShape)
                .background(if (selected) TanukiTheme.colors.accentTint else MaterialTheme.colorScheme.surface)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onClick
                ).padding(horizontal = spacing.md, vertical = spacing.md)
    ) {
        Text(
            text = stringResource(mode.labelResource()),
            style =
                TanukiTheme.typography.body.copy(
                    fontWeight = if (selected) FontWeight.Medium else FontWeight.Normal
                ),
            color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Text(
                text = stringResource(sort.directionLabelResource()),
                style = TanukiTheme.typography.caption.copy(fontWeight = FontWeight.Medium),
                color = TanukiTheme.colors.accent
            )
            val arrowTint = TanukiTheme.colors.accent
            if (sort.direction == Ascending) {
                ArrowUpwardIcon(tint = arrowTint, size = 18.dp)
            } else {
                ArrowDownwardIcon(tint = arrowTint, size = 18.dp)
            }
        }
    }
}

/**
 * The filter pane: reading status (single-select chips incl. "Any"), publication tri-state, and — when
 * the library has any — the category multi-select with per-category counts.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FilterBlock(
    filter: LibraryFilter,
    categories: List<CategoryChip>,
    onReadingStatusChange: (ReadingStatus?) -> Unit,
    onPublicationChange: (TriState) -> Unit,
    onDownloadedChange: (TriState) -> Unit,
    onCategoryToggle: (Category) -> Unit,
    onCategoryMatchModeChange: (CategoryMatchMode) -> Unit
) {
    val spacing = TanukiTheme.spacing
    Column {
        GroupLabel(stringResource(Res.string.library_sheet_section_status))
        Spacer(Modifier.size(spacing.sm))
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(spacing.sm),
            verticalArrangement = Arrangement.spacedBy(spacing.sm)
        ) {
            TanukiFilterChip(
                text = stringResource(Res.string.library_status_all),
                selected = filter.readingStatus == null,
                onClick = { onReadingStatusChange(null) }
            )
            ReadingStatus.entries.forEach { status ->
                TanukiFilterChip(
                    text = stringResource(status.labelResource()),
                    selected = filter.readingStatus == status,
                    // Tapping the active status clears it back to "Any".
                    onClick = { onReadingStatusChange(if (filter.readingStatus == status) null else status) }
                )
            }
        }

        Spacer(Modifier.size(spacing.lg))
        GroupLabel(stringResource(Res.string.library_sheet_section_downloaded))
        Spacer(Modifier.size(spacing.sm))
        TanukiSegmentedControl(
            segments =
                listOf(
                    // REQUIRED keeps only series with something on the device; EXCLUDED only those
                    // with nothing — the arm a plain switch would not have, and the one that makes a
                    // cleanup pass ("what have I not kept?") possible at all.
                    Segment(TriState.DISABLED, stringResource(Res.string.library_downloaded_any)),
                    Segment(TriState.REQUIRED, stringResource(Res.string.library_downloaded_yes)),
                    Segment(TriState.EXCLUDED, stringResource(Res.string.library_downloaded_no))
                ),
            selected = filter.downloaded,
            onSelect = onDownloadedChange,
            modifier = Modifier.fillMaxWidth()
        )

        Spacer(Modifier.size(spacing.lg))
        GroupLabel(stringResource(Res.string.library_sheet_section_publication))
        Spacer(Modifier.size(spacing.sm))
        TanukiSegmentedControl(
            segments =
                listOf(
                    // REQUIRED keeps only finished series, EXCLUDED only ongoing ones (see LibraryFilter).
                    Segment(TriState.DISABLED, stringResource(Res.string.library_publication_any)),
                    Segment(TriState.REQUIRED, stringResource(Res.string.library_publication_completed)),
                    Segment(TriState.EXCLUDED, stringResource(Res.string.library_publication_ongoing))
                ),
            selected = filter.publicationCompleted,
            onSelect = onPublicationChange,
            modifier = Modifier.fillMaxWidth()
        )

        if (categories.isNotEmpty()) {
            Spacer(Modifier.size(spacing.lg))
            CategorySection(
                categories = categories,
                matchMode = filter.categoryMatchMode,
                onToggle = onCategoryToggle,
                onMatchModeChange = onCategoryMatchModeChange
            )
        }
    }
}

/**
 * Category multi-select — a Match any / Match all mode toggle with a plain-language reminder, then the
 * category chips. Each chip shows how many library titles are in that category ([Category.associatedMangas]);
 * a category with zero titles is dimmed unless it's currently selected.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CategorySection(
    categories: List<CategoryChip>,
    matchMode: CategoryMatchMode,
    onToggle: (Category) -> Unit,
    onMatchModeChange: (CategoryMatchMode) -> Unit
) {
    val spacing = TanukiTheme.spacing
    val matchAny = matchMode == CategoryMatchMode.Any
    GroupLabel(stringResource(Res.string.library_sheet_section_categories))
    Spacer(Modifier.size(spacing.sm))
    TanukiSegmentedControl(
        segments =
            listOf(
                Segment(CategoryMatchMode.Any, stringResource(Res.string.library_category_match_any)),
                Segment(CategoryMatchMode.All, stringResource(Res.string.library_category_match_all))
            ),
        selected = matchMode,
        onSelect = onMatchModeChange,
        modifier = Modifier.fillMaxWidth()
    )
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.xs),
        modifier = Modifier.padding(horizontal = spacing.xxs, vertical = spacing.sm)
    ) {
        Text(
            text =
                stringResource(
                    if (matchAny) Res.string.library_category_badge_or else Res.string.library_category_badge_and
                ),
            style = TanukiTheme.typography.caption.copy(fontWeight = FontWeight.Bold, letterSpacing = 0.02.em),
            color = TanukiTheme.colors.accent
        )
        Text(
            text =
                stringResource(
                    if (matchAny) Res.string.library_category_help_any else Res.string.library_category_help_all
                ),
            style = TanukiTheme.typography.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.sm),
        verticalArrangement = Arrangement.spacedBy(spacing.sm)
    ) {
        categories.forEach { chip ->
            val category = chip.category
            val selected = chip.isEnabled
            val count = category.associatedMangas.size
            TanukiFilterChip(
                text = category.name.value,
                selected = selected,
                count = count,
                dimmed = count == 0 && !selected,
                onClick = { onToggle(category) }
            )
        }
    }
}

/** A small uppercase group caption, matching the design system's sheet `GroupLabel`. */
@Composable
private fun GroupLabel(text: String) {
    Text(
        text = text.uppercase(),
        style =
            TanukiTheme.typography.caption.copy(
                fontWeight = FontWeight.Medium,
                letterSpacing = 0.06.em
            ),
        color = TanukiTheme.colors.textFaint,
        modifier = Modifier.padding(horizontal = TanukiTheme.spacing.xxs)
    )
}

/** "Filter" tab label, gaining a trailing dot when any filter criterion is active. */
@Composable
private fun filterTabLabel(filter: LibraryFilter): String {
    val base = stringResource(Res.string.library_sheet_tab_filter)
    return if (filter.isActive) "$base •" else base
}

private fun Direction.flipped(): Direction = if (this == Ascending) Direction.Descending else Ascending

/** The localized row label for a sort mode. */
private fun Mode.labelResource(): StringResource =
    when (this) {
        Mode.Alphabetical -> Res.string.library_sort_alphabetical
        Mode.DateAdded -> Res.string.library_sort_date_added
        Mode.LastRead -> Res.string.library_sort_last_read
    }

/** The localized, mode-aware direction label (e.g. "Oldest first" vs "Newest first"). */
private fun LibrarySort.directionLabelResource(): StringResource {
    val ascending = direction == Ascending
    return when (mode) {
        Mode.Alphabetical -> {
            if (ascending) Res.string.library_sort_alphabetical_asc else Res.string.library_sort_alphabetical_desc
        }

        Mode.DateAdded -> {
            if (ascending) Res.string.library_sort_date_added_asc else Res.string.library_sort_date_added_desc
        }

        Mode.LastRead -> {
            if (ascending) Res.string.library_sort_last_read_asc else Res.string.library_sort_last_read_desc
        }
    }
}

/** The localized chip label for a reading status. */
private fun ReadingStatus.labelResource(): StringResource =
    when (this) {
        ReadingStatus.READING -> Res.string.library_status_reading
        ReadingStatus.COMPLETED -> Res.string.library_status_completed
        ReadingStatus.ON_HOLD -> Res.string.library_status_on_hold
        ReadingStatus.DROPPED -> Res.string.library_status_dropped
        ReadingStatus.PLANNING_TO_READ -> Res.string.library_status_planning
    }

private val SHEET_CONTENT_MAX_HEIGHT = 420.dp