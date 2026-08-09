package pikto.images

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Decodes assets out of the photo library into Compose [ImageBitmap]s, through a memory cache and
 * (for thumbnails) a disk cache that survives launches.
 *
 * One instance is meant to serve the whole app: the caches, the in-flight decode registry and the
 * bounded decode pool all live inside it, so a second instance is a second set of everything and
 * the two will fight over the same disk directory. Build one, keep it, and hand it to Compose with
 * [ProvidePhotoImageLoader] or your dependency graph.
 *
 * Nothing here throws. An asset that has been deleted, a decode the platform refuses and a
 * corrupt cache entry all come back as `null`, because every one of them means the same thing to a
 * caller with a hole in its layout to fill.
 */
public interface PhotoImageLoader {

    /**
     * The already-decoded bitmap, or `null` if it is not in memory.
     *
     * Never suspends and never starts work, so a composable can paint a warm image on its very
     * first frame rather than showing a placeholder for one frame while a coroutine starts.
     */
    public fun peek(assetId: String, size: ImageSize): ImageBitmap?

    /**
     * The bitmap, decoding it if needed. Memory, then disk, then the platform.
     *
     * Callers asking for the same asset at the same size share one decode, and a caller that
     * cancels does not cancel the decode for everyone else. The result still lands in the cache
     * for whoever is about to need it.
     */
    public suspend fun load(assetId: String, size: ImageSize): ImageBitmap?

    /**
     * Starts decoding [assetIds] in the given order without waiting for any of them, so they are
     * warm by the time something asks. Ids that are already cached or already in flight cost
     * nothing, which makes re-issuing an overlapping window on every scroll cheap.
     */
    public fun prefetch(assetIds: List<String>, size: ImageSize)
}

/**
 * Budgets and limits for a [PhotoImageLoader].
 *
 * Thumbnails and full images get separate memory budgets on purpose. Sharing one is a trap: a
 * handful of full-screen decodes, a dozen megabytes each on a dense display, evict every thumbnail
 * in the cache, so coming back from a viewer to a grid re-decodes the entire grid.
 *
 * @property thumbnailMemoryBytes Memory budget for [ImageSize.Thumbnail] decodes.
 * @property fullImageMemoryBytes Memory budget for [ImageSize.Full] decodes.
 * @property diskCacheBytes Ceiling for the on-disk thumbnail cache. Trimmed least-recently-used,
 *   once per process, off the launch path. Ignored when [diskCache] is set.
 * @property maxParallelFullDecodes Bounded low, because a full-screen decode is expensive and two
 *   of them already saturate the IO path.
 * @property maxParallelThumbnailDecodes Thumbnails are cheap and arrive in rows, so a slightly
 *   wider window pays off.
 * @property diskCache Somewhere other than the platform cache directory to keep encoded
 *   thumbnails, or `null` for the default. Pass [PhotoDiskCache.None] to keep nothing on disk.
 */
public data class ImageLoaderConfig(
    val thumbnailMemoryBytes: Long = 24L * 1024 * 1024,
    val fullImageMemoryBytes: Long = 96L * 1024 * 1024,
    val diskCacheBytes: Long = 40L * 1024 * 1024,
    val maxParallelFullDecodes: Int = 2,
    val maxParallelThumbnailDecodes: Int = 3,
    val diskCache: PhotoDiskCache? = null,
) {
    init {
        require(thumbnailMemoryBytes > 0) { "thumbnailMemoryBytes must be positive" }
        require(fullImageMemoryBytes > 0) { "fullImageMemoryBytes must be positive" }
        require(diskCacheBytes >= 0) { "diskCacheBytes cannot be negative" }
        require(maxParallelFullDecodes > 0) { "maxParallelFullDecodes must be positive" }
        require(maxParallelThumbnailDecodes > 0) { "maxParallelThumbnailDecodes must be positive" }
    }
}

/**
 * Creates a [PhotoImageLoader] for the current platform.
 *
 * On Android this uses the application context captured by Pikto's app-startup initializer, so it
 * can be called from anywhere. Use the `PhotoImageLoader(context, config)` overload in
 * `androidMain` if you removed that initializer.
 */
public expect fun PhotoImageLoader(
    config: ImageLoaderConfig = ImageLoaderConfig(),
): PhotoImageLoader
