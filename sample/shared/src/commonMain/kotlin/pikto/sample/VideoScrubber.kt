package pikto.sample

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pikto.video.VideoPlaybackState

/**
 * The playback control: play/pause, elapsed, bar, time left, all on one line.
 *
 * The bar is a hairline that thickens under a finger, with no thumb riding on it. A knob is the
 * part that reads as a settings control rather than a piece of a video player, and it is also the
 * part that has to be hit exactly. Here the whole strip is the target and the fill's own end marks
 * the position.
 *
 * Scrubbing is three steps against [VideoPlaybackState] on purpose: between `startScrub` and
 * `endScrub` the player's own position reports are ignored, so a report that was already in flight
 * when the finger landed cannot drag the bar out from under it.
 */
@Composable
fun VideoScrubber(
    state: VideoPlaybackState,
    modifier: Modifier = Modifier,
) {
    val duration = state.durationMillis
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(ChipShape)
            .background(Color.Black.copy(alpha = 0.42f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (state.isPlaying) "❚❚" else "▶",
            style = MaterialTheme.typography.labelMedium,
            color = Color.White,
            modifier = Modifier
                .clip(ChipShape)
                .clickable(role = Role.Button, onClick = state::togglePlayPause)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        TimeLabel(
            text = formatDuration(state.positionMillis),
            alpha = 1f,
            textAlign = TextAlign.End,
        )
        ScrubTrack(
            fraction = if (duration > 0L) state.positionMillis.toFloat() / duration else 0f,
            enabled = duration > 0L,
            isScrubbing = state.isScrubbing,
            onScrub = { fraction ->
                if (!state.isScrubbing) state.startScrub()
                state.scrubTo((fraction * duration).toLong())
            },
            onScrubEnd = state::endScrub,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
        )
        // Counting down rather than up: the number that matters mid-clip is how much is left.
        TimeLabel(
            text = "-" + formatDuration((duration - state.positionMillis).coerceAtLeast(0L)),
            alpha = 0.6f,
            textAlign = TextAlign.Start,
        )
    }
}

/** Reserves its width so the bar doesn't twitch every time a digit rolls over. */
@Composable
private fun TimeLabel(text: String, alpha: Float, textAlign: TextAlign) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelSmall,
        color = Color.White.copy(alpha = alpha),
        maxLines = 1,
        textAlign = textAlign,
        modifier = Modifier.widthIn(min = TIME_LABEL_MIN_WIDTH),
    )
}

@Composable
private fun ScrubTrack(
    fraction: Float,
    enabled: Boolean,
    isScrubbing: Boolean,
    onScrub: (Float) -> Unit,
    onScrubEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // The only feedback a thumbless bar can give that the finger has been picked up: it swells,
    // holds while you drag, and settles back when you let go.
    val thickness by animateFloatAsState(
        targetValue = if (isScrubbing) SCRUBBING_THICKNESS_DP else RESTING_THICKNESS_DP,
        animationSpec = tween(durationMillis = 150),
    )
    Canvas(
        modifier = modifier
            .height(TOUCH_HEIGHT)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset -> onScrub(offset.x / size.width) },
                    onDragEnd = onScrubEnd,
                    onDragCancel = onScrubEnd,
                    onHorizontalDrag = { change, _ ->
                        // Claimed so the pager underneath can't read the same drag as a page swipe.
                        change.consume()
                        onScrub(change.position.x / size.width)
                    },
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    onScrub(offset.x / size.width)
                    onScrubEnd()
                }
            },
    ) {
        val stroke = thickness.dp.toPx()
        val y = size.height / 2f
        // Inset by half the stroke so the round caps end flush with the bar rather than past it.
        val start = Offset(stroke / 2f, y)
        val end = Offset(size.width - stroke / 2f, y)
        drawLine(
            color = Color.White.copy(alpha = 0.28f),
            start = start,
            end = end,
            strokeWidth = stroke,
            cap = StrokeCap.Round,
        )
        if (fraction > 0f) {
            drawLine(
                color = Color.White,
                start = start,
                end = Offset(start.x + (end.x - start.x) * fraction.coerceIn(0f, 1f), y),
                strokeWidth = stroke,
                cap = StrokeCap.Round,
            )
        }
    }
}

private val ChipShape = RoundedCornerShape(percent = 50)
private const val RESTING_THICKNESS_DP = 3f
private const val SCRUBBING_THICKNESS_DP = 7f
private val TOUCH_HEIGHT = 28.dp
private val TIME_LABEL_MIN_WIDTH = 34.dp
