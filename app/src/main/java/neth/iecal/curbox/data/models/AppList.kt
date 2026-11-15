package neth.iecal.curbox.data.models

/**
 * A named, reusable set of apps. App groups link to lists by id, so editing a list changes
 * every group that uses it. Called "app list" in the UI because "app group" already names
 * the blocking rule.
 */
data class AppList(
    val id: String = "",
    val name: String = "",
    val packages: List<String> = listOf(),
)

/** Every app this group blocks: its own picks plus the apps in each linked list. */
fun AppGroup.allPackages(lists: List<AppList>): Set<String> {
    val linked = appListIds.toHashSet()
    return (selectedPackages + lists.filter { it.id in linked }.flatMap { it.packages })
        .map(String::trim)
        .filter(String::isNotEmpty)
        .toSet()
}

/**
 * The groups with [AppGroup.selectedPackages] expanded to include their linked lists.
 * Only for reading: never save these back, or the link to the lists is lost.
 */
fun Settings.resolvedAppGroups(): List<AppGroup> = blockedAppGroups.map { group ->
    if (group.appListIds.isEmpty()) group
    else group.copy(selectedPackages = group.allPackages(appLists).toList())
}
