package dev.cybercore.nightwatch

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val listPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp)

// ── overview ───────────────────────────────────────────────────────────────

@Composable
fun OverviewScreen(model: NightwatchModel, state: UiState) {
    val o = state.overview
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        state.sessionError?.let { item { ErrorNote(stringResource(R.string.gateway_error, it)) } }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(stringResource(R.string.open_alerts), "${o?.alertsOpen ?: "—"}",
                    if ((o?.alertsCrit ?: 0) > 0) Grid.red else if ((o?.alertsOpen ?: 0) > 0) Grid.amber else Grid.green,
                    Modifier.weight(1f)) { model.selectTab(Tab.Alerts) }
                StatTile(stringResource(R.string.failed_units), "${o?.unitsFailed ?: "—"}",
                    if ((o?.unitsFailed ?: 0) > 0) Grid.red else Grid.green, Modifier.weight(1f)) {
                    model.setUnitFilter("attention"); model.selectTab(Tab.Units)
                }
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatTile(stringResource(R.string.units_active), o?.unitsActive?.let { "$it/${o.unitsTotal}" } ?: "—", Grid.cyan, Modifier.weight(1f)) {
                    model.setUnitFilter("all"); model.selectTab(Tab.Units)
                }
                StatTile(stringResource(R.string.stacks_running), o?.stacksRunning?.let { "$it/${o.stacksTotal}" } ?: "—",
                    if ((o?.stacksPartial ?: 0) > 0) Grid.amber else Grid.purple, Modifier.weight(1f)) { model.selectTab(Tab.Stacks) }
            }
        }
        val open = state.alerts?.alerts?.filter { !it.acked }.orEmpty().take(5)
        if (open.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.latest_alerts)) }
            items(open, key = { "a${it.id}" }) { AlertCard(it, null) }
        }
        state.session?.let { s ->
            item { SectionLabel(stringResource(R.string.services)) }
            item {
                Panel {
                    ServiceLine("DaemonHall", s.daemonhall)
                    ServiceLine("Dockspace", s.dockspace)
                    ServiceLine("Cyberdeck", s.cyberdeck)
                    ServiceLine("Mission Control", s.missionProject != null)
                    ServiceLine(stringResource(R.string.push), s.push)
                    Text("nightwatchd ${s.version} · ${s.deviceName}", color = Grid.muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}

@Composable
private fun ServiceLine(name: String, up: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StateDot(if (up) "ok" else "off")
        Text(name, style = MaterialTheme.typography.bodyMedium)
        Text(if (up) stringResource(R.string.on) else stringResource(R.string.off), color = Grid.muted, style = MaterialTheme.typography.bodySmall)
    }
}

// ── units ──────────────────────────────────────────────────────────────────

@Composable
fun UnitsScreen(model: NightwatchModel, state: UiState) {
    val shown = state.units.filter {
        when (state.unitFilter) {
            "attention" -> it.needsAttention || it.activeState == "failed"
            "system" -> it.scope == "system"
            "user" -> it.scope == "user"
            else -> true
        }
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("attention" to R.string.f_attention, "all" to R.string.f_all, "system" to R.string.f_system, "user" to R.string.f_user).forEach { (k, l) ->
                    FilterChip(selected = state.unitFilter == k, onClick = { model.setUnitFilter(k) }, label = { Text(stringResource(l)) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = Grid.cyan.copy(alpha = .16f), selectedLabelColor = Grid.cyan))
                }
            }
        }
        state.unitsError?.let { item { ErrorNote(it) } }
        if (shown.isEmpty() && state.unitsError == null) item {
            Empty(if (state.unitFilter == "attention") stringResource(R.string.all_quiet) else stringResource(R.string.no_units))
        }
        items(shown, key = { it.id }) { u ->
            Panel(accent = if (u.needsAttention) stateColor(u.activeState) else Grid.line, onClick = { model.open(Detail.UnitPage(u.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StateDot(u.activeState)
                    Text(u.shortName, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatePill(u.statusLabel)
                }
                Text(u.description, color = Grid.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text(u.scope, color = Grid.purple, style = MaterialTheme.typography.labelSmall)
                    if (u.memory > 0) Text(bytes(u.memory), color = Grid.muted, style = MaterialTheme.typography.labelSmall)
                    if (u.restarts > 0) Text("↻ ${u.restarts}", color = Grid.amber, style = MaterialTheme.typography.labelSmall)
                    u.nextRun?.let { Text("next ${ago(it)}", color = Grid.muted, style = MaterialTheme.typography.labelSmall) }
                }
            }
        }
    }
}

@Composable
fun UnitScreen(model: NightwatchModel, state: UiState, id: String) {
    val d = state.unitDetail?.takeIf { it.unit.id == id }
    DisposableEffect(id) { onDispose { model.stopLogs() } }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.textError?.let { item { ErrorNote(it) } }
        if (d == null) { item { Empty("…") }; return@LazyColumn }
        val u = d.unit
        item {
            Panel(accent = stateColor(u.activeState)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StateDot(u.activeState, 12)
                    Text(u.shortName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    StatePill(u.statusLabel)
                }
                Text(u.description, color = Grid.muted, style = MaterialTheme.typography.bodySmall)
                KeyValue("scope", "${u.scope} · ${u.kind}")
                KeyValue("runs as", u.runAs.ifBlank { "—" })
                KeyValue("since", ago(u.activeSince))
                KeyValue("cpu", "%.1f %%".format(u.cpuPct))
                KeyValue("memory", bytes(u.memory))
                KeyValue("restarts", "${u.restarts}")
                if (u.nextRun != null) KeyValue("next run", ago(u.nextRun))
                if (u.lastRun != null) KeyValue("last run", ago(u.lastRun))
                if (d.ports.isNotEmpty()) KeyValue("ports", d.ports.joinToString(" "))
                if (state.canOperate && u.actions.isNotEmpty()) {
                    ActionRow {
                        u.actions.forEach { verb ->
                            NeonButton(verb, color = if (verb == "stop") Grid.red else Grid.cyan, enabled = "unit:${u.id}" !in state.busy, modifier = Modifier.weight(1f)) {
                                model.unitAction(u, verb)
                            }
                        }
                    }
                }
                if (u.protected) Text(stringResource(R.string.protected_note), color = Grid.amber, style = MaterialTheme.typography.labelSmall)
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.logs), Modifier.weight(1f))
                if (state.logsLive) NeonButton(stringResource(R.string.pause), color = Grid.amber) { model.stopLogs() }
                else NeonButton(stringResource(R.string.follow), color = Grid.green) { model.startLogs(id) }
            }
        }
        item { LogView(state.logs) }
        if (state.unitActivity.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.history)) }
            items(state.unitActivity.take(30), key = { "${it.ts}${it.kind}" }) { ActivityLine(it) }
        }
    }
    LaunchedEffect(id) { model.startLogs(id) }
}

