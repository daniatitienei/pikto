package pikto.images

/**
 * How big a copy of a photo to decode.
 *
 * The two cases are not just different numbers: they take different paths. [Thumbnail] goes
 * through the platform's own thumbnailing and is cached to disk between launches, so a grid of
 * covers costs a file read on the second launch instead of a decode. [Full] asks the platform to
 * render the asset at screen resolution and is memory-cached only, because a handful of them would
 * fill any disk budget worth having.
 *
 * The size is also part of the cache key, so a grid asking for `Thumbnail(320)` and another asking
 * for `Thumbnail(321)` share nothing. Pick a small number of sizes and reuse them.
 */
public sealed interface ImageSize {

    /**
     * The asset rendered to fit the screen, for a full-bleed viewer. Sharp on dense displays,
     * expensive to decode, and never written to disk.
     */
    public data object Full : ImageSize

    /**
     * The asset fitted inside a [sidePx] by [sidePx] box, aspect ratio untouched. For grids, rows
     * and any cover art.
     */
    public data class Thumbnail(val sidePx: Int) : ImageSize {
        init {
            require(sidePx > 0) { "sidePx must be positive, was $sidePx" }
        }
    }
}

internal fun ImageSize.cacheKey(assetId: String): String = when (this) {
    ImageSize.Full -> "$assetId@full"
    is ImageSize.Thumbnail -> "$assetId@$sidePx"
}

/** Keyed by side as well as id, because a grid and a row want different boxes of the same photo. */
internal data class ThumbnailRequest(val assetId: String, val sidePx: Int)
