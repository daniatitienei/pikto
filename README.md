# Pikto

**A Compose Multiplatform photo gallery library.** Read the device photo library, show thumbnails,
play videos and delete assets, all from `commonMain`. One Kotlin Multiplatform API over MediaStore
on Android and PhotoKit on iOS.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.daniatitienei/pikto-core.svg?label=Maven%20Central)](https://central.sonatype.com/search?q=g:io.github.daniatitienei)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)

```kotlin
@Composable
fun Gallery() {
    val library = remember { PhotoLibrary() }
    var assets by remember { mutableStateOf(emptyList<PhotoAsset>()) }

    LaunchedEffect(Unit) {
        if (library.ensurePermission().canRead) {
            library.collectAssets().collect { assets = it }
        }
    }

    LazyVerticalGrid(GridCells.Adaptive(96.dp)) {
        items(assets, key = { it.id }) { asset ->
            PhotoImage(
                assetId = asset.id,
                size = ImageSize.Thumbnail(320),
                contentDescription = null,
                modifier = Modifier.aspectRatio(1f),
            )
        }
    }
}
```

That is the whole thing on both platforms. No `expect`/`actual` of your own, no permission
plumbing in shared code, no separate Swift file.

---

## Why this exists

Photo library access is one of the last big holes in Kotlin Multiplatform. `MediaStore` and
`PhotoKit` disagree about almost everything: what an asset id is, whether sizes are cheap, whether
an asset can belong to more than one album, who shows the delete confirmation, and what a
thumbnail costs. Writing that twice per app is the norm, and both copies drift.

Pikto is the abstraction, taken from a shipping photo cleaner and generalised. It is opinionated
about the two things that actually go wrong at scale:

**Nothing is loaded eagerly.** The library arrives in batches you can paint as they land, with the
first batch deliberately tiny because it is your entire time-to-first-pixel budget. A library of
fifty thousand assets never exists as one list you had to wait for.

**Decoding is shared, bounded and cached.** One decode per asset no matter how many composables
ask, a hard cap on how many run at once, separate memory budgets for thumbnails and full images,
and an on-disk thumbnail cache so a cold start is a file read rather than a re-decode.

## Modules

Take only what you need. Each one is published separately.

| Artifact | What it gives you | Pulls in |
| --- | --- | --- |
| `pikto-core` | Querying, permissions, deleting. No UI dependency at all, so it works with Compose Multiplatform, SwiftUI or Views. | coroutines |
| `pikto-images` | Compose Multiplatform composables and `ImageBitmap` decoding for thumbnails and full-size images. | `pikto-core`, Compose Multiplatform |
| `pikto-video` | A Compose Multiplatform video player for library clips, with next-clip preloading. | `pikto-core`, Compose Multiplatform, Media3 on Android |

## The sample app

A runnable gallery using all three artifacts lives in [`sample/`](sample/): grid, full-screen
viewer, video playback, and deleting one or many. It builds for Android and iOS from one
`commonMain` source set.

```
./gradlew :sample:androidApp:installDebug     # Android
open sample/iosApp/iosApp.xcodeproj           # iOS
```

See [`sample/README.md`](sample/README.md).

## Install

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.daniatitienei:pikto-core:1.0.0")
            implementation("io.github.daniatitienei:pikto-images:1.0.0")
            implementation("io.github.daniatitienei:pikto-video:1.0.0")
        }
    }
}
```

Targets: `android`, `iosArm64`, `iosSimulatorArm64`.

### Android setup

One line, in your activity:

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installPikto()
        setContent { App() }
    }
}
```

Showing the permission prompt and the delete confirmation sheet are both activity results, and
Android only lets those be registered before the activity reaches STARTED. That is a call the
library cannot make for you. Everything unregisters on destroy, so it is safe across
configuration changes and across multiple activities.

Reading the library needs none of this. Skip it if you never request permission and never delete.

The read permissions are declared in Pikto's own manifest and merge into your app, so there is
nothing to add. Drop the ones you do not want:

```xml
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" tools:node="remove" />
```

### iOS setup

Add the usage descriptions to your `Info.plist`. iOS terminates an app that touches the photo
library without them:

```xml
<key>NSPhotoLibraryUsageDescription</key>
<string>So you can browse and clean up your photos.</string>
<key>NSPhotoLibraryAddUsageDescription</key>
<string>So you can save photos back to your library.</string>
```

No other setup. Nothing to call, no Swift to write.

---

## Reading the library

`PhotoLibrary` is the whole surface. Build one and keep it. It holds no per-call state, so a single
instance for the lifetime of the app is the intended use.

```kotlin
val library = PhotoLibrary()
```

### Permission

```kotlin
when (val status = library.ensurePermission()) {
    PhotoPermission.GRANTED -> loadEverything()
    PhotoPermission.LIMITED -> loadWhatWeCanAndOfferToWiden()
    PhotoPermission.DENIED -> sendToSettings()
    PhotoPermission.NOT_DETERMINED -> Unit // The user dismissed without answering.
}
```

