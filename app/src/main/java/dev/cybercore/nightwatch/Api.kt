package dev.cybercore.nightwatch

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import okhttp3.Call
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

/** An error the gateway (or a service behind it) answered with. */
class ApiException(val status: Int, message: String) : IOException(message)

data class Session(
    val host: String,
    val version: String,
    val deviceName: String,
    val operate: Boolean,
    val daemonhall: Boolean,
    val dockspace: Boolean,
    val cyberdeck: Boolean,
    val missionProject: String?,
    val push: Boolean,
)

data class Overview(
    val unitsTotal: Int?, val unitsAttention: Int?, val unitsFailed: Int?, val unitsActive: Int?,
    val stacksTotal: Int?, val stacksRunning: Int?, val stacksPartial: Int?, val stacksStopped: Int?,
    val alertsOpen: Int, val alertsCrit: Int,
)

data class UnitInfo(
    val id: String,
    val description: String,
    val activeState: String,
    val subState: String,
    val statusLabel: String,
    val scope: String,
    val kind: String,
    val needsAttention: Boolean,
    val cpuPct: Double,
    val memory: Long,
    val restarts: Int,
    val activeSince: Long?,
    val nextRun: Long?,
    val lastRun: Long?,
    val runAs: String,
    val actions: List<String>,
    val protected: Boolean,
) {
    val shortName: String get() = id.removePrefix("user:")
}

data class UnitDetail(val unit: UnitInfo, val ports: List<String>)

data class ActivityEvent(val ts: Long, val unit: String, val kind: String, val message: String, val source: String, val ok: Boolean?)

data class LogLine(val ts: Long, val prio: Int, val ident: String, val msg: String)

data class Container(val service: String, val state: String, val name: String)

data class StackInfo(
    val id: String,
    val name: String,
    val status: String,
    val services: List<String>,
    val ports: List<String>,
    val containers: List<Container>,
    val lastError: String?,
    val actions: List<String>,
)

data class Report(val path: String, val folder: String?, val title: String, val isMarkdown: Boolean)

data class Alert(
    val id: Long,
    val ts: Long,
    val source: String,
    val severity: String,
    val title: String,
    val body: String,
    val subject: String,
    val count: Int,
    val acked: Boolean,
)

data class AlertPage(val alerts: List<Alert>, val open: Int, val crit: Int)

/** Talks to one host's nightwatchd. */
class NightwatchApi private constructor(private val base: HttpUrl, private val token: String, client: OkHttpClient) {
    constructor(baseUrl: String, token: String) :
        this(normalizeBaseUrl(baseUrl) ?: throw IllegalArgumentException("Not a valid gateway URL"), token, sharedClient)

    private val http = client
    private val streaming = client.newBuilder().readTimeout(0, TimeUnit.MILLISECONDS).build()

    private fun url(vararg segments: String, query: Map<String, String> = emptyMap()): HttpUrl =
        base.newBuilder().addPathSegment("v1").apply {
            segments.forEach { addPathSegment(it) }
            query.forEach { (k, v) -> addQueryParameter(k, v) }
        }.build()

    private fun request(url: HttpUrl) = Request.Builder().url(url)
        .header("Authorization", "Bearer $token")
        .header("Accept", "application/json")

