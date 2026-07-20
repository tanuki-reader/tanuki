package app.tanuki.core.navigation

sealed interface NavEvent {
    data class NavTo(
        val destination: Destination
    ) : NavEvent

    data object NavBack : NavEvent
}