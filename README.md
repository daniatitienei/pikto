<div align="center">

# 📸 Pikto

**The device photo library, in Compose Multiplatform.**

Read photos, show thumbnails, play videos and delete assets, *all from `commonMain`*.<br>
One Kotlin API over **MediaStore** on Android and **PhotoKit** on iOS.

[![Maven Central](https://img.shields.io/maven-central/v/io.github.daniatitienei/pikto-core.svg?label=Maven%20Central)](https://central.sonatype.com/search?q=g:io.github.daniatitienei)
[![License](https://img.shields.io/badge/license-Apache%202.0-blue.svg)](LICENSE)
![Platforms](https://img.shields.io/badge/platforms-Android%20%7C%20iOS-brightgreen.svg)

[Quick start](#-quick-start) •
[Reading photos](#-reading-the-library) •
[Showing photos](#-showing-photos) •
[Playing video](#-playing-video) •
[Sample app](#-sample-app)

</div>

---

## ✨ A whole gallery, in one composable

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

**That's it, on both platforms.** No `expect`/`actual` of your own, no Swift file on the side.

## 💡 Why Pikto

`MediaStore` and `PhotoKit` disagree about almost everything: what an asset id is, whether sizes
are cheap, who shows the delete confirmation, what a thumbnail costs. Most apps end up writing it
twice, and the two copies drift apart.

Pikto was pulled out of a shipping photo cleaner and generalised. It's opinionated about the two
things that actually break at scale:

- ⚡ **Nothing loads eagerly.** The library arrives in batches you paint as they land. The first
  batch is deliberately tiny, because it *is* your time-to-first-pixel. Fifty thousand photos never
  sit in one list you had to wait for.
- 🧠 **Decoding is shared, bounded and cached.** One decode per asset however many composables ask,
  a hard cap on concurrent decodes, separate memory budgets for thumbnails and full images, and an
  on-disk thumbnail cache so a cold start is a file read.

## 🧩 Modules

Take only what you need. Each one is published separately.

| Artifact | What you get | Pulls in |
| --- | --- | --- |
| 📚 `pikto-core` | Querying, permissions, deleting. No UI dependency, so it also works with SwiftUI or Views. | coroutines |
| 🖼️ `pikto-images` | Composables and `ImageBitmap` decoding for thumbnails and full-size images. | `pikto-core`, Compose Multiplatform |
| 🎬 `pikto-video` | A video player for library clips, with next-clip preloading. | `pikto-core`, Compose Multiplatform, Media3 on Android |

---

## 🚀 Quick start

Three steps and you're showing photos.

### 1. Add the dependencies

```kotlin
// build.gradle.kts
kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation("io.github.daniatitienei:pikto-core:1.0.1")
            implementation("io.github.daniatitienei:pikto-images:1.0.1")  // optional
            implementation("io.github.daniatitienei:pikto-video:1.0.1")   // optional
        }
    }
}
```

Targets: `android`, `iosArm64`, `iosSimulatorArm64`.

### 2. 🤖 Android: one line in your activity

```kotlin
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installPikto()
        setContent { App() }
    }
}
```

<details>
<summary><b>Why is this needed?</b></summary>

The permission prompt and the delete confirmation sheet are both activity results, and Android
only lets those be registered before the activity reaches STARTED. That's a call the library can't
make for you. Everything unregisters on destroy, so it's safe across configuration changes and
multiple activities.

</details>

> [!TIP]
> Only **reading** the library? You can skip `installPikto()`. It's needed only to request
> permission or delete.

The read permissions come from Pikto's own manifest and merge into your app, so there's nothing to
add. To drop one you don't want:

```xml
<uses-permission android:name="android.permission.READ_MEDIA_VIDEO" tools:node="remove" />
```

### 3. 🍎 iOS: two keys in `Info.plist`

```xml
<key>NSPhotoLibraryUsageDescription</key>
<string>So you can browse and clean up your photos.</string>
<key>NSPhotoLibraryAddUsageDescription</key>
<string>So you can save photos back to your library.</string>
```

> [!WARNING]
> iOS **terminates** an app that touches the photo library without these keys.

No other setup. Nothing to call, no Swift to write. 🎉

---

## 📚 Reading the library

`PhotoLibrary` is the whole surface. **Build one and keep it.** It holds no per-call state, so one
instance for the app's lifetime is the intended use.

```kotlin
val library = PhotoLibrary()
```

### 🔐 Permission

```kotlin
when (library.ensurePermission()) {
    PhotoPermission.GRANTED -> loadEverything()
    PhotoPermission.LIMITED -> loadWhatWeCanAndOfferToWiden()
    PhotoPermission.DENIED -> sendToSettings()
    PhotoPermission.NOT_DETERMINED -> Unit // A prompt always settles the question.
}
```

`ensurePermission()` returns the current status and prompts *only* if nobody has been asked yet.
For the common case, `canRead` is true for both `GRANTED` and `LIMITED`.

> [!IMPORTANT]
> **Use `ensurePermission()`, not `permissionStatus()`, when a refusal should send the user to
> Settings.** `permissionStatus()` never shows UI, so it can't tell "refused" from "never asked":
> iOS reports `DENIED`, but Android reports `NOT_DETERMINED` for both. Only showing the prompt
> separates them. So `DENIED` is reliable *after* `ensurePermission()`, and iOS-only before it.

> [!NOTE]
> `LIMITED` is a **permanent** state on both platforms, not a step towards `GRANTED`. The user
> picked some photos and the rest stays invisible. Treat it as a working state and offer a way to
> widen the selection, never as a failure to retry.

### 📜 Photos as a growing list *(the usual path)*

Each emission is everything known so far, in order, with sizes filled in as they arrive. The last
emission is the whole library.

```kotlin
library.collectAssets().collect { assets ->
    // The first emission lands in a frame or two. Later ones grow it.
}
```

### 📦 Photos as one list *(background work only)*

```kotlin
val everything: List<PhotoAsset> = library.loadAll()
```

> [!CAUTION]
> This waits for the *last* asset before returning the first. **Never put it in front of UI.**

### 🎛️ Filtering and tuning

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

`MediaQuery.Images` and `MediaQuery.Videos` are ready-made presets.

Two flags have a real cost on iOS:

| Flag | Android | iOS | Advice |
| --- | --- | --- | --- |
| `includeSizes` | Free, it's in the same cursor row | One disk read *per asset* through `PHAssetResource`: milliseconds become seconds | **Turn it off** if nothing on screen shows bytes. Sizes always arrive *after* the assets. |
| `includeAlbums` | Cheap | The most expensive pass by far: PhotoKit can't ask an asset which albums hold it, so every album gets walked | **Off by default.** Turn it on only when you show albums. |

### 🌊 The raw stream

`collectAssets` is a fold over `stream()`. Collect the stream directly when you want albums, or want
to handle each kind of update differently:

```kotlin
library.stream(MediaQuery(includeAlbums = true)).collect { update ->
    when (update) {
        is LibraryUpdate.Assets -> append(update.assets)
        is LibraryUpdate.Sizes -> patchSizes(update.sizeBytesById)
        is LibraryUpdate.Albums -> showAlbumRow(update.snapshot)
    }
}
```

- Emissions are **ordered**: every `Assets` arrives before the `Sizes` and `Albums` that describe it.
- The flow is **cold** and already runs on a background dispatcher, so collecting from the main
  thread is fine.

### 🗑️ Deleting

```kotlin
when (library.delete(selectedIds)) {
    DeleteResult.Deleted -> refresh()
    DeleteResult.Cancelled -> Unit // The user dismissed the system sheet. Not an error.
    is DeleteResult.Failed -> showError()
}
```

Both platforms show a system confirmation sheet first, so *"the user said no"* is an ordinary
outcome. That's why `Cancelled` sits beside `Failed` rather than inside it.

> [!NOTE]
> Nothing is deleted permanently. Android moves assets to the MediaStore trash and iOS to
> *Recently Deleted*. Both are recoverable for about thirty days.

### 🏷️ What a `PhotoAsset` is

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

`id` is stable for as long as the asset exists on the device, and means nothing anywhere else.
**Don't parse it, sort by it, or use it as a cross-device key.**

---

## 🌄 Showing photos

```kotlin
PhotoImage(
    assetId = asset.id,
    size = ImageSize.Thumbnail(320),
    contentDescription = null,
    modifier = Modifier.aspectRatio(1f),
    placeholder = { Box(Modifier.fillMaxSize().background(Color.LightGray)) },
)
```

The two sizes take different paths:

| Size | How it's made | Cached |
| --- | --- | --- |
| `ImageSize.Thumbnail(sidePx)` | The platform's own thumbnailing | 💾 Memory **and disk**, so the second cold start is a file read |
| `ImageSize.Full` | Rendered by the platform at screen resolution | 🧠 Memory only |

> [!TIP]
> The size is part of the cache key, so `Thumbnail(320)` and `Thumbnail(321)` share nothing.
> **Pick a few sizes and reuse them.**

Need the bitmap itself rather than a composable that draws it?

```kotlin
val bitmap: ImageBitmap? = rememberPhotoImage(asset.id, ImageSize.Full)
```

### 🏎️ Prefetching

**The single biggest win for a scrolling grid.** Warm the ids just outside the visible window:

```kotlin
PrefetchPhotos(assetIds = upcomingIds, size = ImageSize.Thumbnail(320))
```

Recomputing the window on every scroll is fine. That's the intended use: ids already cached or
already in flight cost nothing.

### ⚙️ Configuring the loader

`PhotoImage` uses a process-wide loader with sensible defaults. Bring your own when you want
different budgets:

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

> [!WARNING]
> **Keep one instance.** A second loader means a second set of caches and a second writer to the
> same disk directory.

- `diskCache = PhotoDiskCache.None` keeps nothing on disk. Pass your own `PhotoDiskCache` to store
  it somewhere else.
- 🧪 **In tests**, provide a fake `PhotoImageLoader` through `ProvidePhotoImageLoader` and nothing
  touches the platform.

---

## 🎬 Playing video

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

Three things a hand-rolled player usually gets wrong, and how Pikto handles them:

**1. 📌 Keep it mounted.** Creating or releasing a player is main-thread work measured in
*hundreds of milliseconds* on both platforms, so doing it per clip freezes the screen. A `null`
`assetId` means "nothing to play" and keeps the player alive and idle. Keep the composable in the
tree and swap the id underneath it.

**2. ⏭️ Preload the next clip.** Pass `nextAssetId` and that clip is opened and buffered while the
user is still watching, so reaching it is a hand-over rather than a cold start.

**3. 👁️ Hide it with `isVisible`, not a modifier.** The player is a native view that draws itself
on its own thread. A `graphicsLayer` alpha or clip is a statement about the Compose tree that the
surface is under no obligation to honour. Hiding has to happen in the view's own terms
(`visibility` on Android, `hidden` on iOS), and the same goes for `scale`, which is why there's no
`ContentScale` parameter. Pass `state.hasRenderedFirstFrame` and draw a still underneath.

> [!NOTE]
> On Android the player is a `TextureView` rather than the usual `SurfaceView`, so it survives
> being translated or scaled inside a `graphicsLayer` without punching through and blinking.

---

## 📱 Sample app

A runnable gallery that uses all three artifacts lives in [`sample/`](sample/): a grid, a
full-screen viewer, video playback, and deleting one or many. Android and iOS share one
`commonMain` source set.

```bash
./gradlew :sample:androidApp:installDebug     # 🤖 Android
open sample/iosApp/iosApp.xcodeproj           # 🍎 iOS
```

More in [`sample/README.md`](sample/README.md).

## 🔌 Dependency injection

Pikto doesn't assume a DI framework. Both factories are plain functions, so wire them however you
already do. With Koin:

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

## 🚪 Reaching past Pikto

Pikto doesn't wrap all of MediaStore or PhotoKit, and doesn't try to. When you need something it
doesn't cover, take the platform handle and go:

```kotlin
// androidMain
val uri: Uri = photoAssetUri(asset.id)   // Hand to Coil, ExoPlayer, a share sheet.

// iosMain
val phAsset: PHAsset? = phAssetFor(asset.id)   // Live photos, location, favouriting.
```

## 🚫 What Pikto doesn't do

Up front, because these are the things you'll go looking for:

- ✏️ **Writing.** No saving, importing, editing or favouriting. Read and delete only.
- 🔔 **Change observation.** No callback when the library changes under you. Re-stream to refresh.
- 🖥️ **Desktop, web, macOS.** Android and iOS only: consistency across two platforms beats a
  longer list.
- 📷 **Live Photos, RAW, depth data, bursts.** A burst appears as its individual frames.
- ☁️ **Cloud fetch progress.** iCloud downloads are allowed and awaited, but there's no progress
  callback.

Several of these could be added. **Say so in an issue.** 🙌

## ✅ Requirements

| | |
| --- | --- |
| **Kotlin** | 2.4.20+ |
| **Android** | minSdk 24, compileSdk 37 |
| **iOS** | 15+ |
| **Compose Multiplatform** | 1.12+ (for `pikto-images` and `pikto-video`) |

> [!NOTE]
> The iOS 15 floor comes from Kotlin/Native, not Pikto: its `minVersion.ios` is 15.0 as of
> Kotlin 2.4, after 14.0 in 2.3 and 12.0 in 2.2. Nothing Pikto does can lower it.

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md). Issues and pull requests are welcome, *especially*
platform edge cases from real devices.

## 📄 License

Apache 2.0. See [LICENSE](LICENSE).