    private suspend fun call(req: Request): String = withContext(Dispatchers.IO) {
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) {
                val message = runCatching { JSONObject(text).optString("message") }.getOrNull()
                    ?.takeIf { it.isNotBlank() } ?: "HTTP ${resp.code}"
                throw ApiException(resp.code, message)
            }
            text
        }
    }

    private suspend fun get(vararg segments: String, query: Map<String, String> = emptyMap()): String =
        call(request(url(*segments, query = query)).get().build())

    private suspend fun post(vararg segments: String, body: JSONObject? = null): JSONObject {
        val payload = (body ?: JSONObject()).toString().toRequestBody(JSON)
        val text = call(request(url(*segments)).post(payload).build())
        return runCatching { JSONObject(text) }.getOrDefault(JSONObject())
    }

    suspend fun session(): Session = parseSession(JSONObject(get("session")))
    suspend fun overview(): Overview = parseOverview(JSONObject(get("overview")))
    suspend fun units(): List<UnitInfo> = JSONObject(get("units")).optJSONArray("units").objects().map(::parseUnit)
    suspend fun unit(id: String): UnitDetail = JSONObject(get("units", id)).let {
        UnitDetail(parseUnit(it.getJSONObject("unit")), it.optJSONArray("ports").strings())
    }
    suspend fun activity(unit: String? = null, limit: Int = 100): List<ActivityEvent> {
        val q = buildMap { put("limit", limit.toString()); unit?.let { put("unit", it) } }
        return JSONArray(get("activity", query = q)).objects().map(::parseActivity)
    }
    suspend fun unitAction(id: String, verb: String): String = post("units", id, "action", verb).optString("message")

    /** Journal lines as they arrive; the flow ends when the gateway closes the stream. */
    fun unitLogs(id: String, lines: Int = 200): Flow<LogLine> = callbackFlow {
        val req = request(url("units", id, "logs", query = mapOf("lines" to lines.toString())))
            .header("Accept", "text/event-stream").get().build()
        val call: Call = streaming.newCall(req)
        launchReader(call, emit = { line -> trySend(line) }, done = { close() })
        awaitClose { call.cancel() }
    }.flowOn(Dispatchers.IO)

    suspend fun stacks(): List<StackInfo> = JSONObject(get("stacks")).optJSONArray("stacks").objects().map(::parseStack)
    suspend fun stackLogs(id: String): String = JSONObject(get("stacks", id, "logs")).optString("logs")
    suspend fun stackAction(id: String, verb: String): String = post("stacks", id, "action", verb).optString("message")
    suspend fun containerLogs(name: String): String = JSONObject(get("containers", name, "logs")).optString("logs")
    suspend fun containerAction(name: String, verb: String): String =
        post("containers", name, "action", verb).let { it.optString("output").ifBlank { it.optString("message") } }

    suspend fun reports(): List<Report> = JSONArray(get("deck", "reports")).objects().map {
        Report(it.optString("path"), it.optNullableString("folder"), it.optString("title"), it.optBoolean("is_md"))
    }
    suspend fun report(path: String): String = JSONObject(get("deck", "reports", *path.split('/').toTypedArray())).optString("markdown")
    suspend fun scans(): List<String> = JSONObject(get("deck", "scans")).optJSONArray("scans").strings()
    suspend fun runScan(module: String): String = post("deck", "scans", module).optString("message")

    suspend fun alerts(after: Long? = null, limit: Int = 100): AlertPage {
        val q = buildMap { put("limit", limit.toString()); after?.let { put("after", it.toString()) } }
        return parseAlerts(JSONObject(get("alerts", query = q)))
    }
    suspend fun ack(id: Long? = null): Int = post("alerts", "ack", body = JSONObject().apply { id?.let { put("id", it) } }).optInt("acked")

    suspend fun pushEndpoint(): String? = JSONObject(get("push")).optNullableString("endpoint")
    suspend fun registerPush(endpoint: String) { post("push", body = JSONObject().put("endpoint", endpoint)) }
    suspend fun unregisterPush() {
        call(request(url("push")).delete().build())
    }

    suspend fun missionProject(): JSONObject = JSONObject(get("mission"))
    suspend fun missionAgents(): JSONArray = jsonArrayOrField(get("mission", "agents"), "agents")
    suspend fun missionHeartbeats(agent: String): JSONArray = jsonArrayOrField(get("mission", "agents", agent, "heartbeats"), "heartbeats")
    suspend fun missionAudit(): JSONArray = jsonArrayOrField(get("mission", "audit"), "events")

    companion object {
        /** Skips the tailnet-only URL rule, for MockWebServer. */
        internal fun forTesting(url: String, token: String) = NightwatchApi(url.toHttpUrlOrNull()!!, token, sharedClient)

        private val JSON = "application/json".toMediaType()
        val sharedClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(10, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }
}

