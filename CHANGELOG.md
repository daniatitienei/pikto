# Changelog

All notable changes to Pikto are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and Pikto uses
[semantic versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

## 0.1.0

First release. Android and iOS, `iosArm64` and `iosSimulatorArm64`.

### pikto-core

- `PhotoLibrary` with batched streaming enumeration, permission handling and deletion.
- `MediaQuery` for filtering by media type, sort order, sizes, albums and batch shape.
- `collectAssets()` and `loadAll()` for consumers that want a list rather than a stream.
- `DeleteResult` separating a dismissed system sheet from a real failure.
- `installPikto()` on Android for the two operations that need an activity.
- `photoAssetUri()` on Android and `phAssetFor()` on iOS for reaching past the abstraction.

### pikto-images

- `PhotoImageLoader` with a shared, bounded, deduplicated decode pool.
- Separate memory budgets for thumbnails and full images, plus an on-disk thumbnail cache.
- `PhotoImage`, `rememberPhotoImage` and `PrefetchPhotos` composables.
- `PhotoDiskCache` for swapping or disabling on-disk caching.

### pikto-video

- `VideoPlayer`, backed by ExoPlayer on Android and AVPlayer on iOS.
- Next-clip preloading through `nextAssetId`.
- `VideoPlaybackState` with scrub, seek, play/pause and first-frame reporting.
