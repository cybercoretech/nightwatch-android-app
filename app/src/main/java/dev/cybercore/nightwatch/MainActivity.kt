package dev.cybercore.nightwatch

import android.Manifest
import android.content.Intent
import android.hardware.biometrics.BiometricManager.Authenticators.BIOMETRIC_STRONG
import android.hardware.biometrics.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import android.hardware.biometrics.BiometricPrompt
import android.os.Build
import android.os.Bundle
import android.os.CancellationSignal
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

class MainActivity : ComponentActivity() {
    private var model: NightwatchModel? = null
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
        )
        // Host names, unit lists and logs stay out of screenshots and the
        // recents thumbnail.
        if (!BuildConfig.ALLOW_SCREENSHOTS) window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        AlertSync.createChannels(this)
        AlertSync.schedulePeriodic(this)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        setContent {
            val m: NightwatchModel = viewModel()
            model = m
            val state by m.state.collectAsStateWithLifecycle()
            NightwatchTheme { NightwatchApp(m, state, ::authenticate) }
        }
        handle(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handle(intent)
    }

    override fun onResume() {
        super.onResume()
        model?.refresh()
    }

    /** A pairing link pre-fills Add host; a notification tap opens that host's alerts. */
    private fun handle(intent: Intent?) {
        parsePairing(intent?.data)?.let { p ->
            window.decorView.post { model?.pair(p) }
            return
        }
        val host = intent?.getStringExtra(AlertSync.EXTRA_HOST) ?: return
        window.decorView.post {
            model?.let { m ->
                if (m.state.value.hostId != host) m.selectHost(host)
                if (intent.getBooleanExtra(AlertSync.EXTRA_ALERTS, false)) m.selectTab(Tab.Alerts)
            }
        }
    }

    /** Fingerprint, face or screen lock before anything changes on a host. */
    private fun authenticate(title: String, onSuccess: () -> Unit) {
        val prompt = BiometricPrompt.Builder(this)
            .setTitle(title)
            .setSubtitle(getString(R.string.auth_subtitle))
            .setAllowedAuthenticators(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
            .build()
        prompt.authenticate(CancellationSignal(), mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) = onSuccess()
            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                model?.cancelConfirm()
                if (errorCode != BiometricPrompt.BIOMETRIC_ERROR_USER_CANCELED && errorCode != BiometricPrompt.BIOMETRIC_ERROR_CANCELED) {
                    Toast.makeText(this@MainActivity, errString, Toast.LENGTH_SHORT).show()
                }
            }
        })
    }
}
