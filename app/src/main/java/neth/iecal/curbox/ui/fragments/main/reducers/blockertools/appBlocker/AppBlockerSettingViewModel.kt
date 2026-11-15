package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker

import android.app.Application
import android.content.Intent
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.application
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.blockers.AppBlocker
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppList
import neth.iecal.curbox.data.models.allPackages
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.utils.DataStoreManager
import neth.iecal.curbox.utils.UsageStatsHelper
import neth.iecal.curbox.utils.activeWindow
import java.util.Calendar

class AppBlockerSettingViewModel(application: Application) : AndroidViewModel(application) {
    var currentUsageConfig: AppUsageConfig = AppUsageConfig(uniformLimit = 0)
    var currentTimeConfig: AppTimeConfig = AppTimeConfig.allDay()
    var warningScrnConfig: AppBlockerWarningScreenConfig = AppBlockerWarningScreenConfig()

    private val dataStoreManager = DataStoreManager(application)
    private val usageStats = UsageStatsHelper(application)

    /**
     * Time left in the current schedule before this group hits its usage limit, in millis.
     * Returns null while the group's schedule is not active. Usage is the combined
     * total of every app in the group, matching how the blocker compares it.
     */
    suspend fun getRemainingUsageMillis(group: AppGroup): Long? {
        if (group.warningScreenConfig.isOnOpenConfig) return null
        val config = group.config ?: return null
        val window = config.schedule.activeWindow() ?: return null

        val limitMillis = limitForToday(config.usage) * 60_000L
        val packages = group.allPackages(_appLists.value)
        val used = withContext(Dispatchers.IO) {
            usageStats.getForegroundUsageBetween(
                packages,
                window.startMs,
                minOf(System.currentTimeMillis(), window.endMs)
            )
        }
        return (limitMillis - used).coerceAtLeast(0L)
    }

    private fun limitForToday(config: AppUsageConfig): Long {
        return if (config.isDailyUniform) config.uniformLimit
        else config.dailyLimits[Calendar.getInstance().get(Calendar.DAY_OF_WEEK) - 1]
    }

    private val _groups = MutableStateFlow<List<AppGroup>>(emptyList())
    val groups: StateFlow<List<AppGroup>> = _groups
    private val _appLists = MutableStateFlow<List<AppList>>(emptyList())
    val appLists: StateFlow<List<AppList>> = _appLists
    private val _temporaryDisableAvailable = MutableStateFlow(true)
    val temporaryDisableAvailable: StateFlow<Boolean> = _temporaryDisableAvailable
    init {
        viewModelScope.launch {
            dataStoreManager.settingsForEditing.collectLatest { settings ->
                _appLists.value = settings.appLists
                _groups.value = settings.blockedAppGroups
            }
        }
        viewModelScope.launch {
            // This escape hatch must not become usable while enabling it is still pending.
            dataStoreManager.settings.collectLatest { settings ->
                _temporaryDisableAvailable.value =
                    !settings.settingsChangeDelayConfig2.isEnabled &&
                    !settings.settingsChangeDelayConfig2.requireTamperProtectionOff
            }
        }
    }

    private fun requestAppBlockerRefresh() {
        val intent = Intent(AppBlocker.INTENT_ACTION_REFRESH_APP_BLOCKER)
        application.sendBroadcast(intent)
    }

    private suspend fun persistGroups(newGroups: List<AppGroup>) {
        dataStoreManager.updateAppGroups(newGroups)
        requestAppBlockerRefresh()
    }

    fun updateGroups(newGroups: List<AppGroup>) = viewModelScope.launch {
        persistGroups(newGroups)
    }
    
    fun addGroup(group: AppGroup) = viewModelScope.launch {
        addGroupAndWait(group)
    }

    suspend fun addGroupAndWait(group: AppGroup) {
        val currentSettings = dataStoreManager.settingsForEditing.first()
        val updatedGroups = currentSettings.blockedAppGroups.toMutableList().apply { add(group) }
        persistGroups(updatedGroups)
    }

    suspend fun updateGroupById(updatedGroup: AppGroup) {
        val currentSettings = dataStoreManager.settingsForEditing.first()
        val updatedGroups = currentSettings.blockedAppGroups.toMutableList()
        val index = updatedGroups.indexOfFirst { it.id == updatedGroup.id }
        if (index != -1) {
            updatedGroups[index] = updatedGroup
            persistGroups(updatedGroups)
        }
    }

    suspend fun deleteGroup(groupId: String) {
        val currentSettings = dataStoreManager.settingsForEditing.first()
        val updatedGroups = currentSettings.blockedAppGroups.filterNot { it.id == groupId }
        persistGroups(updatedGroups)
    }

    /** Adds the list, or replaces the one with the same id. Removing apps may be delayed. */
    suspend fun saveAppList(list: AppList) {
        val current = dataStoreManager.settingsForEditing.first().appLists
        val updated = if (current.any { it.id == list.id }) {
            current.map { if (it.id == list.id) list else it }
        } else current + list
        dataStoreManager.updateAppLists(updated)
        requestAppBlockerRefresh()
    }

    /**
     * Deletes the list unless a group still links to it. Returns the names of those groups,
     * empty when the list was deleted. Unlinking happens in each group, where the change
     * delay already judges it.
     */
    suspend fun deleteAppList(listId: String): List<String> {
        val settings = dataStoreManager.settingsForEditing.first()
        val users = settings.blockedAppGroups.filter { listId in it.appListIds }.map { it.name }
        if (users.isNotEmpty()) return users
        dataStoreManager.updateAppLists(settings.appLists.filterNot { it.id == listId })
        requestAppBlockerRefresh()
        return emptyList()
    }

    fun updateGroupActiveState(index: Int, isActive: Boolean) {
        viewModelScope.launch {
            val currentSettings = dataStoreManager.settingsForEditing.first()
            val updatedGroups = currentSettings.blockedAppGroups.toMutableList()
            if (index in updatedGroups.indices) {
                updatedGroups[index] = updatedGroups[index].copy(
                    isActive = isActive,
                    temporarilyDisabledUntilMs = 0L
                )
                updateGroups(updatedGroups)
            }
        }
    }

    fun temporarilyDisableGroup(groupId: String, durationMinutes: Long) {
        viewModelScope.launch {
            if (dataStoreManager.temporarilyDisableAppGroup(groupId, durationMinutes)) {
                requestAppBlockerRefresh()
            }
        }
    }
}
