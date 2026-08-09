package pikto

import kotlinx.coroutines.flow.Flow

/**
 * The device photo library: MediaStore on Android, PhotoKit on iOS, one API in `commonMain`.
 *
 * Obtain one with the [PhotoLibrary] factory function. Implementations hold no per-call state, so a
 * single instance can be shared for the lifetime of the app and is the intended way to use it.
 * Put it in your dependency graph rather than building one per screen.
 *
 * ### Nothing is loaded eagerly
 * No method here reads pixels, and [stream] reads metadata in batches it hands over as it goes. A
 * library of tens of thousands of assets can be enumerated without the memory cost scaling with it.
 *
 * ### Permission is the caller's job
 * [stream] does not check permission and does not throw when it is missing: it simply produces
 * nothing, because "the user hand-picked four photos" and "the user has four photos" are the same
 * situation as far as reading goes. Call [ensurePermission] first.
 */
public interface PhotoLibrary {

    /** What the app is allowed to see right now. Never shows UI. */
    public suspend fun permissionStatus(): PhotoPermission

    /**
     * Shows the system permission prompt and suspends until the user answers, returning the status
     * that resulted. Returns immediately with the current status if the platform decides no prompt
     * can be shown, which is what already-answered looks like.
     *
     * On Android this needs an activity, so `installPikto()` must have been called on it. See the
     * Android setup section of the README.
     */
    public suspend fun requestPermission(): PhotoPermission

    /**
     * Enumerates the library described by [query], in batches, as a cold flow: nothing is read
     * until it is collected, and cancelling the collection stops the read.
     *
     * Emissions are ordered: every [LibraryUpdate.Assets] arrives before the
     * [LibraryUpdate.Sizes] and [LibraryUpdate.Albums] that describe it.
     *
     * The flow is already confined to a background dispatcher; collecting it from the main thread
     * is fine and intended.
     */
    public fun stream(query: MediaQuery = MediaQuery()): Flow<LibraryUpdate>

    /**
     * Moves [ids] to the platform's recoverable trash, showing the system confirmation sheet the
     * platform requires. Suspends until the user answers it.
     *
     * Unknown or already-deleted ids are ignored rather than failing the whole call. Deleting
     * nothing succeeds.
     *
     * On Android the confirmation sheet needs an activity, so `installPikto()` must have been
     * called on it.
     */
    public suspend fun delete(ids: List<String>): DeleteResult
}

/**
 * Creates a [PhotoLibrary] for the current platform.
 *
 * On Android this uses the application context that Pikto's app-startup initializer captured, so
 * it can be called from anywhere including your `Application.onCreate`. If you removed the
 * initializer from your manifest, use the `PhotoLibrary(context)` overload in `androidMain`
 * instead.
 */
public expect fun PhotoLibrary(): PhotoLibrary
