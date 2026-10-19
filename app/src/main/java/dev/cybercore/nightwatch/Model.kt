package dev.cybercore.nightwatch

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

enum class Tab { Overview, Units, Stacks, Alerts, More }

/** Screens pushed on top of a tab. */
sealed interface Detail {
    data class UnitPage(val id: String) : Detail
    data class StackPage(val id: String) : Detail
    data class ContainerLogs(val name: String) : Detail
    data class ReportPage(val path: String, val title: String) : Detail
    data object Deck : Detail
    data object Mission : Detail
    data class MissionAgent(val id: String, val name: String) : Detail
    data object Activity : Detail
    data object Hosts : Detail
    data object AddHost : Detail
    data object PushSettings : Detail
}

/** Host details from a `nightwatch://add` pairing link, waiting for the user to connect. */
data class Pairing(val url: String, val token: String, val name: String)

/** An action waiting for the user's yes and their fingerprint. */
data class Confirm(
    val title: String,
    val message: String,
    val key: String,
    /** Changes something on the host: needs the fingerprint/screen lock too. */
    val strong: Boolean = true,
    val run: suspend (NightwatchApi) -> String,
)

data class UiState(
    val hosts: List<Host> = emptyList(),
    val hostId: String? = null,
    val session: Session? = null,
    val sessionError: String? = null,
    val tab: Tab = Tab.Overview,
    val stack: List<Detail> = emptyList(),
    val refreshing: Boolean = false,
    val overview: Overview? = null,
    val units: List<UnitInfo> = emptyList(),
    val unitsError: String? = null,
    val unitFilter: String = "attention",
    val stacks: List<StackInfo> = emptyList(),
    val stacksError: String? = null,
    val alerts: AlertPage? = null,
    val alertsError: String? = null,
    // detail pages
    val unitDetail: UnitDetail? = null,
    val unitActivity: List<ActivityEvent> = emptyList(),
    val logs: List<LogLine> = emptyList(),
    val logsLive: Boolean = false,
    val activity: List<ActivityEvent> = emptyList(),
    val text: String? = null,
    val textError: String? = null,
    val reports: List<Report> = emptyList(),
    val scans: List<String> = emptyList(),
    val missionProject: JSONObject? = null,
    val missionAgents: JSONArray? = null,
    val missionAudit: JSONArray? = null,
    val missionHeartbeats: JSONArray? = null,
    val missionError: String? = null,
    // actions
    val confirm: Confirm? = null,
    val busy: Set<String> = emptySet(),
    val message: String? = null,
    val pairing: Pairing? = null,
) {
    val host: Host? get() = hosts.firstOrNull { it.id == hostId }
    val detail: Detail? get() = stack.lastOrNull()
    val canOperate: Boolean get() = session?.operate == true

    /**
     * Whether Back stays in the app: an open page closes, another tab returns
     * to Watch. On Watch (or the first-run Add host) Android leaves the app.
     */
    val canGoBack: Boolean
        get() = !(hosts.isEmpty() && stack.size <= 1) && (stack.isNotEmpty() || tab != Tab.Overview)
}

class NightwatchModel(app: Application) : AndroidViewModel(app) {
    private val store = HostStore(app)
    private val _state = MutableStateFlow(UiState(hosts = store.list(), hostId = store.selected))
    val state: StateFlow<UiState> = _state
    private var logsJob: Job? = null
    private var pollJob: Job? = null

    private val api: NightwatchApi? get() = _state.value.host?.let { store.api(it) }

    init {
        if (_state.value.hosts.isEmpty()) _state.update { it.copy(stack = listOf(Detail.AddHost)) }
        refresh()
    }

    // ── navigation ─────────────────────────────────────────────────────────

    fun selectTab(tab: Tab) {
        stopLogs()
        _state.update { it.copy(tab = tab, stack = emptyList()) }
        refreshTab()
    }

    fun open(detail: Detail) {
        stopLogs()
        _state.update { it.copy(stack = it.stack + detail, text = null, textError = null) }
        load(detail)
    }

    /** See [UiState.canGoBack]. False when Android should handle Back. */
    fun back(): Boolean {
        val s = _state.value
        if (!s.canGoBack) return false
        if (s.stack.isEmpty()) {
            selectTab(Tab.Overview)
            return true
        }
        stopLogs()
        _state.update { it.copy(stack = it.stack.dropLast(1)) }
        _state.value.detail?.let(::load) ?: refreshTab()
        return true
    }

    fun selectHost(id: String) {
        store.selected = id
        stopLogs()
        _state.update { UiState(hosts = store.list(), hostId = id, tab = it.tab) }
        refresh()
    }

    fun clearMessage() = _state.update { it.copy(message = null) }

