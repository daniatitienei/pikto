package pikto.images

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.os.Build
import android.provider.MediaStore
import android.util.Size
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pikto.InternalPiktoApi
import pikto.isVideoAssetId
import pikto.mediaStoreRowId
import pikto.photoAssetUri
import pikto.piktoApplicationContext
import java.io.ByteArrayOutputStream

@OptIn(InternalPiktoApi::class)
public actual fun PhotoImageLoader(config: ImageLoaderConfig): PhotoImageLoader =
    AndroidPhotoImageLoader(piktoApplicationContext(), config)

/**
 * Creates a [PhotoImageLoader] reading through [context]'s content resolver, and caching
 * thumbnails in its cache directory.
 *
 * Prefer the no-argument factory in `commonMain` unless you have a reason to pick the context
 * yourself. Only the application context is retained, whatever you pass.
 */
public fun PhotoImageLoader(
    context: Context,
    config: ImageLoaderConfig = ImageLoaderConfig(),
): PhotoImageLoader = AndroidPhotoImageLoader(context, config)

internal class AndroidPhotoImageLoader(
    context: Context,
    config: ImageLoaderConfig,
) : PhotoImageLoader {

    private val appContext = context.applicationContext
    private val thumbnailCache = MemoryImageCache(config.thumbnailMemoryBytes)
    private val fullImageCache = MemoryImageCache(config.fullImageMemoryBytes)
    private val diskCache = config.diskCache
        ?: AndroidPhotoDiskCache(appContext, config.diskCacheBytes)

    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fullImageJobs = DecodeJobs<String>(loadScope, config.maxParallelFullDecodes) {
        decodeFullImageCached(it)
    }
    private val thumbnailJobs = DecodeJobs<ThumbnailRequest>(
        loadScope,
        config.maxParallelThumbnailDecodes,
    ) { decodeThumbnailCached(it) }

    // A full-bleed photo fills the screen, so the screen in real pixels is what it has to match.
    // Anything less shows up as softness on a dense display.
    private val screenWidthPx: Int get() = appContext.resources.displayMetrics.widthPixels
    private val screenHeightPx: Int get() = appContext.resources.displayMetrics.heightPixels

    override fun peek(assetId: String, size: ImageSize): ImageBitmap? =
        cacheFor(size).peek(size.cacheKey(assetId))

    override suspend fun load(assetId: String, size: ImageSize): ImageBitmap? {
        cacheFor(size).get(size.cacheKey(assetId))?.let { return it }
        return when (size) {
            ImageSize.Full -> fullImageJobs.load(assetId)
            is ImageSize.Thumbnail -> thumbnailJobs.load(ThumbnailRequest(assetId, size.sidePx))
        }
    }

    override fun prefetch(assetIds: List<String>, size: ImageSize) {
        when (size) {
            ImageSize.Full -> fullImageJobs.prefetch(assetIds)
            is ImageSize.Thumbnail ->
                thumbnailJobs.prefetch(assetIds.map { ThumbnailRequest(it, size.sidePx) })
        }
    }

    private fun cacheFor(size: ImageSize): MemoryImageCache =
        if (size == ImageSize.Full) fullImageCache else thumbnailCache

    /**
     * Memory, then disk, then MediaStore. The disk hop is what a cold launch lives on: decoding a
     * cover out of MediaStore costs orders of magnitude more than reading back the WebP written
     * the last time it was decoded.
     */
    private suspend fun decodeThumbnailCached(request: ThumbnailRequest): ImageBitmap? {
        val key = ImageSize.Thumbnail(request.sidePx).cacheKey(request.assetId)
        thumbnailCache.get(key)?.let { return it }
        readFromDisk(key)?.let {
            thumbnailCache.put(key, it)
            return it
        }
        val bitmap = withContext(Dispatchers.IO) {
            decodeThumbnail(request.assetId, request.sidePx)
        } ?: return null
        val imageBitmap = bitmap.asImageBitmap()
        thumbnailCache.put(key, imageBitmap)
        // Fire and forget: the caller is waiting to paint, not to persist.
        bitmap.toEncodedBytes()?.let { bytes -> loadScope.launch { diskCache.write(key, bytes) } }
        return imageBitmap
    }

    private suspend fun readFromDisk(key: String): ImageBitmap? {
        val bytes = diskCache.read(key) ?: return null
        return withContext(Dispatchers.IO) {
            runCatching { BitmapFactory.decodeByteArray(bytes, 0, bytes.size) }.getOrNull()
        }?.asImageBitmap()
    }

    private suspend fun decodeFullImageCached(assetId: String): ImageBitmap? {
        val key = ImageSize.Full.cacheKey(assetId)
        fullImageCache.get(key)?.let { return it }
        val bitmap = withContext(Dispatchers.IO) { decodeFullImage(assetId) } ?: return null
        val imageBitmap = bitmap.asImageBitmap()
        fullImageCache.put(key, imageBitmap)
        return imageBitmap
    }

    private fun Bitmap.toEncodedBytes(): ByteArray? = runCatching {
        ByteArrayOutputStream().use { stream ->
            compress(encodedFormat(), THUMBNAIL_QUALITY, stream)
            stream.toByteArray()
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun encodedFormat(): Bitmap.CompressFormat =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Bitmap.CompressFormat.WEBP_LOSSY
        } else {
            Bitmap.CompressFormat.WEBP
        }

    private fun decodeThumbnail(assetId: String, sidePx: Int): Bitmap? = try {
        when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> appContext.contentResolver
                .loadThumbnail(photoAssetUri(assetId), Size(sidePx, sidePx), null)
            isVideoAssetId(assetId) -> legacyVideoThumbnail(assetId)
            else -> legacyImageThumbnail(assetId)
        }
    } catch (_: Exception) {
        null
    }

    @Suppress("DEPRECATION")
    private fun legacyImageThumbnail(assetId: String): Bitmap? =
        MediaStore.Images.Thumbnails.getThumbnail(
            appContext.contentResolver,
            mediaStoreRowId(assetId),
            MediaStore.Images.Thumbnails.MINI_KIND,
            null,
        )

    @Suppress("DEPRECATION")
    private fun legacyVideoThumbnail(assetId: String): Bitmap? =
        MediaStore.Video.Thumbnails.getThumbnail(
            appContext.contentResolver,
            mediaStoreRowId(assetId),
            MediaStore.Video.Thumbnails.MINI_KIND,
            null,
        )

    private fun decodeFullImage(assetId: String): Bitmap? =
        if (isVideoAssetId(assetId)) decodeVideoFrame(assetId) else decodeImageFrame(assetId)

    private fun decodeImageFrame(assetId: String): Bitmap? = try {
        val sampleSize = computeSampleSize(assetId)
        appContext.contentResolver.openInputStream(photoAssetUri(assetId))?.use { stream ->
            BitmapFactory.decodeStream(
                stream,
                null,
                BitmapFactory.Options().apply { inSampleSize = sampleSize },
            )
        }
    } catch (_: Exception) {
        null
    }

    /** [BitmapFactory] cannot decode a video file, so a poster frame is pulled instead. */
    private fun decodeVideoFrame(assetId: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(appContext, photoAssetUri(assetId))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                retriever.getScaledFrameAtTime(
                    0L,
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                    screenWidthPx,
                    screenHeightPx,
                )
            } else {
                @Suppress("DEPRECATION")
                retriever.frameAtTime
            }
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    /**
     * Halves only while both sides stay at or above the screen box, so an image that fills the
     * screen is never handed fewer pixels than it paints. A fixed cap under-decodes on dense
     * displays, which is exactly what makes photos look pixelated.
     */
    private fun computeSampleSize(assetId: String): Int {
        val bounds = decodeBounds(assetId) ?: return 1
        var width = bounds.outWidth
        var height = bounds.outHeight
        var sampleSize = 1
        while (width / 2 >= screenWidthPx && height / 2 >= screenHeightPx) {
            width /= 2
            height /= 2
            sampleSize *= 2
        }
        return sampleSize
    }

    private fun decodeBounds(assetId: String): BitmapFactory.Options? = try {
        appContext.contentResolver.openInputStream(photoAssetUri(assetId))?.use { stream ->
            BitmapFactory.Options()
                .apply { inJustDecodeBounds = true }
                .also { BitmapFactory.decodeStream(stream, null, it) }
        }
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val THUMBNAIL_QUALITY = 80
    }
}
