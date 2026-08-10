package pikto.sample

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import pikto.PhotoAsset
import pikto.PhotoPermission
import pikto.images.ImageSize
import pikto.images.PrefetchPhotos
import pikto.images.rememberPhotoImage

/**
 * One size for every cell in the grid.
 *
 * The size is part of the cache key, so a screen that asked for `Thumbnail(320)` and another that
 * asked for `Thumbnail(321)` would share nothing and decode everything twice. Declaring it once is
 * the whole trick.
 */
internal val GridThumbnail = ImageSize.Thumbnail(320)

/** How far past the last visible cell to decode ahead of the user. */
private const val PREFETCH_AHEAD = 32

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GalleryScreen(
    model: GalleryModel,
    onOpenAsset: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    val snackbarHost = remember { SnackbarHostState() }

    LaunchedEffect(model.message) {
        val message = model.message ?: return@LaunchedEffect
        snackbarHost.showSnackbar(message)
        model.message = null
    }

    Scaffold(
        modifier = modifier,
        snackbarHost = { SnackbarHost(snackbarHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = if (model.isSelecting) "${model.selection.size} selected" else "Pikto",
                        fontWeight = FontWeight.SemiBold,
                    )
                },
                navigationIcon = {
                    if (model.isSelecting) {
                        IconButton(onClick = model::clearSelection) { Text("✕") }
                    }
                },
                actions = {
                    if (model.isSelecting) {
                        TextButton(onClick = model::deleteSelected) { Text("Delete") }
                    }
                },
            )
        },
    ) { padding ->
        val permission = model.permission
        Column(Modifier.padding(padding).fillMaxSize()) {
            when {
                permission == null -> Loading()
                !permission.canRead -> PermissionGate(permission, model::requestPermission)
                else -> {
                    AnimatedVisibility(
                        visible = permission == PhotoPermission.LIMITED,
                        enter = expandVertically() + fadeIn(),
                        exit = shrinkVertically() + fadeOut(),
                    ) {
                        LimitedBanner(onWiden = model::requestPermission)
                    }
                    FilterRow(model.filter, model::selectFilter)
                    // Fades rather than pops, because a stream that finishes in one frame would
                    // otherwise flash a bar for no reason.
                    AnimatedVisibility(
                        visible = model.isStreaming,
                        enter = fadeIn(tween(120)),
                        exit = fadeOut(tween(400)),
                    ) {
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                    }
                    AssetGrid(
                        assets = model.assets,
                        selection = model.selection,
                        isSelecting = model.isSelecting,
                        onOpen = onOpenAsset,
                        onToggle = model::toggleSelection,
                        modifier = Modifier.weight(1f),
                    )
                    StatusLine(model.assets)
                }
            }
        }
    }
}

@Composable
private fun Loading() {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator()
    }
}

/**
 * DENIED is the only state worth a different button: asking again shows nothing on either
 * platform, so the only way forward is Settings.
 */
@Composable
private fun PermissionGate(permission: PhotoPermission, onRequest: () -> Unit) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = when (permission) {
                    PhotoPermission.DENIED -> "Pikto has no access to your photos"
                    else -> "Pikto needs access to your photos"
                },
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = when (permission) {
                    PhotoPermission.DENIED ->
                        "The system will not ask again. Grant photo access in Settings, then " +
                            "come back."

                    else -> "Nothing is read until you say yes."
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.size(20.dp))
            if (permission != PhotoPermission.DENIED) {
                Button(onClick = onRequest) { Text("Allow access") }
            }
        }
    }
}

/**
 * LIMITED is a permanent, working state rather than a step towards GRANTED, so this is a banner
 * over a full library view and not an error screen.
 */
@Composable
private fun LimitedBanner(onWiden: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.secondaryContainer,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "You picked a few photos. Everything else stays invisible.",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onWiden) { Text("Change") }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterRow(selected: Filter, onSelect: (Filter) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Filter.entries.forEach { filter ->
            FilterChip(
                selected = filter == selected,
                onClick = { onSelect(filter) },
                label = { Text(filter.label) },
            )
        }
    }
}

