package pikto

import android.Manifest
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlin.time.Instant

/**
 * Creates a [PhotoLibrary] backed by MediaStore, reading through [context]'s content resolver.
 *
 * Prefer the no-argument [PhotoLibrary] factory in `commonMain` unless you have a reason to pick
 * the context yourself. Only the application context is retained, whatever you pass.
 */
public fun PhotoLibrary(context: Context): PhotoLibrary = AndroidPhotoLibrary(context)

internal class AndroidPhotoLibrary(context: Context) : PhotoLibrary {

    private val appContext = context.applicationContext
    private val resolver: ContentResolver get() = appContext.contentResolver

    override suspend fun permissionStatus(): PhotoPermission = when {
        hasFullReadPermission() -> PhotoPermission.GRANTED
        hasPartialReadPermission() -> PhotoPermission.LIMITED
        else -> PhotoPermission.NOT_DETERMINED
    }

    override suspend fun requestPermission(): PhotoPermission {
        ActivityBridge.requestPermissions(requiredPermissions())
        val status = permissionStatus()
        // The prompt has been shown and produced nothing, which is a refusal rather than the
        // "never asked" that the same set of ungranted permissions looked like a moment ago.
        return if (status == PhotoPermission.NOT_DETERMINED) PhotoPermission.DENIED else status
    }

