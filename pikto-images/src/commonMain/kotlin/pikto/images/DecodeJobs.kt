package pikto.images

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit

/**
 * Keeps one decode in flight per key, shared by the prefetcher and by whichever composable is
 * asking for that image right now.
 *
 * Two things stall a scrolling list without this. Every scroll re-issues the prefetch window, and
 * if that cancels the previous one, the decode of the very item about to appear is thrown away and
 * restarted from scratch. Then the item itself starts a *second* decode of the same asset in
 * parallel with the prefetcher. Here a decode starts once, is never cancelled by a later prefetch
 * window, and a caller that goes away (the composable left the composition) does not take the
 * decode down with it: the result still lands in the cache for the item that is about to need it.
 *
 * @param maxParallel Bounds how many decodes run at once, so a prefetch window cannot starve the
 *   decode of the image actually on screen.
 */
internal class DecodeJobs<K>(
    private val scope: CoroutineScope,
    maxParallel: Int,
    private val decode: suspend (key: K) -> ImageBitmap?,
) {
    private val mutex = Mutex()
    private val inFlight = mutableMapOf<K, Deferred<ImageBitmap?>>()
    private val decodeSlots = Semaphore(maxParallel)

    suspend fun load(key: K): ImageBitmap? = decodeOf(key).await()

    /** Warms [keys] in order of urgency, nearest first. */
    fun prefetch(keys: List<K>) {
        scope.launch { keys.forEach { decodeOf(it) } }
    }

    private suspend fun decodeOf(key: K): Deferred<ImageBitmap?> = mutex.withLock {
        inFlight.getOrPut(key) {
            scope.async {
                try {
                    decodeSlots.withPermit { decode(key) }
                } finally {
                    mutex.withLock { inFlight.remove(key) }
                }
            }
        }
    }
}
