package pikto

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlin.time.Instant

/** Replays a fixed script of updates, so the common-code folding can be tested off-device. */
internal class FakePhotoLibrary(
    private val updates: List<LibraryUpdate>,
    private val permission: PhotoPermission = PhotoPermission.GRANTED,
) : PhotoLibrary {

    var requestedPermission: Boolean = false
        private set

    var deleted: List<String> = emptyList()
        private set

    override suspend fun permissionStatus(): PhotoPermission = permission

    override suspend fun requestPermission(): PhotoPermission {
        requestedPermission = true
        return permission
    }

    override fun stream(query: MediaQuery): Flow<LibraryUpdate> = updates.asFlow()

    override suspend fun delete(ids: List<String>): DeleteResult {
        deleted = deleted + ids
        return DeleteResult.Deleted
    }
}

internal fun asset(
    id: String,
    createdAtMillis: Long = 0L,
    sizeBytes: Long? = null,
): PhotoAsset = PhotoAsset(
    id = id,
    createdAt = Instant.fromEpochMilliseconds(createdAtMillis),
    width = 100,
    height = 100,
    sizeBytes = sizeBytes,
)
