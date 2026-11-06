package net.die.phoneapi.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.preference.Preference
import androidx.preference.PreferenceCategory
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.SwitchPreferenceCompat
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.die.phoneapi.PhoneApiApp
import net.die.phoneapi.R
import net.die.phoneapi.helperclient.doNotDisturbOn
import net.die.phoneapi.helperclient.notificationsAllowed
import net.die.phoneapi.model.HelperStatus
import net.die.phoneapi.model.Scope
import net.die.phoneapi.model.TokenInfo

/** Helper, wireless debugging, pairing, and tokens. */
class SettingsFragment : PreferenceFragmentCompat() {
    private val graph
        get() = PhoneApiApp.graph

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        setPreferencesFromResource(R.xml.preferences, rootKey)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            findPreference<Preference>(KEY_WIRELESS)?.isVisible = false
            findPreference<Preference>(KEY_WIRELESS_SETTINGS)?.isVisible = false
        }
        findPreference<SwitchPreferenceCompat>(KEY_HELPER)?.setOnPreferenceChangeListener { _, value
            ->
            if (value == true) graph.helperSupervisor.nudge() else graph.helperSupervisor.stop()
            false
        }
        findPreference<SwitchPreferenceCompat>(KEY_WIRELESS)?.setOnPreferenceChangeListener {
            _,
            value ->
            val on = value as? Boolean ?: return@setOnPreferenceChangeListener false
            try {
                graph.helperSupervisor.setWirelessEnabled(on)
                true
            } catch (e: SecurityException) {
                Log.w(TAG, "Could not change wireless debugging", e)
                false
            }
        }
        findPreference<Preference>(KEY_WIRELESS_SETTINGS)?.setOnPreferenceClickListener {
            if (graph.helperSupervisor.isPaired()) openWirelessDebuggingSettings()
            else pairWireless()
            true
        }
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch { watchControls() }
                launch { graph.tokens.revision.collect { refreshTokens() } }
            }
        }
    }

    private suspend fun watchControls() {
        combine(graph.helper.status, graph.helperSupervisor.wireless) { helper, wirelessOn ->
                Controls(
                    helper,
                    wirelessOn,
                    graph.helperSupervisor.isPaired(),
                    graph.helperSupervisor.canStartHelper(),
                )
            }
            .collect { controls ->
                showHelper(controls)
                showWireless(controls)
            }
    }

    private fun showHelper(controls: Controls) {
        val helper = findPreference<SwitchPreferenceCompat>(KEY_HELPER) ?: return
        val running = controls.helper == HelperStatus.RUNNING
        val starting = controls.helper == HelperStatus.STARTING
        helper.isChecked = running
        helper.isEnabled = running || (controls.canStart && !starting)
        helper.summary =
            when {
                running -> getString(R.string.helper_running)
                starting -> getString(R.string.helper_starting)
                Build.VERSION.SDK_INT < Build.VERSION_CODES.R ->
                    getString(R.string.helper_needs_computer)
                !controls.paired -> getString(R.string.helper_needs_pairing)
                !controls.canStart -> getString(R.string.helper_needs_wireless)
                else -> getString(R.string.helper_off)
            }
    }

    private fun showWireless(controls: Controls) {
        val toggle = findPreference<SwitchPreferenceCompat>(KEY_WIRELESS)
        val openSettings = findPreference<Preference>(KEY_WIRELESS_SETTINGS)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            toggle?.isVisible = false
            openSettings?.isVisible = false
            return
        }
        // Pairing comes first: the switch can't start the helper until the phone is paired.
        val canToggle = controls.paired && graph.helperSupervisor.canControlWireless()
        toggle?.isVisible = canToggle
        openSettings?.isVisible = !canToggle
        if (canToggle) {
            toggle?.isChecked = controls.wirelessOn
            toggle?.summary = getString(R.string.wireless_summary)
        } else if (!controls.paired) {
            openSettings?.summary = getString(R.string.wireless_setup)
        } else {
            val state =
                getString(
                    if (controls.wirelessOn) R.string.wireless_state_on
                    else R.string.wireless_state_off
                )
            openSettings?.summary = getString(R.string.wireless_needs_permission, state)
        }
    }

    private suspend fun refreshTokens() {
        val infos = withContext(graph.ioDispatcher) { graph.tokens.list() }
        val category = findPreference<PreferenceCategory>(KEY_TOKENS) ?: return
        category.removeAll()
        if (infos.isEmpty()) {
            category.addPreference(
                Preference(preferenceManager.context).apply {
                    title = getString(R.string.tokens_none)
                    isSelectable = false
                }
            )
            return
        }
        infos.forEach { info -> category.addPreference(tokenPreference(info)) }
    }

    private fun tokenPreference(info: TokenInfo) =
        Preference(preferenceManager.context).apply {
            key = "token-${info.id}"
            title = info.name
            summary = getString(R.string.token_summary, scopesLabel(info.scopes))
            setOnPreferenceClickListener {
                viewLifecycleOwner.lifecycleScope.launch {
                    withContext(graph.ioDispatcher) { graph.tokens.revoke(info.id) }
                }
                true
            }
        }

    /** "All scopes" for a token that has every one, else its scope names in API order. */
    private fun scopesLabel(scopes: Set<Scope>): String =
        if (scopes.containsAll(Scope.entries)) getString(R.string.token_scopes_all)
        else Scope.entries.filter { it in scopes }.joinToString(", ") { it.name.lowercase() }

    private fun pairWireless() {
        val context = requireContext()
        val developer =
            Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.DEVELOPMENT_SETTINGS_ENABLED,
                0,
            )
        if (developer != 1 || !context.notificationsAllowed()) {
            Toast.makeText(context, R.string.wireless_needs_developer, Toast.LENGTH_LONG).show()
            return
        }
        graph.wirelessPairing.start()
        if (context.doNotDisturbOn()) {
            Toast.makeText(context, R.string.wireless_dnd, Toast.LENGTH_LONG).show()
        }
        openWirelessDebuggingSettings()
    }

    private fun openWirelessDebuggingSettings() {
        val highlight = Bundle().apply { putString(FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_KEY) }
        val intent =
            Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                .putExtra(FRAGMENT_ARG_KEY, WIRELESS_DEBUGGING_KEY)
                .putExtra(SHOW_FRAGMENT_ARGS, highlight)
        try {
            startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.w(TAG, "No developer options screen", e)
        }
    }

    private data class Controls(
        val helper: HelperStatus,
        val wirelessOn: Boolean,
        val paired: Boolean,
        val canStart: Boolean,
    )

    private companion object {
        const val KEY_HELPER = "helper"
        const val KEY_WIRELESS = "wireless"
        const val KEY_WIRELESS_SETTINGS = "wireless_settings"
        const val KEY_TOKENS = "tokens"
        const val TAG = "PhoneApi"
        const val FRAGMENT_ARG_KEY = ":settings:fragment_args_key"
        const val SHOW_FRAGMENT_ARGS = ":settings:show_fragment_args"
        const val WIRELESS_DEBUGGING_KEY = "toggle_adb_wireless"
    }
}
