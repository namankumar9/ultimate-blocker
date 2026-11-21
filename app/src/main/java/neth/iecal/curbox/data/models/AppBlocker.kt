package neth.iecal.curbox.data.models

data class AppGroup(
    val id: String = "",
    val name: String = "name",
    val selectedPackages: List<String> = listOf(),
    /** Ids of [AppList]s whose apps this group also blocks. See [allPackages]. */
    val appListIds: List<String> = listOf(),
    /**
     * Single activities (screens) blocked without blocking their whole app, each as
     * "package/ActivityClass". They follow the group's schedule and warning screen only;
     * the usage limit does not apply to them. See [BlockedActivity].
     */
    val selectedActivities: List<String> = listOf(),
    val config: AppGroupConfig? = null,
    val isActive: Boolean = false,
    /** Non-zero only while the group is using the opt-in temporary-disable escape hatch. */
    val temporarilyDisabledUntilMs: Long = 0L,
    val warningScreenConfig : AppBlockerWarningScreenConfig = AppBlockerWarningScreenConfig(),
    /** Apps installed after this group opted in are added to [selectedPackages] automatically. */
    val autoAddNewApps: Boolean = false,
    /**
     * Apps first installed after this moment are still to be added. Advanced after every check,
     * so an auto-added app the user later removes from the group stays removed.
     */
    val autoAddCheckedUntilMs: Long = 0L,
    /**
     * This group's own settings change delay in minutes. Null uses the main delay. Unlike the
     * main delay, it applies even while the main delay is switched off.
     */
    val changeDelayMinutes: Int? = null,
    /**
     * "salt:hash" of this group's edit password, empty when it has none. Entering it lets a
     * weaker change apply at once instead of waiting out the delay. See
     * [neth.iecal.curbox.utils.WarningPassword] for the format.
     */
    val editPasswordHash: String = "",
    /**
     * When this group turns itself back on automatically. See [AppGroupAutostartConfig].
     * Autostart only ever switches the group on, so gaining or widening it counts as a
     * stricter change and removing it waits out the group's delay like any other loosening.
     */
    val autostartConfig: AppGroupAutostartConfig = AppGroupAutostartConfig(),
    // Kept only so settings written by older versions can be migrated.
    @Deprecated("Use config")
    val blockingType: AppBlockingType = AppBlockingType.Usage, // "USAGE" or "TIME"
    @Deprecated("Use config")
    val setting:String = "",
    @Deprecated("Use config")
    val linkedTimeGroupId: String? = null,
)

enum class AppBlockingType{
    Usage, Timed, OnOpen
}

/** How a group switches itself back on. See [AppGroupAutostartConfig]. */
enum class AutostartMode {
    /** No autostart. */
    None,
    /** Switch on once at [AppGroupAutostartConfig.oneTimeAtMs], then clear itself. */
    OneTime,
    /** Switch on every chosen day at [AppGroupAutostartConfig.timeOfDayMinutes]. */
    TimeOfDay,
}

/**
 * Turns a group on automatically. It never turns a group off, so it only makes the block
 * stronger. The user sets [mode] and its times; [nextFireAtMs] is bookkeeping the app fills
 * in, the wall clock moment of the next switch on, and must not be edited by hand.
 */
data class AppGroupAutostartConfig(
    val mode: AutostartMode = AutostartMode.None,
    /** OneTime: wall clock millis to switch on. */
    val oneTimeAtMs: Long = 0L,
    /** TimeOfDay: minutes past midnight to switch on, 0..1439. */
    val timeOfDayMinutes: Int = 0,
    /** TimeOfDay: days it fires on, 0 = Sunday through 6 = Saturday. */
    val days: List<Int> = emptyList(),
    /** The next switch on, in wall clock millis. 0 when nothing is scheduled. */
    val nextFireAtMs: Long = 0L,
)

data class ScheduledUsageConfig(
    val schedule: AppTimeConfig = AppTimeConfig.allDay(),
    val usage: AppUsageConfig = AppUsageConfig()
)

typealias AppGroupConfig = ScheduledUsageConfig

data class AppTimeConfig(
    var isEveryday: Boolean = true,
    var everydayIntervals: MutableList<TimeInterval> = mutableListOf(TimeInterval()),
    var dailyIntervals: MutableMap<Int, MutableList<TimeInterval>> = mutableMapOf()
) {
    companion object {
        fun allDay() = AppTimeConfig(
            isEveryday = true,
            everydayIntervals = mutableListOf(TimeInterval(0, 0, 24, 0))
        )
    }
}


data class AppUsageConfig(
    var isDailyUniform: Boolean = true,
    var uniformLimit: Long = 60,
    val dailyLimits: LongArray = LongArray(7) { 0 } // 0=Sunday
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as AppUsageConfig

        if (isDailyUniform != other.isDailyUniform) return false
        if (uniformLimit != other.uniformLimit) return false
        if (!dailyLimits.contentEquals(other.dailyLimits)) return false

        return true
    }

    override fun hashCode(): Int {
        var result = isDailyUniform.hashCode()
        result = 31 * result + uniformLimit.hashCode()
        result = 31 * result + dailyLimits.contentHashCode()
        return result
    }
}
