package neth.iecal.curbox.utils

import com.google.gson.Gson
import neth.iecal.curbox.data.models.AppGroup
import neth.iecal.curbox.data.models.AppGroupAutostartConfig
import neth.iecal.curbox.data.models.AppGroupConfig
import neth.iecal.curbox.data.models.AppTimeConfig
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.AutostartMode
import neth.iecal.curbox.data.models.GatedSettingsField
import neth.iecal.curbox.data.models.PendingSettingsChange
import neth.iecal.curbox.data.models.Settings
import neth.iecal.curbox.data.models.TimeInterval
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Calendar

class AutostartTest {

    // --- gating: autostart only ever makes a group stronger ---

    @Test
    fun gainingAutostartIsStricter() {
        assertTrue(sameOrStricter(activeGroup(), activeGroup(timeOfDay(480, listOf(1, 2, 3)))))
    }

    @Test
    fun removingAutostartIsWeaker() {
        assertFalse(sameOrStricter(activeGroup(timeOfDay(480, listOf(1, 2, 3))), activeGroup()))
    }

    @Test
    fun addingDaysAtTheSameTimeIsStricter() {
        assertTrue(
            sameOrStricter(
                activeGroup(timeOfDay(480, listOf(1, 2))),
                activeGroup(timeOfDay(480, listOf(1, 2, 3)))
            )
        )
    }

    @Test
    fun removingDaysIsWeaker() {
        assertFalse(
            sameOrStricter(
                activeGroup(timeOfDay(480, listOf(1, 2, 3))),
                activeGroup(timeOfDay(480, listOf(1, 2)))
            )
        )
    }

    @Test
    fun changingTheTimeWaits() {
        assertFalse(
            sameOrStricter(
                activeGroup(timeOfDay(480, listOf(1, 2, 3))),
                activeGroup(timeOfDay(540, listOf(1, 2, 3)))
            )
        )
    }

    @Test
    fun theNextFireTimeIsIgnoredWhenComparing() {
        val old = activeGroup(timeOfDay(480, listOf(1, 2)).copy(nextFireAtMs = 100L))
        val new = activeGroup(timeOfDay(480, listOf(1, 2)).copy(nextFireAtMs = 999L))
        assertTrue(sameOrStricter(old, new))
    }

    // --- scheduling math ---

    @Test
    fun noneNeverFires() {
        assertEquals(0L, AutostartSchedule.nextFireAfter(AppGroupAutostartConfig(), now()))
    }

    @Test
    fun oneTimeInTheFutureArmsAndInThePastDoesNot() {
        val now = now()
        val future = AppGroupAutostartConfig(mode = AutostartMode.OneTime, oneTimeAtMs = now + 60_000L)
        val past = AppGroupAutostartConfig(mode = AutostartMode.OneTime, oneTimeAtMs = now - 60_000L)
        assertEquals(now + 60_000L, AutostartSchedule.nextFireAfter(future, now))
        assertEquals(0L, AutostartSchedule.nextFireAfter(past, now))
    }

    @Test
    fun oneTimeClearsItselfOnceItFires() {
        val now = now()
        val config = AppGroupAutostartConfig(
            mode = AutostartMode.OneTime, oneTimeAtMs = now - 1_000L, nextFireAtMs = now - 1_000L
        )
        val (result, fired) = AutostartSchedule.fireIfDue(config, now)
        assertTrue(fired)
        assertEquals(AutostartMode.None, result.mode)
        assertEquals(0L, result.nextFireAtMs)
    }

    @Test
    fun timeOfDayAdvancesToItsNextDayOnceItFires() {
        val now = now()
        val config = timeOfDay(480, (0..6).toList()).copy(nextFireAtMs = now - 1_000L)
        val (result, fired) = AutostartSchedule.fireIfDue(config, now)
        assertTrue(fired)
        assertEquals(AutostartMode.TimeOfDay, result.mode)
        assertTrue(result.nextFireAtMs > now)
    }

    @Test
    fun timeOfDayFiresAtTheChosenMinuteOfDay() {
        val now = now()
        val next = AutostartSchedule.nextFireAfter(timeOfDay(8 * 60 + 30, (0..6).toList()), now)
        assertTrue(next > now)
        val cal = Calendar.getInstance().apply { timeInMillis = next }
        assertEquals(8 * 60 + 30, cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE))
    }

    // --- a waiting change lands with fresh autostart bookkeeping ---

    @Test
    fun aWaitingTurnOffIsNotUndoneByItsStaleNextFire() {
        val now = now()
        val config = timeOfDay(480, (0..6).toList())
        // The live group was re-armed after the copy below was saved.
        val live = activeGroup(config.copy(nextFireAtMs = now + 10 * 60 * 60_000L))
        val waiting = live.copy(isActive = false, autostartConfig = config.copy(nextFireAtMs = now - 60 * 60_000L))
        val landed = land(live, waiting, now)
        assertFalse(landed.isActive)
        assertEquals(live.autostartConfig, landed.autostartConfig)
        assertFalse(Settings(blockedAppGroups = listOf(landed)).applyDueAutostarts(now).blockedAppGroups[0].isActive)
    }

    @Test
    fun aOneTimeStartThatFiredWhileAChangeWaitedDoesNotComeBack() {
        val now = now()
        val past = now - 60 * 60_000L
        val live = activeGroup()
        val waiting = live.copy(
            isActive = false,
            autostartConfig = AppGroupAutostartConfig(mode = AutostartMode.OneTime, oneTimeAtMs = past, nextFireAtMs = past)
        )
        val landed = land(live, waiting, now)
        assertEquals(AutostartMode.None, landed.autostartConfig.mode)
        assertFalse(Settings(blockedAppGroups = listOf(landed)).applyDueAutostarts(now).blockedAppGroups[0].isActive)
    }

    @Test
    fun aChangedAutostartIsArmedWhenItLands() {
        val now = now()
        val live = activeGroup(timeOfDay(480, (0..6).toList()))
        val waiting = live.copy(autostartConfig = timeOfDay(600, listOf(1)).copy(nextFireAtMs = now - 1))
        assertTrue(land(live, waiting, now).autostartConfig.nextFireAtMs > now)
    }

    private fun land(live: AppGroup, waiting: AppGroup, now: Long): AppGroup {
        val gson = Gson()
        val change = PendingSettingsChange(
            field = GatedSettingsField.APP_GROUPS.name,
            itemId = live.id,
            newValueJson = gson.toJson(waiting),
            requestedAtMs = now - 1,
            appliesAtMs = now,
        )
        return AppGroupGate.applyPending(listOf(live), change, gson, now)!!.single()
    }

    private fun now() = System.currentTimeMillis()

    private fun timeOfDay(minutes: Int, days: List<Int>) = AppGroupAutostartConfig(
        mode = AutostartMode.TimeOfDay, timeOfDayMinutes = minutes, days = days
    )

    private fun sameOrStricter(old: AppGroup, new: AppGroup) =
        RestrictionComparator.appGroups(listOf(old), listOf(new))

    private fun activeGroup(autostart: AppGroupAutostartConfig = AppGroupAutostartConfig()) = AppGroup(
        id = "group",
        selectedPackages = listOf("example.app"),
        config = AppGroupConfig(
            schedule = AppTimeConfig(everydayIntervals = mutableListOf(TimeInterval(9, 0, 17, 0))),
            usage = AppUsageConfig(uniformLimit = 60)
        ),
        isActive = true,
        autostartConfig = autostart
    )
}
