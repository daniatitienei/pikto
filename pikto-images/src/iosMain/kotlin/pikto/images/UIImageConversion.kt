package pikto.images

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import org.jetbrains.skia.Image
import platform.Foundation.NSData
import platform.Foundation.create
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.posix.memcpy

/**
 * Skia cannot take a `UIImage`, and re-encoding is the only bridge that does not go through a
 * pixel-buffer copy per channel layout. JPEG at 0.9 is visually lossless at any size Pikto decodes
 * to and is a fraction of the cost of the alternatives.
 */
internal fun UIImage.toImageBitmap(): ImageBitmap? {
    val data = UIImageJPEGRepresentation(this, FULL_QUALITY) ?: return null
    return runCatching { Image.makeFromEncoded(data.toByteArray()).toComposeImageBitmap() }
        .getOrNull()
}

/**
 * The same JPEG bytes at a lower quality, for the disk cache: that copy is only ever painted at
 * thumbnail size, where the difference does not show.
 */
internal fun UIImage.toThumbnailBytes(): ByteArray? =
    UIImageJPEGRepresentation(this, THUMBNAIL_QUALITY)?.toByteArray()

internal fun ByteArray.decodeToImageBitmap(): ImageBitmap? {
    if (isEmpty()) return null
    return runCatching { Image.makeFromEncoded(this).toComposeImageBitmap() }.getOrNull()
}

@OptIn(ExperimentalForeignApi::class)
internal fun NSData.toByteArray(): ByteArray {
    val size = length.toInt()
    if (size == 0) return ByteArray(0)
    return ByteArray(size).apply {
        usePinned { pinned -> memcpy(pinned.addressOf(0), bytes, length) }
    }
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ByteArray.toNSData(): NSData {
    if (isEmpty()) return NSData()
    return usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = size.toULong())
    }
}

private const val FULL_QUALITY = 0.9
private const val THUMBNAIL_QUALITY = 0.8
