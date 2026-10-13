package com.ERT.app.ui.screens

import android.content.Intent
import android.widget.Toast
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.ERT.app.MainViewModel
import com.ERT.app.core.Repo
import com.ERT.app.core.Requests
import com.ERT.app.ui.Load
import com.ERT.app.ui.components.BusyBlock
import com.ERT.app.ui.components.ErrorBlock

/**
 * Diagnostics: fetch any endpoint, see the parsed summary, and copy the raw JSON.
 *
 * This is the screen that makes a future breakage fixable in one round-trip — if a page stops
 * parsing, run the matching preset here and send the raw payload.
 */
@Composable
fun DiagnosticsScreen(vm: MainViewModel, onBack: () -> Unit) {
    val state by vm.diag.collectAsState()
    val context = LocalContext.current
    val clipboard = LocalClipboardManager.current

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text(
            "Fetch any Openverse endpoint and inspect exactly what came back.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(Requests.PRESETS) { preset ->
                AssistChip(
                    onClick = { vm.onDiagPathChanged(preset.path) },
                    label = { Text(preset.label) }
                )
            }
        }

        OutlinedTextField(
            value = vm.diagPath,
            onValueChange = vm::onDiagPathChanged,
            label = { Text("Path or full URL") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.runDiagnostics(force = true) }) { Text("Fetch") }
            OutlinedButton(onClick = { vm.cancelDiagnostics() }) { Text("Cancel") }
            Switch(checked = vm.diagUseCache, onCheckedChange = vm::onDiagUseCacheChanged)
            Text("Allow cache", style = MaterialTheme.typography.labelSmall)
        }

        when (val s = state) {
            is Load.Idle -> Text("Nothing fetched yet.", style = MaterialTheme.typography.bodySmall)
            is Load.Busy -> BusyBlock(url = s.url, refreshing = false, onCancel = { vm.cancelDiagnostics() })
            is Load.Failed -> ErrorBlock(error = s.error, onRetry = { vm.runDiagnostics(force = true) })
            is Load.Ready -> {
                val r = s.value
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
                ) {
                    Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("HTTP ${r.status} · ${r.ms} ms" + (if (r.fromCache) " · from cache" else ""),
                            style = MaterialTheme.typography.titleMedium)
                        Text(r.url, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(r.summary, style = MaterialTheme.typography.bodySmall)
                    }
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString(r.body))
                        Toast.makeText(context, "Raw JSON copied", Toast.LENGTH_SHORT).show()
                    }) { Text("Copy raw JSON") }
                    OutlinedButton(onClick = {
                        context.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, r.body)
                        }, "Share raw JSON"))
                    }) { Text("Share") }
                }

                Text("Response (${r.body.length} bytes)", style = MaterialTheme.typography.labelSmall)
                Box(
                    Modifier
                        .fillMaxWidth()
                        .heightIn(max = 420.dp)
                        .horizontalScroll(rememberScrollState())
                ) {
                    SelectionContainer {
                        Text(
                            r.prettyBody.take(120_000),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}
