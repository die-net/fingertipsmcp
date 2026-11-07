package net.die.phoneapi.power

import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import net.die.phoneapi.PhoneApiApp
import net.die.phoneapi.core.awaitDeviceState
import net.die.phoneapi.model.ScreenState

/**
 * Turns the screen on without the helper: a transparent activity that asks to be shown over the
 * keyguard and to turn the display on, and that finishes itself once the screen is on. It is
 * excluded from recents and has no history, so it never shows up as a task.
 *
 * With [EXTRA_DISMISS_KEYGUARD] it also asks the keyguard to go away, which is the only supported
 * way to dismiss a non-secure lock screen from an app. Showing over the keyguard and turning the
 * screen on are declared on the activity in the manifest.
 */
class WakeActivity : ComponentActivity() {
    private val graph
        get() = PhoneApiApp.graph

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                graph.state.refresh()
                val dismiss = intent?.getBooleanExtra(EXTRA_DISMISS_KEYGUARD, false) == true
                // Finishing before the screen is on would take the screen-on request with it.
                val on =
                    awaitDeviceState(graph.state, graph.bus, WAKE_WAIT_MS) {
                        it.screen == ScreenState.ON
                    }
                if (!on) Log.w(TAG, "The screen did not turn on")
                if (dismiss) {
                    getSystemService(KeyguardManager::class.java)
                        .requestDismissKeyguard(this@WakeActivity, null)
                    awaitDeviceState(graph.state, graph.bus, DISMISS_WAIT_MS) {
                        !it.keyguard.locked
                    }
                }
                finish()
            }
        }
    }

    companion object {
        internal const val EXTRA_DISMISS_KEYGUARD = "net.die.phoneapi.extra.DISMISS_KEYGUARD"

        private const val TAG = "PhoneApiPower"
        private const val WAKE_WAIT_MS = 2_000L
        private const val DISMISS_WAIT_MS = 2_000L

        fun start(context: Context, dismissKeyguard: Boolean = false) {
            val intent =
                Intent(context, WakeActivity::class.java)
                    .addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_NO_ANIMATION or
                            Intent.FLAG_ACTIVITY_NO_HISTORY
                    )
                    .putExtra(EXTRA_DISMISS_KEYGUARD, dismissKeyguard)
            context.startActivity(intent)
        }
    }
}
