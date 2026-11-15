package neth.iecal.curbox.ui.activity

import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.CheckBox
import android.widget.SearchView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.graphics.Insets
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.BlockedActivity
import neth.iecal.curbox.databinding.ActivitySelectAppsBinding

/**
 * Lists every activity (screen) one app declares and lets the user pick which to block.
 * Takes [EXTRA_PACKAGE] and the already chosen [EXTRA_ACTIVITIES] for that app; returns the
 * chosen ones under [EXTRA_ACTIVITIES], each as "package/ActivityClass".
 */
class SelectActivitiesActivity : AppCompatActivity() {

    companion object {
        const val EXTRA_PACKAGE = "package"
        const val EXTRA_ACTIVITIES = "activities"
    }

    private data class Row(val className: String, val label: String)

    private lateinit var binding: ActivitySelectAppsBinding
    private lateinit var packageNameArg: String
    private val chosen = LinkedHashSet<String>()
    private var all: List<Row> = emptyList()
    private val shown = mutableListOf<Row>()
    private val adapter = RowAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivitySelectAppsBinding.inflate(layoutInflater)
        setContentView(binding.root)
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { view, insets ->
            val safe = Insets.max(
                insets.getInsets(WindowInsetsCompat.Type.systemBars()),
                insets.getInsets(WindowInsetsCompat.Type.ime())
            )
            view.setPadding(safe.left, safe.top, safe.right, safe.bottom)
            insets
        }

        packageNameArg = intent.getStringExtra(EXTRA_PACKAGE) ?: run { finish(); return }
        chosen += intent.getStringArrayListExtra(EXTRA_ACTIVITIES).orEmpty()
            .map(BlockedActivity::parse)
            .filter { it?.packageName == packageNameArg }
            .mapNotNull { it?.className }
        // Restore ticks after rotation.
        savedInstanceState?.getStringArrayList(EXTRA_ACTIVITIES)?.let { chosen.clear(); chosen += it }

        val appLabel = runCatching {
            packageManager.getApplicationInfo(packageNameArg, 0).loadLabel(packageManager).toString()
        }.getOrDefault(packageNameArg)
        binding.title.text = appLabel
        binding.selectAll.visibility = View.GONE
        binding.selectAppsMagic.visibility = View.GONE
        binding.searchView.queryHint = getString(R.string.activities_search_hint)
        binding.hint.visibility = View.VISIBLE
        binding.hint.setText(R.string.activities_picker_hint)

        binding.appList.layoutManager = LinearLayoutManager(this)
        binding.appList.adapter = adapter

        binding.searchView.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = false
            override fun onQueryTextChange(newText: String?): Boolean {
                filter(newText.orEmpty())
                return true
            }
        })

        binding.confirmSelection.setOnClickListener {
            val result = ArrayList(chosen.map { BlockedActivity(packageNameArg, it).key })
            setResult(RESULT_OK, Intent().putExtra(EXTRA_PACKAGE, packageNameArg)
                .putStringArrayListExtra(EXTRA_ACTIVITIES, result))
            finish()
        }

        lifecycleScope.launch {
            all = withContext(Dispatchers.IO) { loadActivities(appLabel) }
            // Chosen ones first, so what is blocked is visible without scrolling.
            all = all.sortedWith(compareBy<Row> { it.className !in chosen }.thenBy { it.label.lowercase() })
            if (all.isEmpty()) {
                binding.hint.setText(R.string.activities_none_found)
            }
            filter(binding.searchView.query?.toString().orEmpty())
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putStringArrayList(EXTRA_ACTIVITIES, ArrayList(chosen))
    }

    private fun loadActivities(appLabel: String): List<Row> {
        val info = runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.getPackageInfo(
                    packageNameArg,
                    PackageManager.PackageInfoFlags.of(
                        (PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS).toLong()
                    )
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.getPackageInfo(
                    packageNameArg,
                    PackageManager.GET_ACTIVITIES or PackageManager.MATCH_DISABLED_COMPONENTS
                )
            }
        }.getOrNull()
        val declared = info?.activities.orEmpty().map { activity ->
            val label = runCatching { activity.loadLabel(packageManager).toString() }.getOrDefault("")
            // Most screens have no label of their own and fall back to the app's name;
            // the class name tells them apart better.
            val shortName = activity.name.substringAfterLast('.')
            Row(activity.name, if (label.isBlank() || label == appLabel) shortName else label)
        }
        // Keep chosen activities the app no longer declares, so they can still be unticked.
        val missing = chosen.filter { name -> declared.none { it.className == name } }
            .map { Row(it, it.substringAfterLast('.')) }
        return (declared + missing).distinctBy { it.className }
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun filter(query: String) {
        val q = query.trim()
        shown.clear()
        shown += if (q.isEmpty()) all else all.filter {
            it.label.contains(q, ignoreCase = true) || it.className.contains(q, ignoreCase = true)
        }
        adapter.notifyDataSetChanged()
    }

    private inner class RowAdapter : RecyclerView.Adapter<RowAdapter.Holder>() {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val label: TextView = view.findViewById(R.id.activity_label)
            val className: TextView = view.findViewById(R.id.activity_class)
            val checkbox: CheckBox = view.findViewById(R.id.checkbox)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int) = Holder(
            LayoutInflater.from(parent.context).inflate(R.layout.item_activity_row, parent, false)
        )

        override fun getItemCount() = shown.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = shown[position]
            holder.label.text = row.label
            holder.className.text = row.className
            holder.checkbox.setOnCheckedChangeListener(null)
            holder.checkbox.isChecked = row.className in chosen
            holder.checkbox.setOnCheckedChangeListener { _, checked ->
                if (checked) chosen += row.className else chosen -= row.className
            }
            holder.itemView.setOnClickListener { holder.checkbox.isChecked = !holder.checkbox.isChecked }
        }
    }
}
