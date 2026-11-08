package neth.iecal.curbox.api

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExternalUnlockAttemptTest {
    @Test fun approvalIsConsumedOnce() {
        val attempt = ExternalUnlockAttempt()
        attempt.start("fresh", 100L)
        assertTrue(attempt.consume("fresh", true, 101L))
        assertFalse(attempt.consume("fresh", true, 102L))
    }

    @Test fun rejectsWrongMissingCancelledAndExpiredResults() {
        for ((id, approved, now) in listOf(
            Triple("other", true, 101L), Triple(null, true, 101L),
            Triple("fresh", false, 101L), Triple("fresh", true, 600_101L),
            Triple("fresh", true, 99L)
        )) {
            val attempt = ExternalUnlockAttempt()
            attempt.start("fresh", 100L)
            assertFalse(attempt.consume(id, approved, now))
            assertFalse(attempt.consume("fresh", true, 101L))
        }
    }

    @Test fun revokingAndRestoringAccessInvalidatesOutstandingApproval() {
        val attempt = ExternalUnlockAttempt()
        attempt.start("fresh", 100L, "before_revocation")
        assertFalse(attempt.consume("fresh", true, 101L, "after_reapproval"))
        assertFalse(attempt.consume("fresh", true, 102L, "before_revocation"))
        attempt.start("new", 103L, "after_reapproval")
        assertTrue(attempt.consume("new", true, 104L, "after_reapproval"))
    }

    @Test fun clearedOrRecreatedAttemptCannotApprove() {
        val attempt = ExternalUnlockAttempt()
        attempt.start("fresh", 100L)
        attempt.clear()
        assertFalse(attempt.consume("fresh", true, 101L))
        assertFalse(ExternalUnlockAttempt().consume("fresh", true, 101L))
    }
}
