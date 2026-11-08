package neth.iecal.curbox.blockers

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Process
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import neth.iecal.curbox.utils.DataStoreManager

/**
 * Puts newly installed apps into app groups that have "Add newly installed apps" on.
 *
 * Android 8+ no longer delivers PACKAGE_ADDED to manifest receivers, so this listens from the
 * always-running accessibility service. Installs that happen while the service is off are
 * caught on the next start by comparing each app's first install time with the group's
 * watermark. Only apps with a launcher icon count, matching the app selection screen.
 */
class NewAppAutoAdder(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val dataStore = DataStoreManager(context)
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val mutex = Mutex()
    private var registered = false

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            // App updates also fire PACKAGE_ADDED; they are not new installs.
            if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
            val pkg = intent.data?.schemeSpecificPart ?: return
            checkForNewApps { candidates(onlyPackage = pkg) }
        }
    }

    fun start() {
        val filter = IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply { addDataScheme("package") }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        registered = true
        checkForNewApps { candidates(onlyPackage = null) }
    }

    fun stop() {
        if (registered) {
            registered = false
            context.unregisterReceiver(receiver)
        }
    }

    private fun checkForNewApps(collect: () -> Map<String, Long>) {
        scope.launch(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val checkedAt = System.currentTimeMillis()
                    val candidates = collect()
                    Log.d("NewAppAutoAdder", "Checking ${candidates.size} installed app(s)")
                    if (dataStore.addNewlyInstalledApps(candidates, checkedAt)) {
                        context.sendBroadcast(
                            Intent(AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER).setPackage(context.packageName)
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("NewAppAutoAdder", "Failed to add newly installed apps", e)
            }
        }
    }

    /** Launchable apps, or just [onlyPackage], mapped to their first install time. */
    private fun candidates(onlyPackage: String?): Map<String, Long> {
        val excluded = homeScreenPackages() + context.packageName
        val packages = launcherApps.getActivityList(onlyPackage, Process.myUserHandle())
            .map { it.applicationInfo.packageName }
            .toSet() - excluded
        return packages.mapNotNull { pkg ->
            runCatching { pkg to context.packageManager.getPackageInfo(pkg, 0).firstInstallTime }.getOrNull()
        }.toMap()
    }

    /** Blocking a home screen app could leave the user with no way back to their launcher. */
    private fun homeScreenPackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .toSet()
    }
}
