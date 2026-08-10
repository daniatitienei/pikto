# Pikto sample

A small photo gallery built on all three Pikto artifacts, running on Android and iOS from one
`commonMain` source set with no `expect`/`actual` of its own.

What it exercises:

- **Permission**, including `LIMITED` as a working state rather than an error.
- **A grid of the whole library**, painted in batches as they arrive, with a prefetch window ahead
  of the scroll.
- **Filtering** by media type through `MediaQuery`.
- **A full-screen viewer** that swipes between assets, decodes full-size images and plays clips
  with one player mounted for the whole screen.
- **Deleting**, both single-asset from the viewer and batched from a grid selection, with the
  platform confirmation sheet either way.

## Running it

### Android

```
./gradlew :sample:androidApp:installDebug
```

Or open the repository in Android Studio and run the `androidApp` configuration.

### iOS

```
open sample/iosApp/iosApp.xcodeproj
```

Then run. The Xcode project builds the Kotlin framework itself through a `Compile Kotlin
Framework` build phase, so there is no separate Gradle step.

The simulator starts with an empty photo library. Give it something to show:

```
xcrun simctl addmedia booted ~/Pictures/some-photo.jpg ~/Movies/some-clip.mp4
```

## Layout

| Path | What it is |
| --- | --- |
| `shared/` | Everything on screen. Compose Multiplatform, `commonMain` only, plus a one-file iOS entry point. |
| `androidApp/` | A manifest, a theme and one activity. Applies `com.android.application`, which since AGP 9 cannot be applied to a module that also applies Kotlin Multiplatform — hence the split. |
| `iosApp/` | The Xcode project. Two Swift files and an `Info.plist`. |

## Dependencies

`shared/build.gradle.kts` depends on the published Maven coordinates, exactly as any other
consumer would write them:

```kotlin
api("io.github.daniatitienei:pikto-core:$piktoVersion")
implementation("io.github.daniatitienei:pikto-images:$piktoVersion")
implementation("io.github.daniatitienei:pikto-video:$piktoVersion")
```

Inside this repository, `settings.gradle.kts` substitutes the local projects for those
coordinates. That gets both things at once: the build file reads like a consumer's and can be
copied out verbatim, and CI compiles the sample against source, so any API change that breaks the
sample breaks the build instead of being discovered by whoever clones it next.

To resolve the real artifacts from Maven Central instead — the smoke test that a release is
actually consumable — build with:

```
./gradlew :sample:androidApp:assembleDebug -Ppikto.sample.useArtifacts=true
```

## Things worth reading the comments for

- `ViewerScreen.kt` — why the video player is mounted once for the whole screen and collapsed to
  nothing on photo pages, and why a still is always drawn underneath it.
- `GalleryScreen.kt` — why every cell asks for exactly one thumbnail size, and what the prefetch
  window is for.
- `GalleryModel.kt` — the whole Pikto API surface an app actually touches, in about a hundred
  lines.
