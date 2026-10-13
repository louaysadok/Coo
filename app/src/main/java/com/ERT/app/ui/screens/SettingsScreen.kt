package com.ERT.app.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.ERT.app.BuildConfig
import com.ERT.app.MainViewModel

@Composable
fun SettingsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val context = LocalContext.current
    val keyStatus by vm.keyStatus.collectAsState()
    val cacheSize by vm.cacheSize.collectAsState()
    var mature by remember { mutableStateOf(vm.mature) }

    LaunchedEffectRefresh { vm.refreshCacheSize() }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        Text("Content", style = MaterialTheme.typography.titleMedium)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Include mature content")
                Text(
                    "Sends mature=true. Off by default.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(checked = mature, onCheckedChange = {
                mature = it
                vm.mature = it
            })
        }

        HorizontalDivider()

        Text("API key (free)", style = MaterialTheme.typography.titleMedium)
        Text(
            "Anonymous access works but is limited to 5 requests/hour. Registering a free key at " +
                "api.openverse.org raises it to 100/minute. Your key is stored only on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = {
            context.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse("https://api.openverse.org/v1/#section/Register-and-Authenticate"))
            )
        }) { Text("Register for a free key") }

        OutlinedTextField(
            value = vm.clientId,
            onValueChange = vm::onClientIdChanged,
            label = { Text("Client ID") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        OutlinedTextField(
            value = vm.clientSecret,
            onValueChange = vm::onClientSecretChanged,
            label = { Text("Client secret") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.saveKey() }) { Text("Save & test") }
            OutlinedButton(onClick = { vm.clearKey() }) { Text("Sign out") }
        }
        if (keyStatus.isNotBlank()) {
            Text(keyStatus, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        HorizontalDivider()

        Text("Storage", style = MaterialTheme.typography.titleMedium)
        Text(
            "Cache: ${formatKb(cacheSize)} · requests in the last hour: ${vm.hitsLastHour}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        OutlinedButton(onClick = { vm.clearCache() }) { Text("Clear cache") }

        HorizontalDivider()

        Text("About", style = MaterialTheme.typography.titleMedium)
        Text(
            "ERT ${BuildConfig.VERSION_NAME} (build ${BuildConfig.VERSION_CODE})\n" +
                "Openverse client — media is loaded from each provider's own CDN.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LaunchedEffectRefresh(block: () -> Unit) {
    androidx.compose.runtime.LaunchedEffect(Unit) { block() }
}

private fun formatKb(bytes: Long): String = when {
    bytes >= 1_000_000 -> "%.1f MB".format(bytes / 1_000_000.0)
    bytes >= 1_000 -> "%.0f KB".format(bytes / 1_000.0)
    else -> "$bytes B"
}
