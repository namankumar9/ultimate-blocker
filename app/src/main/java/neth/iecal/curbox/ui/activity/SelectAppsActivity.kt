package neth.iecal.curbox.ui.activity

import neth.iecal.curbox.data.models.resolvedAppGroups
import android.annotation.SuppressLint
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherApps
import android.os.Bundle
import android.os.Process
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.ImageView
import android.widget.SearchView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.PopupMenu
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppList
import neth.iecal.curbox.databinding.ActivitySelectAppsBinding
import neth.iecal.curbox.databinding.DialogAddKeywordBinding
import neth.iecal.curbox.utils.DataStoreManager

class SelectAppsActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_ALLOW_CURBOX = "ALLOW_CURBOX"
        /** Boolean. When present, shows the "add newly installed apps" checkbox with this state and returns it. */
        const val EXTRA_AUTO_ADD_NEW_APPS = "AUTO_ADD_NEW_APPS"
        /**
         * String list of linked app list ids. When present, saved app lists are offered as
         * rows above the apps, and the checked ids come back under the same key.
         */
        const val EXTRA_APP_LIST_IDS = "APP_LIST_IDS"
        private const val VIEW_APP = 0
        private const val VIEW_NEW_INSTALLS = 1
        private const val VIEW_APP_LIST = 2
    }

    private lateinit var binding: ActivitySelectAppsBinding
    private lateinit var selectedAppList: HashSet<String>

    private var appItemList: MutableList<AppItem> = mutableListOf()
    private var appsAdapter: ApplicationAdapter? = null
    /** Shown only when picking apps for an app-blocker group; null means no "New installs" row. */
    private var newInstallsChecked: Boolean? = null
    /** Null when this picker does not offer app lists. */
    private var checkedListIds: LinkedHashSet<String>? = null
    private var appLists: List<AppList> = emptyList()
    /** Apps that a checked list already covers. They show as checked and cannot be unticked. */
    private var coveredByLists: Set<String> = emptySet()

    private fun refreshCoveredApps() {
        val ids = checkedListIds ?: return
        coveredByLists = appLists.filter { it.id in ids }.flatMap { it.packages }.toHashSet()
    }

    private fun isChecked(packageName: String) =
        packageName in selectedAppList || packageName in coveredByLists

    // Unified list of (groupName -> packages) from all blocker types
    private var allGroups: List<Pair<String, Set<String>>> = emptyList()
    private val dataStoreManager by lazy { DataStoreManager(this) }
    @SuppressLint("NotifyDataSetChanged")

    /**
     * list of packages that wont be showed in the selection screen
     */
    private var ignoredApps = hashSetOf<String>()
    //change selectAll's text from "Select all" to "Clear all" and vice-versa
    private var allAppsSelected = false
    private fun updateSelectAllButton() {

        val adapter = appsAdapter

        // Check if every app currently in the adapter's list is present in selectedAppList
        allAppsSelected = adapter?.apps?.all { appItem ->
            isChecked(appItem.packageName)
        } == true

        if (allAppsSelected) {
            binding.selectAll.text = getString(R.string.clear_all)
            allAppsSelected = true
        } else {
            binding.selectAll.text = getString(R.string.select_all)
            allAppsSelected = false
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelectAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, windowInsets ->
            val systemBars = windowInsets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = windowInsets.getInsets(WindowInsetsCompat.Type.ime())
            val safeInsets = Insets.max(systemBars, ime)

            view.setPadding(
                safeInsets.left,
                safeInsets.top,
                safeInsets.right,
                safeInsets.bottom
            )
            windowInsets
        }
        selectedAppList =
            intent.getStringArrayListExtra("PRE_SELECTED_APPS")?.toHashSet() ?: HashSet()

        ignoredApps = intent.getStringArrayListExtra("IGNORED_APPS")?.toHashSet() ?: HashSet()
        if (!intent.getBooleanExtra(EXTRA_ALLOW_CURBOX, false)) {
            ignoredApps.add(packageName)
        }

        Log.d("pre-selected-apps", selectedAppList.toString())

        binding.selectAppsMagic.visibility = if (
            intent.getBooleanExtra("ALLOW_CUSTOM_APPS", true)
        ) View.VISIBLE else View.GONE

        binding.appList.layoutManager = LinearLayoutManager(this)

        binding.selectAppsMagic.setOnClickListener {
            lifecycleScope.launch {
                val settings = dataStoreManager.settings.first()
                allGroups = buildList {
                    settings.appLists.forEach { add(it.name to it.packages.toSet()) }
                    settings.resolvedAppGroups().forEach { add(it.name to it.selectedPackages.toSet()) }
                    settings.manualFocusGroups.forEach { add(it.groupName to it.packages) }
                    settings.grayscaleGroups.forEach { add(it.groupName to it.packages) }
                }

                val popupMenu = PopupMenu(this@SelectAppsActivity, binding.selectAppsMagic)

                val categoriesMap = mapOf(
                    ApplicationInfo.CATEGORY_AUDIO to getString(R.string.app_category_audio),
                    ApplicationInfo.CATEGORY_GAME to getString(R.string.app_category_games),
                    ApplicationInfo.CATEGORY_IMAGE to getString(R.string.app_category_images),
                    ApplicationInfo.CATEGORY_MAPS to getString(R.string.app_category_maps),
                    ApplicationInfo.CATEGORY_NEWS to getString(R.string.app_category_news),
                    ApplicationInfo.CATEGORY_PRODUCTIVITY to getString(R.string.app_category_productivity),
                    ApplicationInfo.CATEGORY_SOCIAL to getString(R.string.app_category_social),
                    ApplicationInfo.CATEGORY_VIDEO to getString(R.string.app_category_video),
                    ApplicationInfo.CATEGORY_UNDEFINED to getString(R.string.app_category_undefined)
                )

                val availableCategories = appItemList.mapNotNull { it.appInfo?.category }.toSet().sorted()

                availableCategories.forEach { category ->
                    val categoryName = categoriesMap[category] ?: getString(R.string.select_apps_category_fallback, category)
                    val title = getString(R.string.select_apps_auto_select, categoryName)
                    popupMenu.menu.add(0, category, 0, title)
                }

                popupMenu.menu.add(0, 1000, 0, getString(R.string.select_apps_add_custom_menu))

                if (allGroups.isNotEmpty()) {
                    popupMenu.menu.add(0, 1999, 0, getString(R.string.select_apps_import_from_group)).isEnabled = false
                    allGroups.forEachIndexed { index, (name, _) ->
                        popupMenu.menu.add(0, 2000 + index, 0, name)
                    }
                }

                popupMenu.setOnMenuItemClickListener { menuItem ->
                    when {
                        menuItem.itemId == 1000 -> {
                            makeAddCustomPackageDialog()
                            true
                        }
                        menuItem.itemId >= 2000 -> {
                            val packages = allGroups.getOrNull(menuItem.itemId - 2000)?.second
                            if (packages != null) {
                                selectedAppList.addAll(packages)
                                val slist = sortSelectedItemsToTop(appItemList)
                                appsAdapter?.updateData(slist)
                                updateSelectAllButton()
                            }
                            true
                        }
                        else -> {
                            val categoryToSelect = menuItem.itemId
                            appItemList.forEach { item ->
                                if (item.appInfo?.category == categoryToSelect) {
                                    selectedAppList.add(item.packageName)
                                }
                            }
                            val slist = sortSelectedItemsToTop(appItemList)
                            appsAdapter?.updateData(slist)
                            updateSelectAllButton()
                            true
                        }
                    }
                }
                popupMenu.show()
            }
        }

        //manages the behaviour of the select all button
        binding.selectAll.setOnClickListener {
            val currentListAdapter = appsAdapter
            currentListAdapter?.let { adapter ->
                adapter.apps.forEach { appItem ->
                    // Apps a checked list covers are not picked one by one.
                    if (appItem.packageName in coveredByLists) return@forEach
                    if(allAppsSelected) {
                        selectedAppList.remove(appItem.packageName)
                    }
                    else {
                        selectedAppList.add(appItem.packageName)
                    }
                }
                adapter.notifyDataSetChanged() // To update checkboxes
            }
            updateSelectAllButton()
        }
        if (intent.hasExtra("APP_LIST")) {
            val appList = intent.getStringArrayListExtra("APP_LIST")
            appList?.forEach { packageName ->
                try {
                    val appInfo = packageManager.getApplicationInfo(packageName, 0)
                    appItemList.add(
                        AppItem(
                            packageName,
                            appInfo,
                            appInfo.loadLabel(packageManager).toString()
                        )
                    )
                } catch (e: Exception) {
                    if (selectedAppList.contains(packageName)) {
                        appItemList.add(AppItem(packageName))
                    }
                }
            }
        } else {
            val launcherApps = getSystemService(LAUNCHER_APPS_SERVICE) as LauncherApps
            val profiles = launcherApps.profiles
            val installedPackages = mutableSetOf<String>()

            for (profile in profiles) {
                val apps = launcherApps.getActivityList(null, profile)
                    .map { it.applicationInfo }
                    .filter { !ignoredApps.contains(it.packageName) }
                apps.forEach { appInfo ->
                    installedPackages.add(appInfo.packageName)
                    val profileType = if (profile == Process.myUserHandle()) "" else "(Work)"
                    val appLabel = appInfo.loadLabel(packageManager).toString()
                    val displayName = "$appLabel $profileType"
                    appItemList.add(AppItem(appInfo.packageName, appInfo, displayName))
                }
            }

            // Add uninstalled apps from selectedAppList that aren't already included
            selectedAppList.forEach { packageName ->
                if (!installedPackages.contains(packageName)) {
                    try {
                        val appInfo = packageManager.getApplicationInfo(packageName, 0)
                        appItemList.add(
                            AppItem(
                                packageName,
                                appInfo,
                                appInfo.loadLabel(packageManager).toString()
                            )
                        )
                    } catch (e: Exception) {
                        appItemList.add(AppItem(packageName))
                    }
                }
            }

            appItemList.sortBy { it.displayName.lowercase() }
            updateSelectAllButton()
        }

        binding.confirmSelection.setOnClickListener {
            val selectedAppsArrayList = ArrayList(selectedAppList)
            val resultIntent = intent.apply {
                putStringArrayListExtra("SELECTED_APPS", selectedAppsArrayList)
                checkedListIds?.let { ids ->
                    // Drop ids of lists deleted while this screen was open.
                    val existing = appLists.map(AppList::id).toHashSet()
                    putStringArrayListExtra(EXTRA_APP_LIST_IDS, ArrayList(ids.filter { it in existing }))
                }
                newInstallsChecked?.let { putExtra(EXTRA_AUTO_ADD_NEW_APPS, it) }
            }
            setResult(RESULT_OK, resultIntent)
            finish()
        }

        val sortedAppItemList = sortSelectedItemsToTop(appItemList)
        binding.appList.layoutManager = LinearLayoutManager(this)
        val filteredList = sortedAppItemList.toMutableList()
        if (intent.hasExtra(EXTRA_AUTO_ADD_NEW_APPS)) {
            newInstallsChecked = intent.getBooleanExtra(EXTRA_AUTO_ADD_NEW_APPS, false)
        }
        val adapter = ApplicationAdapter(filteredList, selectedAppList).also { appsAdapter = it }
        binding.appList.adapter = adapter
        if (intent.hasExtra(EXTRA_APP_LIST_IDS)) {
            checkedListIds = LinkedHashSet(intent.getStringArrayListExtra(EXTRA_APP_LIST_IDS).orEmpty())
            lifecycleScope.launch {
                appLists = dataStoreManager.settingsForEditing.first().appLists
                refreshCoveredApps()
                adapter.notifyDataSetChanged()
                updateSelectAllButton()
            }
        }

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?): Boolean {
                return false
            }

            @SuppressLint("NotifyDataSetChanged")
            override fun onQueryTextChange(newText: String?): Boolean {
                val query = newText?.trim() ?: ""
                filteredList.clear()
                filteredList.addAll(
                    appItemList.filter {
                        it.displayName.contains(query, ignoreCase = true)
                    }
                )
                // The "New installs" row is not an app, so keep it out of search results.
                appsAdapter?.showNewInstallsRow = query.isEmpty()
                appsAdapter?.notifyDataSetChanged()
                return true
            }
        })
        updateSelectAllButton()
    }

    fun sortSelectedItemsToTop(appItemList: List<AppItem>): List<AppItem> {
        return appItemList.sortedWith(compareBy<AppItem> {
            !selectedAppList.contains(it.packageName)
        }.thenBy {
            it.displayName.lowercase()
        })
    }

    inner class ApplicationViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val appIcon: ImageView = itemView.findViewById(R.id.app_icon)
        val appName: TextView = itemView.findViewById(R.id.app_name)
        val checkbox: CheckBox = itemView.findViewById(R.id.checkbox)
    }

    inner class ApplicationAdapter(
        var apps: List<AppItem>,

        private val selectedAppList: HashSet<String>
    ) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

        /** False while searching. The row itself only exists when [newInstallsChecked] is set. */
        var showNewInstallsRow = true
        private val hasNewInstallsRow get() = showNewInstallsRow && newInstallsChecked != null
        // List rows hide with the "New installs" row while searching.
        private val listRowCount get() =
            if (showNewInstallsRow && checkedListIds != null) appLists.size else 0
        private val headerCount get() = (if (hasNewInstallsRow) 1 else 0)
        private val offset get() = headerCount + listRowCount

        override fun getItemViewType(position: Int): Int = when {
            hasNewInstallsRow && position == 0 -> VIEW_NEW_INSTALLS
            position < offset -> VIEW_APP_LIST
            else -> VIEW_APP
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            if (viewType == VIEW_NEW_INSTALLS) {
                val view = inflater.inflate(R.layout.select_apps_new_installs_item, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }
            if (viewType == VIEW_APP_LIST) {
                val view = inflater.inflate(R.layout.select_apps_list_item, parent, false)
                return object : RecyclerView.ViewHolder(view) {}
            }
            return ApplicationViewHolder(inflater.inflate(R.layout.select_apps_item, parent, false))
        }

        override fun onBindViewHolder(viewHolder: RecyclerView.ViewHolder, position: Int) {
            if (getItemViewType(position) == VIEW_APP_LIST) {
                bindListRow(viewHolder.itemView, appLists[position - headerCount])
                return
            }
            if (viewHolder !is ApplicationViewHolder) {
                val checkbox = viewHolder.itemView.findViewById<CheckBox>(R.id.checkbox)
                checkbox.setOnCheckedChangeListener(null)
                checkbox.isChecked = newInstallsChecked == true
                checkbox.setOnCheckedChangeListener { _, checked -> newInstallsChecked = checked }
                viewHolder.itemView.setOnClickListener { checkbox.isChecked = !checkbox.isChecked }
                return
            }
            val holder = viewHolder
            val appItem = apps[position - offset]

            holder.appIcon.setImageDrawable(null)
            holder.appName.text = appItem.displayName

            if (appItem.appInfo != null) {
                lifecycleScope.launch(Dispatchers.IO) {
                    val packageManager = holder.itemView.context.packageManager
                    val icon = appItem.appInfo.loadIcon(packageManager)
                    withContext(Dispatchers.Main) {
                        holder.appIcon.setImageDrawable(icon)
                    }
                }
            } else {
                holder.appIcon.setImageResource(android.R.drawable.sym_def_app_icon)
            }

            holder.checkbox.setOnCheckedChangeListener(null)
            val covered = appItem.packageName in coveredByLists
            holder.checkbox.isChecked = covered || selectedAppList.contains(appItem.packageName)
            holder.checkbox.isEnabled = !covered
            holder.itemView.isEnabled = !covered
            holder.itemView.alpha = if (covered) 0.6f else 1f

            holder.checkbox.setOnCheckedChangeListener { _, isChecked ->
                if (isChecked) {
                    selectedAppList.add(appItem.packageName)
                } else {
                    selectedAppList.remove(appItem.packageName)
                }
            }

            holder.itemView.setOnClickListener {
                holder.checkbox.isChecked = !holder.checkbox.isChecked
                updateSelectAllButton()
            }
        }

        override fun getItemCount(): Int = apps.size + offset

        @SuppressLint("NotifyDataSetChanged")
        private fun bindListRow(row: View, list: AppList) {
            val ids = checkedListIds ?: return
            row.findViewById<TextView>(R.id.list_name).text = list.name
            row.findViewById<TextView>(R.id.list_details).text = resources.getQuantityString(
                R.plurals.app_list_app_count, list.packages.size, list.packages.size
            )
            val checkbox = row.findViewById<CheckBox>(R.id.checkbox)
            checkbox.setOnCheckedChangeListener(null)
            checkbox.isChecked = list.id in ids
            checkbox.setOnCheckedChangeListener { _, checked ->
                if (checked) ids.add(list.id) else ids.remove(list.id)
                refreshCoveredApps()
                // Rebind after this click finishes, so the covered apps update.
                row.post {
                    notifyDataSetChanged()
                    updateSelectAllButton()
                }
            }
            row.setOnClickListener { checkbox.isChecked = !checkbox.isChecked }
        }

        @SuppressLint("NotifyDataSetChanged")
        fun updateData(newList: List<AppItem>) {
            apps = newList
            notifyDataSetChanged()
        }
    }


    data class AppItem(
        val packageName: String,
        val appInfo: ApplicationInfo? = null,
        val displayName: String = packageName
    )

    private fun makeAddCustomPackageDialog() {
        val dialogBinding = DialogAddKeywordBinding.inflate(layoutInflater)
        dialogBinding.wHint.hint = getString(R.string.select_apps_custom_hint)

        MaterialAlertDialogBuilder(this)
            .setTitle(R.string.select_apps_add_custom_title)
            .setView(dialogBinding.root)
            .setPositiveButton(getString(R.string.add)) { dialog, _ ->
                val packageName = dialogBinding.keywordInput.text.toString().trim()
                if (packageName.isNotEmpty()) {
                    // Check if package already exists in the list
                    if (appItemList.none { it.packageName == packageName }) {
                        try {
                            // Try to get app info if it's installed
                            val appInfo = packageManager.getApplicationInfo(packageName, 0)
                            val appItem = AppItem(
                                packageName,
                                appInfo,
                                appInfo.loadLabel(packageManager).toString()
                            )
                            appItemList.add(appItem)
                        } catch (e: Exception) {
                            // Add as uninstalled package if not found
                            appItemList.add(AppItem(packageName))
                        }

                        // Update the adapter with the new sorted list
                        val sortedList = sortSelectedItemsToTop(appItemList)
                        appsAdapter?.updateData(sortedList)

                        // Optionally add to selected list
                        selectedAppList.add(packageName)

                        Toast.makeText(this, getString(R.string.added_successfully), Toast.LENGTH_SHORT).show()
                    } else {
                        // Show a message if package already exists
                        Toast.makeText(this, getString(R.string.package_already_exists), Toast.LENGTH_SHORT).show()
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel)) { dialog, _ ->
                dialog.dismiss()
            }
            .show()
    }
}
