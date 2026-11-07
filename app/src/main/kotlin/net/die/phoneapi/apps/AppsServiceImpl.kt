package net.die.phoneapi.apps

import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext
import net.die.phoneapi.core.AppsService
import net.die.phoneapi.core.DeviceStateTracker
import net.die.phoneapi.core.EventBus
import net.die.phoneapi.core.IntentStart
import net.die.phoneapi.core.awaitDeviceState
import net.die.phoneapi.helperclient.HelperShell
import net.die.phoneapi.helperclient.failureMessage
import net.die.phoneapi.model.ActionResult
import net.die.phoneapi.model.ApiException
import net.die.phoneapi.model.AppInfo
import net.die.phoneapi.model.IntentRequest
import net.die.phoneapi.model.LaunchRequest

/**
 * Launching, stopping, clearing and deep-linking apps. Installing is out of scope.
 *
 * Launching goes through the helper when it runs, and through the platform without it; intents,
 * `stop` and `clear` need the helper's shell UID (`apps.manage` in the capability map).
 */
class AppsServiceImpl(
    private val context: Context,
    private val io: CoroutineDispatcher,
    private val prepare: suspend (Boolean) -> Boolean,
    private val invalidateSnapshots: () -> Unit,
    private val seq: StateFlow<Long>,
    private val shell: HelperShell,
    private val state: DeviceStateTracker,
    private val bus: EventBus,
) : AppsService {
    private val packages: PackageManager = context.packageManager

    override suspend fun list(launchableOnly: Boolean): List<AppInfo> =
        withContext(io) {
            val launchable = launchablePackages()
            val names = if (launchableOnly) launchable.toList() else installedPackages()
            names
                .mapNotNull { name -> appInfo(name, name in launchable) }
                .sortedBy { it.label.lowercase() }
        }

    /**
     * Android drops this app's background activity starts without an error, so the launch goes
     * through the helper when it runs, as [intent] does. Without the helper it is this app's own
     * start, which works only while PhoneAPI was on screen in the last few seconds.
     */
    override suspend fun launch(packageName: String, request: LaunchRequest): ActionResult {
        val woke = prepare(true)
        val intent = launchIntent(packageName, request)
        val viaShell = shell.isAvailable
        if (viaShell) startFromShell(intent) else start(intent)
        val arrived = !request.wait || awaitForeground(packageName)
        invalidateSnapshots()
        return ActionResult(
            ok = arrived,
            backend = if (viaShell) "shell" else "activity",
            woke = woke,
            seq = seq.value,
            message = if (arrived) null else notForeground(intent, packageName, viaShell),
        )
    }

    override suspend fun stop(packageName: String): ActionResult =
        manage(packageName, "stop", listOf("am", "force-stop", packageName))

    override suspend fun clear(packageName: String): ActionResult =
        manage(packageName, "clear", listOf("pm", "clear", packageName))

    /**
     * Android drops this app's background activity starts without an error, so intents go through
     * the helper, which is allowed to start them.
     */
    override suspend fun intent(request: IntentRequest): IntentStart {
        val component =
            request.component?.let {
                ComponentName.unflattenFromString(it)
                    ?: throw ApiException.badRequest(
                        "component must look like com.example/.MainActivity"
                    )
            }
        shell.require()
        val woke = prepare(true)
        val started = shell.startActivity(startIntent(request), START_WAIT_MS)
        started.failure?.let { throw intentFailed(describe(request), it, started.message) }
        val packageName = request.packageName ?: component?.packageName ?: started.activityPackage
        val missing = packageName?.takeIf { !started.chooser && !awaitForeground(it) }
        invalidateSnapshots()
        val result =
            ActionResult(
                ok = !started.chooser && missing == null,
                backend = "shell",
                woke = woke,
                seq = seq.value,
                message =
                    when {
                        started.chooser ->
                            "Several apps handle ${describe(request)}, so Android is showing a " +
                                "chooser. Name a package to pick one."
                        missing != null ->
                            "Android accepted ${describe(request)}, but $missing did not come " +
                                "to the foreground."
                        else -> started.warning
                    },
            )
        return IntentStart(result, packageName)
    }

    private suspend fun manage(
        packageName: String,
        what: String,
        argv: List<String>,
    ): ActionResult {
        if (packageName == context.packageName) {
            throw ApiException.badRequest("Refusing to $what PhoneAPI itself")
        }
        if (packageInfo(packageName) == null) {
            throw ApiException.notFound("$packageName is not installed")
        }
        val result = shell.exec(argv)
        if (!result.ok) {
            throw ApiException(
                409,
                "${what}_failed",
                "`${argv.joinToString(" ")}` exited ${result.exit}: ${result.failureMessage()}",
            )
        }
        invalidateSnapshots()
        return ActionResult(
            ok = true,
            backend = "shell",
            seq = seq.value,
            message = result.stdout.trim().takeIf { it.isNotEmpty() },
        )
    }

    private fun launchIntent(packageName: String, request: LaunchRequest): Intent {
        val activity = request.activity
        val intent =
            if (activity == null) {
                packages.getLaunchIntentForPackage(packageName)
                    ?: throw ApiException.notFound(
                        "$packageName is not installed, or has no launcher activity"
                    )
            } else {
                Intent(Intent.ACTION_MAIN)
                    .setComponent(ComponentName(packageName, qualify(packageName, activity)))
            }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (request.fresh) intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK)
        return intent
    }

    private fun start(intent: Intent) {
        try {
            context.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            throw ApiException(
                404,
                "not_found",
                "Nothing handles ${intent.action ?: "this intent"}",
                cause = e,
            )
        } catch (e: SecurityException) {
            throw intentRefused(
                "${describe(intent)} is not exported, so it can only be started by its own app",
                e,
            )
        }
    }

    /** Starts [intent] through the helper, which may start activities. */
    private suspend fun startFromShell(intent: Intent) {
        val started = shell.startActivity(intent, START_WAIT_MS)
        started.failure?.let { throw intentFailed(describe(intent), it, started.message) }
    }

    private suspend fun awaitForeground(packageName: String): Boolean =
        awaitDeviceState(state, bus, FOREGROUND_WAIT_MS) {
            it.foregroundPackage == packageName
        }

    private fun appInfo(packageName: String, launchable: Boolean): AppInfo? {
        val info = packageInfo(packageName) ?: return null
        val app = info.applicationInfo ?: return null
        return AppInfo(
            packageName = packageName,
            label = app.loadLabel(packages).toString(),
            versionName = info.versionName,
            versionCode = info.longVersionCode,
            system = app.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            enabled = app.enabled,
            launchable = launchable,
        )
    }

    /** Apps with a launcher entry, which is what the manifest's `<queries>` makes visible. */
    @Suppress("DEPRECATION") // The ResolveInfoFlags overload needs API 33.
    private fun launchablePackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        return packages.queryIntentActivities(intent, 0).mapNotNullTo(LinkedHashSet()) {
            it.activityInfo?.packageName
        }
    }

    /**
     * Android 11 hides packages an app hasn't declared an interest in, so this is everything this
     * app can see rather than everything installed. The helper's `pm list packages` sees them all.
     */
    @Suppress(
        "DEPRECATION", // The PackageInfoFlags overload needs API 33.
        "QueryPermissionsNeeded", // The partial list is what this wants, as above.
    )
    private fun installedPackages(): List<String> =
        packages.getInstalledPackages(0).map { it.packageName }

    // The PackageInfoFlags overloads only exist from API 33, and this behaves identically.
    @Suppress("DEPRECATION")
    private fun packageInfo(packageName: String): PackageInfo? =
        try {
            packages.getPackageInfo(packageName, 0)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }

    private fun notForeground(intent: Intent, packageName: String, viaShell: Boolean): String {
        val started =
            "Started ${describe(intent)}, but $packageName did not come to the foreground."
        return if (viaShell) {
            started
        } else {
            "$started The shell helper is not running, and Android blocks this app from " +
                "starting activities in the background. Start the helper and try again."
        }
    }

    private fun describe(intent: Intent): String =
        intent.component?.flattenToShortString() ?: intent.`package` ?: intent.action.orEmpty()

    private fun qualify(packageName: String, activity: String): String =
        if (activity.startsWith(".")) packageName + activity else activity

    private companion object {
        const val FOREGROUND_WAIT_MS = 5_000L
        // A start waits for the first frame, and a cold start can take several seconds.
        const val START_WAIT_MS = 20_000L
    }
}
