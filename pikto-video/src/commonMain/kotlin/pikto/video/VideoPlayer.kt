package pikto.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState

/** How a clip fills the space [VideoPlayer] is given. Neither option ever stretches the video. */
public enum class VideoScale {
    /** Letterbox: the whole clip is visible, with empty space on two sides. */
    FIT,

    /** Fill: the clip covers the whole player and the overflow is cropped. */
    CROP,
}

/**
 * Playback position and controls, shared between [VideoPlayer] and whatever is driving it.
 *
 * Hoisted out of the player because a scrubber usually does not live on top of the video. It sits
 * in the chrome around it, or somewhere else entirely, and both need to agree about where playback
 * is. Create one with [rememberVideoPlaybackState].
 *
 * Scrubbing is a three-step gesture on purpose. Between [startScrub] and [endScrub] the player's
 * own position reports are ignored, so the thumb cannot be dragged out from under the user's
 * finger by a report that was already in flight when they grabbed it.
 */
@Stable
public class VideoPlaybackState {

    /** Where playback is, or where the user has dragged the thumb to while scrubbing. */
    public var positionMillis: Long by mutableStateOf(0L)
        internal set

    /** Zero until the player has worked out how long the clip is, which takes a moment. */
    public var durationMillis: Long by mutableStateOf(0L)
        internal set

    public var isScrubbing: Boolean by mutableStateOf(false)
        private set

    public var isPlaying: Boolean by mutableStateOf(true)
        private set

    /**
     * False until the player has actually put a frame on screen.
     *
     * Keep a still of the same asset underneath the player until this flips. Swapping to the
     * player surface before it has anything to show is what makes a clip flash black on the way
     * in, and it is the single most common bug in a photo viewer that handles video.
     */
    public var hasRenderedFirstFrame: Boolean by mutableStateOf(false)
        internal set

    private var pendingSeekMillis: Long? by mutableStateOf(null)

    public fun play() {
        isPlaying = true
    }

    public fun pause() {
        isPlaying = false
    }

    public fun togglePlayPause() {
        isPlaying = !isPlaying
    }

    /** Call when the user's finger lands on the scrubber. */
    public fun startScrub() {
        isScrubbing = true
    }

    /** Call as the finger moves. Updates the reported position without seeking the player. */
    public fun scrubTo(millis: Long) {
        positionMillis = millis.coerceIn(0L, durationMillis)
    }

    /** Call when the finger lifts. This is what actually seeks. */
    public fun endScrub() {
        isScrubbing = false
        pendingSeekMillis = positionMillis
    }

    /** Seeks without a gesture, for a "skip 10 seconds" button or restoring a saved position. */
    public fun seekTo(millis: Long) {
        val target = millis.coerceAtLeast(0L)
        positionMillis = target
        pendingSeekMillis = target
    }

    internal fun report(positionMillis: Long, durationMillis: Long) {
        if (durationMillis > 0L) this.durationMillis = durationMillis
        if (!isScrubbing) this.positionMillis = positionMillis
    }

    internal fun consumePendingSeek(): Long? = pendingSeekMillis.also { pendingSeekMillis = null }
}

/**
 * A [VideoPlaybackState] reset whenever [assetId] changes, which is almost always what you want:
 * a new clip starts at zero, playing, with no frame rendered yet.
 */
@Composable
public fun rememberVideoPlaybackState(assetId: String?): VideoPlaybackState =
    remember(assetId) { VideoPlaybackState() }

/**
 * Plays the video asset [assetId] from the device photo library.
 *
 * ```kotlin
 * val state = rememberVideoPlaybackState(asset.id)
 * VideoPlayer(
 *     assetId = asset.id,
 *     state = state,
 *     isVisible = state.hasRenderedFirstFrame,
 *     modifier = Modifier.fillMaxSize(),
 * )
 * ```
 *
 * ### Keep it in the tree
 * A `null` [assetId] means "nothing to play right now", and the player is kept alive and idle
 * rather than torn down. Building and releasing a player is main-thread work measured in hundreds
 * of milliseconds on both platforms, so doing it per clip freezes whatever is on screen. Keep this
 * composable mounted for as long as the screen lives and swap the id underneath it.
 *
 * ### Preloading
 * [nextAssetId] is opened and buffered while the user is still watching the current clip, so
 * reaching it is a hand-over rather than a cold start. In a feed or a swipe deck, pass the clip
 * one position ahead. It costs a file handle and a few seconds of buffer.
 *
 * ### Visibility is not a modifier
 * [isVisible] is applied to the platform view itself: `visibility` on Android, `hidden` on iOS.
 * This surface is a native view that composites itself, on its own thread, and a `graphicsLayer`
 * alpha or clip around it is a statement about the Compose tree that the surface is under no
 * obligation to honour. Hiding it has to be said in a language the view speaks, or the last
 * decoded frame stays on screen. [scale] is on the platform view for the same reason, which is why
 * there is no `ContentScale` parameter here.
 *
 * The usual thing to pass is `state.hasRenderedFirstFrame`, with a still of the same asset drawn
 * underneath.
 *
 * @param volume Between 0 and 1. Audio focus is negotiated with the rest of the device either way.
 * @param repeat Whether the clip loops. Short clips being reviewed usually should; a feature-length
 *   video should not.
 */
@Composable
public expect fun VideoPlayer(
    assetId: String?,
    state: VideoPlaybackState,
    isVisible: Boolean,
    modifier: Modifier = Modifier,
    nextAssetId: String? = null,
    scale: VideoScale = VideoScale.FIT,
    volume: Float = 1f,
    repeat: Boolean = true,
)

/** Sound keeps playing over the app switcher otherwise, which reads as the app misbehaving. */
@Composable
internal fun isForeground(): Boolean {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    return lifecycleState.isAtLeast(Lifecycle.State.RESUMED)
}

/**
 * Fast enough that a scrubber tracks the clip without visible lag, slow enough that polling costs
 * nothing. Both platforms make position an ordinary property read, so this is cheaper than either
 * platform's own observer machinery.
 */
internal const val PROGRESS_POLL_MILLIS = 120L
