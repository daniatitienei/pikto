package pikto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList

/**
 * The current status, having asked for permission first if the user has never been asked.
 *
 * This is the one call most apps want in front of everything else:
 *
 * ```kotlin
 * if (library.ensurePermission().canRead) {
 *     library.collectAssets().collect { assets -> render(assets) }
 * }
 * ```
 *
 * Prefer this to [PhotoLibrary.permissionStatus] whenever a refusal should send the user to
 * Settings. A refusal is only distinguishable from "never asked" once a prompt has been shown,
 * so this is the call that reports [PhotoPermission.DENIED] on both platforms rather than on iOS
 * alone. Both platforms answer a prompt with a settled state, so
 * [PhotoPermission.NOT_DETERMINED] coming back from here is not an outcome worth designing for.
 *
 * On Android showing the prompt needs an activity, so `installPikto()` must have been called on
 * it. See [PhotoLibrary.requestPermission].
 */
public suspend fun PhotoLibrary.ensurePermission(): PhotoPermission {
    val status = permissionStatus()
    if (status != PhotoPermission.NOT_DETERMINED) return status
    return requestPermission()
}

/** Deletes a single asset. See [PhotoLibrary.delete]. */
public suspend fun PhotoLibrary.delete(id: String): DeleteResult = delete(listOf(id))

/**
 * The whole library as one list, once it has all arrived.
 *
 * Simple, and the wrong choice for anything on screen: it waits for the last asset before
 * returning the first. Use [collectAssets] for UI and this for background work.
 */
public suspend fun PhotoLibrary.loadAll(query: MediaQuery = MediaQuery()): List<PhotoAsset> {
    val builder = LibrarySnapshotBuilder()
    stream(query.copy(batching = BatchStrategy.SingleBatch)).toList().forEach(builder::apply)
    return builder.assets()
}

/**
 * The library as a list that grows, re-emitted every time more of it arrives.
 *
 * This is [PhotoLibrary.stream] folded into the shape a list UI actually wants: each emission is
 * the complete set of assets known so far, in [MediaQuery.sortOrder], with any sizes that have
 * landed already filled in. The last emission is the whole library.
 *
 * Album membership is dropped, since a `List<PhotoAsset>` has nowhere to put it. Collect
 * [PhotoLibrary.stream] directly if you need [LibraryUpdate.Albums].
 *
 * ```kotlin
 * val assets by library.collectAssets().collectAsState(emptyList())
 * ```
 */
public fun PhotoLibrary.collectAssets(query: MediaQuery = MediaQuery()): Flow<List<PhotoAsset>> =
    flow {
        val builder = LibrarySnapshotBuilder()
        stream(query).collect { update ->
            if (builder.apply(update)) emit(builder.assets())
        }
    }

/**
 * Accumulates [LibraryUpdate]s into one ordered list.
 *
 * Sizes are patched in place through an id index rather than by rebuilding the list, because a
 * size update covers hundreds of assets at a time and a scan per asset would be quadratic over the
 * length of the library.
 */
internal class LibrarySnapshotBuilder {

    private val collected = mutableListOf<PhotoAsset>()
    private val indexById = mutableMapOf<String, Int>()

    /** Returns whether this update changed anything worth re-emitting for. */
    fun apply(update: LibraryUpdate): Boolean = when (update) {
        is LibraryUpdate.Assets -> applyAssets(update.assets)
        is LibraryUpdate.Sizes -> applySizes(update.sizeBytesById)
        is LibraryUpdate.Albums -> false
    }

    fun assets(): List<PhotoAsset> = collected.toList()

    private fun applyAssets(assets: List<PhotoAsset>): Boolean {
        if (assets.isEmpty()) return false
        assets.forEach { asset ->
            val existing = indexById[asset.id]
            if (existing != null) {
                collected[existing] = asset
            } else {
                indexById[asset.id] = collected.size
                collected += asset
            }
        }
        return true
    }

    private fun applySizes(sizeBytesById: Map<String, Long>): Boolean {
        var changed = false
        sizeBytesById.forEach { (id, sizeBytes) ->
            val index = indexById[id] ?: return@forEach
            if (collected[index].sizeBytes == sizeBytes) return@forEach
            collected[index] = collected[index].copy(sizeBytes = sizeBytes)
            changed = true
        }
        return changed
    }
}
