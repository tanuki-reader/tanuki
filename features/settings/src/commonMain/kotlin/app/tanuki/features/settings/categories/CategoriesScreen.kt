package app.tanuki.features.settings.categories

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.tanuki.assets.designSystem.TanukiRadii
import app.tanuki.assets.designSystem.TanukiTheme
import app.tanuki.assets.designSystem.component.TanukiButton
import app.tanuki.assets.designSystem.component.TanukiButtonVariant
import app.tanuki.assets.designSystem.component.TanukiDetailTopBar
import app.tanuki.assets.designSystem.component.TanukiDialog
import app.tanuki.assets.designSystem.component.TanukiIconButton
import app.tanuki.assets.designSystem.component.TanukiTextField
import app.tanuki.assets.designSystem.component.TrashIcon
import app.tanuki.features.settings.categories.viewmodel.CategoriesState
import app.tanuki.features.settings.categories.viewmodel.CategoriesViewModel
import app.tanuki.features.settings.categories.viewmodel.CategoryItem
import app.tanuki.features.settings.resources.Res
import app.tanuki.features.settings.resources.settings_add_category
import app.tanuki.features.settings.resources.settings_cancel
import app.tanuki.features.settings.resources.settings_categories
import app.tanuki.features.settings.resources.settings_categories_empty
import app.tanuki.features.settings.resources.settings_category_name_hint
import app.tanuki.features.settings.resources.settings_create
import app.tanuki.features.settings.resources.settings_delete
import app.tanuki.features.settings.resources.settings_delete_category_message
import app.tanuki.features.settings.resources.settings_delete_category_title
import app.tanuki.features.settings.resources.settings_new_category_title
import dev.zacsweers.metrox.viewmodel.metroViewModel
import org.jetbrains.compose.resources.stringResource

/**
 * Category-management sub-screen. Back-navigating leaf pushed onto the Settings tab stack; lists the
 * library shelves with create and (confirmed) delete.
 *
 * @param onBack pop back to the settings index.
 * @param viewModel the ViewModel driving the UI.
 */
@Composable
fun CategoriesRoute(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CategoriesViewModel = metroViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    CategoriesScreen(
        state = state,
        viewModel = viewModel,
        onBack = onBack,
        modifier = modifier
    )
}

@Composable
private fun CategoriesScreen(
    state: CategoriesState,
    viewModel: CategoriesViewModel,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val categories = state.categories

    Column(
        modifier =
            modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
    ) {
        TanukiDetailTopBar(
            title = stringResource(Res.string.settings_categories),
            onBack = onBack
        )
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        horizontal = TanukiTheme.spacing.gutter,
                        vertical = TanukiTheme.spacing.md
                    ),
            verticalArrangement = Arrangement.spacedBy(TanukiTheme.spacing.md)
        ) {
            if (categories.isEmpty()) {
                Text(
                    text = stringResource(Res.string.settings_categories_empty),
                    style = TanukiTheme.typography.body,
                    color = TanukiTheme.colors.textFaint
                )
            } else {
                CategoryList(
                    categories = categories,
                    onDelete = viewModel::onDeleteRequested
                )
            }
            TanukiButton(
                text = stringResource(Res.string.settings_add_category),
                onClick = viewModel::onCreateRequested,
                variant = TanukiButtonVariant.Tonal,
                block = true
            )
        }
    }

    if (state.creating) {
        CreateCategoryDialog(
            onDismiss = viewModel::onDialogDismissed,
            onConfirm = viewModel::onCreateCategory
        )
    }

    state.pendingDeletion?.let { category ->
        DeleteCategoryDialog(
            category = category,
            onDismiss = viewModel::onDialogDismissed,
            onConfirm = viewModel::onDeleteConfirmed
        )
    }
}

/** The category rows in a single rounded surface, separated by hairline dividers (settings-card look). */
@Composable
private fun CategoryList(
    categories: List<CategoryItem>,
    onDelete: (CategoryItem) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier =
            modifier
                .fillMaxWidth()
                .clip(TanukiRadii.chipShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
    ) {
        categories.forEachIndexed { index, category ->
            if (index > 0) {
                HorizontalDivider(
                    thickness = DividerThickness,
                    color = MaterialTheme.colorScheme.outlineVariant
                )
            }
            CategoryRow(category = category, onDelete = { onDelete(category) })
        }
    }
}

@Composable
private fun CategoryRow(
    category: CategoryItem,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier =
            modifier
                .fillMaxWidth()
                .heightIn(min = TanukiTheme.spacing.x5l)
                .padding(start = TanukiTheme.spacing.lg, end = TanukiTheme.spacing.xs),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = category.name,
            style = TanukiTheme.typography.body,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f)
        )
        TanukiIconButton(onClick = onDelete) { _ ->
            TrashIcon(tint = TanukiTheme.colors.danger)
        }
    }
}

/** Name-entry dialog for a new category. Create is disabled until the trimmed name is non-blank. */
@Composable
private fun CreateCategoryDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var name by remember { mutableStateOf("") }
    TanukiDialog(
        title = stringResource(Res.string.settings_new_category_title),
        onDismiss = onDismiss,
        dismissText = stringResource(Res.string.settings_cancel),
        confirmText = stringResource(Res.string.settings_create),
        confirmEnabled = name.isNotBlank(),
        onConfirm = { onConfirm(name) }
    ) {
        TanukiTextField(
            value = name,
            onValueChange = { name = it },
            placeholder = stringResource(Res.string.settings_category_name_hint),
            autoFocus = true,
            imeAction = ImeAction.Done,
            onImeAction = { if (name.isNotBlank()) onConfirm(name) }
        )
    }
}

/** Confirmation before deleting a category — deletion cascades to its manga memberships. */
@Composable
private fun DeleteCategoryDialog(
    category: CategoryItem,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    TanukiDialog(
        title = stringResource(Res.string.settings_delete_category_title),
        onDismiss = onDismiss,
        dismissText = stringResource(Res.string.settings_cancel),
        confirmText = stringResource(Res.string.settings_delete),
        confirmVariant = TanukiButtonVariant.Danger,
        onConfirm = onConfirm
    ) {
        Text(
            text = stringResource(Res.string.settings_delete_category_message, category.name),
            style = TanukiTheme.typography.body,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

private val DividerThickness = Dp.Hairline