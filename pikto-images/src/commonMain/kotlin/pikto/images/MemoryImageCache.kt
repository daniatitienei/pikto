package pikto.images

import androidx.compose.ui.graphics.ImageBitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.concurrent.Volatile

/**
 * A least-recently-used bitmap cache measured in bytes rather than entries, because a thumbnail
 * and a full-screen image differ by three orders of magnitude and an entry count would either
 * starve one or blow the budget on the other.
 *
 * [peek] reads an immutable snapshot published on every write, so the non-suspending lookup that a
 * composable's first frame depends on never has to take the lock.
 */
internal class MemoryImageCache(private val maxBytes: Long) {

    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, ImageBitmap>()
    private var currentBytes = 0L

    @Volatile
    private var snapshot: Map<String, ImageBitmap> = emptyMap()

    fun peek(key: String): ImageBitmap? = snapshot[key]

    /** Also marks the entry as most recently used, which is why it is not just [peek]. */
    suspend fun get(key: String): ImageBitmap? = mutex.withLock {
        entries.remove(key)?.also { entries[key] = it }
    }

    suspend fun put(key: String, bitmap: ImageBitmap): Unit = mutex.withLock {
        removeEntry(key)
        entries[key] = bitmap
        currentBytes += bitmap.byteSize()
        evictUntilWithinBudget()
        snapshot = entries.toMap()
    }

    private fun removeEntry(key: String) {
        val removed = entries.remove(key) ?: return
        currentBytes -= removed.byteSize()
    }

    private fun evictUntilWithinBudget() {
        while (currentBytes > maxBytes && entries.isNotEmpty()) {
            removeEntry(entries.keys.first())
        }
    }

    private fun ImageBitmap.byteSize(): Long = width.toLong() * height * BYTES_PER_PIXEL

    private companion object {
        /** Both platforms hand back 8-bit RGBA for everything Pikto decodes. */
        const val BYTES_PER_PIXEL = 4
    }
}
