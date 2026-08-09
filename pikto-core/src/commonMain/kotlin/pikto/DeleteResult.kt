package pikto

/**
 * What came of a [PhotoLibrary.delete].
 *
 * Deleting photos is one of the few operations both platforms put a system confirmation sheet in
 * front of, so "the user said no" is an ordinary outcome rather than an error, hence [Cancelled]
 * sitting beside [Failed] instead of inside it.
 *
 * Neither platform deletes permanently. Android moves assets to the MediaStore trash and iOS to
 * Recently Deleted, both recoverable by the user for about thirty days.
 */
public sealed interface DeleteResult {

    /** The assets are gone from the library and in the platform's recoverable trash. */
    public data object Deleted : DeleteResult

    /** The user dismissed the system confirmation sheet. Nothing was deleted. */
    public data object Cancelled : DeleteResult

    /**
     * Something went wrong. Some of the assets may have been deleted, so re-read the library
     * rather than assuming the previous state still holds.
     */
    public data class Failed(
        val message: String,
        val cause: Throwable? = null,
    ) : DeleteResult
}

/** Shorthand for `this is DeleteResult.Deleted`, for call sites that only branch two ways. */
public val DeleteResult.isDeleted: Boolean get() = this is DeleteResult.Deleted
