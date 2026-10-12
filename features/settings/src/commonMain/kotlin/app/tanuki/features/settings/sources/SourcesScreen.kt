package app.tanuki.features.settings.sources

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.AddIcon
import app.tanuki.assets.designSystem.component.ErrorIcon
import app.tanuki.assets.designSystem.component.MoreVertIcon
import app.tanuki.assets.designSystem.component.SectionHeader
import app.tanuki.assets.designSystem.component.ShieldCheckIcon
import app.tanuki.assets.designSystem.component.SourceIcon
import app.tanuki.assets.designSystem.component.TanukiButton
import app.tanuki.assets.designSystem.component.TanukiButtonSize
import app.tanuki.assets.designSystem.component.TanukiButtonVariant
import app.tanuki.assets.designSystem.component.TanukiDetailTopBar
import app.tanuki.assets.designSystem.component.TanukiDialog
import app.tanuki.assets.designSystem.component.TanukiEmptyState
import app.tanuki.assets.designSystem.component.TanukiFilterChip
import app.tanuki.assets.designSystem.component.TanukiIconButton
import app.tanuki.assets.designSystem.component.TanukiListRowDefaults
import app.tanuki.assets.designSystem.component.TanukiLoadingIndicator
import app.tanuki.assets.designSystem.component.TanukiLogo
import app.tanuki.assets.designSystem.component.TanukiMenu
import app.tanuki.assets.designSystem.component.TanukiMenuItem
import app.tanuki.assets.designSystem.component.TanukiProgressRing
import app.tanuki.assets.designSystem.component.TanukiRowDivider
import app.tanuki.assets.designSystem.component.TanukiSectionGroup
import app.tanuki.assets.designSystem.component.TanukiSwitchRow
import app.tanuki.assets.designSystem.component.TanukiTag
import app.tanuki.assets.designSystem.component.TanukiTextField
import app.tanuki.assets.designSystem.component.WarningIcon
import app.tanuki.core.domain.download.model.SourceDownloadUsage
import app.tanuki.core.domain.source.model.InstallProgress
import app.tanuki.core.domain.source.model.SourceCompatibility
import app.tanuki.core.domain.source.model.SourceRepoError
import app.tanuki.features.settings.downloads.asFileSize
import app.tanuki.features.settings.resources.Res
import app.tanuki.features.settings.resources.settings_cancel
import app.tanuki.features.settings.resources.settings_install
import app.tanuki.features.settings.resources.settings_languages_all
import app.tanuki.features.settings.resources.settings_ok
import app.tanuki.features.settings.resources.settings_remove
import app.tanuki.features.settings.resources.settings_remove_source_action
import app.tanuki.features.settings.resources.settings_remove_source_downloads
import app.tanuki.features.settings.resources.settings_remove_source_downloads_hint
import app.tanuki.features.settings.resources.settings_remove_source_message
import app.tanuki.features.settings.resources.settings_remove_source_title
import app.tanuki.features.settings.resources.settings_source_adult
import app.tanuki.features.settings.resources.settings_source_details_available
import app.tanuki.features.settings.resources.settings_source_details_installed
import app.tanuki.features.settings.resources.settings_source_downloading
import app.tanuki.features.settings.resources.settings_source_downloading_percent
import app.tanuki.features.settings.resources.settings_source_failed_mismatch
import app.tanuki.features.settings.resources.settings_source_failed_offline
import app.tanuki.features.settings.resources.settings_source_failed_unreachable
import app.tanuki.features.settings.resources.settings_source_install_failed
import app.tanuki.features.settings.resources.settings_source_needs_update
import app.tanuki.features.settings.resources.settings_source_outdated
import app.tanuki.features.settings.resources.settings_source_remove_failed
import app.tanuki.features.settings.resources.settings_source_repo_add
import app.tanuki.features.settings.resources.settings_source_repo_confirm_title
import app.tanuki.features.settings.resources.settings_source_repo_continue
import app.tanuki.features.settings.resources.settings_source_repo_error_already_added
import app.tanuki.features.settings.resources.settings_source_repo_error_bad_signature
import app.tanuki.features.settings.resources.settings_source_repo_error_invalid_url
import app.tanuki.features.settings.resources.settings_source_repo_error_key_changed
import app.tanuki.features.settings.resources.settings_source_repo_error_mismatch
import app.tanuki.features.settings.resources.settings_source_repo_error_offline
import app.tanuki.features.settings.resources.settings_source_repo_error_title
import app.tanuki.features.settings.resources.settings_source_repo_error_unreachable
import app.tanuki.features.settings.resources.settings_source_repo_error_unsigned
import app.tanuki.features.settings.resources.settings_source_repo_error_unsupported
import app.tanuki.features.settings.resources.settings_source_repo_key_changed
import app.tanuki.features.settings.resources.settings_source_repo_refresh_error_title
import app.tanuki.features.settings.resources.settings_source_repo_remove_action
import app.tanuki.features.settings.resources.settings_source_repo_remove_message
import app.tanuki.features.settings.resources.settings_source_repo_remove_title
import app.tanuki.features.settings.resources.settings_source_repo_signed
import app.tanuki.features.settings.resources.settings_source_repo_summary
import app.tanuki.features.settings.resources.settings_source_repo_tampered
import app.tanuki.features.settings.resources.settings_source_repo_trust
import app.tanuki.features.settings.resources.settings_source_repo_url_label
import app.tanuki.features.settings.resources.settings_source_repo_url_placeholder
import app.tanuki.features.settings.resources.settings_source_repos
import app.tanuki.features.settings.resources.settings_source_retry
import app.tanuki.features.settings.resources.settings_source_verifying
import app.tanuki.features.settings.resources.settings_sources
import app.tanuki.features.settings.resources.settings_sources_available
import app.tanuki.features.settings.resources.settings_sources_available_hint
import app.tanuki.features.settings.resources.settings_sources_empty
import app.tanuki.features.settings.resources.settings_sources_empty_title
import app.tanuki.features.settings.resources.settings_sources_installed
import app.tanuki.features.settings.resources.settings_sources_no_repos
import app.tanuki.features.settings.sources.viewmodel.LanguageOption
import app.tanuki.features.settings.sources.viewmodel.SourceItem
import app.tanuki.features.settings.sources.viewmodel.SourceRepoItem
import app.tanuki.features.settings.sources.viewmodel.SourceReposState
import app.tanuki.features.settings.sources.viewmodel.SourceReposViewModel
import app.tanuki.features.settings.sources.viewmodel.SourcesState
import app.tanuki.features.settings.sources.viewmodel.SourcesViewModel
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource
import kotlin.math.roundToInt

