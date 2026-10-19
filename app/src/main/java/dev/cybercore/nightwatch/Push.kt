package dev.cybercore.nightwatch

import android.app.Activity
import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.unifiedpush.android.connector.FailedReason
import org.unifiedpush.android.connector.PushService
import org.unifiedpush.android.connector.UnifiedPush
import org.unifiedpush.android.connector.data.PushEndpoint
import org.unifiedpush.android.connector.data.PushMessage

/**
 * UnifiedPush, one registration ("instance") per host. The distributor is
 * whatever the user installed (the ntfy app, pointed at their own server);
 * its endpoint goes to that host's nightwatchd, which POSTs to it.
 */
object Push {
    private fun prefs(context: Context) = context.getSharedPreferences("push", Context.MODE_PRIVATE)

    fun distributors(context: Context): List<String> = UnifiedPush.getDistributors(context)

    /**
     * The chosen distributor. The *saved* one, not the acknowledged one:
     * UnifiedPush only acknowledges a distributor after its first
     * registration, so waiting for the ack meant never registering.
     */
    fun current(context: Context): String? = UnifiedPush.getSavedDistributor(context)

    /** "ntfy" rather than "io.heckel.ntfy". */
    fun label(context: Context, pkg: String): String = runCatching {
        val pm = context.packageManager
        pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    /** Status line per host for the settings screen. */
    fun status(context: Context, hostId: String): String? = prefs(context).getString("status:$hostId", null)

    internal fun setStatus(context: Context, hostId: String, status: String) =
        prefs(context).edit { putString("status:$hostId", status) }

    /** Pick (or reuse) a distributor, then register every host. */
    fun enable(activity: Activity, onResult: (Boolean) -> Unit) {
        UnifiedPush.tryUseCurrentOrDefaultDistributor(activity) { ok ->
            if (ok) registerAll(activity)
            onResult(ok)
        }
    }

    fun use(context: Context, distributor: String) {
        UnifiedPush.saveDistributor(context, distributor)
        registerAll(context)
    }

    fun registerAll(context: Context) {
        HostStore(context).list().forEach { register(context, it) }
    }

    fun register(context: Context, host: Host) {
        if (current(context) == null) return
        setStatus(context, host.id, context.getString(R.string.push_waiting))
        UnifiedPush.register(context, host.id, "Nightwatch · ${host.label}")
    }

    fun unregister(context: Context, hostId: String) {
        UnifiedPush.unregister(context, hostId)
        prefs(context).edit { remove("status:$hostId") }
    }

    fun disable(context: Context) {
        HostStore(context).list().forEach { unregister(context, it.id) }
        UnifiedPush.removeDistributor(context)
    }
}

class NightwatchPushService : PushService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewEndpoint(endpoint: PushEndpoint, instance: String) {
        val store = HostStore(this)
        val host = store.get(instance) ?: return
        val api = store.api(host) ?: return
        scope.launch {
            val status = runCatching { api.registerPush(endpoint.url) }.fold(
                onSuccess = { getString(R.string.push_on) },
                onFailure = { getString(R.string.push_refused, it.message ?: "?") },
            )
            Push.setStatus(this@NightwatchPushService, instance, status)
        }
    }

    /**
     * The message only says "something happened on this host" (or "this is
     * a test"); what's shown always comes from the app, never the payload.
     */
    override fun onMessage(message: PushMessage, instance: String) {
        val host = HostStore(this).get(instance) ?: return
        if (isTestPush(message.content)) AlertSync.notifyTest(this, host) else AlertSync.syncSoon(this, instance)
    }

    override fun onRegistrationFailed(reason: FailedReason, instance: String) {
        Push.setStatus(this, instance, getString(R.string.push_failed, reason.name))
    }

    override fun onUnregistered(instance: String) {
        val store = HostStore(this)
        val host = store.get(instance)
        val api = host?.let { store.api(it) }
        if (api != null) scope.launch { runCatching { api.unregisterPush() } }
        Push.setStatus(this, instance, getString(R.string.push_off))
    }
}

/** `{"type":"test", ...}` from `POST /v1/push/test`. */
fun isTestPush(content: ByteArray): Boolean =
    runCatching { org.json.JSONObject(String(content, Charsets.UTF_8)).optString("type") == "test" }.getOrDefault(false)
