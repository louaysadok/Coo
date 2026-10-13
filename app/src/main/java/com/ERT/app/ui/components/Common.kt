package com.ERT.app.ui.components

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BrokenImage
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.BookmarkBorder
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.SubcomposeAsyncImage
import com.ERT.app.core.MediaItem
import com.ERT.app.core.MediaType
import com.ERT.app.ui.Load

/**
 * Grid thumbnail. Always tries the API thumbnail proxy first, then falls back to the provider's
 * own CDN file, and only then gives up — this is the mitigation for dead or hotlink-blocked
 * provider links (see SITE-ANALYSIS.md §8).
 */
@Composable
fun ThumbImage(item: MediaItem, modifier: Modifier = Modifier) {
    var stage by remember(item.id) { mutableStateOf(0) } // 0 = thumb, 1 = direct url, 2 = dead
    val model = when (stage) {
        0 -> item.thumbnail ?: item.url
        1 -> item.url
        else -> null
    }

    if (model == null) {
        Placeholder(item, modifier, dead = stage >= 2)
        return
    }

    SubcomposeAsyncImage(
        model = model,
        contentDescription = item.displayTitle,
        contentScale = ContentScale.Crop,
        modifier = modifier,
        loading = {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
            }
        },
        error = {
            // Advance to the next candidate on failure rather than showing a broken box.
            androidx.compose.runtime.LaunchedEffect(item.id, stage) {
                if (stage < 2) stage++
            }
            if (stage >= 2) Placeholder(item, modifier, dead = true)
        }
    )
}

@Composable
private fun Placeholder(item: MediaItem, modifier: Modifier = Modifier, dead: Boolean) {
    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = when {
                dead -> Icons.Default.BrokenImage
                item.mediaType == MediaType.AUDIO -> Icons.Default.Audiotrack
                else -> Icons.Default.BrokenImage
            },
            contentDescription = if (dead) "Image unavailable" else null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
            modifier = Modifier.size(28.dp)
        )
    }
}

@Composable
fun MediaCard(
    item: MediaItem,
    bookmarked: Boolean,
    onOpen: () -> Unit,
    onBookmark: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onOpen),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Box {
            ThumbImage(
                item = item,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(4f / 3f)
                    .clip(RoundedCornerShape(topStart = 14.dp, topEnd = 14.dp))
            )
            if (item.mediaType == MediaType.AUDIO) {
                Icon(
                    Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.Center)
                        .size(36.dp)
                        .background(Color.Black.copy(alpha = 0.35f), RoundedCornerShape(50))
                        .padding(6.dp)
                )
            }
            if (item.mature) {
                Icon(
                    Icons.Default.Lock,
                    contentDescription = "Mature",
                    tint = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .size(16.dp)
                )
            }
            IconButton(
                onClick = onBookmark,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .size(36.dp)
            ) {
                Icon(
                    if (bookmarked) Icons.Default.Bookmark else Icons.Default.BookmarkBorder,
                    contentDescription = if (bookmarked) "Remove bookmark" else "Bookmark",
                    tint = if (bookmarked) MaterialTheme.colorScheme.primary else Color.White,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
        Column(Modifier.padding(10.dp)) {
            Text(
                item.displayTitle,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(2.dp))
            Text(
                buildString {
                    item.creator?.let { append(it) }
                    if (item.source != null) {
                        if (isNotEmpty()) append(" · ")
                        append(item.source)
                    }
                }.ifBlank { item.licenseLabel },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

// --------------------------------------------------------------- chip rows

@Composable
fun <T> ChipRow(
    label: String,
    options: List<Pair<T?, String>>,
    selected: T?,
    onSelect: (T?) -> Unit
) {
    Column(Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(end = 8.dp)) {
            items(options.size) { i ->
                val (value, text) = options[i]
                FilterChip(
                    selected = selected == value,
                    onClick = { onSelect(value) },
                    label = { Text(text, style = MaterialTheme.typography.labelLarge) }
                )
            }
        }
    }
}

@Composable
fun TagChips(tags: List<com.ERT.app.core.Tag>, onTag: (String) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items(tags.size) { i ->
            AssistChip(
                onClick = { onTag(tags[i].name) },
                label = { Text(tags[i].name) },
                colors = AssistChipDefaults.assistChipColors()
            )
        }
    }
}

// ------------------------------------------------------------ state blocks

/** Spinner with a Cancel button — no screen may spin without a way out. */
@Composable
fun BusyBlock(url: String, refreshing: Boolean, showUrl: Boolean = true, onCancel: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        CircularProgressIndicator(modifier = Modifier.size(28.dp), strokeWidth = 3.dp)
        Text(
            if (refreshing) "Loading…" else "Fetching from Openverse…",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (showUrl) {
            Text(
                com.ERT.app.core.Url.pretty(url),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center
            )
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

@Composable
fun ErrorBlock(
    error: com.ERT.app.core.ApiError,
    onRetry: () -> Unit,
    onOpenSettings: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier.fillMaxWidth().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            when (error.kind) {
                com.ERT.app.core.ApiError.Kind.RATE_LIMIT -> "Rate limited"
                com.ERT.app.core.ApiError.Kind.NOT_FOUND -> "Not found"
                com.ERT.app.core.ApiError.Kind.OFFLINE -> "Offline"
                com.ERT.app.core.ApiError.Kind.TIMEOUT -> "Timed out"
                com.ERT.app.core.ApiError.Kind.BAD_REQUEST -> "Bad request"
                com.ERT.app.core.ApiError.Kind.AUTH -> "Not authorised"
                com.ERT.app.core.ApiError.Kind.SERVER -> "Server error"
                else -> "Something went wrong"
            },
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error
        )
        Text(
            error.uiMessage, // e.g. "Not found. (404) · /images/abc"
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        if (error.kind == com.ERT.app.core.ApiError.Kind.RATE_LIMIT) {
            Text(
                "Openverse allows anonymous clients 5 requests/hour. A free API key raises this to 100/minute.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
            error.retryAfterSec?.let {
                Text(
                    "Retry in about ${formatWait(it)}.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (onOpenSettings != null) {
                OutlinedButton(onClick = onOpenSettings) { Text("Add free API key") }
            }
        }
        TextButton(onClick = onRetry) { Text("Retry") }
    }
}

fun formatWait(sec: Long): String = when {
    sec < 60 -> "$sec s"
    sec < 3600 -> "${sec / 60} min"
    else -> "${sec / 3600} h ${(sec % 3600) / 60} min"
}

@Composable
fun EmptyBlock(message: String, url: String? = null) {
    Column(
        Modifier.fillMaxWidth().padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Text(message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        url?.let {
            Text(
                com.ERT.app.core.Url.pretty(it),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
        }
    }
}

@Composable
fun CacheBadge(fromCache: Boolean, atMs: Long) {
    if (!fromCache) return
    Text(
        "cached · ${ageLabel(atMs)}",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
    )
}

fun ageLabel(atMs: Long): String {
    val s = (System.currentTimeMillis() - atMs) / 1000L
    return when {
        s < 60 -> "${s.coerceAtLeast(0)}s ago"
        s < 3600 -> "${s / 60}m ago"
        else -> "${s / 3600}h ago"
    }
}