/**
 * Plain HTTP is only allowed to a Tailscale MagicDNS name (traffic is inside
 * the WireGuard tunnel; the network security config enforces the same);
 * anything else must be HTTPS.
 */
fun normalizeBaseUrl(input: String, emulatorHost: Boolean = BuildConfig.EMULATOR_HOST): HttpUrl? {
    val trimmed = input.trim().trimEnd('/')
    val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
    val url = withScheme.toHttpUrlOrNull() ?: return null
    if (url.username.isNotEmpty() || url.password.isNotEmpty() || url.query != null || url.fragment != null) return null
    // Debug builds may also reach the development machine from the emulator.
    if (url.scheme == "http" && !url.host.endsWith(".ts.net") && !(emulatorHost && url.host == "10.0.2.2")) return null
    return url.newBuilder().encodedPath("/").build()
}

// ── parsing ───────────────────────────────────────────────────────────────

fun JSONArray?.objects(): List<JSONObject> = this?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it) } } ?: emptyList()
fun JSONArray?.strings(): List<String> = this?.let { a -> (0 until a.length()).map { a.optString(it) } } ?: emptyList()
fun JSONObject.optNullableString(key: String): String? = if (isNull(key)) null else optString(key).takeIf { has(key) }
private fun JSONObject.optNullableLong(key: String): Long? = if (has(key) && !isNull(key)) optLong(key) else null
private fun JSONObject.optNullableInt(key: String): Int? = if (has(key) && !isNull(key)) optInt(key) else null

private fun jsonArrayOrField(text: String, field: String): JSONArray =
    runCatching { JSONArray(text) }.getOrElse { JSONObject(text).optJSONArray(field) ?: JSONArray() }

fun parseSession(o: JSONObject): Session {
    val device = o.optJSONObject("device") ?: JSONObject()
    val s = o.optJSONObject("services") ?: JSONObject()
    return Session(
        host = o.optString("host"),
        version = o.optString("version"),
        deviceName = device.optString("name"),
        operate = device.optBoolean("operate"),
        daemonhall = s.optBoolean("daemonhall"),
        dockspace = s.optBoolean("dockspace"),
        cyberdeck = s.optBoolean("cyberdeck"),
        missionProject = s.optNullableString("mission")?.takeIf { it != "false" && it != "true" },
        push = s.optBoolean("push"),
    )
}

fun parseOverview(o: JSONObject): Overview {
    val u = o.optJSONObject("units")
    val st = o.optJSONObject("stacks")
    val a = o.optJSONObject("alerts") ?: JSONObject()
    return Overview(
        u?.optNullableInt("total"), u?.optNullableInt("attention"), u?.optNullableInt("failed"), u?.optNullableInt("active"),
        st?.optNullableInt("total"), st?.optNullableInt("running"), st?.optNullableInt("partial"), st?.optNullableInt("stopped"),
        a.optInt("open"), a.optInt("crit"),
    )
}

fun parseUnit(o: JSONObject) = UnitInfo(
    id = o.optString("id"),
    description = o.optString("description"),
    activeState = o.optString("active_state"),
    subState = o.optString("sub_state"),
    statusLabel = o.optString("status_label").ifBlank { o.optString("sub_state") },
    scope = o.optString("scope"),
    kind = o.optString("kind"),
    needsAttention = o.optBoolean("needs_attention"),
    cpuPct = o.optDouble("cpu_pct", 0.0).takeUnless { it.isNaN() } ?: 0.0,
    memory = o.optLong("memory"),
    restarts = o.optInt("n_restarts"),
    activeSince = o.optNullableLong("active_since"),
    nextRun = o.optNullableLong("next_run"),
    lastRun = o.optNullableLong("last_run"),
    runAs = o.optString("run_as"),
    actions = o.optJSONArray("nw_actions").strings(),
    protected = o.optBoolean("nw_protected"),
)

