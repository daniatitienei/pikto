package pikto.sample

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pikto.PhotoAsset
import pikto.images.ImageSize
import pikto.images.PhotoImage
import pikto.video.VideoPlayer
import pikto.video.VideoScale
import pikto.video.rememberVideoPlaybackState
import kotlin.math.absoluteValue

/**
 * Full-screen viewer: swipe between assets, play the clips, tap to hide the chrome.
 *
 * ### One player for the whole screen
 * The player is mounted once, outside the pager, and the asset id is swapped underneath it.
 * Building and releasing a player is main-thread work measured in hundreds of milliseconds on
 * both platforms, so a player per page would stutter the swipe it exists to serve.
 *
 * ### It only takes up space on a video page
 * The player is a real platform view, and on iOS that means a hole cut in the Compose canvas
 * rather than something drawn into it. A full-screen player left in the layout over a photo page
 * therefore covers the photo with the window background, whatever `isVisible` says. So the player
 * stays in the composition — no rebuild — but collapses to nothing when the page is not a video.
 *
 * ### A still underneath, always
 * Every page draws its own full-size photo, including video pages. The player is hidden until it
 * reports a first frame, so what the user sees during the hand-over is the poster rather than a
 * black rectangle. That flash is the most common bug in a viewer that handles video.
 */
@Composable
fun ViewerScreen(
    assets: List<PhotoAsset>,
    initialIndex: Int,
    onClose: () -> Unit,
    onDelete: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (assets.isEmpty()) return

    val pagerState = rememberPagerState(
        initialPage = initialIndex.coerceIn(0, assets.lastIndex),
        pageCount = { assets.size },
    )
    val current = assets.getOrNull(pagerState.currentPage) ?: return
    val next = assets.getOrNull(pagerState.currentPage + 1)

    val playback = rememberVideoPlaybackState(current.id.takeIf { current.isVideo })

    var chromeVisible by remember { mutableStateOf(true) }
    // Any move to another asset brings the chrome back, so the counter and the scrubber are never
    // hidden at the moment they change.
    LaunchedEffect(pagerState.currentPage) { chromeVisible = true }

    Box(modifier.fillMaxSize().background(Color.Black)) {
        HorizontalPager(
            state = pagerState,
            // Tap-to-toggle lives on the pager, not on the root: on the root it sits under the
            // chrome and eats the taps meant for the buttons drawn on top of it.
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures { chromeVisible = !chromeVisible }
                },
        ) { page ->
            val asset = assets[page]
            // Pages ease back and fade as they leave, so a swipe reads as depth rather than as a
            // strip of photos sliding past.
            val offset = ((page - pagerState.currentPage) +
                pagerState.currentPageOffsetFraction).absoluteValue
            PhotoImage(
                assetId = asset.id,
                size = ImageSize.Full,
                contentDescription = if (asset.isVideo) "Video" else "Photo",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        val scale = lerp(1f, 0.86f, offset.coerceIn(0f, 1f))
                        scaleX = scale
                        scaleY = scale
                        alpha = lerp(1f, 0.4f, offset.coerceIn(0f, 1f))
                    },
                placeholder = {
                    // The thumbnail is already decoded and cached from the grid, so this paints
                    // immediately and the full-size decode swaps in behind it.
                    PhotoImage(
                        assetId = asset.id,
                        size = GridThumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize(),
                    )
                },
            )
        }

        // Visibility is applied to the platform view itself, not through the Compose tree, which
        // is why it is a parameter rather than a modifier. Settling the pager first keeps the
        // surface off screen while pages are sliding past it.
        val isSettled = !pagerState.isScrollInProgress
        VideoPlayer(
            assetId = current.id.takeIf { current.isVideo },
            state = playback,
            isVisible = current.isVideo && isSettled && playback.hasRenderedFirstFrame,
            modifier = if (current.isVideo) Modifier.fillMaxSize() else Modifier.size(0.dp),
            // Buffer the next clip while this one plays, so reaching it is a hand-over.
            nextAssetId = next?.id?.takeIf { next.isVideo },
            scale = VideoScale.FIT,
            repeat = true,
        )

        AnimatedVisibility(
            visible = chromeVisible,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { -it },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { -it },
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            TopChrome(
                asset = current,
                position = pagerState.currentPage + 1,
                total = assets.size,
                onClose = onClose,
                onDelete = { onDelete(current.id) },
            )
        }

        AnimatedVisibility(
            visible = chromeVisible && current.isVideo,
            enter = fadeIn(tween(180)) + slideInVertically(tween(220)) { it },
            exit = fadeOut(tween(140)) + slideOutVertically(tween(200)) { it },
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            VideoScrubber(
                state = playback,
                modifier = Modifier
                    .safeDrawingPadding()
                    .padding(horizontal = 12.dp, vertical = 12.dp),
            )
        }
    }
}

private fun lerp(start: Float, stop: Float, fraction: Float): Float =
    start + (stop - start) * fraction

@Composable
private fun TopChrome(
    asset: PhotoAsset,
    position: Int,
    total: Int,
    onClose: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black.copy(alpha = 0.42f))
            .safeDrawingPadding()
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(onClick = onClose) { Text("Close", color = Color.White) }
        Spacer(Modifier.weight(1f))
        // The single-asset delete. Same system confirmation sheet as the batch one in the grid.
        TextButton(onClick = onDelete) { Text("Delete", color = Color.White) }
        Spacer(Modifier.size(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(
                text = "$position of $total",
                style = MaterialTheme.typography.labelMedium,
                color = Color.White,
            )
            Text(
                text = buildString {
                    append("${asset.width}×${asset.height}")
                    asset.sizeBytes?.let { append(" · ${formatBytes(it)}") }
                },
                style = MaterialTheme.typography.labelSmall,
                color = Color.White.copy(alpha = 0.7f),
                textAlign = TextAlign.End,
            )
        }
        Spacer(Modifier.size(4.dp))
    }
}
