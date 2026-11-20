package neth.iecal.curbox.utils

import com.google.gson.Gson
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.data.models.AppList
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.PendingSettingsChange
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.SettingsChangeDelayConfig
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppGroupGateTest {
    private val gson = Gson()
    private val now = 1_000_000L
    private val minute = 60_000L

    private fun group(id: String, delay: Int? = null, limit: Long = 30, active: Boolean = true, lists: List<String> = emptyList()) =
        AppGroup(
            id = id,
            name = id,
            selectedPackages = listOf("$id.app"),
            appListIds = lists,
            config = AppGroupConfig(usage = AppUsageConfig(uniformLimit = limit)),
            isActive = active,
            changeDelayMinutes = delay,
        )

    private fun settings(vararg groups: AppGroup, mainDelay: Int = 0, lists: List<AppList> = emptyList()) = Settings(
        blockedAppGroups = groups.toList(),
        appLists = lists,
        settingsChangeDelayConfig2 = SettingsChangeDelayConfig(isEnabled = mainDelay > 0, delayMinutes = mainDelay),
    )

    /** Saves [edit] applied to the groups as the editor shows them, like [DataStoreManager.updateAppGroups]. */
    private fun save(current: Settings, at: Long = now, edit: (List<AppGroup>) -> List<AppGroup>): AppGroupGate.Result {
        val overlay = current.copy(blockedAppGroups = current.settingsChangeDelayConfig2.pendingChanges
            .fold(current.blockedAppGroups) { groups, change -> AppGroupGate.applyPending(groups, change, gson)!! })
        return AppGroupGate.apply(current, overlay, edit(overlay.blockedAppGroups), at, gson)
    }

    private fun Settings.pending(id: String) =
        settingsChangeDelayConfig2.pendingChanges.find { AppGroupGate.isForGroup(it, id) }

    private fun List<AppGroup>.edit(id: String, change: (AppGroup) -> AppGroup) =
        map { if (it.id == id) change(it) else it }

    @Test
    fun eachGroupWaitsOnItsOwnTimer() {
        var s = settings(group("a", delay = 120), group("b", delay = 10))
        s = save(s) { it.edit("a") { g -> g.copy(isActive = false) } }.settings
        s = save(s) { it.edit("b") { g -> g.copy(isActive = false) } }.settings

        assertEquals(now + 120 * minute, s.pending("a")!!.appliesAtMs)
        assertEquals(now + 10 * minute, s.pending("b")!!.appliesAtMs)
        // Both are still on until their own timer ends.
        assertTrue(s.blockedAppGroups.all { it.isActive })
    }

    @Test
    fun aStricterEditToOneGroupAppliesWhileAnotherWaits() {
        var s = settings(group("a", delay = 120), group("b", delay = 10))
        s = save(s) { it.edit("a") { g -> g.copy(isActive = false) } }.settings
        s = save(s) { it.edit("b") { g -> g.copy(config = AppGroupConfig(usage = AppUsageConfig(uniformLimit = 5))) } }.settings

        assertEquals(5L, s.blockedAppGroups.find { it.id == "b" }!!.config!!.usage.uniformLimit)
        assertNull(s.pending("b"))
        assertEquals(now + 120 * minute, s.pending("a")!!.appliesAtMs)
    }

    @Test
    fun aGroupDelayAppliesWhileTheMainDelayIsOff() {
        val s = save(settings(group("a", delay = 60))) { it.edit("a") { g -> g.copy(isActive = false) } }.settings
        assertTrue(s.blockedAppGroups.single().isActive)
        assertEquals(now + 60 * minute, s.pending("a")!!.appliesAtMs)
    }

    @Test
    fun groupsWithoutTheirOwnDelayUseTheMainDelay() {
        val s = save(settings(group("a"), mainDelay = 240)) { it.edit("a") { g -> g.copy(isActive = false) } }.settings
        assertEquals(now + 240 * minute, s.pending("a")!!.appliesAtMs)
    }

    @Test
    fun aZeroGroupDelayAppliesAtOnceEvenWithTheMainDelayOn() {
        val s = save(settings(group("a", delay = 0), mainDelay = 240)) { it.edit("a") { g -> g.copy(isActive = false) } }.settings
        assertFalse(s.blockedAppGroups.single().isActive)
        assertNull(s.pending("a"))
    }

    @Test
    fun loweringAGroupDelayWaitsTheOldDelay() {
        val s = save(settings(group("a", delay = 120))) { it.edit("a") { g -> g.copy(changeDelayMinutes = 10) } }.settings
        assertEquals(120, s.blockedAppGroups.single().changeDelayMinutes)
        assertEquals(now + 120 * minute, s.pending("a")!!.appliesAtMs)
    }

    @Test
    fun raisingAGroupDelayAppliesAtOnce() {
        val s = save(settings(group("a", delay = 10))) { it.edit("a") { g -> g.copy(changeDelayMinutes = 120) } }.settings
        assertEquals(120, s.blockedAppGroups.single().changeDelayMinutes)
        assertNull(s.pending("a"))
    }

    @Test
    fun goingBackToTheMainDelayIsLowerWhenTheMainDelayIsShorter() {
        val s = save(settings(group("a", delay = 120), mainDelay = 10)) {
            it.edit("a") { g -> g.copy(changeDelayMinutes = null) }
        }.settings
        assertEquals(120, s.blockedAppGroups.single().changeDelayMinutes)
        assertEquals(now + 120 * minute, s.pending("a")!!.appliesAtMs)
    }

    @Test
    fun deletingAGroupWaitsAndAddingOneAppliesAtOnce() {
        var s = settings(group("a", delay = 60))
        s = save(s) { it.filterNot { g -> g.id == "a" } + group("b", delay = 60) }.settings

        assertEquals(listOf("a", "b"), s.blockedAppGroups.map { it.id })
        assertEquals("", s.pending("a")!!.newValueJson)
        assertNull(s.pending("b"))

        val due = AppGroupGate.applyPending(s.blockedAppGroups, s.pending("a")!!, gson)!!
        assertEquals(listOf("b"), due.map { it.id })
    }

    @Test
    fun anEditOnTopOfAWaitingChangeThatOnlyAddsKeepsTheTimer() {
        var s = settings(group("a", delay = 60))
        s = save(s) { it.edit("a") { g -> g.copy(config = AppGroupConfig(usage = AppUsageConfig(uniformLimit = 90))) } }.settings
        val result = save(s, at = now + 30 * minute) {
            it.edit("a") { g -> g.copy(selectedPackages = g.selectedPackages + "more.app") }
        }

        assertTrue(result.outcome.keptTimer)
        assertEquals(now + 60 * minute, result.settings.pending("a")!!.appliesAtMs)
        // The added app is stricter than the live group, so it is live already.
        assertTrue("more.app" in result.settings.blockedAppGroups.single().selectedPackages)
        assertTrue(result.outcome.appliedOverWaiting)
    }

    @Test
    fun anUnchangedGroupLeavesItsWaitingChangeAlone() {
        var s = settings(group("a", delay = 60), group("b", delay = 60))
        s = save(s) { it.edit("a") { g -> g.copy(isActive = false) } }.settings
        val waiting = s.pending("a")
        // A save that only touches b, made from the edited view, keeps a's change waiting.
        s = save(s, at = now + minute) { it.edit("b") { g -> g.copy(name = "renamed") } }.settings
        assertEquals(waiting, s.pending("a"))
        assertEquals("renamed", s.blockedAppGroups.find { it.id == "b" }!!.name)
    }

    @Test
    fun appListChangesWaitForTheLongestDelayAmongTheirGroups() {
        val list = AppList(id = "l", name = "l", packages = listOf("x.app", "y.app"))
        val current = settings(
            group("a", delay = 10, lists = listOf("l")),
            group("b", delay = 120, lists = listOf("l")),
            group("c", delay = 2000),
            lists = listOf(list),
        )
        val proposed = current.copy(appLists = listOf(list.copy(packages = listOf("x.app"))))
        assertEquals(120, AppGroupGate.appListsDelayMinutes(current, proposed))
    }

    @Test
    fun appListChangesUseTheMainDelayForGroupsWithoutTheirOwn() {
        val list = AppList(id = "l", name = "l", packages = listOf("x.app"))
        val current = settings(group("a", lists = listOf("l")), mainDelay = 60, lists = listOf(list))
        val proposed = current.copy(appLists = emptyList())
        assertEquals(60, AppGroupGate.appListsDelayMinutes(current, proposed))
    }

    @Test
    fun temporaryDisableIsOffForAGroupWithItsOwnDelay() {
        val config = SettingsChangeDelayConfig()
        assertTrue(AppGroupGate.temporaryDisableAllowed(config, group("a")))
        assertTrue(AppGroupGate.temporaryDisableAllowed(config, group("a", delay = 0)))
        assertFalse(AppGroupGate.temporaryDisableAllowed(config, group("a", delay = 10)))
    }

    @Test
    fun aWholeListWaitingChangeFromAnOlderVersionSplitsPerGroup() {
        val a = group("a")
        val b = group("b")
        val c = group("c")
        val legacy = PendingSettingsChange(
            field = GatedSettingsField.APP_GROUPS.name,
            newValueJson = gson.toJson(listOf(a.copy(isActive = false), b)),
            requestedAtMs = 5L,
            appliesAtMs = 9L,
        )
        val split = AppGroupGate.splitLegacyPending(legacy, listOf(a, b, c), gson)

        assertEquals(listOf("a", "c"), split.map { it.itemId })
        assertTrue(split.all { it.appliesAtMs == 9L && it.requestedAtMs == 5L })
        assertFalse(AppGroupGate.parseGroup(split[0].newValueJson, gson)!!.isActive)
        assertEquals("", split[1].newValueJson)
    }
}