fun parseActivity(o: JSONObject) = ActivityEvent(
    ts = o.optLong("ts"), unit = o.optString("unit"), kind = o.optString("kind"),
    message = o.optString("message"), source = o.optString("source"),
    ok = if (o.has("ok") && !o.isNull("ok")) o.optBoolean("ok") else null,
)

fun parseStack(o: JSONObject) = StackInfo(
    id = o.optString("id"),
    name = o.optString("name"),
    status = o.optString("status"),
    services = o.optJSONArray("services").strings(),
    ports = o.optJSONArray("ports").strings(),
    containers = o.optJSONArray("containers").objects().map { Container(it.optString("service"), it.optString("state"), it.optString("name")) },
    lastError = o.optNullableString("last_error"),
    actions = o.optJSONArray("nw_actions").strings(),
)

fun parseAlert(o: JSONObject) = Alert(
    id = o.optLong("id"), ts = o.optLong("ts"), source = o.optString("source"),
    severity = o.optString("severity"), title = o.optString("title"), body = o.optString("body"),
    subject = o.optString("subject"), count = o.optInt("count", 1), acked = o.optBoolean("acked"),
)

fun parseAlerts(o: JSONObject) = AlertPage(o.optJSONArray("alerts").objects().map(::parseAlert), o.optInt("open"), o.optInt("crit"))

/** Parses `data: {...}` server-sent events into log lines. */
fun parseSseData(line: String): LogLine? {
    val data = line.removePrefix("data:").trim().takeIf { line.startsWith("data:") && it.isNotEmpty() } ?: return null
    val o = runCatching { JSONObject(data) }.getOrNull() ?: return LogLine(0, 6, "", data)
    return LogLine(o.optLong("ts"), o.optInt("prio", 6), o.optString("ident"), o.optString("msg"))
}

private fun launchReader(call: Call, emit: (LogLine) -> Unit, done: () -> Unit): Thread = Thread {
    try {
        call.execute().use { resp ->
            if (!resp.isSuccessful) {
                val message = runCatching { JSONObject(resp.body?.string().orEmpty()).optString("message") }.getOrNull()
                emit(LogLine(0, 3, "nightwatch", message?.ifBlank { null } ?: "HTTP ${resp.code}"))
                return@use
            }
            val source = resp.body?.source() ?: return@use
            while (!source.exhausted()) {
                val line = source.readUtf8Line() ?: break
                parseSseData(line)?.let(emit)
            }
        }
    } catch (_: IOException) {
        // Cancelled or the connection dropped; the flow just ends.
    } finally {
        done()
    }
}.apply { isDaemon = true; name = "nightwatch-logs"; start() }

/**
 * `nightwatch://add?url=…&token=nw_…&name=…`, as printed by
 * `nightwatchd token add --qr`. The URL must pass [normalizeBaseUrl] and the
 * token must look like one; anything else is ignored.
 */
fun parsePairing(uri: android.net.Uri?): Pairing? =
    uri?.let { pairingFrom(it.scheme, it.host, it::getQueryParameter) }

fun pairingFrom(scheme: String?, host: String?, param: (String) -> String?): Pairing? {
    if (scheme != "nightwatch" || host != "add") return null
    val url = param("url")?.let { normalizeBaseUrl(it) } ?: return null
    val token = param("token")?.trim()?.takeIf { TOKEN.matches(it) } ?: return null
    val name = param("name")?.take(40)?.filter { it.isLetterOrDigit() || it in " -_." }.orEmpty()
    return Pairing(url.toString(), token, name)
}

private val TOKEN = Regex("^nw_[0-9a-f]{8}_[0-9a-f]{64}$")
