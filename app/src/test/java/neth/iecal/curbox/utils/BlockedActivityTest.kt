package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.data.models.AppList
import neth.iecal.curbox.data.models.BlockedActivity
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.partialActivities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class BlockedActivityTest {
    private val dev = "com.android.settings/com.android.settings.DevSettings"
    private val group = AppGroup(id = "g", selectedActivities = listOf(dev), config = AppGroupConfig(), isActive = true)
    private val current = Settings(blockedAppGroups = listOf(group))

    private fun ok(proposed: AppGroup, from: Settings = current) = RestrictionComparator.isSameOrStricter(
        GatedSettingsField.APP_GROUPS, from, from.copy(blockedAppGroups = listOf(proposed))
    )

    @Test
    fun parsesFullAndShortForms() {
        assertEquals(BlockedActivity("a.b", "a.b.Main"), BlockedActivity.parse("a.b/a.b.Main"))
        assertEquals(BlockedActivity("a.b", "a.b.Main"), BlockedActivity.parse("a.b/.Main"))
        assertNull(BlockedActivity.parse("a.b"))
        assertNull(BlockedActivity.parse("/a.b.Main"))
    }

    @Test
    fun addingAnActivityIsStricter() {
        assertTrue(ok(group.copy(selectedActivities = listOf(dev, "x/x.Y"))))
    }

    @Test
    fun removingAnActivityWaits() {
        assertFalse(ok(group.copy(selectedActivities = emptyList())))
    }

    @Test
    fun blockingTheWholeAppInsteadIsStricter() {
        assertTrue(ok(group.copy(selectedActivities = emptyList(), selectedPackages = listOf("com.android.settings"))))
    }

    @Test
    fun wholeAppFromAListAlsoCovers() {
        val withList = current.copy(appLists = listOf(AppList("L", "S", listOf("com.android.settings"))))
        assertTrue(ok(group.copy(selectedActivities = emptyList(), appListIds = listOf("L")), withList))
    }

    @Test
    fun inactiveGroupsChangeFreely() {
        val inactive = current.copy(blockedAppGroups = listOf(group.copy(isActive = false)))
        assertTrue(ok(group.copy(isActive = false, selectedActivities = emptyList()), inactive))
    }

    @Test
    fun activitiesOfWhollyBlockedAppsAreSkipped() {
        val both = group.copy(selectedPackages = listOf("com.android.settings"), selectedActivities = listOf(dev, "x/x.Y"))
        assertEquals(listOf(BlockedActivity("x", "x.Y")), both.partialActivities(emptyList()))
    }

    @Test
    fun backupKeepsActivitiesAndRejectsBadOnes() {
        val restored = SettingsBackupCodec.decode(SettingsBackupCodec.encode(current).inputStream())
        assertEquals(listOf(dev), restored.blockedAppGroups.single().selectedActivities)
        val bad = current.copy(blockedAppGroups = listOf(group.copy(selectedActivities = listOf("nonsense"))))
        assertThrows(Exception::class.java) {
            SettingsBackupCodec.decode(SettingsBackupCodec.encode(bad).inputStream())
        }
    }
}
