package com.ERT.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ERT.app.MainViewModel
import com.ERT.app.core.MediaItem
import com.ERT.app.ui.components.EmptyBlock
import com.ERT.app.ui.components.MediaCard
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Bookmarks and history, both stored locally as JSON in SharedPreferences. */
@Composable
fun SavedScreen(vm: MainViewModel, onOpen: (MediaItem) -> Unit) {
    var tab by rememberSaveable { mutableStateOf(0) }
    val bookmarks by vm.bookmarks.collectAsState()
    val history by vm.history.collectAsState()
    val bookmarkIds = bookmarks.map { it.id }.toSet()

    LaunchedEffect(Unit) {
        vm.refreshBookmarks()
        vm.refreshHistory()
    }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = tab == 0, onClick = { tab = 0 }, label = { Text("Bookmarks (${bookmarks.size})") })
            FilterChip(selected = tab == 1, onClick = { tab = 1 }, label = { Text("History (${history.size})") })
            if (tab == 1 && history.isNotEmpty()) {
                TextButton(onClick = { vm.clearHistory() }) { Text("Clear") }
            }
        }

        when (tab) {
            0 -> if (bookmarks.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyBlock("No bookmarks yet. Tap the bookmark icon on any result.")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(bookmarks, key = { it.id }) { item ->
                        MediaCard(
                            item = item,
                            bookmarked = bookmarkIds.contains(item.id),
                            onOpen = { onOpen(item) },
                            onBookmark = { vm.toggleBookmark(item) }
                        )
                    }
                }
            }
            else -> if (history.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    EmptyBlock("Nothing viewed yet.")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(history, key = { it.first.id + it.second }) { (item, at) ->
                        Column {
                            MediaCard(
                                item = item,
                                bookmarked = bookmarkIds.contains(item.id),
                                onOpen = { onOpen(item) },
                                onBookmark = { vm.toggleBookmark(item) }
                            )
                            Text(
                                SimpleDateFormat("d MMM, HH:mm", Locale.getDefault()).format(Date(at)),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
