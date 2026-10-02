# Changelog

All notable changes to Pikto are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and Pikto uses
[semantic versioning](https://semver.org/spec/v2.0.0.html).

## Unreleased

## 1.0.1

### Changed

- **Consuming apps now need `compileSdk 37`.** Compose Multiplatform 1.12 and `androidx.core` 1.19
  compile against API 37 and push that floor onto everything that depends on them, so it reaches
  you whether or not you name those libraries yourself. `minSdk` stays at 24 and nothing changes at
  runtime: this is a build-tooling bump, not a device one.
- Toolchain moved to Kotlin 2.4.20, Compose Multiplatform 1.12.0, coroutines 1.11.0, AGP 9.4.0,
  Media3 1.11.1 and Gradle 9.7.1.

### Fixed

- **pikto-video:** the published POM put `lifecycle-runtime-compose 2.11.0-beta01` on the runtime
  classpath of every consumer. It now asks for the 2.11.0 release.
- **docs:** the stated iOS requirement was wrong, and had been since before this release. The
  README claimed iOS 13+; Kotlin/Native's own `minVersion.ios` has been 15.0 since Kotlin 2.4.10,
  so no framework built with that toolchain loads below iOS 15. Anyone who planned iOS 13 or 14
  support on the strength of that line was planning for something that could not work. Corrected
  to iOS 15+, which also moots the old note about `LIMITED` needing iOS 14.
- **pikto-core, docs:** `PhotoLibrary.permissionStatus()` never reports `DENIED` on Android, where
  a refusal and a question never asked are both just an absent permission. Nothing said so, and the
  README's example implied the value was portable. `ensurePermission()` is the call that separates
  the two on both platforms, and the KDoc now says which is which.
- **pikto-video, iOS:** the player no longer swallows touches. Compose wraps every interop view in
  a container of its own and that container claimed every touch landing on the clip, so a pager or
  a swipe deck under a `VideoPlayer` stopped responding as soon as a video was on screen.
- **pikto-video, iOS:** the player's own background is black rather than transparent. An interop
  view is a hole cut in the Compose canvas, so the bars around a letterboxed clip showed the
  window background — white — instead of whatever was drawn underneath.

### Added

- A runnable sample app under `sample/`, built against the published coordinates and wired to the
  local projects inside this repo. See `sample/README.md`.

## 1.0.0

First release. Android and iOS, `iosArm64` and `iosSimulatorArm64`.

Every public declaration is covered by semantic versioning from here on: no breaking change to
anything listed below without a 2.0.0.

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
