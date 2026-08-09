package pikto.images

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.DefaultAlpha
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.ContentScale

/**
 * The loader that [PhotoImage] and [rememberPhotoImage] read from.
 *
 * Defaults to a process-wide loader built with [ImageLoaderConfig] defaults, created the first
 * time anything actually asks for a photo, so the common case needs no setup at all. Override it
 * with [ProvidePhotoImageLoader] when you want your own budgets, your own disk cache, or a fake in
 * a test.
 */
public val LocalPhotoImageLoader: ProvidableCompositionLocal<PhotoImageLoader> =
    staticCompositionLocalOf { DefaultPhotoImageLoader }

private val DefaultPhotoImageLoader: PhotoImageLoader by lazy { PhotoImageLoader() }

/** Puts [loader] in scope for every [PhotoImage] and [rememberPhotoImage] inside [content]. */
@Composable
public fun ProvidePhotoImageLoader(
    loader: PhotoImageLoader,
    content: @Composable () -> Unit,
) {
    androidx.compose.runtime.CompositionLocalProvider(
        LocalPhotoImageLoader provides loader,
        content = content,
    )
}

/**
 * The decoded bitmap for [assetId], or `null` while it is still being decoded or if it could not
 * be decoded at all.
 *
 * Recomposes once when the image lands. An image already in memory is returned on the first
 * composition with no intermediate `null`, so scrolling back over a warm row does not flash empty.
 *
 * A `null` [assetId] means "nothing to show" and produces `null` without starting any work, which
 * is what makes it safe to call unconditionally in a list.
 */
@Composable
public fun rememberPhotoImage(
    assetId: String?,
    size: ImageSize,
    loader: PhotoImageLoader = LocalPhotoImageLoader.current,
): ImageBitmap? {
    var image by remember(assetId, size, loader) {
        mutableStateOf(assetId?.let { loader.peek(it, size) })
    }
    LaunchedEffect(assetId, size, loader) {
        if (assetId == null || image != null) return@LaunchedEffect
        image = loader.load(assetId, size)
    }
    return image
}

/**
 * Draws the photo behind [assetId], showing [placeholder] until it is ready.
 *
 * ```kotlin
 * PhotoImage(
 *     assetId = asset.id,
 *     size = ImageSize.Thumbnail(320),
 *     contentDescription = null,
 *     modifier = Modifier.aspectRatio(1f),
 *     placeholder = { Box(Modifier.fillMaxSize().background(Color.LightGray)) },
 * )
 * ```
 *
 * @param contentDescription What a screen reader should say. Pass `null` only when the photo is
 *   decorative or the surrounding row already describes it.
 * @param placeholder Drawn in the same box while there is nothing to show. Give it a size, or the
 *   layout will collapse before the first photo arrives.
 */
@Composable
public fun PhotoImage(
    assetId: String?,
    size: ImageSize,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
    alignment: Alignment = Alignment.Center,
    alpha: Float = DefaultAlpha,
    colorFilter: ColorFilter? = null,
    filterQuality: FilterQuality = DrawScope.DefaultFilterQuality,
    loader: PhotoImageLoader = LocalPhotoImageLoader.current,
    placeholder: @Composable BoxScope.() -> Unit = {},
) {
    val image = rememberPhotoImage(assetId, size, loader)
    Box(modifier = modifier, contentAlignment = alignment) {
        if (image == null) {
            placeholder()
        } else {
            Image(
                bitmap = image,
                contentDescription = contentDescription,
                modifier = Modifier.fillMaxSize(),
                alignment = alignment,
                contentScale = contentScale,
                alpha = alpha,
                colorFilter = colorFilter,
                filterQuality = filterQuality,
            )
        }
    }
}

/**
 * Warms [assetIds] at [size] whenever the list changes, so they are decoded before anything asks
 * for them.
 *
 * Put the ids just outside the visible window in here: the next screenful of a grid, the two
 * cards behind the top of a deck. Re-issuing an overlapping window is cheap, so recomputing it on
 * every scroll is the intended use.
 */
@Composable
public fun PrefetchPhotos(
    assetIds: List<String>,
    size: ImageSize,
    loader: PhotoImageLoader = LocalPhotoImageLoader.current,
) {
    LaunchedEffect(assetIds, size, loader) {
        loader.prefetch(assetIds, size)
    }
}
