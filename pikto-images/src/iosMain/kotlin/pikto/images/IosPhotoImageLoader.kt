package pikto.images

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import pikto.phAssetFor
import platform.CoreGraphics.CGSize
import platform.CoreGraphics.CGSizeMake
import platform.Photos.PHCachingImageManager
import platform.Photos.PHImageContentModeAspectFit
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.Photos.PHImageRequestOptionsResizeModeExact
import platform.UIKit.UIImage
import platform.UIKit.UIScreen
import kotlin.concurrent.AtomicInt
import kotlin.coroutines.resume

public actual fun PhotoImageLoader(config: ImageLoaderConfig): PhotoImageLoader =
    IosPhotoImageLoader(config)

@OptIn(ExperimentalForeignApi::class)
internal class IosPhotoImageLoader(config: ImageLoaderConfig) : PhotoImageLoader {

    private val thumbnailCache = MemoryImageCache(config.thumbnailMemoryBytes)
    private val fullImageCache = MemoryImageCache(config.fullImageMemoryBytes)
    private val diskCache = config.diskCache ?: IosPhotoDiskCache(config.diskCacheBytes)

    private val imageManager = PHCachingImageManager()
    private val loadScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val fullImageJobs = DecodeJobs<String>(loadScope, config.maxParallelFullDecodes) {
        loadFullImageCached(it)
    }
    private val thumbnailJobs = DecodeJobs<ThumbnailRequest>(
        loadScope,
        config.maxParallelThumbnailDecodes,
    ) { loadThumbnailCached(it) }

    /**
     * A full-bleed photo covers the whole screen, so anything smaller than the screen in device
     * pixels is visibly soft. Asking for exactly the screen box, rather than a fixed side, keeps
     * it sharp without decoding megapixels that get scaled away.
     */
    private val screenSizePx: CValue<CGSize> by lazy {
        val scale = UIScreen.mainScreen.scale
        UIScreen.mainScreen.bounds.useContents { CGSizeMake(size.width * scale, size.height * scale) }
    }

    override fun peek(assetId: String, size: ImageSize): ImageBitmap? =
        cacheFor(size).peek(size.cacheKey(assetId))

    override suspend fun load(assetId: String, size: ImageSize): ImageBitmap? {
        cacheFor(size).get(size.cacheKey(assetId))?.let { return it }
        return when (size) {
            ImageSize.Full -> fullImageJobs.load(assetId)
            is ImageSize.Thumbnail -> thumbnailJobs.load(ThumbnailRequest(assetId, size.sidePx))
        }
    }

    /**
     * Photos keeps its own decoded cache, and [PHCachingImageManager.startCachingImagesForAssets]
     * is what turns it on. Warming Pikto's memory cache alongside it means the covers survive in
     * both places.
     */
    override fun prefetch(assetIds: List<String>, size: ImageSize) {
        when (size) {
            ImageSize.Full -> fullImageJobs.prefetch(assetIds)
            is ImageSize.Thumbnail -> {
                thumbnailJobs.prefetch(assetIds.map { ThumbnailRequest(it, size.sidePx) })
                loadScope.launch {
                    val assets = assetIds.mapNotNull(::phAssetFor)
                    if (assets.isEmpty()) return@launch
                    val side = size.sidePx.toDouble()
                    imageManager.startCachingImagesForAssets(
                        assets = assets,
                        targetSize = CGSizeMake(side, side),
                        contentMode = PHImageContentModeAspectFit,
                        options = highQualityOptions(),
                    )
                }
            }
        }
    }

    private fun cacheFor(size: ImageSize): MemoryImageCache =
        if (size == ImageSize.Full) fullImageCache else thumbnailCache

    /**
     * Memory, then disk, then Photos. The disk hop is what a cold launch lives on: a Photos
     * request hits the asset store and re-renders the image, where the cached copy is a file read.
     */
    private suspend fun loadThumbnailCached(request: ThumbnailRequest): ImageBitmap? {
        val key = ImageSize.Thumbnail(request.sidePx).cacheKey(request.assetId)
        thumbnailCache.get(key)?.let { return it }
        diskCache.read(key)?.decodeToImageBitmap()?.let {
            thumbnailCache.put(key, it)
            return it
        }
        val side = request.sidePx.toDouble()
        return withContext(Dispatchers.Default) {
            val image = requestImage(request.assetId, CGSizeMake(side, side))
                ?: return@withContext null
            val bitmap = image.toImageBitmap() ?: return@withContext null
            thumbnailCache.put(key, bitmap)
            // Fire and forget: the caller is waiting to paint, not to persist.
            image.toThumbnailBytes()?.let { bytes -> loadScope.launch { diskCache.write(key, bytes) } }
            bitmap
        }
    }

    private suspend fun loadFullImageCached(assetId: String): ImageBitmap? {
        val key = ImageSize.Full.cacheKey(assetId)
        fullImageCache.get(key)?.let { return it }
        return withContext(Dispatchers.Default) {
            val image = requestImage(assetId, screenSizePx) ?: return@withContext null
            val bitmap = image.toImageBitmap() ?: return@withContext null
            fullImageCache.put(key, bitmap)
            bitmap
        }
    }

    /**
     * Every step runs off the main dispatcher. Fetching the asset and requesting the image are
     * synchronous Photos calls that hit disk, so leaving them on the caller's context (a
     * composable's `LaunchedEffect`, which is the main thread) freezes the UI.
     *
     * Photos calls the handler more than once for a single request when it has a degraded
     * placeholder to offer before the real thing, and resuming a continuation twice is a crash,
     * hence the guard.
     */
    private suspend fun requestImage(assetId: String, targetSize: CValue<CGSize>): UIImage? {
        val asset = phAssetFor(assetId) ?: return null
        return suspendCancellableCoroutine { continuation ->
            val resumed = AtomicInt(0)
            imageManager.requestImageForAsset(
                asset = asset,
                targetSize = targetSize,
                contentMode = PHImageContentModeAspectFit,
                options = highQualityOptions(),
            ) { image, _ ->
                if (resumed.compareAndSet(0, 1)) continuation.resume(image)
            }
        }
    }

    /**
     * Exact resizing: without it Photos is free to hand back whatever cached representation is
     * nearest, which is often a small one, and that is what makes photos look pixelated.
     */
    private fun highQualityOptions(): PHImageRequestOptions = PHImageRequestOptions().apply {
        deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
        resizeMode = PHImageRequestOptionsResizeModeExact
        networkAccessAllowed = true
    }
}
