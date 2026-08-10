@file:OptIn(ExperimentalForeignApi::class)

package pikto.video

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.UIKitInteropProperties
import androidx.compose.ui.viewinterop.UIKitView
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import pikto.phAssetFor
import platform.AVFAudio.AVAudioSession
import platform.AVFAudio.AVAudioSessionCategoryPlayback
import platform.AVFAudio.setActive
import platform.AVFoundation.AVLayerVideoGravityResizeAspect
import platform.AVFoundation.AVLayerVideoGravityResizeAspectFill
import platform.AVFoundation.AVPlayer
import platform.AVFoundation.AVPlayerItem
import platform.AVFoundation.AVPlayerItemStatusReadyToPlay
import platform.AVFoundation.AVPlayerLayer
import platform.AVFoundation.currentItem
import platform.AVFoundation.currentTime
import platform.AVFoundation.duration
import platform.AVFoundation.pause
import platform.AVFoundation.play
import platform.AVFoundation.replaceCurrentItemWithPlayerItem
import platform.AVFoundation.seekToTime
import platform.AVFoundation.volume
import platform.CoreGraphics.CGRectZero
import platform.CoreMedia.CMTime
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSNotificationCenter
import platform.Photos.PHImageManager
import platform.Photos.PHVideoRequestOptions
import platform.QuartzCore.CATransaction
import platform.UIKit.UIColor
import platform.UIKit.UIView
import platform.darwin.NSObjectProtocol
import kotlin.coroutines.resume

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
    val shouldPlay = assetId != null && isForeground() && state.isPlaying

    /**
     * Deliberately unkeyed: one player for the whole composition, swapping items rather than being
     * rebuilt per clip. Handing a *new* player to the layer re-creates its surface every time,
     * which makes a clip appear, vanish and appear again on every change.
     */
    val player = remember { AVPlayer() }
    val container = remember { PlayerContainerView() }
    val latestState by rememberUpdatedState(state)
    val latestShouldPlay by rememberUpdatedState(shouldPlay)

    /*
     * Belt and braces with the interop `update` hook below. Whether the clip is on screen comes
     * down entirely to this one property, and this runs after every composition that changes it,
     * without depending on how the interop layer chooses to schedule its updates.
     */
    SideEffect {
        container.setHidden(!isVisible)
        container.setScale(scale)
        player.volume = volume.coerceIn(0f, 1f)
    }

    DisposableEffect(player, container) {
        container.attach(player)
        onDispose {
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
            container.attach(null)
        }
    }

    /**
     * Drops the outgoing clip synchronously, before anything is fetched for the new one. Fetching
     * an item out of the photo library takes long enough that leaving the old one in place means
     * the clip the caller has just moved off carries on playing over its replacement, and the
     * layer stays ready for display, so nothing underneath comes back up to cover it either.
     *
     * The loop observer is re-registered here too: the notification is posted by the item, not by
     * the player.
     */
    DisposableEffect(player, assetId, repeat) {
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        latestState.hasRenderedFirstFrame = false
        val loopObserver = if (repeat) observeLoop(player) else null
        onDispose {
            loopObserver?.let(NSNotificationCenter.defaultCenter::removeObserver)
        }
    }

    /**
     * Once per composition, and off the main thread: activating an audio session negotiates with
     * whatever else is playing on the device and blocks for as long as that takes. Doing it on
     * every clip, on the thread that draws, is enough on its own to freeze the UI.
     */
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) { configureAudioSession() }
    }

    /**
     * The clip behind this one, fetched out of the photo library and left ready. That fetch is the
     * slow half of starting a video here: it goes through [PHImageManager] and can take a beat.
     */
    val queued = remember { QueuedItem() }

    LaunchedEffect(nextAssetId) {
        if (nextAssetId == null || queued.id == nextAssetId) return@LaunchedEffect
        queued.put(nextAssetId, playerItemFor(nextAssetId) ?: return@LaunchedEffect)
    }

    LaunchedEffect(player, assetId) {
        if (assetId == null) return@LaunchedEffect
        val item = queued.take(assetId) ?: playerItemFor(assetId) ?: return@LaunchedEffect
        player.replaceCurrentItemWithPlayerItem(item)
        // The play/pause effect below is keyed to the flag, not the asset, so it does not re-run
        // when the clip changes. This is what actually starts the one that just landed.
        if (latestShouldPlay) player.play()
    }

    LaunchedEffect(player, shouldPlay) {
        if (shouldPlay) player.play() else player.pause()
    }

    LaunchedEffect(player, state) {
        while (true) {
            state.consumePendingSeek()?.let { millis ->
                player.seekToTime(CMTimeMakeWithSeconds(millis / 1000.0, TIMESCALE))
            }
            state.report(
                positionMillis = player.currentTime().millisOrZero(),
                durationMillis = player.currentItem?.duration?.millisOrZero() ?: 0L,
            )
            // The layer's own answer to "is there a frame on screen yet".
            //
            // The item's status is accepted as well, because callers keep the layer hidden until
            // this flips and a hidden layer is under no obligation to render: taking the layer's
            // word as the only answer is a deadlock, where the clip waits to be shown and the
            // showing waits on the clip. Readiness is reported by the item whether or not anyone
            // is looking at it.
            val item = player.currentItem
            if (!state.hasRenderedFirstFrame && item != null &&
                (container.isReadyForDisplay() || item.status == AVPlayerItemStatusReadyToPlay)
            ) {
                state.hasRenderedFirstFrame = true
            }
            delay(PROGRESS_POLL_MILLIS)
        }
    }

    UIKitView(
        modifier = modifier,
        factory = { container },
        // An interop view is a real UIView sitting above the Compose canvas: it ignores the alpha
        // and the transforms of the layer it nominally lives in, so this is the only instruction
        // about being on screen that it actually takes.
        update = { it.setHidden(!isVisible) },
        // The player draws; it does not handle gestures. Compose wraps every interop view in a
        // container of its own, and that wrapper is what claims touches — clearing
        // `userInteractionEnabled` on the view inside it changes nothing. Left interactive, the
        // wrapper swallows every touch that lands on the clip, so a pager or a swipe deck
        // underneath silently stops responding the moment a video is on screen.
        properties = UIKitInteropProperties(interactionMode = null),
    )
}

