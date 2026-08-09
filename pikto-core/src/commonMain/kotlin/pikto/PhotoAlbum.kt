package pikto

/**
 * What kind of pile an album is. Decided by the platform, because only it can tell a MediaStore
 * folder from a `PHAssetCollection` subtype.
 */
public enum class AlbumKind {
    /** Something the user made themselves. */
    USER,

    /** A collection the system maintains: Screenshots, Bursts, Selfies, Panoramas. */
    SMART,

    /**
     * A folder another app writes into: WhatsApp, Telegram, Downloads. Android only, because iOS
     * gives every app the one shared library and no folder of its own.
     */
    APP_FOLDER,
}

public data class PhotoAlbum(
    val id: String,
    val name: String,
    val kind: AlbumKind,
)

/**
 * Album membership for one enumeration of the library.
 *
 * Kept beside the assets rather than on [PhotoAsset] because on iOS a single asset belongs to any
 * number of collections, which a field on the asset cannot express.
 *
 * @property albumIdsByAssetId Maps [PhotoAsset.id] to the [PhotoAlbum.id]s holding it. An asset
 *   missing from the map is in no album that was enumerated.
 */
public data class AlbumSnapshot(
    val albums: List<PhotoAlbum> = emptyList(),
    val albumIdsByAssetId: Map<String, List<String>> = emptyMap(),
) {
    public val isEmpty: Boolean get() = albums.isEmpty()

    public fun albumsFor(assetId: String): List<PhotoAlbum> {
        val ids = albumIdsByAssetId[assetId]?.toSet() ?: return emptyList()
        return albums.filter { it.id in ids }
    }
}