/**
 * The Sources screen (design system: `SourcesScreen`, `SourceRow`, `AddSourceRepo`): installed sources,
 * the ones the user's repos offer, and the repos themselves. A leaf pushed onto the Settings tab stack.
 *
 * Two ViewModels share it: [SourcesViewModel] owns the source lists, [SourceReposViewModel] the repos
 * and the add flow. With no repo added and nothing installed it is the first-run empty state, whose one
 * action is Add repo — the app never implies it provides content.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SourcesRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SourcesViewModel = metroViewModel(),
    reposViewModel: SourceReposViewModel = metroViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val repos by reposViewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
    ) {
        TanukiDetailTopBar(title = stringResource(Res.string.settings_sources), onBack = onBack) {
            val addLabel = stringResource(Res.string.settings_source_repo_add)
            TanukiIconButton(onClick = reposViewModel::onAddRequested) { tint ->
                AddIcon(tint = tint, modifier = Modifier.semantics { contentDescription = addLabel })
            }
        }
        when {
            // Held on the spinner until the first emission: a user with sources must never be shown
            // "no sources" for a frame while the bundles are still being read.
            state.loading || repos.loading -> Box(Modifier.fillMaxSize(), Alignment.Center) { TanukiLoadingIndicator() }

            state.repoCount == 0 && state.installed.isEmpty() ->
                Box(Modifier.fillMaxSize(), Alignment.Center) {
                    TanukiEmptyState(
                        title = stringResource(Res.string.settings_sources_empty_title),
                        message = stringResource(Res.string.settings_sources_no_repos),
                        icon = { tint -> TanukiLogo(size = EmptyMarkSize, tint = tint) },
                        action = {
                            TanukiButton(
                                text = stringResource(Res.string.settings_source_repo_add),
                                onClick = reposViewModel::onAddRequested
                            )
                        }
                    )
                }

            else ->
                PullToRefreshBox(
                    isRefreshing = repos.refreshing,
                    onRefresh = reposViewModel::onRefresh,
                    modifier = Modifier.fillMaxSize()
                ) {
                    SourceSections(state = state, repos = repos, viewModel = viewModel, reposViewModel = reposViewModel)
                }
        }
    }

    SourceDialogs(state = state, viewModel = viewModel)
    RepoDialogs(state = repos, viewModel = reposViewModel)
}

@Composable
private fun SourceSections(
    state: SourcesState,
    repos: SourceReposState,
    viewModel: SourcesViewModel,
    reposViewModel: SourceReposViewModel
) {
    val spacing = TanukiTheme.spacing
    Column(
        modifier =
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = spacing.gutter, vertical = spacing.md),
        verticalArrangement = Arrangement.spacedBy(spacing.md)
    ) {
        if (state.languages.isNotEmpty()) {
            LanguageChips(state.languages, state.selectedLanguage, viewModel::onLanguageSelected)
        }
        if (state.installed.isEmpty() && state.available.isEmpty()) {
            Text(
                text = stringResource(Res.string.settings_sources_empty),
                style = TanukiTheme.typography.body,
                color = TanukiTheme.colors.textFaint
            )
        }
        if (state.installed.isNotEmpty()) {
            SectionHeader(title = stringResource(Res.string.settings_sources_installed))
            SourceGroup(state.installed, installed = true, showRepo = state.showRepo, viewModel = viewModel)
        }
        if (state.available.isNotEmpty()) {
            SectionHeader(title = stringResource(Res.string.settings_sources_available))
            Text(
                text = stringResource(Res.string.settings_sources_available_hint),
                style = TanukiTheme.typography.caption,
                color = TanukiTheme.colors.textFaint
            )
            SourceGroup(state.available, installed = false, showRepo = state.showRepo, viewModel = viewModel)
        }
        if (repos.repos.isNotEmpty()) {
            SectionHeader(title = stringResource(Res.string.settings_source_repos))
            TanukiSectionGroup {
                repos.repos.forEachIndexed { index, repo ->
                    if (index > 0) TanukiRowDivider(startInset = TanukiListRowDefaults.dividerInset(0.dp))
                    RepoRow(repo = repo, onRemove = { reposViewModel.onRemoveRequested(repo) })
                }
            }
        }
    }
}

/** `All` plus one chip per language with its count. Language names are the platform's, in the user's language. */
@Composable
private fun LanguageChips(
    languages: List<LanguageOption>,
    selected: String?,
    onSelect: (String?) -> Unit
) {
    Row(
        modifier = Modifier.horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.sm)
    ) {
        TanukiFilterChip(text = stringResource(Res.string.settings_languages_all), selected = selected == null, onClick = { onSelect(null) })
        languages.forEach { language ->
            TanukiFilterChip(
                text = languageName(language.tag),
                selected = selected == language.tag,
                count = language.count,
                onClick = { onSelect(language.tag) }
            )
        }
    }
}

