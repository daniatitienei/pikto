package pikto

import kotlin.time.Instant

/** Whether an asset is a still or a clip. */
public enum class MediaType {
    IMAGE,
    VIDEO,
}

/**
 * One photo or video in the device library, described by its metadata only. Pixels are never
 * carried here: loading those is the job of `pikto-images`, and keeping them apart is what lets a
 * library of fifty thousand assets be enumerated without decoding a single one.
 *
 * @property id An opaque handle to the asset, stable for as long as the asset exists on the device.
 *   It is a MediaStore row id on Android and a `PHAsset` local identifier on iOS, so it means
 *   nothing outside the device that produced it and must not be parsed, sorted or persisted as a
 *   cross-device key.
 * @property sizeBytes The file size, or `null` when it is not known yet. On iOS reading sizes is
 *   the slow half of enumerating a library, so assets arrive without one and it is filled in by a
 *   later [LibraryUpdate.Sizes]. See [MediaQuery.includeSizes].
 * @property width Pixel width of the original, before any thumbnailing.
 * @property durationMillis Clip length for videos; zero for stills.
 * @property isScreenshot Whether the device considers this a screenshot: the `Screenshots` folder
 *   on Android, the screenshots smart album on iOS.
 */
public data class PhotoAsset(
    val id: String,
    val createdAt: Instant,
    val width: Int,
    val height: Int,
    val mediaType: MediaType = MediaType.IMAGE,
    val sizeBytes: Long? = null,
    val durationMillis: Long = 0L,
    val isScreenshot: Boolean = false,
) {
    public val isVideo: Boolean get() = mediaType == MediaType.VIDEO

    /** Width over height, or `1f` when the platform reported no dimensions for this asset. */
    public val aspectRatio: Float
        get() = if (width <= 0 || height <= 0) 1f else width.toFloat() / height.toFloat()
}
