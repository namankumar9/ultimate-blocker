package neth.iecal.curbox.utils

import neth.iecal.curbox.data.models.AppGroupAutostartConfig
import neth.iecal.curbox.data.models.AutostartMode
import java.util.Calendar

/**
 * Works out when a group's autostart ([AppGroupAutostartConfig]) next switches it on. Times
 * use the device's own clock and time zone, so a Time of Day autostart fires at the same wall
 * clock moment each day even across daylight saving changes.
 */
object AutostartSchedule {

    /** The config with [AppGroupAutostartConfig.nextFireAtMs] set to its next switch on. */
    fun armed(config: AppGroupAutostartConfig, nowMs: Long): AppGroupAutostartConfig =
        config.copy(nextFireAtMs = nextFireAfter(config, nowMs))

    /**
     * The autostart [incoming] should land with when it replaces [live], for example when a
     * waiting change applies. Its next fire time may be stale, since a waiting change keeps the
     * copy saved when it was made. The same config keeps [live]'s bookkeeping; a different one
     * is armed from [nowMs], and a One Time start that has already passed is dropped.
     */
    fun rebased(live: AppGroupAutostartConfig?, incoming: AppGroupAutostartConfig, nowMs: Long): AppGroupAutostartConfig {
        if (live != null && live.copy(nextFireAtMs = 0L) == incoming.copy(nextFireAtMs = 0L)) return live
        val armed = armed(incoming, nowMs)
        return if (armed.mode == AutostartMode.OneTime && armed.nextFireAtMs == 0L) AppGroupAutostartConfig() else armed
    }

    /** The next switch on strictly after [nowMs], or 0 when nothing is scheduled. */
    fun nextFireAfter(config: AppGroupAutostartConfig, nowMs: Long): Long = when (config.mode) {
        AutostartMode.None -> 0L
        AutostartMode.OneTime -> if (config.oneTimeAtMs > nowMs) config.oneTimeAtMs else 0L
        AutostartMode.TimeOfDay -> nextTimeOfDay(nowMs, config.timeOfDayMinutes, config.days)
    }

    /**
     * Applies a due autostart to one config. Returns the config to store and whether it fired.
     * A One Time autostart clears itself; a Time of Day one arms its next day. A config whose
     * next fire was never worked out (0) is armed first, so an old or freshly loaded one still
     * starts ticking.
     */
    fun fireIfDue(config: AppGroupAutostartConfig, nowMs: Long): Pair<AppGroupAutostartConfig, Boolean> {
        val armed = if (config.nextFireAtMs == 0L && config.mode != AutostartMode.None) armed(config, nowMs) else config
        if (armed.nextFireAtMs !in 1..nowMs) return armed to false
        return when (armed.mode) {
            AutostartMode.OneTime ->
                armed.copy(mode = AutostartMode.None, oneTimeAtMs = 0L, nextFireAtMs = 0L) to true
            AutostartMode.TimeOfDay ->
                armed.copy(nextFireAtMs = nextTimeOfDay(nowMs, armed.timeOfDayMinutes, armed.days)) to true
            AutostartMode.None -> armed.copy(nextFireAtMs = 0L) to false
        }
    }

    private fun nextTimeOfDay(nowMs: Long, minutesPastMidnight: Int, days: List<Int>): Long {
        if (days.isEmpty()) return 0L
        val minutes = minutesPastMidnight.coerceIn(0, 1439)
        // Eight steps cover the case where today's time has already passed and the next
        // matching day is a week away.
        for (daysAhead in 0..7) {
            val candidate = Calendar.getInstance().apply {
                timeInMillis = nowMs
                add(Calendar.DAY_OF_YEAR, daysAhead)
                set(Calendar.HOUR_OF_DAY, minutes / 60)
                set(Calendar.MINUTE, minutes % 60)
                set(Calendar.SECOND, 0)
                set(Calendar.MILLISECOND, 0)
            }
            // Calendar weekdays run Sunday = 1; our days run Sunday = 0.
            val dayOfWeek = candidate.get(Calendar.DAY_OF_WEEK) - 1
            if (dayOfWeek in days && candidate.timeInMillis > nowMs) return candidate.timeInMillis
        }
        return 0L
    }
}
