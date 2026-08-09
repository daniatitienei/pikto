package pikto

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.Foundation.NSNumber
import platform.Foundation.NSPredicate
import platform.Foundation.NSSortDescriptor
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.valueForKey
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAsset
import platform.Photos.PHAssetChangeRequest
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionSubtypeAny
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumAnimated
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumBursts
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumDepthEffect
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumLivePhotos
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumPanoramas
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumScreenshots
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumSelfPortraits
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumSlomoVideos
import platform.Photos.PHAssetCollectionSubtypeSmartAlbumTimelapses
import platform.Photos.PHAssetCollectionTypeAlbum
import platform.Photos.PHAssetCollectionTypeSmartAlbum
import platform.Photos.PHAssetMediaTypeImage
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAssetResource
import platform.Photos.PHAuthorizationStatus
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHAuthorizationStatusNotDetermined
import platform.Photos.PHFetchOptions
import platform.Photos.PHFetchResult
import platform.Photos.PHPhotoLibrary
import kotlin.coroutines.resume
import kotlin.time.Instant

@OptIn(ExperimentalForeignApi::class)
internal class IosPhotoLibrary : PhotoLibrary {

    override suspend fun permissionStatus(): PhotoPermission = PHPhotoLibrary
        .authorizationStatusForAccessLevel(PHAccessLevelReadWrite)
        .toPhotoPermission()

    override suspend fun requestPermission(): PhotoPermission =
        suspendCancellableCoroutine { continuation ->
            PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { status ->
                continuation.resume(status.toPhotoPermission())
            }
        }

    /**
     * Enumerating the [PHAsset]s is cheap. Reading each one's file size through [PHAssetResource]
     * hits disk per asset and is what makes a large library take seconds rather than milliseconds,
     * so sizes are left out of the first pass entirely and streamed afterwards, once everything the
     * user can see is already on screen.
     */
    override fun stream(query: MediaQuery): Flow<LibraryUpdate> = flow {
        if (query.mediaTypes.isEmpty()) return@flow

        val screenshotIds = if (query.wantsImages) fetchScreenshotIds() else emptySet()
        val assets = fetchAssets(query)

        var index = 0
        var batchSize = query.batching.firstBatchSize
        while (index < assets.size) {
            val end = minOf(index.toLong() + batchSize, assets.size.toLong()).toInt()
            emit(
                LibraryUpdate.Assets(
                    assets.subList(index, end).map { it.toPhotoAsset(screenshotIds) },
                ),
            )
            index = end
            batchSize = query.batching.nextSize(batchSize)
        }

        // Strictly after the assets. There is no way to ask a PHAsset which collections hold it, so
        // this walks every collection instead, which is nothing like the first-batch budget.
        if (query.includeAlbums) emit(LibraryUpdate.Albums(fetchAlbumMembership()))

        if (query.includeSizes) {
            assets.chunked(SIZE_BATCH_SIZE).forEach { chunk ->
                emit(
                    LibraryUpdate.Sizes(
                        chunk.associate { it.localIdentifier to it.fileSizeBytes() },
                    ),
                )
            }
        }
    }.flowOn(Dispatchers.Default)

    override suspend fun delete(ids: List<String>): DeleteResult {
        if (ids.isEmpty()) return DeleteResult.Deleted
        return suspendCancellableCoroutine { continuation ->
            val fetchResult = PHAsset.fetchAssetsWithLocalIdentifiers(ids, null)
            PHPhotoLibrary.sharedPhotoLibrary().performChanges(
                changeBlock = { PHAssetChangeRequest.deleteAssets(fetchResult) },
                completionHandler = { success, error ->
                    // PhotoKit reports a dismissed confirmation sheet the same way it reports a
                    // real failure, so the presence of an NSError is the only thing separating
                    // "the user said no" from "something went wrong".
                    continuation.resume(
                        when {
                            success -> DeleteResult.Deleted
                            error == null -> DeleteResult.Cancelled
                            else -> DeleteResult.Failed(error.localizedDescription)
                        },
                    )
                },
            )
        }
    }

    /**
     * Images and videos come through a single predicate rather than two fetches, so the result is
     * already in order across both media types with no merge step needed.
     */
    private fun fetchAssets(query: MediaQuery): List<PHAsset> {
        val options = PHFetchOptions().apply {
            predicate = mediaTypePredicate(query)
            sortDescriptors = listOf(
                NSSortDescriptor.sortDescriptorWithKey(
                    key = "creationDate",
                    ascending = query.sortOrder == SortOrder.OLDEST_FIRST,
                ),
            )
        }
        return buildList {
            PHAsset.fetchAssetsWithOptions(options).enumerateObjectsUsingBlock { asset, _, _ ->
                (asset as? PHAsset)?.let(::add)
            }
        }
    }

    private fun mediaTypePredicate(query: MediaQuery): NSPredicate = when {
        query.wantsImages && query.wantsVideos -> NSPredicate.predicateWithFormat(
            "mediaType == %d OR mediaType == %d",
            PHAssetMediaTypeImage,
            PHAssetMediaTypeVideo,
        )
        query.wantsImages -> NSPredicate.predicateWithFormat(
            "mediaType == %d",
            PHAssetMediaTypeImage,
        )
        else -> NSPredicate.predicateWithFormat("mediaType == %d", PHAssetMediaTypeVideo)
    }

