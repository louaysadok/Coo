package com.ERT.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ERT.app.MainViewModel
import com.ERT.app.core.MediaItem
import com.ERT.app.ui.Load
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter

/**
 * The browse/search grid shared by the Images, Audio and Search screens.
 *
 * Two columns fixed: on a 720x1600 phone that gives ~360 dp cards, which is the sweet spot for
 * thumbnails you can actually recognise. Infinite scroll loads the next page when the user reaches
 * the last row; the Cancel button in [BusyBlock] aborts the in-flight request.
 */
@Composable
fun FeedScreen(
    vm: MainViewModel,
    holder: MainViewModel.FeedHolder,
    filters: (@Composable () -> Unit)? = null,
    onOpen: (MediaItem) -> Unit
) {
    val state by holder.state.collectAsState()
    val items by holder.items.collectAsState()
    val bookmarks by vm.bookmarks.collectAsState()
    val bookmarkIds = bookmarks.map { it.id }.toSet()

    LaunchedEffect(Unit) {
        if (state !is Load.Ready && items.isEmpty()) holder.load(force = false)
    }

    Column(Modifier.fillMaxSize()) {
        filters?.invoke()

        when (val s = state) {
            is Load.Idle -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                BusyBlock(url = holder.query.toUrl(), refreshing = false, onCancel = { holder.cancel() })
            }

            is Load.Failed -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                ErrorBlock(
                    error = s.error,
                    onRetry = { holder.retry() },
                    onOpenSettings = { vm.pendingSettingsRequest = true }
                )
            }

            is Load.Busy -> {
                if (items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        BusyBlock(url = s.url, refreshing = s.refreshing, onCancel = { holder.cancel() })
                    }
                } else {
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Grid(vm, items, bookmarkIds, holder, onOpen)
                }
            }

            is Load.Ready -> {
                if (items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        EmptyBlock(
                            message = "No results for this query.",
                            url = s.url
                        )
                    }
                } else {
                    Grid(vm, items, bookmarkIds, holder, onOpen)
                }
            }
        }
    }
}

@Composable
private fun Grid(
    vm: MainViewModel,
    items: List<MediaItem>,
    bookmarkIds: Set<String>,
    holder: MainViewModel.FeedHolder,
    onOpen: (MediaItem) -> Unit
) {
    val gridState = rememberLazyGridState()

    // Infinite scroll: kick off the next page a row early so it feels continuous.
    LaunchedEffect(items.size) {
        snapshotFlow { gridState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .filter { last -> last >= items.size - 5 && items.isNotEmpty() }
            .collect { holder.loadMore() }
    }

    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        state = gridState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        items(items, key = { it.id }) { item ->
            MediaCard(
                item = item,
                bookmarked = bookmarkIds.contains(item.id),
                onOpen = { onOpen(item) },
                onBookmark = { vm.toggleBookmark(item) }
            )
        }
        item {
            LoadMoreRow(holder)
        }
    }
}

@Composable
private fun LoadMoreRow(holder: MainViewModel.FeedHolder) {
    val state by holder.state.collectAsState()
    val items by holder.items.collectAsState()
    Box(
        Modifier.fillMaxWidth().padding(vertical = 12.dp),
        contentAlignment = Alignment.Center
    ) {
        when (state) {
            is Load.Busy -> CircularProgressIndicator(Modifier.padding(4.dp), strokeWidth = 2.dp)
            is Load.Failed -> {
                val err = (state as Load.Failed).error
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    androidx.compose.material3.Text(
                        err.uiMessage,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error
                    )
                    Button(onClick = { holder.retry() }) { androidx.compose.material3.Text("Retry") }
                }
            }
            else -> if (items.isNotEmpty()) Button(onClick = { holder.loadMore() }) {
                androidx.compose.material3.Text("Load more")
            }
        }
    }
}
