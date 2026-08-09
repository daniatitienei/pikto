package pikto

/**
 * One piece of an in-progress enumeration of the library.
 *
 * [PhotoLibrary.stream] emits these rather than one list at the end so the first assets reach the
 * screen while the rest is still being read. Consumers that do not care about any of that should
 * use [collectAssets] or [loadAll], which fold these back into a plain list.
 */
public sealed interface LibraryUpdate {

    /**
     * A chunk of the library, in [MediaQuery.sortOrder]. Chunks never overlap and never repeat, so
     * appending each one in arrival order rebuilds the full library in order.
     */
    public data class Assets(val assets: List<PhotoAsset>) : LibraryUpdate

    /**
     * Sizes for assets that were emitted with [PhotoAsset.sizeBytes] still `null`. Arrives after
     * the assets it describes, and only when [MediaQuery.includeSizes] is on.
     */
    public data class Sizes(val sizeBytesById: Map<String, Long>) : LibraryUpdate

    /**
     * Which albums the assets belong to. One emission for the whole library rather than a stream
     * of them, and only when [MediaQuery.includeAlbums] is on.
     */
    public data class Albums(val snapshot: AlbumSnapshot) : LibraryUpdate
}
