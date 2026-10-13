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
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.ERT.app.MainViewModel
import com.ERT.app.core.MediaItem
import com.ERT.app.core.MediaType
import com.ERT.app.core.Requests
import com.ERT.app.ui.components.CacheBadge
import com.ERT.app.ui.components.ChipRow
import com.ERT.app.ui.components.FeedScreen
import androidx.compose.runtime.collectAsState

/** Images feed: newest-first browse with category / aspect-ratio / size / licence / sort chips. */
@Composable
fun ImagesScreen(vm: MainViewModel, onOpen: (MediaItem) -> Unit) {
    val state by vm.images.state.collectAsState()

    var category by rememberSaveable { mutableStateOf<String?>(null) }
    var aspect by rememberSaveable { mutableStateOf<String?>(null) }
    var size by rememberSaveable { mutableStateOf<String?>(null) }
    var licenseType by rememberSaveable { mutableStateOf<String?>(null) }
    var sort by rememberSaveable { mutableStateOf(Requests.Sort.INDEXED_ON) }
    var dir by rememberSaveable { mutableStateOf(Requests.Dir.DESC) }

    fun apply() {
        vm.images.setQuery(
            Requests.FeedQuery(
                type = MediaType.IMAGE,
                category = category,
                aspectRatio = aspect,
                size = size,
                licenseType = licenseType,
                sort = sort,
                dir = dir,
                mature = vm.mature
            )
        )
    }

    FeedScreen(vm, vm.images, filters = {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            ChipRow("Category", Requests.IMAGE_CATEGORIES, category) { category = it; apply() }
            ChipRow("Shape", Requests.ASPECT_RATIOS, aspect) { aspect = it; apply() }
            ChipRow("Size", Requests.SIZES, size) { size = it; apply() }
            ChipRow("Licence", Requests.LICENSE_TYPES, licenseType) { licenseType = it; apply() }
            ChipRow("Sort", listOf(Requests.Sort.INDEXED_ON to "Newest", Requests.Sort.RELEVANCE to "Relevance"), sort) {
                sort = it ?: Requests.Sort.INDEXED_ON; apply()
            }
            if (sort == Requests.Sort.INDEXED_ON) {
                ChipRow("Order", listOf(Requests.Dir.DESC to "Newest first", Requests.Dir.ASC to "Oldest first"), dir) {
                    dir = it ?: Requests.Dir.DESC; apply()
                }
            }
            if (state is com.ERT.app.ui.Load.Ready) {
                CacheBadge((state as com.ERT.app.ui.Load.Ready).fromCache, (state as com.ERT.app.ui.Load.Ready).atMs)
            }
        }
    }, onOpen = onOpen)
}
