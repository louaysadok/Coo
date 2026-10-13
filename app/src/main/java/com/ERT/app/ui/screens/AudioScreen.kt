package com.ERT.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ERT.app.MainViewModel
import com.ERT.app.core.MediaItem
import com.ERT.app.core.MediaType
import com.ERT.app.core.Requests
import com.ERT.app.ui.Load
import com.ERT.app.ui.components.CacheBadge
import com.ERT.app.ui.components.ChipRow
import com.ERT.app.ui.components.FeedScreen

/** Audio feed. Tapping a card opens the detail screen, which plays the file from its own CDN. */
@Composable
fun AudioScreen(vm: MainViewModel, onOpen: (MediaItem) -> Unit) {
    val state by vm.audio.state.collectAsState()

    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var length by rememberSaveable { mutableStateOf<String?>(null) }
    var licenseType by rememberSaveable { mutableStateOf<String?>(null) }

    fun apply() {
        vm.audio.setQuery(
            Requests.FeedQuery(
                type = MediaType.AUDIO,
                category = category,
                length = length,
                licenseType = licenseType,
                sort = Requests.Sort.INDEXED_ON,
                dir = Requests.Dir.DESC,
                mature = vm.mature
            )
        )
    }

    FeedScreen(vm, vm.audio, filters = {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChipRow("Category", Requests.AUDIO_CATEGORIES, category) { category = it; apply() }
            ChipRow("Length", Requests.LENGTHS, length) { length = it; apply() }
            ChipRow("Licence", Requests.LICENSE_TYPES, licenseType) { licenseType = it; apply() }
            if (state is Load.Ready) {
                CacheBadge((state as Load.Ready).fromCache, (state as Load.Ready).atMs)
            }
        }
    }, onOpen = onOpen)
}