@Composable
private fun AssetGrid(
    assets: List<PhotoAsset>,
    selection: Set<String>,
    isSelecting: Boolean,
    onOpen: (Int) -> Unit,
    onToggle: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val gridState = rememberLazyGridState()

    // Decode the next screenful before the user gets there. Recomputing this on every scroll is
    // the intended use: re-issuing an overlapping window costs nothing.
    val prefetchIds by remember(assets) {
        derivedStateOf {
            val lastVisible = gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                ?: return@derivedStateOf emptyList()
            val from = (lastVisible + 1).coerceAtMost(assets.size)
            val to = (from + PREFETCH_AHEAD).coerceAtMost(assets.size)
            assets.subList(from, to).map { it.id }
        }
    }
    PrefetchPhotos(prefetchIds, GridThumbnail)

    LazyVerticalGrid(
        columns = GridCells.Adaptive(108.dp),
        state = gridState,
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        itemsIndexed(assets, key = { _, asset -> asset.id }) { index, asset ->
            AssetCell(
                asset = asset,
                isSelected = asset.id in selection,
                onClick = {
                    if (isSelecting) onToggle(asset.id) else onOpen(index)
                },
                onLongClick = { onToggle(asset.id) },
                // Deleting reflows the grid; without this the survivors teleport into the gaps.
                modifier = Modifier.animateItem(),
            )
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun AssetCell(
    asset: PhotoAsset,
    isSelected: Boolean,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // `rememberPhotoImage` rather than `PhotoImage` so the arrival of the bitmap is something this
    // cell can animate. An image already in memory comes back on the first composition with no
    // intermediate null, so scrolling back over a warm row does not re-fade.
    val image = rememberPhotoImage(asset.id, GridThumbnail)
    val imageAlpha by animateFloatAsState(
        targetValue = if (image == null) 0f else 1f,
        animationSpec = tween(durationMillis = 220),
        label = "thumbnailFade",
    )
    // Springs rather than tweens: selection is a direct response to a finger, and a spring is what
    // makes it feel like one.
    val selectionScale by animateFloatAsState(
        targetValue = if (isSelected) 0.88f else 1f,
        animationSpec = spring(dampingRatio = Spring.DampingRatioMediumBouncy),
        label = "selectionScale",
    )
    val selectionAlpha by animateFloatAsState(
        targetValue = if (isSelected) 1f else 0f,
        animationSpec = tween(durationMillis = 160),
        label = "selectionAlpha",
    )

    Box(
        modifier = modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        if (image != null) {
            Image(
                bitmap = image,
                // The cell is one tap target and the badges already describe it.
                contentDescription = if (asset.isVideo) "Video" else "Photo",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer {
                        alpha = imageAlpha
                        scaleX = selectionScale
                        scaleY = selectionScale
                    },
            )
        }

        if (asset.isVideo) {
            Badge(
                text = formatDuration(asset.durationMillis),
                modifier = Modifier.align(Alignment.BottomEnd).padding(4.dp),
            )
        } else if (asset.isScreenshot) {
            Badge(
                text = "Screenshot",
                modifier = Modifier.align(Alignment.BottomStart).padding(4.dp),
            )
        }

        if (selectionAlpha > 0f) {
            Box(
                Modifier
                    .fillMaxSize()
                    .graphicsLayer { alpha = selectionAlpha }
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)),
            )
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .graphicsLayer {
                        alpha = selectionAlpha
                        // Overshoots past 1 on the way in, from the same spring as the cell.
                        val pop = 1f + (1f - selectionScale) * 2f
                        scaleX = pop
                        scaleY = pop
                    }
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    text = "✓",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimary,
                )
            }
        }
    }
}

@Composable
private fun Badge(text: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(4.dp))
            .background(Color.Black.copy(alpha = 0.55f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(text, style = MaterialTheme.typography.labelSmall, color = Color.White)
    }
}

/**
 * The counts, and the byte total that arrives late.
 *
 * On iOS sizes are a separate pass, so this line is where `LibraryUpdate.Sizes` becomes visible:
 * the assets land first and the total climbs behind them.
 */
@Composable
private fun StatusLine(assets: List<PhotoAsset>) {
    val summary = remember(assets) { assets.summarise() }
    Surface(color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "${summary.photos} photos · ${summary.videos} videos",
                style = MaterialTheme.typography.labelMedium,
            )
            Spacer(Modifier.weight(1f))
            Text(
                text = buildString {
                    append(formatBytes(summary.knownBytes))
                    if (summary.assetsMissingSize > 0) {
                        append(" · sizing ${summary.assetsMissingSize}")
                    }
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
