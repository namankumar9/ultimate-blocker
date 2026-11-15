package neth.iecal.curbox.data.models

/**
 * One activity (screen) of an app, stored in [AppGroup.selectedActivities] as
 * "package/fully.qualified.ActivityClass", the same form Android uses for components.
 */
data class BlockedActivity(val packageName: String, val className: String) {
    val key: String get() = "$packageName/$className"

    companion object {
        fun parse(key: String): BlockedActivity? {
            val pkg = key.substringBefore('/', "")
            val cls = key.substringAfter('/', "")
            if (pkg.isBlank() || cls.isBlank()) return null
            // "pkg/.Main" is Android's short form for "pkg/pkg.Main".
            return BlockedActivity(pkg, if (cls.startsWith('.')) pkg + cls else cls)
        }
    }
}

/**
 * Activities this group blocks one by one. An activity of an app the group already blocks
 * whole adds nothing, so it is left out.
 */
fun AppGroup.partialActivities(lists: List<AppList>): List<BlockedActivity> {
    val wholeApps = allPackages(lists)
    return selectedActivities.mapNotNull(BlockedActivity::parse).filter { it.packageName !in wholeApps }
}