`ensurePermission()` returns the current status, prompting only if nobody has been asked yet.
`status.canRead` collapses GRANTED and LIMITED for the common case.

`LIMITED` is a permanent state on both platforms, not a step towards `GRANTED`. The user picked
some photos and everything else stays invisible. Treat it as a working state and offer a way to
widen the selection, never as a failure to retry.

### The library as a growing list

The convenience path. Each emission is everything known so far, in order, with sizes filled in as
they arrive. The last emission is the whole library.

```kotlin
library.collectAssets().collect { assets ->
    // First emission lands in a frame or two. Later ones grow it.
}
```

### The library as one list

For background work, where nothing is waiting to be painted:

```kotlin
val everything: List<PhotoAsset> = library.loadAll()
```

This waits for the last asset before returning the first. Never put it in front of UI.

### Filtering and tuning

```kotlin
val query = MediaQuery(
    mediaTypes = setOf(MediaType.VIDEO),
    sortOrder = SortOrder.OLDEST_FIRST,
    includeSizes = true,
    includeAlbums = false,
    batching = BatchStrategy(firstBatchSize = 20, growthFactor = 4, maxBatchSize = 500),
)

library.collectAssets(query).collect { videos -> }
```

`MediaQuery.Images` and `MediaQuery.Videos` are presets for the common two.

**`includeSizes`** is worth understanding. On Android the file size comes free in the same cursor
row. On iOS every size is a separate disk read through `PHAssetResource`, and that is the
difference between enumerating a large library in milliseconds and in seconds. So sizes always
arrive *after* the assets, and if nothing on screen shows bytes, turn them off.

**`includeAlbums`** is off by default because it is the most expensive pass on iOS by a wide
margin. PhotoKit has no way to ask an asset which collections hold it, so the only way to build
the mapping is to walk every collection.

### The raw stream

`collectAssets` is a fold over `stream()`. Collect the stream directly when you want albums, or
when you want to react to each kind of update differently:

```kotlin
library.stream(MediaQuery(includeAlbums = true)).collect { update ->
    when (update) {
        is LibraryUpdate.Assets -> append(update.assets)
        is LibraryUpdate.Sizes -> patchSizes(update.sizeBytesById)
        is LibraryUpdate.Albums -> showAlbumRow(update.snapshot)
    }
}
```

Emissions are ordered: every `Assets` arrives before the `Sizes` and `Albums` that describe it.
The flow is cold and already confined to a background dispatcher, so collecting from the main
thread is fine.

### Deleting

```kotlin
when (library.delete(selectedIds)) {
    DeleteResult.Deleted -> refresh()
    DeleteResult.Cancelled -> Unit // The user dismissed the system sheet. Not an error.
    is DeleteResult.Failed -> showError()
}
```

Both platforms put a system confirmation sheet in front of this, so "the user said no" is an
ordinary outcome and sits beside `Failed` rather than inside it. Neither platform deletes
permanently: Android moves assets to the MediaStore trash and iOS to Recently Deleted, both
recoverable for about thirty days.

### What a `PhotoAsset` is

```kotlin
data class PhotoAsset(
    val id: String,          // Opaque. A MediaStore row id or a PHAsset local identifier.
    val createdAt: Instant,
    val width: Int,
    val height: Int,
    val mediaType: MediaType,
    val sizeBytes: Long?,    // Null until a Sizes update fills it in.
    val durationMillis: Long,
    val isScreenshot: Boolean,
)
```

`id` is stable for as long as the asset exists on the device and means nothing off it. Do not
parse it, sort by it, or use it as a cross-device key.

---

## Showing photos

```kotlin
PhotoImage(
    assetId = asset.id,
    size = ImageSize.Thumbnail(320),
    contentDescription = null,
    modifier = Modifier.aspectRatio(1f),
    placeholder = { Box(Modifier.fillMaxSize().background(Color.LightGray)) },
)
```

Two sizes, and they take different paths:

- **`ImageSize.Thumbnail(sidePx)`** goes through the platform's own thumbnailing and is cached to
  disk between launches, so the second cold start is a file read.
- **`ImageSize.Full`** asks the platform to render at screen resolution, memory-cached only.

The size is part of the cache key, so `Thumbnail(320)` and `Thumbnail(321)` share nothing. Pick a
small number of sizes and reuse them.

Need the bitmap rather than a composable that draws it?

```kotlin
val bitmap: ImageBitmap? = rememberPhotoImage(asset.id, ImageSize.Full)
```

### Prefetching

The single biggest thing you can do for a scrolling grid. Warm the ids just outside the visible
window:

```kotlin
PrefetchPhotos(assetIds = upcomingIds, size = ImageSize.Thumbnail(320))
```

