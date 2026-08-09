package pikto

import platform.Photos.PHAsset

/**
 * The [PHAsset] behind [assetId], or `null` if the asset no longer exists.
 *
 * Provided so you can reach the parts of PhotoKit that Pikto does not wrap (live photos, location
 * metadata, favouriting) without having to re-derive the fetch yourself. The call is synchronous
 * and hits the photo database, so keep it off the main thread.
 */
public fun phAssetFor(assetId: String): PHAsset? =
    PHAsset.fetchAssetsWithLocalIdentifiers(listOf(assetId), null).firstObject as? PHAsset
