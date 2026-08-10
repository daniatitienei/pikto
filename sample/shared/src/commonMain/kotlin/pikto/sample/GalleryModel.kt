package pikto.sample

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import pikto.DeleteResult
import pikto.MediaQuery
import pikto.MediaType
import pikto.PhotoAsset
import pikto.PhotoLibrary
import pikto.PhotoPermission
import pikto.collectAssets
import pikto.delete
import pikto.ensurePermission

/** The three things the filter row can ask for. */
enum class Filter(val label: String, val query: MediaQuery) {
    All("All", MediaQuery()),
    Photos("Photos", MediaQuery.Images),
    Videos("Videos", MediaQuery.Videos),
}

/**
 * Everything the sample knows, in one place.
 *
 * A plain class rather than a `ViewModel` because the point here is the Pikto calls, and a
 * `ViewModel` would drag in a lifecycle dependency that has nothing to teach about photo
 * libraries. A real app should use one; the calls below are identical either way.
 */
@Stable
class GalleryModel(
    private val library: PhotoLibrary,
    private val scope: CoroutineScope,
) {

    var permission by mutableStateOf<PhotoPermission?>(null)
        private set

    var assets by mutableStateOf<List<PhotoAsset>>(emptyList())
        private set

    /** True while a stream is still delivering batches, so the UI can show it filling in. */
    var isStreaming by mutableStateOf(false)
        private set

    var filter by mutableStateOf(Filter.All)
        private set

    var selection by mutableStateOf(emptySet<String>())
        private set

    /** Whatever the last delete had to say. Shown in a snackbar and cleared by the UI. */
    var message by mutableStateOf<String?>(null)

    val isSelecting: Boolean get() = selection.isNotEmpty()

    private var streamJob: Job? = null

    /**
     * Asks for permission only if nobody has been asked yet, then starts reading.
     *
     * This is the one call almost every app wants in front of everything else.
     */
    fun start() {
        scope.launch {
            val status = library.ensurePermission()
            permission = status
            if (status.canRead) restream()
        }
    }

    /**
     * Shows the system prompt again. On DENIED both platforms decline to show anything and the
     * status comes straight back, which is why the UI sends the user to Settings instead.
     *
     * On iOS with LIMITED this is what re-opens the picker so more photos can be let through.
     */
    fun requestPermission() {
        scope.launch {
            val status = library.requestPermission()
            permission = status
            if (status.canRead) restream()
        }
    }

    fun selectFilter(next: Filter) {
        if (next == filter) return
        filter = next
        selection = emptySet()
        if (permission?.canRead == true) restream()
    }

    fun toggleSelection(id: String) {
        selection = if (id in selection) selection - id else selection + id
    }

    fun clearSelection() {
        selection = emptySet()
    }

    /**
     * Moves the selected assets to the platform's recoverable trash, showing the system
     * confirmation sheet. "The user said no" is an ordinary outcome, not an error.
     */
    fun deleteSelected() {
        val ids = selection.toList()
        if (ids.isEmpty()) return
        scope.launch {
            when (val result = library.delete(ids)) {
                DeleteResult.Deleted -> {
                    selection = emptySet()
                    message = "Moved ${ids.size} to the trash"
                    // The library changed underneath us, so read it again rather than
                    // guessing what it looks like now.
                    restream()
                }

                DeleteResult.Cancelled -> message = "Delete cancelled"

                is DeleteResult.Failed -> {
                    message = "Delete failed: ${result.message}"
                    // Some of them may be gone, so the old list is no longer trustworthy.
                    restream()
                }
            }
        }
    }

    /**
     * Deletes exactly one asset, the way the viewer wants to.
     *
     * `delete(id)` is the single-asset shorthand for [PhotoLibrary.delete]; it is the same system
     * confirmation sheet and the same three outcomes, just without building a list at the call
     * site.
     */
    fun delete(id: String, onDeleted: () -> Unit = {}) {
        scope.launch {
            when (val result = library.delete(id)) {
                DeleteResult.Deleted -> {
                    selection = selection - id
                    message = "Moved to the trash"
                    onDeleted()
                    restream()
                }

                DeleteResult.Cancelled -> message = "Delete cancelled"

                is DeleteResult.Failed -> {
                    message = "Delete failed: ${result.message}"
                    restream()
                }
            }
        }
    }

    /**
     * Collects the library as a list that grows.
     *
     * Each emission is everything known so far, so the first screenful paints in a frame or two
     * and the rest fills in behind it. Cancelling the previous job stops the previous read: the
     * flow is cold, so nothing keeps working for a filter the user has moved off.
     */
    private fun restream() {
        streamJob?.cancel()
        assets = emptyList()
        isStreaming = true
        streamJob = scope.launch {
            try {
                library.collectAssets(filter.query).collect { assets = it }
            } finally {
                isStreaming = false
            }
        }
    }
}

@Composable
fun rememberGalleryModel(library: PhotoLibrary): GalleryModel {
    val scope = rememberCoroutineScope()
    return remember(library, scope) { GalleryModel(library, scope) }
}

/** Counts for the status line, which is also what makes the late-arriving sizes visible. */
data class LibrarySummary(
    val photos: Int,
    val videos: Int,
    val knownBytes: Long,
    val assetsMissingSize: Int,
)

fun List<PhotoAsset>.summarise(): LibrarySummary {
    var photos = 0
    var videos = 0
    var bytes = 0L
    var missing = 0
    forEach { asset ->
        if (asset.mediaType == MediaType.VIDEO) videos++ else photos++
        val size = asset.sizeBytes
        if (size == null) missing++ else bytes += size
    }
    return LibrarySummary(photos, videos, bytes, missing)
}
