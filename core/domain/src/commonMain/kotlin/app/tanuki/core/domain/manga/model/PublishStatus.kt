package app.tanuki.core.domain.manga.model

enum class PublishStatus {
    /** The series is currently being published with new chapters releasing. */
    ONGOING,

    /** The series has ended and all chapters have been published. */
    COMPLETED,

    /** The series is licensed, which may restrict the source from distributing it. */
    LICENSED,

    /** The original publisher has finished releasing chapters, but the series may not be fully translated. */
    PUBLISHING_FINISHED,

    /** Publication of the series has been permanently discontinued. */
    CANCELLED,

    /** Publication is temporarily suspended; new chapters are expected to resume. */
    HIATUS,

    /** The source does not provide publication status information for this series. */
    UNKNOWN
}