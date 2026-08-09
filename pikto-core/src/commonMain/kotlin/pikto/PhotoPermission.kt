package pikto

/**
 * How much of the photo library the app is currently allowed to see.
 *
 * [LIMITED] is a real, permanent state on both platforms rather than a step on the way to
 * [GRANTED]. The user picked a handful of photos and everything else stays invisible. Treat it as
 * a working state: read what is there and offer a way to widen the selection, never as a failure to
 * retry.
 */
public enum class PhotoPermission {
    /** Never asked. Nothing has been shown to the user yet. */
    NOT_DETERMINED,

    /** The whole library is readable. */
    GRANTED,

    /** Only the assets the user hand-picked are readable. */
    LIMITED,

    /** The user said no, or a policy says no. Asking again will not show a system prompt. */
    DENIED;

    /** True when a [PhotoLibrary.stream] will produce something rather than nothing. */
    public val canRead: Boolean get() = this == GRANTED || this == LIMITED
}
