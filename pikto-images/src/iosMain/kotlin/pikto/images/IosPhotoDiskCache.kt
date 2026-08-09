package pikto.images

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import platform.Foundation.NSCachesDirectory
import platform.Foundation.NSData
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSFileSize
import platform.Foundation.NSNumber
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.writeToFile

/** Encoded thumbnails in the caches directory, trimmed least-recently-used to a byte budget. */
@OptIn(ExperimentalForeignApi::class)
internal class IosPhotoDiskCache(private val budgetBytes: Long) : PhotoDiskCache {

    private val fileManager = NSFileManager.defaultManager
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val directory: String by lazy {
        val caches = NSSearchPathForDirectoriesInDomains(
            NSCachesDirectory,
            NSUserDomainMask,
            true,
        ).firstOrNull() as? String ?: ""
        "$caches/$DIRECTORY_NAME".also { fileManager.createDirectoryAtPath(it, true, null, null) }
    }

    init {
        // Once per process, off the launch path: the budget is a ceiling, not something that has
        // to be true before the first read.
        scope.launch { trim() }
    }

    override suspend fun read(key: String): ByteArray? = withContext(Dispatchers.Default) {
        val path = pathFor(key)
        val data = NSData.dataWithContentsOfFile(path) ?: return@withContext null
        // Doubles as the LRU stamp: trim evicts by modification date.
        fileManager.setAttributes(mapOf(NSFileModificationDate to NSDate()), path, null)
        data.toByteArray()
    }

    override suspend fun write(key: String, bytes: ByteArray) {
        withContext(Dispatchers.Default) {
            // Atomic, so a read landing mid-write sees either the old entry or the new one, never
            // a half-written file it would have to decode and fail on.
            bytes.toNSData().writeToFile(pathFor(key), atomically = true)
        }
    }

    private fun pathFor(key: String): String = "$directory/${diskFileName(key)}"

    private fun trim() {
        val names = fileManager.contentsOfDirectoryAtPath(directory, null)
            ?.filterIsInstance<String>()
            ?: return
        val entries = names.mapNotNull { name ->
            val path = "$directory/$name"
            val attributes = fileManager.attributesOfItemAtPath(path, null) ?: return@mapNotNull null
            val size = (attributes[NSFileSize] as? NSNumber)?.longLongValue ?: return@mapNotNull null
            val modified =
                (attributes[NSFileModificationDate] as? NSDate)?.timeIntervalSince1970 ?: 0.0
            Entry(path = path, sizeBytes = size, modifiedAt = modified)
        }.sortedBy(Entry::modifiedAt)

        var total = entries.sumOf(Entry::sizeBytes)
        for (entry in entries) {
            if (total <= budgetBytes) return
            total -= entry.sizeBytes
            fileManager.removeItemAtPath(entry.path, null)
        }
    }

    private data class Entry(val path: String, val sizeBytes: Long, val modifiedAt: Double)

    private companion object {
        const val DIRECTORY_NAME = "pikto-thumbnails"
    }
}
