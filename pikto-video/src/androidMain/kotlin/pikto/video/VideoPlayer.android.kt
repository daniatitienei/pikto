package pikto.video

import android.view.Gravity
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import kotlinx.coroutines.delay
import pikto.photoAssetUri

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
public actual fun VideoPlayer(
    assetId: String?,
    state: VideoPlaybackState,
    isVisible: Boolean,
    modifier: Modifier,
    nextAssetId: String?,
    scale: VideoScale,
    volume: Float,
    repeat: Boolean,
) {
    val context = LocalContext.current
    val shouldPlay = assetId != null && isForeground() && state.isPlaying

    /**
     * Deliberately unkeyed: one player for the whole composition, swapping media items rather than
     * being rebuilt per clip. Handing a *new* player to the same view tears down and re-creates its
     * surface every time, which makes a clip appear, vanish and appear again on every change.
     */
    val player = remember {
        ExoPlayer.Builder(context).build().apply {
            // Ducking other audio through the standard focus flow is what makes an autoplaying
            // clip acceptable: whatever the user was listening to gets handled for us.
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(C.USAGE_MEDIA)
                    .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                    .build(),
                /* handleAudioFocus = */ true,
            )
            // Opens and buffers the queued clip while the user is still on this one.
            setPreloadConfiguration(ExoPlayer.PreloadConfiguration(PRELOAD_TARGET_US))
        }
    }

    /** Mirrors the player's playlist, so a change of clip knows whether it is already queued up. */
    val queued = remember { mutableListOf<String>() }

    /**
     * A [TextureView] rather than the `PlayerView` default of a `SurfaceView`. A player inside a
     * `graphicsLayer` that translates or scales it (any swipe, any shared-element transition) is
     * a case a `SurfaceView` cannot handle: it is a separate window that the layer cannot
     * transform, so it punches through and blinks for the length of the animation. A
     * [TextureView] composites like any other view, so it goes wherever the layout goes.
     */
    val textureView = remember { TextureView(context) }
    val frame = remember {
        AspectRatioFrameLayout(context).apply {
            resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
            addView(
                textureView,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
    }

    /**
     * The frame sizes *itself* to the clip's shape, smaller than the player when letterboxed and
     * larger when cropped to fill, so it needs a parent that centres it either way. Handed to the
     * interop layer directly it would be pinned to the top-left corner, which turns a cropped clip
     * into one whose bottom and right edges are the parts thrown away.
     */
    val stage = remember {
        FrameLayout(context).apply {
            clipChildren = true
            addView(
                frame,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    Gravity.CENTER,
                ),
            )
        }
    }

    val latestState by rememberUpdatedState(state)

    DisposableEffect(player, textureView) {
        val listener = object : Player.Listener {
            override fun onRenderedFirstFrame() {
                latestState.hasRenderedFirstFrame = true
            }

            /** Keeps the surface the same shape as the clip, so nothing is ever stretched. */
            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.height == 0) return
                frame.setAspectRatio(
                    videoSize.width * videoSize.pixelWidthHeightRatio / videoSize.height,
                )
            }
        }
        player.addListener(listener)
        player.setVideoTextureView(textureView)
        onDispose {
            player.removeListener(listener)
            player.clearVideoTextureView(textureView)
            player.release()
        }
    }

    LaunchedEffect(player, volume) {
        player.volume = volume.coerceIn(0f, 1f)
    }

    /**
     * `REPEAT_MODE_ONE` rather than `REPEAT_MODE_ALL`, so the queued clip stays queued: it is
     * there to be preloaded, not to start playing on its own when this one runs out.
     */
    LaunchedEffect(player, repeat) {
        player.repeatMode = if (repeat) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
    }

    /**
     * A null asset empties the player instead of releasing it. [ExoPlayer.release] joins the
     * playback thread and tears the codecs down inline, and a stall there freezes whatever was
     * last drawn, which is the clip that just went away.
     *
     * Reaching a clip that was already queued is a seek, not a load: the source is open and
     * buffered by then, so the first frame lands in a frame or two instead of after a cold open.
     */
    LaunchedEffect(player, assetId, nextAssetId) {
        if (assetId == null) {
            latestState.hasRenderedFirstFrame = false
            player.stop()
            player.clearMediaItems()
            queued.clear()
            return@LaunchedEffect
        }

        when (assetId) {
            queued.getOrNull(0) -> Unit // Already the one playing.
            queued.getOrNull(1) -> {
                latestState.hasRenderedFirstFrame = false
                // By index rather than seekToNextMediaItem: repeating the current item makes
                // "next" resolve back to itself, so playback would never move off this clip.
                player.seekTo(1, 0L)
                player.removeMediaItem(0)
                queued.removeAt(0)
            }
            else -> {
                latestState.hasRenderedFirstFrame = false
                player.setMediaItem(MediaItem.fromUri(photoAssetUri(assetId)))
                player.prepare()
                queued.clear()
                queued.add(assetId)
            }
        }

        // Re-queue the clip behind this one, dropping whatever was queued for a position the
        // caller has since moved off.
        if (queued.size > 1 && queued[1] != nextAssetId) {
            player.removeMediaItem(1)
            queued.removeAt(1)
        }
        if (nextAssetId != null && queued.size == 1) {
            player.addMediaItem(MediaItem.fromUri(photoAssetUri(nextAssetId)))
            queued.add(nextAssetId)
        }
    }

    LaunchedEffect(player, shouldPlay) {
        player.playWhenReady = shouldPlay
    }

    /**
     * Every seek normally flushes the decoder, which is a black frame per drag of the thumb.
     * Scrubbing mode keeps the pipeline warm for exactly this case.
     */
    LaunchedEffect(player, state.isScrubbing) {
        player.setScrubbingModeEnabled(state.isScrubbing)
    }

    LaunchedEffect(player, state) {
        while (true) {
            state.consumePendingSeek()?.let(player::seekTo)
            state.report(player.currentPosition, player.duration.coerceAtLeast(0L))
            // `onRenderedFirstFrame` above is the honest answer to "is there a frame on screen
            // yet", but it can only fire once this view is actually being drawn, and callers
            // typically keep the surface INVISIBLE until this very flag flips. A TextureView that
            // is never drawn never gets a SurfaceTexture to decode into, so waiting on that
            // callback alone is a deadlock: the clip waits to be shown and the showing waits on
            // the clip.
            //
            // The player's own readiness is reported whether or not anyone is looking at it, so it
            // breaks the tie: it is the same fallback the iOS side takes off the player item's status.
            if (!state.hasRenderedFirstFrame && player.playbackState == Player.STATE_READY) {
                state.hasRenderedFirstFrame = true
            }
            delay(PROGRESS_POLL_MILLIS)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { stage },
        update = {
            // INVISIBLE, not GONE: the view stays attached and keeps its surface, so the clip
            // carries on decoding into it and is ready the moment it is shown again. Only the
            // compositing stops.
            it.visibility = if (isVisible) View.VISIBLE else View.INVISIBLE
            frame.resizeMode = when (scale) {
                VideoScale.CROP -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
                VideoScale.FIT -> AspectRatioFrameLayout.RESIZE_MODE_FIT
            }
        },
    )
}

/** Enough of the queued clip to cover its own opening seconds. */
private const val PRELOAD_TARGET_US = 3_000_000L
