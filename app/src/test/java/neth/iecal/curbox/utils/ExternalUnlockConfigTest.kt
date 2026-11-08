package neth.iecal.curbox.utils

import com.google.gson.Gson
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.ExternalUnlockCondition
import neth.iecal.curbox.data.models.Settings
import org.junit.Assert.*
import org.junit.Test

class ExternalUnlockConfigTest {
    private val condition = ExternalUnlockCondition("example.provider", "goal", "Practice", "example.provider.Check", listOf("certificate"))
    private val protected = AppBlockerWarningScreenConfig(externalUnlockCondition = condition)

    @Test fun removingOrChangingConditionIsWeaker() {
        assertFalse(RestrictionComparator.warningConfig(protected, protected.copy(externalUnlockCondition = null)))
        for (changed in listOf(condition.copy(id = "other"), condition.copy(packageName = "other"),
            condition.copy(activityName = "other"), condition.copy(signingCertificates = listOf("other")))) {
            assertFalse(RestrictionComparator.warningConfig(protected, protected.copy(externalUnlockCondition = changed)))
        }
    }

    @Test fun addingConditionDoesNotWeakenAndOtherLimitsStillApply() {
        assertTrue(RestrictionComparator.warningConfig(AppBlockerWarningScreenConfig(), protected))
        assertTrue(RestrictionComparator.warningConfig(protected, protected.copy(message = listOf("New message"))))
        assertFalse(RestrictionComparator.warningConfig(protected, protected.copy(timeInterval = protected.timeInterval + 1)))
        assertFalse(RestrictionComparator.warningConfig(protected, protected.copy(proceedDelayInSecs = 0)))
    }

    @Test fun oldSettingsLoadWithoutRequirementAndNewSettingsRoundTrip() {
        val gson = Gson()
        assertNull(gson.fromJson("{}", AppBlockerWarningScreenConfig::class.java).externalUnlockCondition)
        assertTrue(gson.fromJson("{}", Settings::class.java).externalUnlockConditions.isEmpty())
        assertEquals(protected, gson.fromJson(gson.toJson(protected), AppBlockerWarningScreenConfig::class.java))
    }
}
