package pikto.sample

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import pikto.PhotoLibrary

/**
 * The Pikto sample.
 *
 * A grid of the device library, a full-screen viewer that plays clips, and a delete flow, all in
 * `commonMain` with no `expect`/`actual` of its own. The platform entry points are one file each:
 * `MainActivity` on Android, `MainViewController` on iOS.
 */
@Composable
fun App() {
    MaterialTheme(colorScheme = darkColorScheme()) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // One library for the lifetime of the app. It holds no per-call state, so building
            // one per screen would buy nothing and cost a MediaStore/PhotoKit handle each time.
            val library = remember { PhotoLibrary() }
            val model = rememberGalleryModel(library)

            LaunchedEffect(model) { model.start() }

            var isViewerOpen by remember { mutableStateOf(false) }
            // Held separately so the viewer still knows which asset it is showing on the way out,
            // after it has been told to close and is playing its exit animation.
            //
            // Both are set in the same event handler, deliberately. `rememberPagerState` reads
            // this on the viewer's first composition, so updating it from an effect instead would
            // hand the pager the *previous* index and open the last photo rather than the tapped
            // one, correcting itself only on the next open.
            var openedIndex by remember { mutableStateOf(0) }

            GalleryScreen(
                model = model,
                onOpenAsset = { index ->
                    openedIndex = index
                    isViewerOpen = true
                },
            )

            AnimatedVisibility(
                visible = isViewerOpen && model.assets.isNotEmpty(),
                // Grows out of the grid rather than sliding in from an edge, so opening a photo
                // reads as that photo getting bigger.
                enter = fadeIn(tween(180)) + scaleIn(tween(220), initialScale = 0.9f),
                exit = fadeOut(tween(160)) + scaleOut(tween(200), targetScale = 0.92f),
            ) {
                ViewerScreen(
                    assets = model.assets,
                    initialIndex = openedIndex,
                    onClose = { isViewerOpen = false },
                    // Closing on success, because the asset the viewer is showing is gone.
                    onDelete = { id -> model.delete(id) { isViewerOpen = false } },
                )
            }
        }
    }
}
