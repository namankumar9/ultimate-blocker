package neth.iecal.curbox.utils

import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.reflect.TypeToken
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.PendingSettingsChange
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.SettingsChangeDelayConfig
import neth.iecal.curbox.data.models.upgradeLegacyAppGroupConfigs

/**
 * The settings change delay for App Pause groups, one group at a time.
 *
 * Every group waits on its own timer: a weaker edit to one group is parked as its own
 * [PendingSettingsChange] (keyed by [PendingSettingsChange.itemId]) for that group's delay,
 * see [RestrictionComparator.groupDelayMinutes]. Other groups are not held back by it.
 * The rules inside one group match the gate for every other setting: a same or stricter edit
 * applies at once and drops that group's waiting change, a stricter part of an edit made on
 * top of a waiting change goes live at once, and an edit that only adds to the waiting
 * change keeps its timer.
 */
object AppGroupGate {

    private val groupListType = object : TypeToken<List<AppGroup>>() {}.type

    /** What one call to [apply] did, for the notice shown to the user. */
    data class Outcome(
        /** The waiting change this edit created or replaced, if any. */
        val deferred: PendingSettingsChange? = null,
        /** [deferred] replaced a change that was already waiting for the same group. */
        val replacedExisting: Boolean = false,
        val keptTimer: Boolean = false,
        /** Part of the edit went live at once while the rest keeps waiting. */
        val appliedOverWaiting: Boolean = false,
        /** A same or stricter edit cancelled a change that was waiting. */
        val droppedPending: Boolean = false,
        val tamperGated: Boolean = false,
    )

    data class Result(val settings: Settings, val outcome: Outcome)

    /**
     * Gates a full list of groups as an editor saved it. [overlay] is what the editor showed:
     * [current] with every waiting change applied. Groups equal to their [overlay] value are
     * left alone, so an edit to one group never touches another group's waiting change.
     *
     * [passwords] maps group ids to an edit password the user typed. A group whose live
     * password matches skips its wait, see [unlockedGroups].
     */
    fun apply(
        current: Settings,
        overlay: Settings,
        newGroups: List<AppGroup>,
        nowMs: Long,
        gson: Gson,
        passwords: Map<String, String> = emptyMap(),
    ): Result {
        val unlocked = unlockedGroups(current, passwords)
        val before = overlay.blockedAppGroups.associateBy { it.id }
        val after = newGroups.associateBy { it.id }
        val ids = (newGroups.map { it.id } + overlay.blockedAppGroups.map { it.id }).distinct()
        var settings = current
        var outcome = Outcome()
        for (id in ids) {
            val newJson = after[id]?.let(gson::toJson) ?: ""
            val beforeJson = before[id]?.let(gson::toJson) ?: ""
            if (newJson == beforeJson) continue
            val step = applyOne(settings, id, beforeJson, newJson, nowMs, gson, id in unlocked)
            settings = step.settings
            // One editor save changes one group. Keep the most telling notice if there are more.
            outcome = mergeOutcomes(outcome, step.outcome)
        }
        return Result(settings, outcome)
    }

    private fun mergeOutcomes(a: Outcome, b: Outcome): Outcome = Outcome(
        deferred = b.deferred ?: a.deferred,
        replacedExisting = if (b.deferred != null) b.replacedExisting else a.replacedExisting,
        keptTimer = if (b.deferred != null) b.keptTimer else a.keptTimer,
        appliedOverWaiting = a.appliedOverWaiting || b.appliedOverWaiting,
        droppedPending = a.droppedPending || b.droppedPending,
        tamperGated = a.tamperGated || b.tamperGated,
    )

    /**
     * The groups whose edit password is in [passwords]. Checked against the live group, so a
     * password change that is still waiting can't be used before it lands.
     */
    fun unlockedGroups(current: Settings, passwords: Map<String, String>): Set<String> =
        current.blockedAppGroups.filter { group ->
            group.editPasswordHash.isNotEmpty() &&
                passwords[group.id]?.let { WarningPassword.verify(it, group.editPasswordHash) } == true
        }.map { it.id }.toSet()

