package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Password clause of [RestrictionComparator.warningConfig]: true = same or stricter. */
class RestrictionComparatorPasswordTest {
    private val base = AppBlockerWarningScreenConfig()
    private val locked = base.copy(isPasswordRequirementEnabled = true, passwordHash = "aa:bb")

    @Test
    fun `adding a password is stricter`() {
        assertTrue(RestrictionComparator.warningConfig(base, locked))
    }

    @Test
    fun `keeping the same password is not weaker`() {
        assertTrue(RestrictionComparator.warningConfig(locked, locked.copy(proceedDelayInSecs = 30)))
    }

    @Test
    fun `removing the password is weaker`() {
        assertFalse(RestrictionComparator.warningConfig(locked, base))
    }

    @Test
    fun `changing the password is weaker`() {
        assertFalse(RestrictionComparator.warningConfig(locked, locked.copy(passwordHash = "cc:dd")))
    }

    @Test
    fun `switching from typing to password is weaker`() {
        val typing = base.copy(isTypingRequirementEnabled = true, typingSentence = "hello")
        assertFalse(RestrictionComparator.warningConfig(typing, locked))
    }
}