    /** Opens Add host pre-filled; nothing is saved until the user taps Connect. */
    fun pair(p: Pairing) {
        stopLogs()
        _state.update { it.copy(pairing = p, stack = if (it.detail == Detail.AddHost) it.stack else it.stack + Detail.AddHost) }
    }

    fun consumePairing() = _state.update { it.copy(pairing = null) }
    fun setUnitFilter(f: String) = _state.update { it.copy(unitFilter = f) }

    // ── hosts ──────────────────────────────────────────────────────────────

    /** Checks the URL and token against the gateway before saving anything. */
    fun addHost(label: String, url: String, token: String, done: (String?) -> Unit) {
        val base = normalizeBaseUrl(url) ?: return done(getApplication<Application>().getString(R.string.bad_url))
        viewModelScope.launch {
            val session = runCatching { NightwatchApi(base.toString(), token.trim()).session() }
            session.onFailure { return@launch done(it.message ?: it.javaClass.simpleName) }
            val name = label.trim().ifBlank { session.getOrThrow().host.ifBlank { base.host } }
            val host = store.add(name, base.toString(), token.trim())
            Push.register(getApplication(), host)
            store.selected = host.id
            _state.update { UiState(hosts = store.list(), hostId = host.id) }
            AlertSync.sync(getApplication(), host.id)
            refresh()
            done(null)
        }
    }

    fun removeHost(id: String) {
        Push.unregister(getApplication(), id)
        store.remove(id)
        val hosts = store.list()
        _state.update { UiState(hosts = hosts, hostId = store.selected, stack = if (hosts.isEmpty()) listOf(Detail.AddHost) else listOf(Detail.Hosts)) }
        refresh()
    }

    fun renameHost(id: String, label: String) {
        if (label.isBlank()) return
        store.rename(id, label.trim())
        _state.update { it.copy(hosts = store.list()) }
    }

    // ── loading ────────────────────────────────────────────────────────────

    fun refresh() {
        val api = api ?: return
        viewModelScope.launch {
            _state.update { it.copy(refreshing = true) }
            runCatching { api.session() }
                .onSuccess { s -> _state.update { it.copy(session = s, sessionError = null) } }
                .onFailure { e -> _state.update { it.copy(sessionError = e.message ?: "unreachable") } }
            refreshTab()
            _state.value.detail?.let(::load)
            _state.update { it.copy(refreshing = false) }
        }
        startPolling()
    }