@Composable
private fun LogView(lines: List<LogLine>) {
    Panel {
        if (lines.isEmpty()) Text("…", color = Grid.muted)
        val listState = rememberLazyListState()
        LaunchedEffect(lines.size) { if (lines.isNotEmpty()) listState.scrollToItem(lines.size - 1) }
        SelectionContainer {
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 420.dp), state = listState) {
                items(lines.size) { i ->
                    val l = lines[i]
                    val c = when { l.prio <= 3 -> Grid.red; l.prio == 4 -> Grid.amber; else -> Grid.fg }
                    Text(
                        (if (l.ident.isNotBlank()) "${l.ident}: " else "") + l.msg,
                        color = c, fontSize = 11.sp, lineHeight = 14.sp, fontFamily = Mono,
                    )
                }
            }
        }
    }
}

@Composable
fun ActivityLine(e: ActivityEvent) {
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StateDot(when { e.ok == false || e.kind == "failed" -> "failed"; e.kind == "auto-restart" -> "warn"; else -> "ok" }, 7)
        Column(Modifier.weight(1f)) {
            Text("${e.kind} · ${e.unit.removePrefix("user:")}", style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
            Text(e.message, style = MaterialTheme.typography.bodySmall, color = Grid.muted)
        }
        Text(ago(microsToSecs(e.ts)), style = MaterialTheme.typography.labelSmall, color = Grid.muted)
    }
}