@Composable
private fun SourceGroup(
    sources: List<SourceItem>,
    installed: Boolean,
    showRepo: Boolean,
    viewModel: SourcesViewModel
) {
    TanukiSectionGroup {
        sources.forEachIndexed { index, source ->
            if (index > 0) TanukiRowDivider(startInset = TanukiListRowDefaults.dividerInset(AvatarSize))
            SourceRow(
                source = source,
                installed = installed,
                showRepo = showRepo,
                onInstall = { viewModel.onInstall(source.sourceId) },
                onRemove = { viewModel.onRemovalRequested(source.sourceId) }
            )
        }
    }
}

/**
 * One source and its life cycle (design system: `SourceRow`). The status line says what is happening in
 * words, never color alone: the version and language at rest, "Downloading · 42%" then "Verifying" while
 * installing, the failed step in `danger` with Retry, or why an incompatible version can't install (a
 * `warning` glyph, no action, the avatar dimmed).
 */
@Composable
private fun SourceRow(
    source: SourceItem,
    installed: Boolean,
    showRepo: Boolean,
    onInstall: () -> Unit,
    onRemove: () -> Unit
) {
    val spacing = TanukiTheme.spacing
    val blocked = !installed && source.compatibility != SourceCompatibility.Compatible
    val failure = if (installed && source.removeFailed) stringResource(Res.string.settings_source_remove_failed) else source.installFailure?.installMessage()
    val status: String? =
        when (val progress = source.progress) {
            is InstallProgress.Downloading ->
                progress.fraction?.let { stringResource(Res.string.settings_source_downloading_percent, (it * 100).roundToInt()) }
                    ?: stringResource(Res.string.settings_source_downloading)
            InstallProgress.Verifying -> stringResource(Res.string.settings_source_verifying)
            null -> null
        }

    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(min = RowMinHeight)
                .padding(horizontal = spacing.md, vertical = spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SourceIcon(
            name = source.name,
            iconUrl = source.iconUrl,
            color = TanukiTheme.colors.accent,
            modifier = if (blocked) Modifier.alpha(BlockedAlpha) else Modifier
        )
        Column(
            modifier = Modifier.weight(1f).padding(horizontal = spacing.md),
            verticalArrangement = Arrangement.spacedBy(spacing.xxs)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.sm)) {
                Text(
                    text = source.name,
                    style = TanukiTheme.typography.body,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (source.nsfw) TanukiTag(text = stringResource(Res.string.settings_source_adult))
            }
            when {
                status != null -> StatusLine(status, MaterialTheme.colorScheme.onSurfaceVariant, mono = true)
                failure != null -> StatusLine(failure, TanukiTheme.colors.danger, glyph = { ErrorIcon(tint = it, size = GlyphSize) })
                blocked -> StatusLine(source.blockedMessage(), TanukiTheme.colors.warning, glyph = { WarningIcon(tint = it, size = GlyphSize) })
                else -> details(source, installed, showRepo)?.let { StatusLine(it, TanukiTheme.colors.textFaint) }
            }
        }

        when {
            source.progress != null || source.removing ->
                Box(Modifier.size(spacing.tapMin), Alignment.Center) {
                    TanukiProgressRing(progress = (source.progress as? InstallProgress.Downloading)?.fraction, size = RingSize)
                }
            installed -> RemoveMenu(source.name, onRemove)
            blocked -> Unit
            source.installFailure != null ->
                TanukiButton(
                    text = stringResource(Res.string.settings_source_retry),
                    onClick = onInstall,
                    variant = TanukiButtonVariant.Text,
                    size = TanukiButtonSize.Small
                )
            else ->
                TanukiButton(
                    text = stringResource(Res.string.settings_install),
                    onClick = onInstall,
                    variant = TanukiButtonVariant.Tonal,
                    size = TanukiButtonSize.Small
                )
        }
    }
}