    /**
     * Gates the edit of group [id] from [beforeJson] to [newJson] ("" means no group).
     * [unlocked] means the user entered the group's edit password: the wait is skipped, but
     * "require tamper protection off" still holds the change.
     */
    private fun applyOne(
        current: Settings,
        id: String,
        beforeJson: String,
        newJson: String,
        nowMs: Long,
        gson: Gson,
        unlocked: Boolean = false,
    ): Result {
        val delayConfig = current.settingsChangeDelayConfig2
        val existing = delayConfig.pendingChanges.find { isForGroup(it, id) }
        if (existing?.newValueJson == newJson) return Result(current, Outcome())

        val live = current.blockedAppGroups.find { it.id == id }
        val newGroup = parseGroup(newJson, gson)
        if (newJson.isNotEmpty() && newGroup == null) return Result(current, Outcome())
        val proposed = current.copy(blockedAppGroups = withGroup(current.blockedAppGroups, id, newGroup))
        val mainDelay = RestrictionComparator.mainDelayMinutes(delayConfig)
        val delay = if (unlocked) 0 else live?.let { RestrictionComparator.groupDelayMinutes(it, mainDelay) } ?: 0
        val tamperGated = delayConfig.requireTamperProtectionOff && current.antiUninstallConfig2.isEnabled

        if ((delay == 0 && !tamperGated) || isSameOrStricter(current, proposed)) {
            val applied = proposed.copy(
                settingsChangeDelayConfig2 = delayConfig.copy(
                    pendingChanges = delayConfig.pendingChanges.filterNot { isForGroup(it, id) }
                )
            )
            return Result(applied, Outcome(droppedPending = existing != null))
        }

        // The edit was made on top of a waiting change. What it changed may still be same or
        // stricter than the live group; that part goes live now.
        val liveAfterMerge = existing?.let { mergedLive(current, id, beforeJson, newJson, gson) }
        val base = liveAfterMerge ?: current

        val waiting = existing?.let {
            current.copy(blockedAppGroups = withGroup(current.blockedAppGroups, id, parseGroup(it.newValueJson, gson)))
        }
        // With the password the only thing left to wait for is tamper protection.
        val kept = existing?.takeIf { !unlocked && waiting != null && isSameOrStricter(waiting, proposed) }
        val appliesAt = kept?.appliesAtMs ?: (nowMs + delay * 60_000L)
        val pending = PendingSettingsChange(
            field = GatedSettingsField.APP_GROUPS.name,
            itemId = id,
            newValueJson = newJson,
            requestedAtMs = kept?.requestedAtMs ?: nowMs,
            appliesAtMs = appliesAt,
        )
        val parked = base.copy(
            settingsChangeDelayConfig2 = base.settingsChangeDelayConfig2.copy(
                pendingChanges = delayConfig.pendingChanges.filterNot { isForGroup(it, id) } + pending
            )
        )
        return Result(
            parked,
            Outcome(
                deferred = pending,
                replacedExisting = existing != null,
                keptTimer = kept != null,
                appliedOverWaiting = liveAfterMerge != null,
                tamperGated = tamperGated,
            )
        )
    }