    /**
     * Images and videos live in separate MediaStore tables, so both are queried and merged as they
     * are read, which is cheaper than sorting a combined list, and it means the first batch is correct
     * without either cursor having been walked to the end.
     *
     * Sizes ride along in the same cursor row on Android, so no [LibraryUpdate.Sizes] is ever
     * needed here.
     */
    override fun stream(query: MediaQuery): Flow<LibraryUpdate> = flow {
        if (query.mediaTypes.isEmpty()) return@flow

        val direction = if (query.sortOrder == SortOrder.NEWEST_FIRST) "DESC" else "ASC"
        val imageCursor = if (query.wantsImages) {
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                imageProjection(),
                null,
                null,
                "${MediaStore.Images.Media.DATE_TAKEN} $direction",
            )
        } else {
            null
        }
        val videoCursor = if (query.wantsVideos) {
            resolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                videoProjection(),
                null,
                null,
                "${MediaStore.Video.Media.DATE_TAKEN} $direction",
            )
        } else {
            null
        }

        // Folder membership rides along in the same cursors, one extra column each rather than a
        // second query, and is emitted once at the end so both platforms deliver it the same way.
        val albumNamesById = mutableMapOf<String, String>()
        val albumIdsByAssetId = mutableMapOf<String, List<String>>()

        var batch = mutableListOf<PhotoAsset>()
        var batchSize = query.batching.firstBatchSize
        try {
            var hasImage = imageCursor?.moveToFirst() == true
            var hasVideo = videoCursor?.moveToFirst() == true
            while (hasImage || hasVideo) {
                val takeImage = when {
                    !hasVideo -> true
                    !hasImage -> false
                    query.sortOrder == SortOrder.NEWEST_FIRST ->
                        imageCursor!!.takenAtMillis() >= videoCursor!!.takenAtMillis()
                    else -> imageCursor!!.takenAtMillis() <= videoCursor!!.takenAtMillis()
                }
                val cursor = if (takeImage) imageCursor!! else videoCursor!!
                val asset = if (takeImage) {
                    cursor.toImageAsset(query.includeSizes)
                } else {
                    cursor.toVideoAsset(query.includeSizes)
                }
                batch += asset
                if (query.includeAlbums) {
                    cursor.recordAlbum(asset.id, albumNamesById, albumIdsByAssetId)
                }
                if (takeImage) hasImage = cursor.moveToNext() else hasVideo = cursor.moveToNext()

                if (batch.size < batchSize) continue
                emit(LibraryUpdate.Assets(batch))
                batch = mutableListOf()
                batchSize = query.batching.nextSize(batchSize)
            }
        } finally {
            imageCursor?.close()
            videoCursor?.close()
        }
        if (batch.isNotEmpty()) emit(LibraryUpdate.Assets(batch))
        if (query.includeAlbums) {
            emit(LibraryUpdate.Albums(albumSnapshot(albumNamesById, albumIdsByAssetId)))
        }
    }.flowOn(Dispatchers.IO)

    override suspend fun delete(ids: List<String>): DeleteResult {
        if (ids.isEmpty()) return DeleteResult.Deleted
        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> trashWithConfirmation(ids)
            Build.VERSION.SDK_INT == Build.VERSION_CODES.Q -> deleteRecoverable(ids)
            else -> deleteDirectly(ids)
        }
    }

    private fun hasFullReadPermission(): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            return isGranted(Manifest.permission.READ_MEDIA_IMAGES) &&
                isGranted(Manifest.permission.READ_MEDIA_VIDEO)
        }
        @Suppress("DEPRECATION")
        return isGranted(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun hasPartialReadPermission(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return false
        return isGranted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)
    }

    private fun isGranted(permission: String): Boolean =
        ContextCompat.checkSelfPermission(appContext, permission) ==
            PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION")
    private fun requiredPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU -> arrayOf(
            Manifest.permission.READ_MEDIA_IMAGES,
            Manifest.permission.READ_MEDIA_VIDEO,
        )
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    private fun imageProjection(): Array<String> {
        val base = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DATE_TAKEN,
            MediaStore.Images.Media.DATE_ADDED,
            MediaStore.Images.Media.SIZE,
            MediaStore.Images.Media.WIDTH,
            MediaStore.Images.Media.HEIGHT,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
        )
        val pathColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Images.Media.RELATIVE_PATH
        } else {
            @Suppress("DEPRECATION")
            MediaStore.Images.Media.DATA
        }
        return base + pathColumn
    }

    private fun videoProjection(): Array<String> = arrayOf(
        MediaStore.Video.Media._ID,
        MediaStore.Video.Media.DATE_TAKEN,
        MediaStore.Video.Media.DATE_ADDED,
        MediaStore.Video.Media.SIZE,
        MediaStore.Video.Media.WIDTH,
        MediaStore.Video.Media.HEIGHT,
        MediaStore.Video.Media.DURATION,
        MediaStore.Video.Media.BUCKET_ID,
        MediaStore.Video.Media.BUCKET_DISPLAY_NAME,
    )

    private fun Cursor.toImageAsset(includeSizes: Boolean): PhotoAsset = PhotoAsset(
        id = longValue(MediaStore.Images.Media._ID).toString(),
        createdAt = Instant.fromEpochMilliseconds(takenAtMillis()),
        width = longValue(MediaStore.Images.Media.WIDTH).toInt(),
        height = longValue(MediaStore.Images.Media.HEIGHT).toInt(),
        mediaType = MediaType.IMAGE,
        sizeBytes = if (includeSizes) longValue(MediaStore.Images.Media.SIZE) else null,
        isScreenshot = isScreenshot(),
    )

    private fun Cursor.toVideoAsset(includeSizes: Boolean): PhotoAsset = PhotoAsset(
        id = videoAssetId(longValue(MediaStore.Video.Media._ID)),
        createdAt = Instant.fromEpochMilliseconds(takenAtMillis()),
        width = longValue(MediaStore.Video.Media.WIDTH).toInt(),
        height = longValue(MediaStore.Video.Media.HEIGHT).toInt(),
        mediaType = MediaType.VIDEO,
        sizeBytes = if (includeSizes) longValue(MediaStore.Video.Media.SIZE) else null,
        durationMillis = longValue(MediaStore.Video.Media.DURATION),
    )

    /** `DATE_TAKEN` is null for anything the camera did not produce, so `DATE_ADDED` backs it up. */
    private fun Cursor.takenAtMillis(): Long {
        val taken = longValue(MediaStore.Images.Media.DATE_TAKEN)
        if (taken > 0) return taken
        return longValue(MediaStore.Images.Media.DATE_ADDED) * 1000
    }

    private fun Cursor.isScreenshot(): Boolean {
        val bucket = stringValue(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
        val path = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            stringValue(MediaStore.Images.Media.RELATIVE_PATH)
        } else {
            @Suppress("DEPRECATION")
            stringValue(MediaStore.Images.Media.DATA)
        }
        return bucket.equals("Screenshots", ignoreCase = true) ||
            path.contains("screenshots", ignoreCase = true)
    }

    /**
     * A row's folder, read from whichever table it came from. `BUCKET_ID` is derived from the
     * directory path, so the same folder carries the same id in the images and video tables, which
     * is what keeps a Camera folder holding both from splitting into two albums.
     */
    private fun Cursor.recordAlbum(
        assetId: String,
        albumNamesById: MutableMap<String, String>,
        albumIdsByAssetId: MutableMap<String, List<String>>,
    ) {
        val albumId = longValue(MediaStore.Images.Media.BUCKET_ID)
            .takeIf { it != 0L }?.toString() ?: return
        val name = stringValue(MediaStore.Images.Media.BUCKET_DISPLAY_NAME).ifEmpty { return }
        albumNamesById[albumId] = name
        // A file lives in exactly one directory, so this list is always a single element. It is a
        // list because iOS albums are many-to-many and both platforms share the one shape.
        albumIdsByAssetId[assetId] = listOf(albumId)
    }

    private fun albumSnapshot(
        albumNamesById: Map<String, String>,
        albumIdsByAssetId: Map<String, List<String>>,
    ): AlbumSnapshot {
        val albums = albumNamesById.map { (id, name) ->
            PhotoAlbum(id = id, name = name, kind = kindOf(name))
        }
        return AlbumSnapshot(albums = albums, albumIdsByAssetId = albumIdsByAssetId)
    }

    /**
     * Matched on the folder name rather than the full path, because the name is what the user sees
     * and what they would recognise the pile by.
     */
    private fun kindOf(name: String): AlbumKind {
        val normalized = name.lowercase()
        return if (APP_FOLDERS.any { it in normalized }) AlbumKind.APP_FOLDER else AlbumKind.USER
    }

    private fun Cursor.longValue(column: String): Long {
        val index = getColumnIndex(column)
        return if (index >= 0) getLong(index) else 0L
    }

    private fun Cursor.stringValue(column: String): String {
        val index = getColumnIndex(column)
        return if (index >= 0) getString(index).orEmpty() else ""
    }

    /** API 30+: one sheet for the whole batch, and the assets land in the MediaStore trash. */
    private suspend fun trashWithConfirmation(ids: List<String>): DeleteResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return DeleteResult.Failed("Trashing needs API 30 or newer")
        }
        return try {
            val request = MediaStore.createTrashRequest(resolver, ids.map(::photoAssetUri), true)
            val confirmed = ActivityBridge.confirmWithUser(request.intentSender)
            if (confirmed) DeleteResult.Deleted else DeleteResult.Cancelled
        } catch (exception: SecurityException) {
            DeleteResult.Failed("Not allowed to trash these assets", exception)
        }
    }

    /**
     * API 29: no batch request exists, so it is one asset at a time and one sheet each. The first
     * write to an asset the app does not own throws a [RecoverableSecurityException] carrying the
     * sheet to show.
     */
    private suspend fun deleteRecoverable(ids: List<String>): DeleteResult {
        for (id in ids) {
            val uri = photoAssetUri(id)
            try {
                resolver.delete(uri, null, null)
            } catch (exception: SecurityException) {
                val recoverable = exception as? RecoverableSecurityException
                    ?: return DeleteResult.Failed("Not allowed to delete $id", exception)
                val confirmed = ActivityBridge.confirmWithUser(
                    recoverable.userAction.actionIntent.intentSender,
                )
                if (!confirmed) return DeleteResult.Cancelled
                runCatching { resolver.delete(uri, null, null) }
                    .onFailure { return DeleteResult.Failed("Could not delete $id", it) }
            }
        }
        return DeleteResult.Deleted
    }

    /** Below API 29 the storage permission is the whole story and no sheet is involved. */
    private fun deleteDirectly(ids: List<String>): DeleteResult = try {
        ids.forEach { resolver.delete(photoAssetUri(it), null, null) }
        DeleteResult.Deleted
    } catch (exception: Exception) {
        DeleteResult.Failed("Could not delete the assets", exception)
    }

    private companion object {
        /** Folders other apps dump into, which fill up without anyone deciding to keep anything. */
        val APP_FOLDERS = listOf(
            "whatsapp",
            "telegram",
            "download",
            "screenshot",
            "instagram",
            "messenger",
            "signal",
            "viber",
            "snapchat",
            "twitter",
            "facebook",
            "discord",
            "wechat",
        )
    }
}
