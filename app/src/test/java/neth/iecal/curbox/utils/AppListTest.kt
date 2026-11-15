package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.data.models.AppList
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.allPackages
import neth.iecal.curbox.data.models.resolvedAppGroups
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AppListTest {
    private val social = AppList(id = "L1", name = "Social", packages = listOf("a", "b"))
    private val group = AppGroup(
        id = "g", name = "G", selectedPackages = listOf("c"), appListIds = listOf("L1"),
        config = AppGroupConfig(), isActive = true
    )
    private val current = Settings(blockedAppGroups = listOf(group), appLists = listOf(social))

    private fun listsOk(proposed: List<AppList>, from: Settings = current) =
        RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_LISTS, from, from.copy(appLists = proposed))

    private fun groupsOk(proposed: AppGroup) =
        RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_GROUPS, current,
            current.copy(blockedAppGroups = listOf(proposed)))

    @Test
    fun groupBlocksItsOwnAppsAndItsListsApps() {
        assertEquals(setOf("a", "b", "c"), group.allPackages(listOf(social)))
        assertEquals(setOf("a", "b", "c"), current.resolvedAppGroups().single().selectedPackages.toSet())
        // A link to a list that no longer exists adds nothing.
        assertEquals(setOf("c"), group.allPackages(emptyList()))
    }

    @Test
    fun addingAppsToAListInUseIsStricter() {
        assertTrue(listsOk(listOf(social.copy(packages = listOf("a", "b", "d")))))
    }

    @Test
    fun removingAppsFromAListInUseWaits() {
        assertFalse(listsOk(listOf(social.copy(packages = listOf("a")))))
    }

    @Test
    fun removingAnAppTheGroupAlsoPicksItselfIsNotLoosening() {
        val overlapping = current.copy(blockedAppGroups = listOf(group.copy(selectedPackages = listOf("a", "c"))))
        assertTrue(listsOk(listOf(social.copy(packages = listOf("b"))), overlapping))
    }

    @Test
    fun deletingAListInUseWaits() {
        assertFalse(listsOk(emptyList()))
    }

    @Test
    fun listsUsedOnlyByInactiveGroupsChangeRightAway() {
        val inactive = current.copy(blockedAppGroups = listOf(group.copy(isActive = false)))
        assertTrue(listsOk(emptyList(), inactive))
    }

    @Test
    fun listsUsedByTemporarilyDisabledGroupsStillWait() {
        val paused = current.copy(blockedAppGroups = listOf(
            group.copy(isActive = false, temporarilyDisabledUntilMs = 123L)
        ))
        assertFalse(listsOk(listOf(social.copy(packages = listOf("a"))), paused))
    }

    @Test
    fun unlinkingAListFromAnActiveGroupWaits() {
        assertFalse(groupsOk(group.copy(appListIds = emptyList())))
    }

    @Test
    fun swappingAListForTheSameAppsPickedByHandIsNotLoosening() {
        assertTrue(groupsOk(group.copy(appListIds = emptyList(), selectedPackages = listOf("a", "b", "c"))))
    }

    @Test
    fun linkingAnotherListIsStricter() {
        val more = current.copy(appLists = listOf(social, AppList("L2", "Games", listOf("x"))))
        assertTrue(RestrictionComparator.isSameOrStricter(GatedSettingsField.APP_GROUPS, more,
            more.copy(blockedAppGroups = listOf(group.copy(appListIds = listOf("L1", "L2"))))))
    }

    @Test
    fun backupKeepsListsAndLinks() {
        val restored = SettingsBackupCodec.decode(SettingsBackupCodec.encode(current).inputStream())
        assertEquals(listOf(social), restored.appLists)
        assertEquals(listOf("L1"), restored.blockedAppGroups.single().appListIds)
    }

    @Test
    fun backupWithDuplicateListIdsIsRejected() {
        val bad = current.copy(appLists = listOf(social, social.copy(name = "Other")))
        assertThrows(Exception::class.java) {
            SettingsBackupCodec.decode(SettingsBackupCodec.encode(bad).inputStream())
        }
    }
}

class AppListScheduleConflictTest {
    @Test
    fun appsFromListsCountAsSharedApps() {
        val list = AppList("L1", "Social", listOf("cal"))
        val viaList = AppGroup(id = "a", appListIds = listOf("L1"), config = AppGroupConfig(), isActive = true)
        val direct = AppGroup(id = "b", selectedPackages = listOf("cal"), config = AppGroupConfig(), isActive = true)
        assertEquals(setOf("cal"), viaList.scheduleConflictsWith(listOf(direct), listOf(list)).single().sharedPackages)
        assertTrue(viaList.scheduleConflictsWith(listOf(direct)).isEmpty())
    }
}
