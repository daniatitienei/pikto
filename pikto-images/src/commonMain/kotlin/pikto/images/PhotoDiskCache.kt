package pikto.images

/**
 * Encoded thumbnails kept between launches.
 *
 * Decoding a cover out of MediaStore or PhotoKit is the slow part of drawing a grid, and without
 * this it is paid again on every cold start, because decoded bitmaps only ever live in memory.
 * Holding the *encoded* bytes on disk is a fraction of the size of the decoded pixels and turns
 * that decode into a file read.
 *
 * Bytes rather than bitmaps, because each platform already has an encoded form on hand at the
 * moment it decodes: an Android `Bitmap` compresses straight to WebP, and iOS is handed a
 * `UIImage` it can ask for a JPEG representation of. Going back through a decoded bitmap to
 * re-encode would be the expensive way round.
 *
 * Implementations must be best-effort. A miss, a full disk and a corrupt file all read as "not
 * cached", never as an error the caller has to handle.
 */
public interface PhotoDiskCache {

    public suspend fun read(key: String): ByteArray?

    public suspend fun write(key: String, bytes: ByteArray)

    public companion object {
        /** Keeps nothing. Every cold start re-decodes from the platform. */
        public val None: PhotoDiskCache = object : PhotoDiskCache {
            override suspend fun read(key: String): ByteArray? = null
            override suspend fun write(key: String, bytes: ByteArray) = Unit
        }
    }
}

/** Asset ids are numeric on Android but path-shaped on iOS, so neither can be a filename as-is. */
internal fun diskFileName(key: String): String = buildString(key.length) {
    key.forEach { char ->
        append(if (char.isLetterOrDigit() || char == '-' || char == '_') char else '_')
    }
}
