package pikto

/**
 * Marks a declaration that exists only so Pikto's own modules can reach it across a module
 * boundary. It is not part of the public API: it can change or disappear in any release, including
 * a patch. Nothing outside Pikto should opt in.
 */
@RequiresOptIn(
    level = RequiresOptIn.Level.ERROR,
    message = "This is Pikto's internal plumbing and can change in any release.",
)
@Retention(AnnotationRetention.BINARY)
@Target(AnnotationTarget.FUNCTION, AnnotationTarget.CLASS, AnnotationTarget.PROPERTY)
public annotation class InternalPiktoApi
