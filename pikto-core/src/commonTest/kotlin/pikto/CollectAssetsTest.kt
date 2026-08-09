package pikto

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class CollectAssetsTest {

    @Test
    fun `each emission holds everything that has arrived so far`() = runTest {
        val library = FakePhotoLibrary(
            listOf(
                LibraryUpdate.Assets(listOf(asset("a"), asset("b"))),
                LibraryUpdate.Assets(listOf(asset("c"))),
            ),
        )

        val emissions = library.collectAssets().toList()

        assertEquals(2, emissions.size)
        assertEquals(listOf("a", "b"), emissions[0].map(PhotoAsset::id))
        assertEquals(listOf("a", "b", "c"), emissions[1].map(PhotoAsset::id))
    }

    @Test
    fun `a size update patches the asset it names and keeps the order`() = runTest {
        val library = FakePhotoLibrary(
            listOf(
                LibraryUpdate.Assets(listOf(asset("a"), asset("b"))),
                LibraryUpdate.Sizes(mapOf("b" to 2_048L)),
            ),
        )

        val last = library.collectAssets().toList().last()

        assertEquals(listOf("a", "b"), last.map(PhotoAsset::id))
        assertEquals(null, last[0].sizeBytes)
        assertEquals(2_048L, last[1].sizeBytes)
    }

    /** A size for something that never arrived is a no-op, not a crash and not a phantom row. */
    @Test
    fun `a size for an unknown asset is ignored`() = runTest {
        val library = FakePhotoLibrary(
            listOf(
                LibraryUpdate.Assets(listOf(asset("a"))),
                LibraryUpdate.Sizes(mapOf("ghost" to 99L)),
            ),
        )

        val emissions = library.collectAssets().toList()

        // Nothing changed, so nothing was re-emitted.
        assertEquals(1, emissions.size)
        assertEquals(listOf("a"), emissions.single().map(PhotoAsset::id))
    }

    @Test
    fun `albums do not produce a redundant emission`() = runTest {
        val library = FakePhotoLibrary(
            listOf(
                LibraryUpdate.Assets(listOf(asset("a"))),
                LibraryUpdate.Albums(AlbumSnapshot(listOf(PhotoAlbum("1", "Camera", AlbumKind.USER)))),
            ),
        )

        assertEquals(1, library.collectAssets().toList().size)
    }

    @Test
    fun `loadAll returns the whole library once`() = runTest {
        val library = FakePhotoLibrary(
            listOf(
                LibraryUpdate.Assets(listOf(asset("a"), asset("b"))),
                LibraryUpdate.Sizes(mapOf("a" to 512L)),
            ),
        )

        val all = library.loadAll()

        assertEquals(listOf("a", "b"), all.map(PhotoAsset::id))
        assertEquals(512L, all.first().sizeBytes)
    }

    @Test
    fun `ensurePermission only prompts when nobody has been asked`() = runTest {
        val granted = FakePhotoLibrary(emptyList(), permission = PhotoPermission.GRANTED)
        assertEquals(PhotoPermission.GRANTED, granted.ensurePermission())
        assertEquals(false, granted.requestedPermission)

        val fresh = FakePhotoLibrary(emptyList(), permission = PhotoPermission.NOT_DETERMINED)
        fresh.ensurePermission()
        assertEquals(true, fresh.requestedPermission)
    }
}
