package com.ERT.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.OpenInBrowser
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem as ExoMediaItem
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.SubcomposeAsyncImage
import com.ERT.app.MainViewModel
import com.ERT.app.core.MediaItem
import com.ERT.app.core.MediaType
import com.ERT.app.core.PageResult
import com.ERT.app.ui.Load
import com.ERT.app.ui.components.BusyBlock
import com.ERT.app.ui.components.EmptyBlock
import com.ERT.app.ui.components.ErrorBlock
import com.ERT.app.ui.components.MediaCard
import com.ERT.app.ui.components.TagChips
import kotlinx.coroutines.delay

/**
 * Full record view: the media itself (image or audio, played straight from the provider's own CDN),
 * the licence/attribution block, and related items.
 *
 * The item passed in from the grid is shown immediately and then refreshed, so navigation is never
 * blocked on the network.
 */
@Composable
fun DetailScreen(vm: MainViewModel, onBack: () -> Unit) {
    val item = vm.selected
    val detail by vm.detail.collectAsState()
    val related by vm.related.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    val context = LocalContext.current

    if (item == null) {
        when (val s = detail) {
            is Load.Failed -> ErrorBlock(error = s.error, onRetry = { vm.loadDetail(MediaType.IMAGE, "", force = true) })
            is Load.Busy -> BusyBlock(url = s.url, refreshing = s.refreshing, onCancel = { vm.cancelDetail() })
            else -> EmptyBlock("Nothing selected.")
        }
        return
    }

    val bookmarked = bookmarks.any { it.id == item.id }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        // ---- media -------------------------------------------------------
        Card(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
        ) {
            when (item.mediaType) {
                MediaType.IMAGE -> ImageStage(item)
                MediaType.AUDIO -> AudioStage(item)
            }
        }

        // ---- refresh / error strip --------------------------------------
        when (val s = detail) {
            is Load.Busy -> Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp)
                Text("Refreshing details…", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                OutlinedButton(onClick = { vm.cancelDetail() }) { Text("Cancel") }
            }
            is Load.Failed -> ErrorBlock(error = s.error, onRetry = { vm.loadDetail(item.mediaType, item.id, force = true) })
            else -> Unit
        }

        // ---- metadata ----------------------------------------------------
        Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(item.displayTitle, style = MaterialTheme.typography.titleLarge)
            item.creator?.let {
                Text("by $it", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(item.licenseLabel, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                item.provider?.let { Text("via $it", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            item.attribution?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            val facts = ArrayList<String>()
            if (item.width != null && item.height != null) facts.add("${item.width}×${item.height}")
            item.durationMs?.let { facts.add(formatDuration(it)) }
            item.filesize?.let { facts.add(formatBytes(it)) }
            item.filetype?.let { facts.add(it.uppercase()) }
            item.category?.let { facts.add(it) }
            if (facts.isNotEmpty()) {
                Text(facts.joinToString("  ·  "), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }

        // ---- tags --------------------------------------------------------
        if (item.tags.isNotEmpty()) {
            Text("Tags", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 16.dp, top = 14.dp))
            TagChips(item.tags) { tag -> vm.onSearchTextChanged(tag); vm.submitSearch(item.mediaType); onBack() }
        }

        // ---- actions -----------------------------------------------------
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { vm.toggleBookmark(item) }) {
                Icon(if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(if (bookmarked) "Saved" else "Save")
            }
            item.foreignLandingUrl?.let { url ->
                OutlinedButton(onClick = {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                }) {
                    Icon(Icons.Default.OpenInBrowser, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Original page")
                }
            }
            item.url?.let { url ->
                OutlinedButton(onClick = {
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, "$url\n\n${item.attribution ?: item.displayTitle}")
                    }, "Share"))
                }) {
                    Icon(Icons.Default.Share, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Share file")
                }
            }
        }

        // ---- related -----------------------------------------------------
        Text("Related", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(horizontal = 16.dp))
        Spacer(Modifier.height(8.dp))
        when (val r = related) {
            is Load.Busy -> if (r.refreshing.not()) BusyBlock(url = r.url, refreshing = true, showUrl = false, onCancel = { vm.repo.cancel(com.ERT.app.core.Repo.SLOT_RELATED) })
            is Load.Failed -> ErrorBlock(error = r.error, onRetry = { vm.loadDetail(item.mediaType, item.id, force = true) })
            is Load.Ready -> RelatedRow(vm, r.value)
            else -> Unit
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun RelatedRow(vm: MainViewModel, page: PageResult) {
    if (page.items.isEmpty()) {
        EmptyBlock("No related items.")
        return
    }
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        page.items.take(10).forEach { item ->
            MediaCard(
                item = item,
                bookmarked = false,
                onOpen = { vm.openDetail(item) },
                onBookmark = { vm.toggleBookmark(item) },
                modifier = Modifier.width(170.dp)
            )
        }
    }
}

/** Image viewer: pinch to zoom, drag to pan, double-tap to reset. */
@Composable
private fun ImageStage(item: MediaItem) {
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    val transformState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        offset += panChange
    }

    Box(
        Modifier
            .fillMaxWidth()
            .aspectRatio(if (item.height != null && item.width != null && item.height > 0) item.width.toFloat() / item.height.toFloat() else 4f / 3f)
            .background(Color.Black)
            .transformable(transformState)
            .pointerInput(Unit) {
                detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 5f)
                    offset += pan
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onDoubleTap = {
                    scale = 1f
                    offset = Offset.Zero
                })
            }
    ) {
        val url = item.url ?: item.thumbnail
        if (url != null) {
            SubcomposeAsyncImage(
                model = url,
                contentDescription = item.displayTitle,
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer(
                        scaleX = scale,
                        scaleY = scale,
                        translationX = offset.x,
                        translationY = offset.y
                    ),
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(28.dp), strokeWidth = 2.dp)
                    }
                },
                error = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "This file is no longer available at the source.\nTry “Original page”.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        } else {
            EmptyBlock("No file URL for this record.")
        }
    }
}