@Composable
private fun StatusLine(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    mono: Boolean = false,
    glyph: (@Composable (androidx.compose.ui.graphics.Color) -> Unit)? = null
) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.xs)) {
        glyph?.invoke(color)
        Text(
            text = text,
            style = if (mono) TanukiTheme.typography.numeric else TanukiTheme.typography.caption,
            color = color,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/** An installed source's actions live in an overflow menu, so removal is a deliberate two-step. */
@Composable
private fun RemoveMenu(
    name: String,
    onRemove: () -> Unit
) {
    var open by remember { mutableStateOf(false) }
    val label = stringResource(Res.string.settings_remove_source_action, name)
    Box {
        TanukiIconButton(onClick = { open = true }) { tint ->
            MoreVertIcon(tint = tint, modifier = Modifier.semantics { contentDescription = label })
        }
        TanukiMenu(expanded = open, onDismissRequest = { open = false }) {
            TanukiMenuItem(
                text = stringResource(Res.string.settings_remove),
                onClick = {
                    open = false
                    onRemove()
                },
                destructive = true
            )
        }
    }
}

@Composable
private fun RepoRow(
    repo: SourceRepoItem,
    onRemove: () -> Unit
) {
    val spacing = TanukiTheme.spacing
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = spacing.md, vertical = spacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.xxs)) {
                Text(text = repo.name, style = TanukiTheme.typography.body, color = MaterialTheme.colorScheme.onSurface)
                Text(text = repo.host, style = TanukiTheme.typography.caption, color = TanukiTheme.colors.textFaint)
            }
            var open by remember { mutableStateOf(false) }
            val label = stringResource(Res.string.settings_source_repo_remove_action, repo.name)
            Box {
                TanukiIconButton(onClick = { open = true }) { tint ->
                    MoreVertIcon(tint = tint, modifier = Modifier.semantics { contentDescription = label })
                }
                TanukiMenu(expanded = open, onDismissRequest = { open = false }) {
                    TanukiMenuItem(
                        text = stringResource(Res.string.settings_remove),
                        onClick = {
                            open = false
                            onRemove()
                        },
                        destructive = true
                    )
                }
            }
        }
        // Fails closed, never silently: the repo keeps serving its last verified index, and says why it stopped updating.
        val problem =
            when (repo.problem) {
                SourceRepoError.KeyChanged -> stringResource(Res.string.settings_source_repo_key_changed)
                SourceRepoError.BadSignature -> stringResource(Res.string.settings_source_repo_tampered)
                else -> null
            }
        if (problem != null) {
            StatusLine(problem, TanukiTheme.colors.danger, glyph = { ErrorIcon(tint = it, size = GlyphSize) })
        }
    }
}

