package com.ERT.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.LibraryBooks
import androidx.compose.material.icons.filled.Audiotrack
import androidx.compose.material.icons.filled.Bookmarks
import androidx.compose.material.icons.filled.GridOn
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ERT.app.core.MediaType
import com.ERT.app.ui.screens.AudioScreen
import com.ERT.app.ui.screens.DetailScreen
import com.ERT.app.ui.screens.DiagnosticsScreen
import com.ERT.app.ui.screens.ImagesScreen
import com.ERT.app.ui.screens.SavedScreen
import com.ERT.app.ui.screens.SearchScreen
import com.ERT.app.ui.screens.SettingsScreen
import com.ERT.app.ui.screens.SourcesScreen
import com.ERT.app.ui.theme.ErtTheme

/** Where the user can be. Bottom tabs plus three overlay screens pushed on top. */
sealed interface Screen {
    data object Images : Screen
    data object Audio : Screen
    data object Search : Screen
    data object Sources : Screen
    data object Saved : Screen
    data class Detail(val id: String, val type: MediaType) : Screen
    data object Settings : Screen
    data object Diagnostics : Screen
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ErtTheme {
                App()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(vm: MainViewModel = viewModel()) {
    var screen by rememberSaveable { mutableStateOf<Screen>(Screen.Images) }
    var tab by rememberSaveable { mutableStateOf<Screen>(Screen.Images) }

    // Back from an overlay returns to the tab you came from; back from a tab is the system's.
    // An error block can ask to be taken to Settings (e.g. "Add free API key" on a 429).
    LaunchedEffect(vm.pendingSettingsRequest) {
        if (vm.pendingSettingsRequest) {
            screen = Screen.Settings
            vm.pendingSettingsRequest = false
        }
    }

    val onOverlay: Boolean = screen is Screen.Detail || screen is Screen.Settings || screen is Screen.Diagnostics
    BackHandler(enabled = onOverlay) {
        vm.cancelDetail()
        vm.cancelDiagnostics()
        screen = tab
    }

    Scaffold(
        topBar = {
            val title = when (screen) {
                Screen.Images -> "Images"
                Screen.Audio -> "Audio"
                Screen.Search -> "Search"
                Screen.Sources -> "Sources"
                Screen.Saved -> "Saved"
                is Screen.Detail -> vm.selected?.displayTitle ?: "Details"
                Screen.Settings -> "Settings"
                Screen.Diagnostics -> "Diagnostics"
            }
            TopAppBar(
                title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                colors = TopAppBarDefaults.topAppBarColors(),
                actions = {
                    when (screen) {
                        Screen.Images -> IconButton(onClick = { vm.images.retry() }) {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                        Screen.Audio -> IconButton(onClick = { vm.audio.retry() }) {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                        Screen.Sources -> IconButton(onClick = { vm.loadSources(force = true) }) {
                            Icon(Icons.Default.Refresh, "Refresh")
                        }
                        else -> Unit
                    }
                    var menu by remember { mutableStateOf(false) }
                    IconButton(onClick = { menu = true }) { Icon(Icons.Default.MoreVert, "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(
                            text = { Text("Diagnostics") },
                            onClick = { menu = false; screen = Screen.Diagnostics }
                        )
                        DropdownMenuItem(
                            text = { Text("Settings") },
                            onClick = { menu = false; screen = Screen.Settings }
                        )
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                Tab.entries.forEach { t ->
                    NavigationBarItem(
                        selected = tab === t.screen && !onOverlay,
                        onClick = {
                            tab = t.screen
                            screen = t.screen
                            when (t.screen) {
                                Screen.Sources -> vm.loadSources()
                                else -> Unit
                            }
                        },
                        icon = { Icon(t.icon, t.label) },
                        label = { Text(t.label) },
                        colors = NavigationBarItemDefaults.colors()
                    )
                }
            }
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when (screen) {
                Screen.Images -> ImagesScreen(vm) { item -> vm.openDetail(item); screen = Screen.Detail(item.id, item.mediaType) }
                Screen.Audio -> AudioScreen(vm) { item -> vm.openDetail(item); screen = Screen.Detail(item.id, item.mediaType) }
                Screen.Search -> SearchScreen(vm) { item -> vm.openDetail(item); screen = Screen.Detail(item.id, item.mediaType) }
                Screen.Sources -> SourcesScreen(vm) { source ->
                    vm.browseBySource(source)
                    tab = Screen.Images
                    screen = Screen.Images
                }
                Screen.Saved -> SavedScreen(vm) { item -> vm.openDetail(item); screen = Screen.Detail(item.id, item.mediaType) }
                is Screen.Detail -> DetailScreen(vm, onBack = { screen = tab })
                Screen.Settings -> SettingsScreen(vm, onBack = { screen = tab })
                Screen.Diagnostics -> DiagnosticsScreen(vm, onBack = { screen = tab })
            }
        }
    }
}

private enum class Tab(val screen: Screen, val label: String, val icon: ImageVector) {
    IMAGES(Screen.Images, "Images", Icons.Default.PhotoLibrary),
    AUDIO(Screen.Audio, "Audio", Icons.Default.Audiotrack),
    SEARCH(Screen.Search, "Search", Icons.Default.Search),
    SOURCES(Screen.Sources, "Sources", Icons.Default.GridOn),
    SAVED(Screen.Saved, "Saved", Icons.Default.Bookmarks)
}
