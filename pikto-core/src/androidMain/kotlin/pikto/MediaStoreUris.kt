package pikto

import android.content.ContentUris
import android.net.Uri
import android.provider.MediaStore

/**
 * Images and videos are different MediaStore tables whose row ids can collide, so video ids carry
 * a prefix that keeps every [PhotoAsset.id] unambiguous across both.
 */
private const val VIDEO_ID_PREFIX = "video:"

/** The MediaStore content URI for [assetId], for handing to Coil, ExoPlayer, a share sheet. */
public fun photoAssetUri(assetId: String): Uri {
    val table = if (isVideoAssetId(assetId)) {
        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
    } else {
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
    }
    return ContentUris.withAppendedId(table, mediaStoreRowId(assetId))
}

/** Whether [assetId] came from the video table, without having to query for it. */
public fun isVideoAssetId(assetId: String): Boolean = assetId.startsWith(VIDEO_ID_PREFIX)

/** The underlying MediaStore `_ID`, for the platform APIs that take one directly. */
public fun mediaStoreRowId(assetId: String): Long =
    assetId.removePrefix(VIDEO_ID_PREFIX).toLong()

internal fun videoAssetId(rowId: Long): String = "$VIDEO_ID_PREFIX$rowId"
