package pikto.images

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** Encoded thumbnails in the app cache directory, trimmed least-recently-used to a byte budget. */
internal class AndroidPhotoDiskCache(
    context: Context,
    private val budgetBytes: Long,
) : PhotoDiskCache {

    private val directory = File(context.cacheDir, DIRECTORY_NAME)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    init {
        // Once per process, off the launch path: the budget is a ceiling, not something that has
        // to be true before the first read.
        scope.launch { trim() }
    }

    override suspend fun read(key: String): ByteArray? = withContext(Dispatchers.IO) {
        runCatching {
            val file = fileFor(key)
            if (!file.exists()) return@runCatching null
            // Doubles as the LRU stamp: trim evicts by last modified.
            file.setLastModified(System.currentTimeMillis())
            file.readBytes()
        }.getOrNull()
    }

    /**
     * Written to a temporary file and renamed, so a read landing mid-write sees either the old
     * entry or the new one, never a half-written file it would have to decode and fail on.
     */
    override suspend fun write(key: String, bytes: ByteArray) {
        withContext(Dispatchers.IO) {
            try {
                directory.mkdirs()
                val temporary = File(directory, "${diskFileName(key)}.tmp")
                temporary.writeBytes(bytes)
                if (!temporary.renameTo(fileFor(key))) temporary.delete()
            } catch (_: Exception) {
                // Best effort. A full disk just means the next launch decodes this cover again.
            }
        }
    }

    private fun fileFor(key: String): File = File(directory, diskFileName(key))

    private fun trim() {
        runCatching {
            val files = directory.listFiles()?.sortedBy(File::lastModified) ?: return
            var total = files.sumOf(File::length)
            for (file in files) {
                if (total <= budgetBytes) return
                total -= file.length()
                file.delete()
            }
        }
    }

    private companion object {
        const val DIRECTORY_NAME = "pikto-thumbnails"
    }
}
