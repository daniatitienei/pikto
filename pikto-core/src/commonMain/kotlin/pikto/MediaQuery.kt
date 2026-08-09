package pikto

/** Which end of the library comes first. */
public enum class SortOrder {
    NEWEST_FIRST,
    OLDEST_FIRST,
}

/**
 * How [PhotoLibrary.stream] cuts the library into [LibraryUpdate.Assets].
 *
 * The first batch is deliberately tiny, because it is the whole time-to-first-pixel budget: it is
 * what the screen has to draw before anything else arrives. Later batches grow, because once the
 * screen is full the cost that matters is whatever the consumer recomputes per batch, not paint
 * latency.
 *
 * @property firstBatchSize How many assets are in the first emission.
 * @property growthFactor What each batch is multiplied by to get the next one.
 * @property maxBatchSize The ceiling batches grow to.
 */
public data class BatchStrategy(
    val firstBatchSize: Int = 40,
    val growthFactor: Int = 4,
    val maxBatchSize: Int = 2_000,
) {
    init {
        require(firstBatchSize > 0) { "firstBatchSize must be positive, was $firstBatchSize" }
        require(growthFactor >= 1) { "growthFactor must be at least 1, was $growthFactor" }
        require(maxBatchSize >= firstBatchSize) {
            "maxBatchSize ($maxBatchSize) must be at least firstBatchSize ($firstBatchSize)"
        }
    }

    internal fun nextSize(current: Int): Int {
        val grown = current.toLong() * growthFactor
        return grown.coerceAtMost(maxBatchSize.toLong()).toInt()
    }

    public companion object {
        /** Tuned for a screen that wants to paint immediately and fill in behind itself. */
        public val Default: BatchStrategy = BatchStrategy()

        /**
         * One emission holding the whole library. Simpler to consume and the right choice for a
         * background pass, but it means nothing reaches the screen until the last asset is read.
         */
        public val SingleBatch: BatchStrategy = BatchStrategy(
            firstBatchSize = Int.MAX_VALUE,
            growthFactor = 1,
            maxBatchSize = Int.MAX_VALUE,
        )
    }
}

/**
 * What to enumerate, and in what shape. Every field has a default, so [MediaQuery] on its own
 * means "the whole library, newest first, with sizes".
 *
 * @property mediaTypes Which media types to include. An empty set yields nothing.
 * @property sortOrder Which end of the library arrives first.
 * @property includeSizes Whether to fill in [PhotoAsset.sizeBytes]. On Android sizes come free
 *   with the metadata and this costs nothing either way. On iOS each size is a separate disk read,
 *   so this is the difference between enumerating a large library in milliseconds and in
 *   seconds. Turn it off if nothing on screen shows bytes. Sizes always arrive *after* the assets, in
 *   [LibraryUpdate.Sizes].
 * @property includeAlbums Whether to enumerate album membership. Off by default because it is the
 *   most expensive pass on iOS by a wide margin: there is no way to ask an asset which collections
 *   hold it, so every collection has to be walked. Arrives in [LibraryUpdate.Albums].
 * @property batching How the assets are cut into emissions.
 */
public data class MediaQuery(
    val mediaTypes: Set<MediaType> = setOf(MediaType.IMAGE, MediaType.VIDEO),
    val sortOrder: SortOrder = SortOrder.NEWEST_FIRST,
    val includeSizes: Boolean = true,
    val includeAlbums: Boolean = false,
    val batching: BatchStrategy = BatchStrategy.Default,
) {
    internal val wantsImages: Boolean get() = MediaType.IMAGE in mediaTypes
    internal val wantsVideos: Boolean get() = MediaType.VIDEO in mediaTypes

    public companion object {
        /** Every still, no videos. */
        public val Images: MediaQuery = MediaQuery(mediaTypes = setOf(MediaType.IMAGE))

        /** Every clip, no stills. */
        public val Videos: MediaQuery = MediaQuery(mediaTypes = setOf(MediaType.VIDEO))
    }
}
