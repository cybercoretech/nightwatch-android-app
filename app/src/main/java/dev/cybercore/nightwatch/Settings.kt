package dev.cybercore.nightwatch

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.material3.IconButton
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp

@Composable
fun AddHostScreen(model: NightwatchModel, state: UiState) {
    var label by rememberSaveable { mutableStateOf("") }
    var url by rememberSaveable { mutableStateOf("") }
    var token by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    LaunchedEffect(state.pairing) {
        state.pairing?.let { p -> url = p.url; token = p.token; if (label.isBlank()) label = p.name; error = null; model.consumePairing() }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        if (state.hosts.isEmpty()) {
            Text("NIGHTWATCH", style = MaterialTheme.typography.headlineMedium, color = Grid.cyan)
            Text(stringResource(R.string.welcome), color = Grid.muted, style = MaterialTheme.typography.bodyMedium)
        }
        if (token.isNotBlank() && url.isNotBlank()) Text(stringResource(R.string.pairing_ready), color = Grid.green, style = MaterialTheme.typography.bodySmall)
        Panel(accent = Grid.purple) {
            Text(stringResource(R.string.add_host_steps), style = MaterialTheme.typography.bodySmall, color = Grid.fg)
        }
        OutlinedTextField(url, { url = it; error = null }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.gateway_url)) },
            placeholder = { Text("http://blackbox.tailXXXX.ts.net:8769") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri))
        var showToken by remember { mutableStateOf(false) }
        // 76 characters: wrap instead of scrolling off, and let the user check it.
        OutlinedTextField(token, { token = it.trim(); error = null }, Modifier.fillMaxWidth(), singleLine = false, maxLines = 3,
            label = { Text(stringResource(R.string.device_token)) },
            placeholder = { Text("nw_…") },
            visualTransformation = if (showToken) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showToken = !showToken }) {
                    Icon(if (showToken) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                        stringResource(if (showToken) R.string.hide_token else R.string.show_token))
                }
            },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password))
        OutlinedTextField(label, { label = it }, Modifier.fillMaxWidth(), singleLine = true,
            label = { Text(stringResource(R.string.host_name_optional)) })
        error?.let { ErrorNote(it) }
        NeonButton(if (busy) stringResource(R.string.checking) else stringResource(R.string.connect), enabled = !busy && url.isNotBlank() && token.startsWith("nw_"), modifier = Modifier.fillMaxWidth()) {
            busy = true
            model.addHost(label, url, token) { e -> busy = false; error = e; if (e == null) token = "" }
        }
    }
}

@Composable
fun HostsScreen(model: NightwatchModel, state: UiState) {
    var removing by remember { mutableStateOf<Host?>(null) }
    var renaming by remember { mutableStateOf<Host?>(null) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.hosts.forEach { h ->
            Panel(accent = if (h.id == state.hostId) Grid.cyan else Grid.line, onClick = { model.selectHost(h.id) }) {
                Text(h.label, style = MaterialTheme.typography.titleSmall, color = if (h.id == state.hostId) Grid.cyan else Grid.fg)
                Text(h.baseUrl, color = Grid.muted, style = MaterialTheme.typography.labelSmall)
                ActionRow {
                    NeonButton(stringResource(R.string.rename), modifier = Modifier.weight(1f)) { renaming = h }
                    NeonButton(stringResource(R.string.remove), color = Grid.red, modifier = Modifier.weight(1f)) { removing = h }
                }
            }
        }
        NeonButton(stringResource(R.string.add_host), color = Grid.green, modifier = Modifier.fillMaxWidth()) { model.open(Detail.AddHost) }
    }
    removing?.let { h ->
        AlertDialog(
            onDismissRequest = { removing = null }, containerColor = Grid.panel,
            title = { Text(stringResource(R.string.remove_host, h.label)) },
            text = { Text(stringResource(R.string.remove_host_note), color = Grid.muted) },
            confirmButton = { TextButton(onClick = { model.removeHost(h.id); removing = null }) { Text(stringResource(R.string.remove), color = Grid.red) } },
            dismissButton = { TextButton(onClick = { removing = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
    renaming?.let { h ->
        var name by remember(h.id) { mutableStateOf(h.label) }
        AlertDialog(
            onDismissRequest = { renaming = null }, containerColor = Grid.panel,
            title = { Text(stringResource(R.string.rename)) },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = { TextButton(onClick = { model.renameHost(h.id, name); renaming = null }) { Text(stringResource(R.string.save)) } },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text(stringResource(R.string.cancel)) } },
        )
    }
}

@Composable
fun PushScreen(model: NightwatchModel, state: UiState) {
    val context = LocalContext.current
    var tick by remember { mutableIntStateOf(0) }
    val distributors = remember(tick) { Push.distributors(context) }
    val current = remember(tick) { Push.current(context) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Panel(accent = Grid.purple) {
            Text(stringResource(R.string.push_explain), style = MaterialTheme.typography.bodySmall)
        }
        when {
            distributors.isEmpty() -> ErrorNote(stringResource(R.string.push_no_distributor))
            current == null -> {
                Text(stringResource(R.string.push_pick), color = Grid.muted, style = MaterialTheme.typography.bodySmall)
                distributors.forEach { d ->
                    NeonButton(d, color = Grid.green, modifier = Modifier.fillMaxWidth()) { Push.use(context, d); tick++ }
                }
                (context as? Activity)?.let { act ->
                    NeonButton(stringResource(R.string.push_default), modifier = Modifier.fillMaxWidth()) { Push.enable(act) { tick++ } }
                }
            }
            else -> {
                Panel {
                    Text(stringResource(R.string.push_using, current), style = MaterialTheme.typography.bodyMedium, color = Grid.green)
                    state.hosts.forEach { h ->
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(h.label, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                            Text(Push.status(context, h.id) ?: "—", color = Grid.muted, style = MaterialTheme.typography.labelSmall)
                        }
                    }
                }
                NeonButton(stringResource(R.string.push_test, state.host?.label ?: ""), color = Grid.green, modifier = Modifier.fillMaxWidth()) { model.testPush() }
                ActionRow {
                    NeonButton(stringResource(R.string.push_reregister), modifier = Modifier.weight(1f)) { Push.registerAll(context); tick++ }
                    NeonButton(stringResource(R.string.push_disable), color = Grid.red, modifier = Modifier.weight(1f)) { Push.disable(context); tick++ }
                }
            }
        }
        state.session?.takeIf { !it.push }?.let { ErrorNote(stringResource(R.string.push_off_on_host)) }
        Text(stringResource(R.string.push_fallback), color = Grid.muted, style = MaterialTheme.typography.labelSmall)
        NeonButton(stringResource(R.string.refresh), color = Grid.muted) { tick++ }
    }
}
