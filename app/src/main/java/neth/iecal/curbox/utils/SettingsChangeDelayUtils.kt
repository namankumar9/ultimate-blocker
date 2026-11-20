package neth.iecal.curbox.utils

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import neth.iecal.curbox.CrashLogger
import neth.iecal.curbox.R
import com.google.gson.Gson
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.PendingSettingsChange
import neth.iecal.curbox.data.models.Settings

internal fun Settings.restoreExpiredTemporaryDisables(nowMs: Long): Settings = copy(
    blockedAppGroups = blockedAppGroups.map { group ->
        if (group.temporarilyDisabledUntilMs in 1..nowMs)
            group.copy(isActive = true, temporarilyDisabledUntilMs = 0L) else group
    },
    keywordBlockerConfig = keywordBlockerConfig.copy(keywordGroups = keywordBlockerConfig.keywordGroups.map { group ->
        if (group.temporarilyDisabledUntilMs in 1..nowMs)
            group.copy(isActive = true, temporarilyDisabledUntilMs = 0L) else group
    }),
    reelBlockerConfig = if (reelBlockerConfig.temporarilyDisabledUntilMs in 1..nowMs)
        reelBlockerConfig.copy(isActive = true, temporarilyDisabledUntilMs = 0L) else reelBlockerConfig
)

/**
 * Small helpers around the settings change delay. The gate itself lives in
 * [DataStoreManager.applyDuePendingChanges] and DataStoreManager's updateGated.
 */
object SettingsChangeDelayUtils {

    private val sweepScope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** Fire and forget sweep for callers without a coroutine scope, like Application.onCreate. */
    fun applyDuePendingChangesAsync(context: Context) {
        val appContext = context.applicationContext
        sweepScope.launch {
            try {
                DataStoreManager(appContext).apply {
                    applyDuePendingChanges()
                    restoreDueTemporaryAppGroups()
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLogger(appContext).logNonFatalError(e)
            }
        }
    }

    fun fieldLabel(context: Context, fieldName: String): String {
        val field = runCatching { GatedSettingsField.valueOf(fieldName) }.getOrNull()
        return context.getString(
            when (field) {
                GatedSettingsField.APP_GROUPS -> R.string.change_delay_field_app_groups
                GatedSettingsField.APP_LISTS -> R.string.change_delay_field_app_lists
                GatedSettingsField.AUTO_DND_GROUPS -> R.string.change_delay_field_auto_dnd
                GatedSettingsField.REEL_BLOCKER -> R.string.change_delay_field_reel_blocker
                GatedSettingsField.KEYWORD_BLOCKER -> R.string.change_delay_field_keyword_blocker
                GatedSettingsField.REEL_COUNTER -> R.string.change_delay_field_reel_counter
                GatedSettingsField.GRAYSCALE_GROUPS -> R.string.change_delay_field_grayscale
                GatedSettingsField.MINDFUL_MESSAGES -> R.string.change_delay_field_mindful_messages
                GatedSettingsField.SIP_STRETCH -> R.string.change_delay_field_sip_stretch
                GatedSettingsField.SCHEDULED_NOTIFICATIONS -> R.string.scheduled_notifications
                GatedSettingsField.UI_HIDER -> R.string.change_delay_field_ui_hider
                GatedSettingsField.APP_USAGE_TRACKING -> R.string.app_usage_tracking
                GatedSettingsField.WEBSITE_USAGE_TRACKING -> R.string.website_usage_tracking
                GatedSettingsField.CHANGE_DELAY, null -> R.string.change_delay_field_change_delay
            }
        )
    }

    /** Like [fieldLabel], but names the group when the change belongs to one App Pause group. */
    fun changeLabel(context: Context, change: PendingSettingsChange, settings: Settings?): String {
        if (change.field == GatedSettingsField.APP_GROUPS.name && change.itemId.isNotEmpty()) {
            val name = AppGroupGate.parseGroup(change.newValueJson, Gson())?.name
                ?: settings?.blockedAppGroups?.find { it.id == change.itemId }?.name
            if (name != null) return context.getString(R.string.change_delay_field_app_group_named, name)
        }
        return fieldLabel(context, change.field)
    }

    fun showPasswordApplyResult(context: Context, result: DataStoreManager.PasswordApplyResult) {
        val message = when (result) {
            DataStoreManager.PasswordApplyResult.APPLIED -> R.string.group_password_applied
            DataStoreManager.PasswordApplyResult.HELD_BY_TAMPER -> R.string.group_password_held_by_tamper
            DataStoreManager.PasswordApplyResult.WRONG_PASSWORD -> R.string.anti_uninstall_wrong_password
            DataStoreManager.PasswordApplyResult.NOT_WAITING -> R.string.group_password_not_waiting
        }
        android.widget.Toast.makeText(context, message, android.widget.Toast.LENGTH_SHORT).show()
    }

    fun formatRemaining(context: Context, remainingMs: Long): String {
        val totalSeconds = (remainingMs / 1000).coerceAtLeast(0)
        val days = totalSeconds / 86_400
        val hours = (totalSeconds % 86_400) / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (days > 0) {
            context.getString(R.string.anti_uninstall_remaining_with_days, days, hours, minutes, seconds)
        } else {
            context.getString(R.string.anti_uninstall_remaining, hours, minutes, seconds)
        }
    }
}
