package app.tanuki.core.domain.manga.model

enum class ReadingStatus {
    /** The user is actively reading this series. */
    READING,

    /** The user has finished reading this series. */
    COMPLETED,

    /** The user has paused reading and intends to continue later. */
    ON_HOLD,

    /** The user has stopped reading this series with no intention to continue. */
    DROPPED,

    /** The user plans to read this series but has not started yet. */
    PLANNING_TO_READ
}