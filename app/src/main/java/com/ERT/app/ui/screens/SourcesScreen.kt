package com.ERT.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ERT.app.MainViewModel
import com.ERT.app.core.SourceStat
import com.ERT.app.ui.Load
import com.ERT.app.ui.components.BusyBlock
import com.ERT.app.ui.components.EmptyBlock
import com.ERT.app.ui.components.ErrorBlock
import kotlinx.coroutines.launch

/**
 * A–Z index of providers (from /v1/{type}/stats/), with type-to-filter and a letter jump row.
 * Tapping a provider browses that provider's images.
 */
@Composable
fun SourcesScreen(vm: MainViewModel, onSource: (String) -> Unit) {
    val state by vm.sources.collectAsState()
    val listState = rememberLazyListState()
    val scope = rememberLazyCoroutineScope()

    LaunchedEffect(Unit) {
        if (state !is Load.Ready) vm.loadSources(force = false)
    }

    Column(Modifier.fillMaxSize()) {
        OutlinedTextField(
            value = vm.sourceFilter,
            onValueChange = vm::onSourceFilterChanged,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            singleLine = true,
            placeholder = { Text("Filter providers…") },
            leadingIcon = { Icon(Icons.Default.Search, null) }
        )

        when (val s = state) {
            is Load.Idle -> BusyBlock(url = "…", refreshing = false, showUrl = false, onCancel = { vm.repo.cancel(com.ERT.app.core.Repo.SLOT_STATS) })
            is Load.Busy -> BusyBlock(url = s.url, refreshing = s.refreshing, onCancel = { vm.repo.cancel(com.ERT.app.core.Repo.SLOT_STATS) })
            is Load.Failed -> ErrorBlock(error = s.error, onRetry = { vm.loadSources(force = true) }, onOpenSettings = { vm.pendingSettingsRequest = true })
            is Load.Ready -> {
                val all = s.value
                val filtered = all.filter {
                    val name = it.displayName ?: it.sourceName
                    vm.sourceFilter.isBlank() || name.contains(vm.sourceFilter, ignoreCase = true) ||
                        it.sourceName.contains(vm.sourceFilter, ignoreCase = true)
                }

                if (filtered.isEmpty()) {
                    EmptyBlock("No provider matches “${vm.sourceFilter}”.", url = s.url)
                } else {
                    // Flat rows: a header row per initial, then its providers.
                    val rows = ArrayList<Row>()
                    var lastInitial = ""
                    filtered.forEach {
                        val initial = initialOf(it.displayName ?: it.sourceName)
                        if (initial != lastInitial) {
                            rows.add(Row.Header(initial))
                            lastInitial = initial
                        }
                        rows.add(Row.Item(it))
                    }

                    val jumpTargets = rows.mapIndexedNotNull { i, r -> if (r is Row.Header) r.letter to i else null }

                    LetterJump(jumpTargets.map { it.first }) { letter ->
                        val index = jumpTargets.firstOrNull { it.first == letter }?.second ?: return@LetterJump
                        scope.launch { listState.animateScrollToItem(index) }
                    }

                    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
                        itemsIndexed(rows) { _, row ->
                            when (row) {
                                is Row.Header -> Text(
                                    row.letter,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                                is Row.Item -> SourceRow(row.stat, onSource)
                            }
                        }
                    }
                }
            }
        }
    }
}

private sealed interface Row {
    data class Header(val letter: String) : Row
    data class Item(val stat: SourceStat) : Row
}

@Composable
private fun LetterJump(letters: List<String>, onJump: (String) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        items(letters.size) { i ->
            TextButton(onClick = { onJump(letters[i]) }, modifier = Modifier.padding(0.dp)) {
                Text(letters[i], style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun SourceRow(stat: SourceStat, onSource: (String) -> Unit) {
    Surface(
        onClick = { onSource(stat.sourceName) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(Modifier.weight(1f)) {
                Text(stat.displayName ?: stat.sourceName, style = MaterialTheme.typography.bodyMedium)
                Text(
                    stat.sourceName,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                stat.imageCount?.let { formatCount(it) } ?: "—",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.End
            )
        }
    }
}

private fun initialOf(name: String): String {
    val c = name.firstOrNull() ?: return "#"
    return if (c.isLetter()) c.uppercaseChar().toString() else "#"
}

private fun formatCount(n: Long): String = when {
    n >= 1_000_000_000 -> "%.1fB".format(n / 1_000_000_000.0)
    n >= 1_000_000 -> "%.1fM".format(n / 1_000_000.0)
    n >= 1_000 -> "%.1fk".format(n / 1_000.0)
    else -> n.toString()
}

@Composable
private fun rememberLazyCoroutineScope() = rememberCoroutineScope()