@Composable
private fun SourceDialogs(
    state: SourcesState,
    viewModel: SourcesViewModel
) {
    val source = state.pendingRemoval ?: return
    RemoveSourceDialog(
        source = source,
        usage = state.pendingUsage,
        deleteDownloads = state.removalDeletesDownloads,
        onDeleteDownloadsChange = viewModel::onRemovalDeleteDownloadsChange,
        onDismiss = viewModel::onRemovalDismissed,
        onConfirm = viewModel::onRemovalConfirmed
    )
}

/**
 * Confirmation before removing a source. The message's job is to correct the assumption a delete button
 * invites — this one does **not** touch the library. When the source has downloaded chapters it also
 * carries the *offer* to delete them, defaulted **off**: those chapters keep reading offline with the
 * source gone, so removing them is a second, separate loss the user has to choose.
 */
@Composable
private fun RemoveSourceDialog(
    source: SourceItem,
    usage: SourceDownloadUsage?,
    deleteDownloads: Boolean,
    onDeleteDownloadsChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    TanukiDialog(
        title = stringResource(Res.string.settings_remove_source_title, source.name),
        onDismiss = onDismiss,
        dismissText = stringResource(Res.string.settings_cancel),
        confirmText = stringResource(Res.string.settings_remove),
        confirmVariant = TanukiButtonVariant.Danger,
        onConfirm = onConfirm
    ) {
        DialogText(stringResource(Res.string.settings_remove_source_message))
        if (usage != null && !usage.isEmpty) {
            TanukiSwitchRow(
                title = pluralStringResource(Res.plurals.settings_remove_source_downloads, usage.chapters, usage.chapters),
                subtitle = stringResource(Res.string.settings_remove_source_downloads_hint, usage.bytes.asFileSize()),
                checked = deleteDownloads,
                onCheckedChange = onDeleteDownloadsChange
            )
        }
    }
}

/** Add repo is two steps (paste a URL, then confirm the repo and its key); then remove, and errors. */
@Composable
private fun RepoDialogs(
    state: SourceReposState,
    viewModel: SourceReposViewModel
) {
    val preview = state.preview
    val removal = state.pendingRemoval
    when {
        state.adding -> AddRepoDialog(state.busy, state.error, viewModel::onDialogDismissed, viewModel::onUrlSubmitted)

        preview != null -> ConfirmRepoDialog(preview, state.busy, viewModel::onDialogDismissed, viewModel::onPreviewConfirmed)

        removal != null ->
            TanukiDialog(
                title = stringResource(Res.string.settings_source_repo_remove_title, removal.name),
                onDismiss = viewModel::onDialogDismissed,
                dismissText = stringResource(Res.string.settings_cancel),
                confirmText = stringResource(Res.string.settings_remove),
                confirmVariant = TanukiButtonVariant.Danger,
                onConfirm = viewModel::onRemoveConfirmed
            ) { DialogText(stringResource(Res.string.settings_source_repo_remove_message)) }

        state.error != null || state.refreshError != null -> {
            val error = state.error ?: state.refreshError!!
            TanukiDialog(
                title =
                    stringResource(
                        if (state.error != null) Res.string.settings_source_repo_error_title else Res.string.settings_source_repo_refresh_error_title
                    ),
                onDismiss = viewModel::onDialogDismissed,
                confirmText = stringResource(Res.string.settings_ok),
                onConfirm = viewModel::onDialogDismissed
            ) { DialogText(error.repoMessage()) }
        }
    }
}

/** The URL step. Validated on submit (by fetching the repo), and the error stays under the field to fix in place. */
@Composable
private fun AddRepoDialog(
    busy: Boolean,
    error: SourceRepoError?,
    onDismiss: () -> Unit,
    onSubmit: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }
    TanukiDialog(
        title = stringResource(Res.string.settings_source_repo_add),
        onDismiss = onDismiss,
        dismissText = stringResource(Res.string.settings_cancel),
        confirmText = stringResource(Res.string.settings_source_repo_continue),
        confirmEnabled = url.isNotBlank() && !busy,
        onConfirm = { onSubmit(url) }
    ) {
        Text(
            text = stringResource(Res.string.settings_source_repo_url_label),
            style = TanukiTheme.typography.caption,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TanukiTextField(
            value = url,
            onValueChange = { url = it },
            placeholder = stringResource(Res.string.settings_source_repo_url_placeholder),
            autoFocus = true,
            imeAction = ImeAction.Go,
            onImeAction = { if (url.isNotBlank() && !busy) onSubmit(url) }
        )
        when {
            busy -> TanukiProgressRing(progress = null, size = RingSize)
            error != null -> StatusLine(error.repoMessage(), TanukiTheme.colors.danger, glyph = { ErrorIcon(tint = it, size = GlyphSize) })
        }
    }
}