@Composable
fun ActivityScreen(state: UiState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
        state.textError?.let { item { ErrorNote(it) } }
        items(state.activity, key = { "${it.ts}${it.unit}${it.kind}" }) { ActivityLine(it) }
    }
}

// ── stacks ─────────────────────────────────────────────────────────────────

@Composable
fun StacksScreen(model: NightwatchModel, state: UiState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.stacksError?.let { item { ErrorNote(it) } }
        if (state.stacks.isEmpty() && state.stacksError == null) item { Empty(stringResource(R.string.no_stacks)) }
        items(state.stacks, key = { it.id }) { s ->
            Panel(accent = if (s.status == "Running") Grid.line else stateColor(s.status), onClick = { model.open(Detail.StackPage(s.id)) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StateDot(s.status)
                    Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    StatePill(s.status)
                }
                Text(s.services.joinToString(" · "), color = Grid.muted, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (s.ports.isNotEmpty()) Text(s.ports.joinToString(" "), color = Grid.purple, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun StackScreen(model: NightwatchModel, state: UiState, id: String) {
    val s = state.stacks.firstOrNull { it.id == id }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (s == null) { item { Empty("…") }; return@LazyColumn }
        item {
            Panel(accent = stateColor(s.status)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    StatePill(s.status)
                }
                if (s.ports.isNotEmpty()) KeyValue("ports", s.ports.joinToString(" "))
                s.lastError?.let { Text(it, color = Grid.red, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis) }
                if (state.canOperate) ActionRow {
                    s.actions.forEach { verb ->
                        NeonButton(verb, enabled = "stack:${s.id}" !in state.busy, modifier = Modifier.weight(1f)) { model.stackAction(s, verb) }
                    }
                }
            }
        }
        if (s.containers.isNotEmpty()) item { SectionLabel(stringResource(R.string.containers)) }
        items(s.containers, key = { it.name.ifBlank { it.service } }) { c ->
            Panel(onClick = { if (c.name.isNotBlank()) model.open(Detail.ContainerLogs(c.name)) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StateDot(c.state)
                    Column(Modifier.weight(1f)) {
                        Text(c.service, style = MaterialTheme.typography.titleSmall)
                        Text(c.name, color = Grid.muted, style = MaterialTheme.typography.labelSmall)
                    }
                    StatePill(c.state)
                }
                if (state.canOperate && c.name.isNotBlank()) ActionRow {
                    listOf("start", "stop", "restart").forEach { verb ->
                        NeonButton(verb, color = if (verb == "stop") Grid.red else Grid.cyan, enabled = "container:${c.name}" !in state.busy, modifier = Modifier.weight(1f)) {
                            model.containerAction(c, verb)
                        }
                    }
                }
            }
        }
        item { SectionLabel(stringResource(R.string.logs)) }
        item { MonoBlock(state.text ?: state.textError ?: "…") }
    }
}

@Composable
fun TextScreen(state: UiState, title: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(listPadding)) {
        SectionLabel(title)
        state.textError?.let { ErrorNote(it) }
        MonoBlock(state.text ?: "…")
    }
}

@Composable
private fun MonoBlock(text: String) {
    Panel {
        SelectionContainer {
            Text(text.takeLast(60_000), Modifier.horizontalScroll(rememberScrollState()), fontFamily = Mono, fontSize = 11.sp, lineHeight = 14.sp, softWrap = false)
        }
    }
}

// ── alerts ─────────────────────────────────────────────────────────────────

@Composable
fun AlertsScreen(model: NightwatchModel, state: UiState) {
    val page = state.alerts
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.alertsError?.let { item { ErrorNote(it) } }
        if (page != null && page.open > 0 && state.canOperate) item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(pluralStringResource(R.plurals.n_open, page.open, page.open), Modifier.weight(1f), color = Grid.amber)
                NeonButton(stringResource(R.string.ack_all), color = Grid.pink) { model.ack(null) }
            }
        }
        if (page != null && page.alerts.isEmpty()) item { Empty(stringResource(R.string.no_alerts)) }
        items(page?.alerts.orEmpty(), key = { it.id }) { a ->
            AlertCard(a, if (state.canOperate && !a.acked) ({ model.ack(a.id) }) else null) {
                when (a.source) {
                    "daemonhall" -> if (a.subject.isNotBlank()) model.open(Detail.UnitPage(a.subject))
                    "dockspace" -> state.stacks.firstOrNull { it.name == a.subject }?.let { model.open(Detail.StackPage(it.id)) }
                }
            }
        }
    }
}