Re-issuing an overlapping window is cheap, so recomputing it on every scroll is the intended use.
Ids already cached or already in flight cost nothing.

### Configuring the loader

`PhotoImage` uses a process-wide loader with sensible defaults, created the first time anything
asks for a photo. Override it when you want your own budgets:

```kotlin
val loader = remember {
    PhotoImageLoader(
        ImageLoaderConfig(
            thumbnailMemoryBytes = 32L * 1024 * 1024,
            fullImageMemoryBytes = 128L * 1024 * 1024,
            diskCacheBytes = 80L * 1024 * 1024,
        )
    )
}

ProvidePhotoImageLoader(loader) { App() }
```

Keep one instance. A second loader is a second set of caches and a second writer to the same disk
directory.

Pass `diskCache = PhotoDiskCache.None` to keep nothing on disk, or your own `PhotoDiskCache` to
put it somewhere else. In tests, provide a fake `PhotoImageLoader` through
`ProvidePhotoImageLoader` and nothing touches the platform at all.

---

## Playing video

```kotlin
val state = rememberVideoPlaybackState(asset.id)

Box {
    // The still underneath, so nothing flashes black on the way in.
    PhotoImage(asset.id, ImageSize.Full, null, Modifier.fillMaxSize())

    VideoPlayer(
        assetId = asset.id,
        state = state,
        isVisible = state.hasRenderedFirstFrame,
        modifier = Modifier.fillMaxSize(),
        nextAssetId = nextClipId,
        scale = VideoScale.CROP,
    )
}

Slider(
    value = state.positionMillis.toFloat(),
    valueRange = 0f..state.durationMillis.coerceAtLeast(1L).toFloat(),
    onValueChangeFinished = state::endScrub,
    onValueChange = {
        state.startScrub()
        state.scrubTo(it.toLong())
    },
)
```

Three things this handles that a hand-rolled player usually does not:

**Keep it mounted.** A `null` `assetId` means "nothing to play", and the player is kept alive and
idle rather than torn down. Building and releasing a player is main-thread work measured in
hundreds of milliseconds on both platforms, so doing it per clip freezes the screen. Keep the
composable in the tree and swap the id underneath it.

**`nextAssetId` preloads.** The clip behind the current one is opened and buffered while the user
is still watching, so reaching it is a hand-over rather than a cold start.

**`isVisible` is not a modifier.** The player is a native view that composites itself, on its own
thread. A `graphicsLayer` alpha or clip around it is a statement about the Compose tree that the
surface is under no obligation to honour, so hiding it has to be said in a language the view
speaks: `visibility` on Android, `hidden` on iOS. Same for `scale`, which is why there is no
`ContentScale` parameter. Pass `state.hasRenderedFirstFrame` and draw a still underneath.

On Android this is a `TextureView` rather than the usual `SurfaceView`, so the player survives
being translated or scaled inside a `graphicsLayer` without punching through and blinking.

---

## Dependency injection

Nothing in Pikto assumes a DI framework. Both factories are plain functions, so wire them however
you already do. With Koin:

```kotlin
val piktoModule = module {
    single<PhotoLibrary> { PhotoLibrary() }
    single<PhotoImageLoader> { PhotoImageLoader() }
}
```

On Android both no-argument factories work from `Application.onCreate` onwards, because an
`androidx.startup` initializer captures the application context at process start. If you strip
that initializer out, use the `PhotoLibrary(context)` and `PhotoImageLoader(context, config)`
overloads in `androidMain`.

## Reaching past Pikto

Pikto does not wrap all of MediaStore or all of PhotoKit, and it does not try to. When you need
something it does not cover, take the platform handle and go:

```kotlin
// androidMain
val uri: Uri = photoAssetUri(asset.id)   // Hand to Coil, ExoPlayer, a share sheet.

// iosMain
val phAsset: PHAsset? = phAssetFor(asset.id)   // Live photos, location, favouriting.
```

## What Pikto does not do

Being clear about this up front, because these are the things you will look for:

- **Writing.** No saving, importing, editing or favouriting. Read and delete only.
- **Change observation.** No callback when the library changes under you. Re-stream to refresh.
- **Desktop, web, macOS.** Android and iOS. Consistency across two platforms beats a longer list.
- **Live Photos, RAW, depth data, bursts.** A burst appears as its individual frames.
- **Cloud fetch progress.** iCloud downloads are allowed and awaited, but there is no progress
  callback for one.

Several of these are open to being added. Say so in an issue.

## Requirements

- Kotlin 2.4+
- Android minSdk 24, compileSdk 36
- iOS 13+ (iOS 14+ for the `LIMITED` permission state)
- Compose Multiplatform 1.11+ for `pikto-images` and `pikto-video`

## Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Issues and pull requests welcome, especially platform
edge cases from real devices.

## License

Apache 2.0. See [LICENSE](LICENSE).
