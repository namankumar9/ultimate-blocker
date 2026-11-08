package neth.iecal.curbox.blockers

import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.UserHandle
import android.os.UserManager
import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.utils.DataStoreManager

/**
 * Puts newly installed apps into app groups that have "Add apps you install later" on.
 *
 * Uses [LauncherApps], like the app picker, so apps installed in a work profile count too:
 * a PACKAGE_ADDED receiver only hears about its own profile. Installs that happen while the
 * service is off are caught on the next start by comparing each app's first install time
 * with the group's watermark. Only apps with a launcher icon count, matching the app picker.
 *
 * Android only reports install times for this app's own profile: for a work profile app it
 * returns 0, or the personal copy's time when both profiles have it. Work profile apps are
 * instead compared with the set seen on the previous check, and an app missing from that set
 * counts as installed now.
 */
class NewAppAutoAdder(
    private val context: Context,
    private val scope: CoroutineScope,
) {
    private val dataStore = DataStoreManager(context)
    private val launcherApps = context.getSystemService(LauncherApps::class.java)
    private val userManager = context.getSystemService(UserManager::class.java)
    private val seenPrefs = context.getSharedPreferences("new_app_auto_add", Context.MODE_PRIVATE)
    private val mutex = Mutex()
    private var registered = false

    private val callback = object : LauncherApps.Callback() {
        // Called for new installs only; app updates arrive as onPackageChanged.
        override fun onPackageAdded(packageName: String, user: UserHandle) {
            checkForNewApps { now -> candidates(onlyPackage = packageName, onlyUser = user, now = now) }
        }

        // Forget removed apps so installing one again counts as new.
        override fun onPackageRemoved(packageName: String, user: UserHandle) {
            val key = "${userManager.getSerialNumberForUser(user)}:$packageName"
            scope.launch(Dispatchers.IO) {
                mutex.withLock {
                    val seen = seenPrefs.getStringSet(KEY_SEEN, null) ?: return@withLock
                    if (key in seen) seenPrefs.edit().putStringSet(KEY_SEEN, seen - key).apply()
                }
            }
        }
        override fun onPackageChanged(packageName: String, user: UserHandle) = Unit
        override fun onPackagesAvailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = Unit
        override fun onPackagesUnavailable(packageNames: Array<out String>, user: UserHandle, replacing: Boolean) = Unit
    }

    fun start() {
        launcherApps.registerCallback(callback, Handler(Looper.getMainLooper()))
        registered = true
        checkForNewApps { now -> candidates(onlyPackage = null, onlyUser = null, now = now) }
    }

    fun stop() {
        if (registered) {
            registered = false
            launcherApps.unregisterCallback(callback)
        }
    }

    private fun checkForNewApps(collect: (checkedAt: Long) -> Map<String, Long>) {
        scope.launch(Dispatchers.IO) {
            try {
                mutex.withLock {
                    val checkedAt = System.currentTimeMillis()
                    if (dataStore.addNewlyInstalledApps(collect(checkedAt), checkedAt)) {
                        context.sendBroadcast(
                            Intent(AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER).setPackage(context.packageName)
                        )
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("NewAppAutoAdder", "Failed to add newly installed apps", e)
                CrashLogger(context).logNonFatalError(e)
            }
        }
    }

    /**
     * Launchable apps in every profile (or just [onlyPackage] in [onlyUser]), mapped to their
     * first install time. Groups store package names, so a package installed in several
     * profiles keeps its newest install time.
     */
    private fun candidates(onlyPackage: String?, onlyUser: UserHandle?, now: Long): Map<String, Long> {
        val excluded = homeScreenPackages() + context.packageName
        val profiles = onlyUser?.let { listOf(it) } ?: launcherApps.profiles
        val previouslySeen = seenPrefs.getStringSet(KEY_SEEN, null).orEmpty()
        val knownProfiles = seenPrefs.getStringSet(KEY_PROFILES, null).orEmpty()
        val seen = previouslySeen.toMutableSet()
        val updatedProfiles = knownProfiles.toMutableSet()
        val result = mutableMapOf<String, Long>()
        for (profile in profiles) {
            val serial = userManager.getSerialNumberForUser(profile).toString()
            val isOwnProfile = profile == Process.myUserHandle()
            // A paused or locked work profile can refuse or list nothing. Keep its old record,
            // or every app in it would look new once it is back.
            val activities = runCatching { launcherApps.getActivityList(onlyPackage, profile) }
                .getOrNull()
                ?.filter { it.applicationInfo.packageName !in excluded }
            if (activities.isNullOrEmpty()) continue
            // The first time a profile shows up, record its apps without adding them, so a newly
            // set up work profile does not pull in everything it came with.
            val isKnownProfile = serial in knownProfiles
            if (onlyPackage == null) {
                // A full listing replaces the record, so an app removed and installed again counts as new.
                seen.removeAll { it.startsWith("$serial:") }
                updatedProfiles += serial
            }
            for (activity in activities) {
                val pkg = activity.applicationInfo.packageName
                val key = "$serial:$pkg"
                val installedAt = when {
                    isOwnProfile -> activity.firstInstallTime
                    isKnownProfile && key !in previouslySeen -> now
                    else -> 0L
                }
                seen += key
                result[pkg] = maxOf(result[pkg] ?: 0L, installedAt)
            }
        }
        seenPrefs.edit()
            .putStringSet(KEY_SEEN, seen)
            .putStringSet(KEY_PROFILES, updatedProfiles)
            .apply()
        return result
    }

    private companion object {
        const val KEY_SEEN = "seen_profile_packages"
        const val KEY_PROFILES = "seen_profiles"
    }

    /** Blocking a home screen app could leave the user with no way back to their launcher. */
    private fun homeScreenPackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return context.packageManager.queryIntentActivities(home, PackageManager.MATCH_ALL)
            .map { it.activityInfo.packageName }
            .toSet()
    }
}