@Composable
fun AlertCard(a: Alert, onAck: (() -> Unit)?, onOpen: (() -> Unit)? = null) {
    val c = stateColor(a.severity)
    Panel(accent = if (a.acked) Grid.line else c, onClick = onOpen) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            StateDot(if (a.acked) "off" else a.severity)
            Text(a.title, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = if (a.acked) Grid.muted else Grid.fg)
            if (a.count > 1) Text("×${a.count}", color = c, style = MaterialTheme.typography.labelSmall)
        }
        if (a.body.isNotBlank()) Text(a.body, color = Grid.muted, style = MaterialTheme.typography.bodySmall, maxLines = 6, overflow = TextOverflow.Ellipsis)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("${a.source} · ${ago(a.ts)}", Modifier.weight(1f), color = Grid.purple, style = MaterialTheme.typography.labelSmall)
            if (onAck != null) NeonButton(stringResource(R.string.ack), color = Grid.pink, onClick = onAck)
        }
    }
}

// ── more: deck, mission, settings ──────────────────────────────────────────

@Composable
fun MoreScreen(model: NightwatchModel, state: UiState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { MoreLink(stringResource(R.string.deck), stringResource(R.string.deck_sub), state.session?.cyberdeck != false) { model.open(Detail.Deck) } }
        item { MoreLink(stringResource(R.string.mission), state.session?.missionProject ?: stringResource(R.string.mission_off), state.session?.missionProject != null) { model.open(Detail.Mission) } }
        item { MoreLink(stringResource(R.string.activity), stringResource(R.string.activity_sub), true) { model.open(Detail.Activity) } }
        item { SectionLabel(stringResource(R.string.settings)) }
        item { MoreLink(stringResource(R.string.hosts), pluralStringResource(R.plurals.n_hosts, state.hosts.size, state.hosts.size), true) { model.open(Detail.Hosts) } }
        item { MoreLink(stringResource(R.string.push), stringResource(R.string.push_sub), true) { model.open(Detail.PushSettings) } }
        item {
            Text(stringResource(R.string.about, BuildConfig.VERSION_NAME), Modifier.padding(top = 16.dp), color = Grid.muted, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MoreLink(title: String, sub: String, on: Boolean, onClick: () -> Unit) {
    Panel(onClick = onClick) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = if (on) Grid.cyan else Grid.muted)
        Text(sub, color = Grid.muted, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
fun DeckScreen(model: NightwatchModel, state: UiState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.textError?.let { item { ErrorNote(it) } }
        if (state.canOperate && state.scans.isNotEmpty()) {
            item { SectionLabel(stringResource(R.string.scans)) }
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    state.scans.forEach { m -> NeonButton(m, color = Grid.purple, enabled = "scan:$m" !in state.busy) { model.runScan(m) } }
                }
            }
        }
        item { SectionLabel(stringResource(R.string.reports)) }
        items(state.reports.filter { it.isMarkdown }, key = { it.path }) { r ->
            Panel(onClick = { model.open(Detail.ReportPage(r.path, r.title)) }) {
                Text(r.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(r.path, color = Grid.muted, style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
fun ReportScreen(state: UiState, title: String) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(listPadding)) {
        state.textError?.let { ErrorNote(it) }
        Panel {
            SelectionContainer {
                // Reports are mostly aligned command output: scroll sideways
                // rather than wrap the columns apart.
                Text(state.text?.let { renderMarkdown(it) } ?: androidx.compose.ui.text.AnnotatedString(title),
                    Modifier.horizontalScroll(rememberScrollState()),
                    fontFamily = Mono, fontSize = 12.sp, lineHeight = 16.sp, softWrap = false)
            }
        }
    }
}

@Composable
fun MissionScreen(model: NightwatchModel, state: UiState) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        state.missionError?.let { item { ErrorNote(it) } }
        state.missionProject?.let { p ->
            item {
                Panel(accent = Grid.purple) {
                    val proj = p.optJSONObject("project") ?: p
                    Text(proj.optString("name"), style = MaterialTheme.typography.titleMedium, color = Grid.purple)
                    if (proj.optString("mission").isNotBlank()) Text(proj.optString("mission"), color = Grid.fg, style = MaterialTheme.typography.bodySmall)
                    Text(proj.optString("id"), color = Grid.muted, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        val agents = state.missionAgents.objects()
        if (agents.isNotEmpty()) item { SectionLabel(stringResource(R.string.agents)) }
        items(agents, key = { it.optString("id") }) { a ->
            val last = isoSecs(a.optNullableString("last_seen_at"))
            val stale = last == null || System.currentTimeMillis() / 1000 - last > 90
            Panel(accent = if (stale) Grid.amber else Grid.line, onClick = { model.open(Detail.MissionAgent(a.optString("id"), a.optString("name"))) }) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StateDot(if (stale) "warn" else "ok")
                    Text(a.optString("name"), Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                    Text(last?.let { ago(it) } ?: stringResource(R.string.never), color = Grid.muted, style = MaterialTheme.typography.labelSmall)
                }
                Text("${a.optString("machine_name")} · ${a.optString("platform")} · ${a.optString("status")}", color = Grid.muted, style = MaterialTheme.typography.bodySmall)
            }
        }
        val audit = state.missionAudit.objects()
        if (audit.isNotEmpty()) item { SectionLabel(stringResource(R.string.audit)) }
        items(audit.take(100), key = { it.optString("id") }) { e ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                Column(Modifier.weight(1f)) {
                    Text(e.optString("event_type"), style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.Bold)
                    Text("${e.optString("actor")} → ${e.optString("resource_type")} ${e.optString("resource_id")}", style = MaterialTheme.typography.labelSmall, color = Grid.muted)
                }
                Text(ago(isoSecs(e.optNullableString("occurred_at"))), style = MaterialTheme.typography.labelSmall, color = Grid.muted)
            }
        }
    }
}

/** Mission Control timestamps are ISO-8601. */
private fun isoSecs(s: String?): Long? = s?.let { runCatching { java.time.Instant.parse(it).epochSecond }.getOrNull() }

@Composable
fun HeartbeatsScreen(state: UiState, name: String) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = listPadding) {
        item { SectionLabel(name) }
        state.missionError?.let { item { ErrorNote(it) } }
        items(state.missionHeartbeats.objects(), key = { it.optString("id") }) { h ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StateDot(h.optString("status").let { if (it == "healthy" || it == "ok") "ok" else it })
                Text(h.optString("status"), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                Text(ago(isoSecs(h.optNullableString("observed_at"))), style = MaterialTheme.typography.labelSmall, color = Grid.muted)
            }
        }
    }
}
