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
    /**
     * Nothing is readable and no prompt has resolved the question yet.
     *
     * On iOS that means exactly "never asked". On Android it also covers "asked and refused",
     * because the platform records only the absence of the permission, not the refusal. See
     * [PhotoLibrary.permissionStatus].
     */
    NOT_DETERMINED,

    /** The whole library is readable. */
    GRANTED,

    /** Only the assets the user hand-picked are readable. */
    LIMITED,

    /**
     * The user said no, or a policy says no. Asking again will not show a system prompt.
     *
     * Reachable from [PhotoLibrary.permissionStatus] on iOS only. On Android a refusal is
     * indistinguishable from never having asked until a prompt has been shown, so it surfaces
     * here only through [PhotoLibrary.requestPermission] or [ensurePermission].
     */
    DENIED;

    /** True when a [PhotoLibrary.stream] will produce something rather than nothing. */
    public val canRead: Boolean get() = this == GRANTED || this == LIMITED
}