/** Audio player. Streams the provider's file with ExoPlayer; nothing is downloaded or re-hosted. */
@Composable
private fun AudioStage(item: MediaItem) {
    val context = LocalContext.current
    val url = item.url ?: item.altFiles.firstOrNull()?.url
    if (url == null) {
        EmptyBlock("No playable file for this record.")
        return
    }

    val player = remember(item.id) { ExoPlayer.Builder(context).build() }
    var playing by remember { mutableStateOf(false) }
    var position by remember { mutableLongStateOf(0L) }
    var duration by remember { mutableLongStateOf(item.durationMs ?: 0L) }

    LaunchedEffect(url) {
        player.setMediaItem(ExoMediaItem.fromUri(url))
        player.prepare()
        player.playWhenReady = true
    }

    // Progress ticker: 4 Hz is plenty and keeps recomposition cheap.
    LaunchedEffect(player) {
        while (true) {
            position = player.currentPosition
            val d = player.duration
            if (d > 0) duration = d
            playing = player.isPlaying
            delay(250)
        }
    }

    DisposableEffect(item.id) {
        onDispose {
            player.release()
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        IconButton(
            onClick = { if (player.isPlaying) player.pause() else player.play() },
            modifier = Modifier
                .size(56.dp)
                .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(50))
                .align(Alignment.CenterHorizontally)
        ) {
            Icon(
                if (playing) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = if (playing) "Pause" else "Play",
                tint = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
        Slider(
            value = if (duration > 0) position.coerceAtMost(duration).toFloat() else 0f,
            valueRange = 0f..(duration.takeIf { it > 0 } ?: 1L).toFloat(),
            onValueChange = { position = it.toLong(); player.seekTo(it.toLong()) },
            modifier = Modifier.fillMaxWidth()
        )
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(formatDuration(position), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(formatDuration(duration), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatDuration(ms: Long): String {
    val total = (ms / 1000L).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) String.format("%d:%02d:%02d", h, m, s) else String.format("%d:%02d", m, s)
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