    /** Keeps the visible lists fresh while the app is open. */
    private fun startPolling() {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            while (isActive) {
                delay(20_000)
                if (_state.value.detail == null) refreshTab()
            }
        }
    }

    private fun refreshTab() {
        val api = api ?: return
        viewModelScope.launch {
            when (_state.value.tab) {
                Tab.Overview -> {
                    runCatching { api.overview() }.onSuccess { o -> _state.update { it.copy(overview = o) } }
                    loadAlerts(api)
                }
                Tab.Units -> loadUnits(api)
                Tab.Stacks -> loadStacks(api)
                Tab.Alerts -> loadAlerts(api)
                Tab.More -> Unit
            }
        }
    }

    private suspend fun loadUnits(api: NightwatchApi) = runCatching { api.units() }
        .onSuccess { u -> _state.update { it.copy(units = u, unitsError = null) } }
        .onFailure { e -> _state.update { it.copy(unitsError = e.message) } }

    private suspend fun loadStacks(api: NightwatchApi) = runCatching { api.stacks() }
        .onSuccess { s -> _state.update { it.copy(stacks = s, stacksError = null) } }
        .onFailure { e -> _state.update { it.copy(stacksError = e.message) } }

    private suspend fun loadAlerts(api: NightwatchApi) = runCatching { api.alerts() }
        .onSuccess { a -> _state.update { it.copy(alerts = a, alertsError = null) } }
        .onFailure { e -> _state.update { it.copy(alertsError = e.message) } }

    private fun load(detail: Detail) {
        val api = api ?: return
        viewModelScope.launch {
            when (detail) {
                is Detail.UnitPage -> {
                    runCatching { api.unit(detail.id) }
                        .onSuccess { d -> _state.update { it.copy(unitDetail = d, textError = null) } }
                        .onFailure { e -> _state.update { it.copy(textError = e.message) } }
                    runCatching { api.activity(detail.id, 30) }.onSuccess { a -> _state.update { it.copy(unitActivity = a) } }
                }
                is Detail.StackPage -> {
                    loadStacks(api)
                    runCatching { api.stackLogs(detail.id) }
                        .onSuccess { t -> _state.update { it.copy(text = t) } }
                        .onFailure { e -> _state.update { it.copy(textError = e.message) } }
                }
                is Detail.ContainerLogs -> runCatching { api.containerLogs(detail.name) }
                    .onSuccess { t -> _state.update { it.copy(text = t, textError = null) } }
                    .onFailure { e -> _state.update { it.copy(textError = e.message) } }
                is Detail.ReportPage -> runCatching { api.report(detail.path) }
                    .onSuccess { t -> _state.update { it.copy(text = t, textError = null) } }
                    .onFailure { e -> _state.update { it.copy(textError = e.message) } }
                Detail.Deck -> {
                    runCatching { api.reports() }
                        .onSuccess { r -> _state.update { it.copy(reports = r, textError = null) } }
                        .onFailure { e -> _state.update { it.copy(textError = e.message) } }
                    runCatching { api.scans() }.onSuccess { s -> _state.update { it.copy(scans = s) } }
                }
                Detail.Mission -> {
                    val r = runCatching {
                        Triple(api.missionProject(), api.missionAgents(), api.missionAudit())
                    }
                    r.onSuccess { (p, a, au) -> _state.update { it.copy(missionProject = p, missionAgents = a, missionAudit = au, missionError = null) } }
                        .onFailure { e -> _state.update { it.copy(missionError = e.message) } }
                }
                is Detail.MissionAgent -> runCatching { api.missionHeartbeats(detail.id) }
                    .onSuccess { h -> _state.update { it.copy(missionHeartbeats = h, missionError = null) } }
                    .onFailure { e -> _state.update { it.copy(missionError = e.message) } }
                Detail.Activity -> runCatching { api.activity(limit = 200) }
                    .onSuccess { a -> _state.update { it.copy(activity = a, textError = null) } }
                    .onFailure { e -> _state.update { it.copy(textError = e.message) } }
                Detail.Hosts, Detail.AddHost, Detail.PushSettings -> Unit
            }
        }
    }

    // ── logs ───────────────────────────────────────────────────────────────

    fun startLogs(unitId: String) {
        val api = api ?: return
        stopLogs()
        _state.update { it.copy(logs = emptyList(), logsLive = true) }
        logsJob = viewModelScope.launch {
            api.unitLogs(unitId).collect { line ->
                _state.update { s -> s.copy(logs = (s.logs + line).takeLast(MAX_LOG_LINES)) }
            }
            _state.update { it.copy(logsLive = false) }
        }
    }

    fun stopLogs() {
        logsJob?.cancel()
        logsJob = null
        _state.update { it.copy(logsLive = false) }
    }

    // ── actions ────────────────────────────────────────────────────────────

    fun ask(confirm: Confirm) = _state.update { it.copy(confirm = confirm) }
    fun cancelConfirm() = _state.update { it.copy(confirm = null) }

    /** Called after the user confirmed and passed the biometric prompt. */
    fun runConfirmed() {
        val c = _state.value.confirm ?: return
        val api = api ?: return
        _state.update { it.copy(confirm = null, busy = it.busy + c.key) }
        viewModelScope.launch {
            val msg = runCatching { c.run(api) }.fold({ it.ifBlank { "done" } }, { it.message ?: "failed" })
            _state.update { it.copy(busy = it.busy - c.key, message = msg) }
            delay(1500)
            refreshTab()
            _state.value.detail?.let(::load)
        }
    }

    fun unitAction(u: UnitInfo, verb: String) = ask(Confirm(
        title = "${verb.replaceFirstChar { it.uppercase() }} ${u.shortName}?",
        message = "${u.id} on ${_state.value.host?.label}",
        key = "unit:${u.id}",
    ) { it.unitAction(u.id, verb) })

    fun stackAction(s: StackInfo, verb: String) = ask(Confirm(
        title = "${verb.replaceFirstChar { it.uppercase() }} stack ${s.name}?",
        message = "docker compose $verb on ${_state.value.host?.label}",
        key = "stack:${s.id}",
    ) { it.stackAction(s.id, verb) })

    fun containerAction(c: Container, verb: String) = ask(Confirm(
        title = "${verb.replaceFirstChar { it.uppercase() }} ${c.name}?",
        message = "container ${c.service} on ${_state.value.host?.label}",
        key = "container:${c.name}",
    ) { it.containerAction(c.name, verb) })

    fun runScan(module: String) = ask(Confirm(
        title = "Run the $module scan?",
        message = "Cyberdeck regenerates that report on ${_state.value.host?.label}",
        key = "scan:$module",
    ) { it.runScan(module) })

    fun ack(id: Long?) = ask(Confirm(
        title = if (id == null) "Acknowledge all alerts?" else "Acknowledge this alert?",
        message = "on ${_state.value.host?.label}",
        key = "ack:${id ?: "all"}",
        strong = false,
    ) { "${it.ack(id)} acknowledged" })

    override fun onCleared() {
        stopLogs()
        super.onCleared()
    }

    private companion object {
        const val MAX_LOG_LINES = 1000
    }
}