/**
 * Confirm the repo before anything is saved: its name, how many sources, its host, and the key it will
 * be pinned to, as the mono fingerprint the repo publishes. Add repo is the only prominent action.
 */
@Composable
private fun ConfirmRepoDialog(
    repo: SourceRepoItem,
    busy: Boolean,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    val spacing = TanukiTheme.spacing
    TanukiDialog(
        title = stringResource(Res.string.settings_source_repo_confirm_title, repo.name),
        onDismiss = onDismiss,
        dismissText = stringResource(Res.string.settings_cancel),
        confirmText = stringResource(Res.string.settings_source_repo_add),
        confirmEnabled = !busy,
        onConfirm = onConfirm
    ) {
        val count = repo.sourceCount ?: 0
        DialogText(pluralStringResource(Res.plurals.settings_source_repo_summary, count, count, repo.host))
        Column(verticalArrangement = Arrangement.spacedBy(spacing.xs)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(spacing.xs)) {
                ShieldCheckIcon(tint = TanukiTheme.colors.success, size = GlyphSize)
                Text(
                    text = stringResource(Res.string.settings_source_repo_signed),
                    style = TanukiTheme.typography.caption,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(text = repo.fingerprint, style = TanukiTheme.typography.numeric, color = MaterialTheme.colorScheme.onSurface)
        }
        DialogText(stringResource(Res.string.settings_source_repo_trust))
        if (busy) TanukiProgressRing(progress = null, size = RingSize)
    }
}

@Composable
private fun DialogText(text: String) {
    Text(text = text, style = TanukiTheme.typography.body, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** Language · version, plus the repo when more than one is added; just the language without a version. */
@Composable
private fun details(
    source: SourceItem,
    installed: Boolean,
    showRepo: Boolean
): String? {
    val lang = source.lang?.let { languageName(it) }
    val version = source.version
    val repo = source.repoName
    return when {
        lang == null || version == null -> lang
        showRepo && !installed && repo != null -> stringResource(Res.string.settings_source_details_available, lang, version, repo)
        else -> stringResource(Res.string.settings_source_details_installed, lang, version)
    }
}

@Composable
private fun SourceItem.blockedMessage(): String =
    stringResource(
        if (compatibility == SourceCompatibility.Outdated) Res.string.settings_source_outdated else Res.string.settings_source_needs_update
    )

/** Which install step failed, in the design system's words. */
@Composable
private fun SourceRepoError.installMessage(): String =
    stringResource(
        when (this) {
            SourceRepoError.Offline -> Res.string.settings_source_failed_offline
            SourceRepoError.Mismatch -> Res.string.settings_source_failed_mismatch
            SourceRepoError.Unreachable -> Res.string.settings_source_failed_unreachable
            else -> Res.string.settings_source_install_failed
        }
    )

@Composable
private fun SourceRepoError.repoMessage(): String =
    stringResource(
        when (this) {
            SourceRepoError.InvalidUrl -> Res.string.settings_source_repo_error_invalid_url
            SourceRepoError.Unreachable -> Res.string.settings_source_repo_error_unreachable
            SourceRepoError.Unsigned -> Res.string.settings_source_repo_error_unsigned
            SourceRepoError.BadSignature -> Res.string.settings_source_repo_error_bad_signature
            SourceRepoError.KeyChanged -> Res.string.settings_source_repo_error_key_changed
            SourceRepoError.Mismatch -> Res.string.settings_source_repo_error_mismatch
            SourceRepoError.UnsupportedFormat -> Res.string.settings_source_repo_error_unsupported
            SourceRepoError.Offline -> Res.string.settings_source_repo_error_offline
            SourceRepoError.AlreadyAdded -> Res.string.settings_source_repo_error_already_added
        }
    )

/** Taller than a text-only settings row: this one carries a source avatar and a two-line label. */
private val RowMinHeight = 60.dp

private val AvatarSize = 40.dp

private val RingSize = 24.dp

private val GlyphSize = 16.dp

private val EmptyMarkSize = 56.dp

/** A source this app can't run shows its avatar at reduced opacity (design system: `SourceAvatar`). */
private const val BlockedAlpha = 0.4f
