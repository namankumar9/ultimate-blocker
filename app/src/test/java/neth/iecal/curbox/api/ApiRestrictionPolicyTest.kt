package neth.iecal.curbox.api

import neth.iecal.curbox.Constants
import neth.iecal.curbox.data.models.*
import org.junit.Assert.*
import org.junit.Test

class ApiRestrictionPolicyTest {
    private val condition = ExternalUnlockCondition("provider", "goal", "Goal", "Check", listOf("cert"))
    private val config = AppBlockerWarningScreenConfig(externalUnlockCondition = condition)
    private val app = AppGroup(id = "apps", isActive = true, warningScreenConfig = config)
    private val keyword = KeywordGroup(id = "keywords", isActive = true, warningScreenConfig = config)
    private val settings = Settings(
        blockedAppGroups = listOf(app),
        keywordBlockerConfig = KeywordBlocker(isActive = true, keywordGroups = listOf(keyword)),
        reelBlockerConfig = ReelBlocker(isActive = true, warningScreenConfig = config)
    )

    @Test fun exactCurrentWarningIsRequiredForEveryBlocker() {
        for ((mode, target) in listOf(Constants.WARNING_SCREEN_MODE_APP_BLOCKER to "apps",
            Constants.WARNING_SCREEN_MODE_KEYWORD_BLOCKER to "keywords",
            Constants.WARNING_SCREEN_MODE_VIEW_BLOCKER to "reel")) {
            assertTrue(ApiRestrictionPolicy.warningStillCurrent(settings, mode, target, config))
            for (changed in listOf(config.copy(isProceedDisabled = true),
                config.copy(timeInterval = 1L), config.copy(externalUnlockCondition = condition.copy(id = "other")))) {
                assertFalse(ApiRestrictionPolicy.warningStillCurrent(settings, mode, target, changed))
            }
        }
    }

    @Test fun deletedDisabledAndUnknownTargetsCannotAcceptOldApproval() {
        assertFalse(ApiRestrictionPolicy.warningStillCurrent(settings.copy(blockedAppGroups = emptyList()),
            Constants.WARNING_SCREEN_MODE_APP_BLOCKER, "apps", config))
        assertFalse(ApiRestrictionPolicy.warningStillCurrent(settings.copy(blockedAppGroups = listOf(app.copy(isActive = false))),
            Constants.WARNING_SCREEN_MODE_APP_BLOCKER, "apps", config))
        assertFalse(ApiRestrictionPolicy.warningStillCurrent(settings.copy(keywordBlockerConfig = KeywordBlocker()),
            Constants.WARNING_SCREEN_MODE_KEYWORD_BLOCKER, "keywords", config))
        assertFalse(ApiRestrictionPolicy.warningStillCurrent(settings.copy(reelBlockerConfig = ReelBlocker()),
            Constants.WARNING_SCREEN_MODE_VIEW_BLOCKER, "reel", config))
        assertFalse(ApiRestrictionPolicy.warningStillCurrent(settings, 999, "apps", config))
        assertFalse(ApiRestrictionPolicy.warningStillCurrent(settings, Constants.WARNING_SCREEN_MODE_APP_BLOCKER, "other", config))
    }

    @Test fun lockedFocusCannotBeStoppedReplacedOrShortened() {
        val group = ManualFocusGroup(groupId = "locked", exitable = false)
        val locked = Settings(manualFocusGroups = listOf(group), activeManualFocusGroupId = "locked" to 200L)
        assertFalse(ApiRestrictionPolicy.canReplaceFocus(locked, 100L))
        assertFalse(ApiRestrictionPolicy.canReplaceFocus(locked.copy(manualFocusGroups = emptyList()), 100L))
        assertTrue(ApiRestrictionPolicy.canReplaceFocus(locked, 200L))
        assertTrue(ApiRestrictionPolicy.canReplaceFocus(locked.copy(manualFocusGroups = listOf(group.copy(exitable = true))), 100L))
        assertTrue(ApiRestrictionPolicy.canReplaceFocus(Settings(), 100L))
    }
}