    private fun isSameOrStricter(current: Settings, proposed: Settings) =
        RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_GROUPS, current, proposed)

    /**
     * Carries what the edit changed (from [beforeJson] to [newJson]) over to the live group.
     * Returns the result only when it differs from [current] and is same or stricter.
     */
    private fun mergedLive(current: Settings, id: String, beforeJson: String, newJson: String, gson: Gson): Settings? =
        runCatching {
            if (beforeJson.isEmpty() || newJson.isEmpty()) return null
            val live = current.blockedAppGroups.find { it.id == id } ?: return null
            val liveJson = JsonParser.parseString(gson.toJson(live))
            val merged = GatedEditMerge.merge(
                JsonParser.parseString(beforeJson),
                JsonParser.parseString(newJson),
                liveJson
            )
            if (merged == null || merged == liveJson) return null
            val group = gson.fromJson(merged, AppGroup::class.java)
            val candidate = current.copy(blockedAppGroups = withGroup(current.blockedAppGroups, id, group))
            candidate.takeIf { isSameOrStricter(current, it) }
        }.getOrNull()

    fun isForGroup(change: PendingSettingsChange, id: String) =
        change.field == GatedSettingsField.APP_GROUPS.name && change.itemId == id

    /** Applies a waiting change for one group. Null when it cannot be read. */
    fun applyPending(groups: List<AppGroup>, change: PendingSettingsChange, gson: Gson): List<AppGroup>? {
        if (change.newValueJson.isEmpty()) return withGroup(groups, change.itemId, null)
        val group = parseGroup(change.newValueJson, gson) ?: return null
        return withGroup(groups, change.itemId, group)
    }

    /** [groups] with group [id] replaced in place, appended when new, or removed when [group] is null. */
    fun withGroup(groups: List<AppGroup>, id: String, group: AppGroup?): List<AppGroup> = when {
        group == null -> groups.filterNot { it.id == id }
        groups.any { it.id == id } -> groups.map { if (it.id == id) group else it }
        else -> groups + group
    }

    fun parseGroup(json: String, gson: Gson): AppGroup? = runCatching {
        if (json.isEmpty()) null
        else listOf(gson.fromJson(json, AppGroup::class.java)).upgradeLegacyAppGroupConfigs(gson).first()
    }.getOrNull()

    /**
     * Turns a waiting change for the whole group list, as older versions stored it, into one
     * waiting change per group that it changes. Each keeps the original timer.
     */
    fun splitLegacyPending(change: PendingSettingsChange, liveGroups: List<AppGroup>, gson: Gson): List<PendingSettingsChange> {
        val waitingGroups = runCatching {
            gson.fromJson<List<AppGroup>>(change.newValueJson, groupListType)
        }.getOrNull() ?: return listOf(change)
        val live = liveGroups.associateBy { it.id }
        val waiting = waitingGroups.associateBy { it.id }
        val changed = waitingGroups.filter { group ->
            live[group.id]?.let(gson::toJson) != gson.toJson(group)
        }.map { change.copy(itemId = it.id, newValueJson = gson.toJson(it)) }
        val deleted = liveGroups.filter { it.id !in waiting }
            .map { change.copy(itemId = it.id, newValueJson = "") }
        return changed + deleted
    }

    /**
     * How long a change to the app lists waits: the longest delay among the groups it can
     * loosen, which are the groups that link a list the change edits or deletes.
     */
    fun appListsDelayMinutes(current: Settings, proposed: Settings): Int {
        val mainDelay = RestrictionComparator.mainDelayMinutes(current.settingsChangeDelayConfig2)
        val proposedById = proposed.appLists.associateBy { it.id }
        val changedIds = current.appLists.filter { proposedById[it.id] != it }.map { it.id }.toHashSet()
        return current.blockedAppGroups
            .filter { it.isActive || it.temporarilyDisabledUntilMs > 0L }
            .filter { group -> group.appListIds.any { it in changedIds } }
            .maxOfOrNull { RestrictionComparator.groupDelayMinutes(it, mainDelay) }
            ?: mainDelay
    }

    /**
     * True when temporary disable may be offered for [group]: nothing makes its changes wait,
     * or [passwordEntered] is its edit password.
     */
    fun temporaryDisableAllowed(config: SettingsChangeDelayConfig, group: AppGroup, passwordEntered: String? = null): Boolean {
        if (config.requireTamperProtectionOff) return false
        if (passwordEntered != null && group.editPasswordHash.isNotEmpty()) {
            return WarningPassword.verify(passwordEntered, group.editPasswordHash)
        }
        return !config.isEnabled && (group.changeDelayMinutes ?: 0) == 0 && group.editPasswordHash.isEmpty()
    }
}