    private fun fetchScreenshotIds(): Set<String> {
        val collections = PHAssetCollection.fetchAssetCollectionsWithType(
            PHAssetCollectionTypeSmartAlbum,
            PHAssetCollectionSubtypeSmartAlbumScreenshots,
            null,
        )
        val ids = mutableSetOf<String>()
        collections.enumerateObjectsUsingBlock { collection, _, _ ->
            val album = collection as? PHAssetCollection ?: return@enumerateObjectsUsingBlock
            PHAsset.fetchAssetsInAssetCollection(album, null)
                .enumerateObjectsUsingBlock { asset, _, _ ->
                    (asset as? PHAsset)?.let { ids += it.localIdentifier }
                }
        }
        return ids
    }

    /**
     * Every album the user can meaningfully act on: the ones they made themselves, plus the system
     * collections that pile up on their own.
     */
    private fun fetchAlbumMembership(): AlbumSnapshot {
        val albums = mutableListOf<PhotoAlbum>()
        val albumIdsByAssetId = mutableMapOf<String, MutableList<String>>()

        fun collect(collections: PHFetchResult, kind: AlbumKind) {
            collections.enumerateObjectsUsingBlock { collection, _, _ ->
                val album = collection as? PHAssetCollection ?: return@enumerateObjectsUsingBlock
                val name = album.localizedTitle ?: return@enumerateObjectsUsingBlock
                val albumId = album.localIdentifier
                var count = 0
                PHAsset.fetchAssetsInAssetCollection(album, null)
                    .enumerateObjectsUsingBlock { asset, _, _ ->
                        val id = (asset as? PHAsset)?.localIdentifier
                            ?: return@enumerateObjectsUsingBlock
                        albumIdsByAssetId.getOrPut(id) { mutableListOf() } += albumId
                        count++
                    }
                // An empty smart album is one the user has never produced a photo for. Reporting
                // it would only ever mean an empty row on screen.
                if (count > 0) albums += PhotoAlbum(id = albumId, name = name, kind = kind)
            }
        }

        collect(
            PHAssetCollection.fetchAssetCollectionsWithType(
                PHAssetCollectionTypeAlbum,
                PHAssetCollectionSubtypeAny,
                null,
            ),
            AlbumKind.USER,
        )
        SMART_ALBUM_SUBTYPES.forEach { subtype ->
            collect(
                PHAssetCollection.fetchAssetCollectionsWithType(
                    PHAssetCollectionTypeSmartAlbum,
                    subtype,
                    null,
                ),
                AlbumKind.SMART,
            )
        }

        return AlbumSnapshot(albums = albums, albumIdsByAssetId = albumIdsByAssetId)
    }

    /** [PhotoAsset.sizeBytes] stays null here and is filled in by a later [LibraryUpdate.Sizes]. */
    private fun PHAsset.toPhotoAsset(screenshotIds: Set<String>): PhotoAsset {
        val isVideo = mediaType == PHAssetMediaTypeVideo
        return PhotoAsset(
            id = localIdentifier,
            createdAt = createdAtInstant(),
            width = pixelWidth.toInt(),
            height = pixelHeight.toInt(),
            mediaType = if (isVideo) MediaType.VIDEO else MediaType.IMAGE,
            sizeBytes = null,
            durationMillis = if (isVideo) (duration * 1000).toLong() else 0L,
            isScreenshot = !isVideo && localIdentifier in screenshotIds,
        )
    }

    private fun PHAsset.createdAtInstant(): Instant {
        val seconds = creationDate?.timeIntervalSince1970 ?: 0.0
        return Instant.fromEpochMilliseconds((seconds * 1000).toLong())
    }

    private fun PHAsset.fileSizeBytes(): Long {
        val resource = PHAssetResource.assetResourcesForAsset(this)
            .firstOrNull() as? PHAssetResource
            ?: return 0L
        return (resource.valueForKey("fileSize") as? NSNumber)?.longLongValue ?: 0L
    }

    private fun PHAuthorizationStatus.toPhotoPermission(): PhotoPermission = when (this) {
        PHAuthorizationStatusAuthorized -> PhotoPermission.GRANTED
        PHAuthorizationStatusLimited -> PhotoPermission.LIMITED
        PHAuthorizationStatusNotDetermined -> PhotoPermission.NOT_DETERMINED
        else -> PhotoPermission.DENIED
    }

    private companion object {
        /** Coarse, because a size update only patches numbers into rows that are already drawn. */
        const val SIZE_BATCH_SIZE = 500

        val SMART_ALBUM_SUBTYPES = listOf(
            PHAssetCollectionSubtypeSmartAlbumBursts,
            PHAssetCollectionSubtypeSmartAlbumSelfPortraits,
            PHAssetCollectionSubtypeSmartAlbumPanoramas,
            PHAssetCollectionSubtypeSmartAlbumSlomoVideos,
            PHAssetCollectionSubtypeSmartAlbumTimelapses,
            PHAssetCollectionSubtypeSmartAlbumDepthEffect,
            PHAssetCollectionSubtypeSmartAlbumLivePhotos,
            PHAssetCollectionSubtypeSmartAlbumAnimated,
        )
    }
}