/**
 * One ready-to-play item, held for the clip behind the one playing. An item can only be handed to
 * a player once, so taking it clears the slot: whatever asks for it next gets a fresh fetch.
 */
private class QueuedItem {
    var id: String? = null
        private set

    private var item: AVPlayerItem? = null

    fun put(id: String, item: AVPlayerItem) {
        this.id = id
        this.item = item
    }

    fun take(id: String): AVPlayerItem? {
        if (this.id != id) return null
        return item.also {
            this.id = null
            this.item = null
        }
    }
}

/**
 * Compose owns this view's frame, so the layer is resized from [layoutSubviews] rather than being
 * given a fixed one, and without CoreAnimation's implicit animation, which would otherwise make
 * the video visibly slide into place on every layout pass.
 */
private class PlayerContainerView : UIView(frame = CGRectZero.readValue()) {

    private val playerLayer = AVPlayerLayer().apply {
        videoGravity = AVLayerVideoGravityResizeAspect
    }

    init {
        layer.addSublayer(playerLayer)
        // An interop view is a hole cut in the Compose canvas, so whatever Compose drew underneath
        // is not what shows around a letterboxed clip: the window background is, and that is white.
        // Black is what every video surface on both platforms fills its bars with.
        backgroundColor = UIColor.blackColor
        // Starts out of the way: nothing is shown until a clip is ready, so an empty layer cannot
        // flash over whatever is underneath on the way in.
        setHidden(true)
    }

    fun attach(player: AVPlayer?) {
        playerLayer.player = player
    }

    /**
     * Gravity, not a frame change: the layer keeps filling the view either way, and only how it
     * scales the clip inside itself changes. Set without an implicit animation for the same reason
     * the frame is: CoreAnimation would otherwise cross-fade the video as it resizes.
     */
    fun setScale(scale: VideoScale) {
        val gravity = when (scale) {
            VideoScale.CROP -> AVLayerVideoGravityResizeAspectFill
            VideoScale.FIT -> AVLayerVideoGravityResizeAspect
        }
        if (playerLayer.videoGravity == gravity) return
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.videoGravity = gravity
        CATransaction.commit()
    }

    fun isReadyForDisplay(): Boolean = playerLayer.readyForDisplay

    override fun layoutSubviews() {
        super.layoutSubviews()
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        playerLayer.setFrame(bounds)
        CATransaction.commit()
    }
}

private fun observeLoop(player: AVPlayer): NSObjectProtocol =
    NSNotificationCenter.defaultCenter.addObserverForName(
        name = AV_PLAYER_ITEM_DID_PLAY_TO_END,
        `object` = null,
        queue = null,
    ) { notification ->
        if (notification?.`object` !== player.currentItem) return@addObserverForName
        player.seekToTime(CMTimeMakeWithSeconds(0.0, TIMESCALE))
        player.play()
    }

private suspend fun playerItemFor(assetId: String): AVPlayerItem? {
    val asset = phAssetFor(assetId) ?: return null
    val options = PHVideoRequestOptions().apply { networkAccessAllowed = true }
    return suspendCancellableCoroutine { continuation ->
        PHImageManager.defaultManager().requestPlayerItemForVideo(
            asset = asset,
            options = options,
            resultHandler = { item, _ -> continuation.resume(item) },
        )
    }
}

/** Playback category, so a clip is audible even with the ringer switch flipped to silent. */
private fun configureAudioSession() {
    val session = AVAudioSession.sharedInstance()
    session.setCategory(AVAudioSessionCategoryPlayback, null)
    session.setActive(true, null)
}

private fun CValue<CMTime>.millisOrZero(): Long {
    val seconds = CMTimeGetSeconds(this)
    return if (seconds.isNaN() || seconds < 0.0) 0L else (seconds * 1000).toLong()
}

private const val AV_PLAYER_ITEM_DID_PLAY_TO_END = "AVPlayerItemDidPlayToEndTimeNotification"
private const val TIMESCALE = 600
