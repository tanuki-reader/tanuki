package app.tanuki.core.navigation

/**
 * App-level navigation intents that no single screen owns — tab selection and deep links that may
 * cross tab boundaries. Emitted by [AppNavigator] and handled once by the root host.
 *
 * Deliberately distinct from [NavEvent], which is a *screen-scoped* push/pop within the active tab's
 * own back stack: a ViewModel that just opens a detail screen within its tab uses [NavEvent]; truly
 * cross-cutting navigation (open a tab from a notification, follow a deep link from cold start) uses
 * this. Keeping the two apart is what lets per-tab stacks stay self-contained (see CLAUDE.md →
 * "Navigation: shared contract, native rendering").
 */
sealed interface NavCommand {
    /** Switch to a root tab, e.g., from a notification or an in-app shortcut. */
    data class SelectTab(
        val tab: TopLevelDestination
    ) : NavCommand

    /**
     * Navigate to any destination from anywhere: the host selects the owning tab first (if the
     * destination isn't already on the active stack), then pushes it. This is the deep-link entry.
     */
    data class DeepLink(
        val destination: Destination
    ) : NavCommand
}