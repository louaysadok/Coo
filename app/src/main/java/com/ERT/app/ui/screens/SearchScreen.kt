package com.ERT.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ERT.app.MainViewModel
import com.ERT.app.core.MediaItem
import com.ERT.app.core.MediaType
import com.ERT.app.ui.components.EmptyBlock
import com.ERT.app.ui.components.FeedScreen

/** Search across images or audio. Queries are UTF-8 encoded and capped at 200 chars server-side. */
@Composable
fun SearchScreen(vm: MainViewModel, onOpen: (MediaItem) -> Unit) {
    var type by rememberSaveable { mutableStateOf(MediaType.IMAGE) }
    val state by vm.search.state.collectAsState()
    val items by vm.search.items.collectAsState()

    fun submit() {
        if (vm.searchText.isBlank()) return
        vm.search.setQuery(
            com.ERT.app.core.Requests.FeedQuery(type = type, q = vm.searchText.trim(), mature = vm.mature)
        )
    }

    Column(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedTextField(
                value = vm.searchText,
                onValueChange = vm::onSearchTextChanged,
                modifier = Modifier.weight(1f),
                singleLine = true,
                placeholder = { Text("Search open-licensed media…") },
                leadingIcon = { Icon(Icons.Default.Search, null) },
                trailingIcon = {
                    if (vm.searchText.isNotEmpty()) {
                        IconButton(onClick = { vm.onSearchTextChanged("") }) {
                            Icon(Icons.Default.Close, "Clear")
                        }
                    }
                },
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(imeAction = androidx.compose.ui.text.input.ImeAction.Search),
                keyboardActions = androidx.compose.foundation.text.KeyboardActions(onSearch = { submit() })
            )
        }
        Row(Modifier.padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = type == MediaType.IMAGE, onClick = { type = MediaType.IMAGE; submit() }, label = { Text("Images") })
            FilterChip(selected = type == MediaType.AUDIO, onClick = { type = MediaType.AUDIO; submit() }, label = { Text("Audio") })
        }

        if (state is com.ERT.app.ui.Load.Idle && items.isEmpty()) {
            EmptyBlock("Search Openverse — images and audio under open licences.")
        } else {
            FeedScreen(vm, vm.search, filters = null, onOpen = onOpen)
        }
    }

    // Keep results in step with the media-type toggle.
    LaunchedEffect(type) {
        if (items.isNotEmpty()) submit()
    }
}
