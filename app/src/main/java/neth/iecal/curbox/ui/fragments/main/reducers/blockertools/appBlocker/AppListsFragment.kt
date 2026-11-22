package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.appBlocker

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.AppList
import neth.iecal.curbox.databinding.DialogAddKeywordBinding
import neth.iecal.curbox.ui.activity.SelectAppsActivity
import java.util.UUID

/**
 * Manages app lists: named sets of apps that app groups can link to instead of picking the
 * same apps again. Removing apps from a list in use counts as loosening those groups, so
 * the settings change delay may hold it back.
 */
class AppListsFragment : Fragment() {

    companion object {
        const val FRAGMENT_ID = "app_lists"
        /** Optional list id. The screen opens that list's app picker right away. */
        const val EXTRA_EDIT_LIST_ID = "edit_list_id"
        private const val STATE_EDIT_ID = "edit_id"
        private const val STATE_EDIT_NAME = "edit_name"

        private val DIFF = object : DiffUtil.ItemCallback<Row>() {
            override fun areItemsTheSame(oldItem: Row, newItem: Row) = oldItem.list.id == newItem.list.id
            override fun areContentsTheSame(oldItem: Row, newItem: Row) = oldItem == newItem
        }
    }

    private data class Row(val list: AppList, val usedBy: Int)

    private val viewModel: AppBlockerSettingViewModel by activityViewModels()
    private val adapter = ListsAdapter()

    // The list whose apps are being picked. Kept across the picker so a new list's name survives.
    private var editId: String? = null
    private var editName: String? = null

    private val pickApps = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val id = editId
        val name = editName
        editId = null
        editName = null
        if (result.resultCode != AppCompatActivity.RESULT_OK || id == null || name == null) return@registerForActivityResult
        val apps = result.data?.getStringArrayListExtra("SELECTED_APPS") ?: return@registerForActivityResult
        if (apps.isEmpty()) {
            Toast.makeText(requireContext(), R.string.please_select_at_least_one_app, Toast.LENGTH_SHORT).show()
            return@registerForActivityResult
        }
        save(AppList(id = id, name = name, packages = apps.sorted()))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handledEditRequest = savedInstanceState != null
        editId = savedInstanceState?.getString(STATE_EDIT_ID)
        editName = savedInstanceState?.getString(STATE_EDIT_NAME)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_EDIT_ID, editId)
        outState.putString(STATE_EDIT_NAME, editName)
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        val view = inflater.inflate(R.layout.fragment_app_lists, container, false)
        val rv = view.findViewById<RecyclerView>(R.id.rv_app_lists)
        rv.layoutManager = LinearLayoutManager(requireContext())
        rv.adapter = adapter
        view.findViewById<FloatingActionButton>(R.id.fab_add_list).setOnClickListener {
            askName(R.string.app_list_add, "") { name -> pickAppsFor(UUID.randomUUID().toString(), name, emptyList()) }
        }
        return view
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        val empty = view.findViewById<TextView>(R.id.tv_empty_state)
        val rv = view.findViewById<RecyclerView>(R.id.rv_app_lists)
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.appLists.combine(viewModel.groups) { lists, groups ->
                    lists.map { list -> Row(list, groups.count { list.id in it.appListIds }) }
                }.collect { rows ->
                    openRequestedList(rows)
                    empty.visibility = if (rows.isEmpty()) View.VISIBLE else View.GONE
                    rv.visibility = if (rows.isEmpty()) View.GONE else View.VISIBLE
                    adapter.submitList(rows)
                }
            }
        }
    }

    private var handledEditRequest = false

    /** Opens the list named by [EXTRA_EDIT_LIST_ID] once, and not again after a rotation. */
    private fun openRequestedList(rows: List<Row>) {
        if (handledEditRequest) return
        val id = arguments?.getString(EXTRA_EDIT_LIST_ID) ?: return
        // The first emission can be empty while lists load, so wait until the list shows up.
        val row = rows.firstOrNull { it.list.id == id } ?: return
        handledEditRequest = true
        if (editId != null) return
        pickAppsFor(row.list.id, row.list.name, row.list.packages)
    }

    private fun pickAppsFor(id: String, name: String, current: List<String>) {
        editId = id
        editName = name
        pickApps.launch(Intent(requireContext(), SelectAppsActivity::class.java).apply {
            putStringArrayListExtra("PRE_SELECTED_APPS", ArrayList(current))
            putExtra(SelectAppsActivity.EXTRA_ALLOW_CURBOX, true)
            putExtra(SelectAppsActivity.EXTRA_OFFER_SAVE_AS_LIST, false)
        })
    }

    private fun save(list: AppList) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.saveAppList(list)
        }
    }

    private fun askName(title: Int, initial: String, onName: (String) -> Unit) {
        val dialogBinding = DialogAddKeywordBinding.inflate(layoutInflater)
        dialogBinding.wHint.hint = getString(R.string.app_list_name_hint)
        dialogBinding.keywordInput.setText(initial)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setView(dialogBinding.root)
            .setPositiveButton(R.string.app_list_next, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        // Keep the dialog open until a name is given.
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val name = dialogBinding.keywordInput.text?.toString()?.trim().orEmpty()
            if (name.isEmpty()) {
                dialogBinding.wHint.error = getString(R.string.app_list_name_required)
            } else {
                dialog.dismiss()
                onName(name)
            }
        }
    }

    private fun showOptions(row: Row) {
        val list = row.list
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(list.name)
            .setItems(arrayOf(
                getString(R.string.app_list_edit_apps),
                getString(R.string.app_list_rename),
                getString(R.string.delete),
            )) { _, which ->
                when (which) {
                    0 -> pickAppsFor(list.id, list.name, list.packages)
                    1 -> askName(R.string.app_list_rename, list.name) { name -> save(list.copy(name = name)) }
                    2 -> confirmDelete(list)
                }
            }
            .show()
    }

    private fun confirmDelete(list: AppList) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.app_list_delete_title, list.name))
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.delete) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    val usedBy = viewModel.deleteAppList(list.id)
                    if (usedBy.isNotEmpty()) {
                        MaterialAlertDialogBuilder(requireContext())
                            .setTitle(R.string.app_list_in_use_title)
                            .setMessage(getString(R.string.app_list_in_use_message, usedBy.joinToString(", ")))
                            .setPositiveButton(android.R.string.ok, null)
                            .show()
                    }
                }
            }
            .show()
    }

    private inner class ListsAdapter : ListAdapter<Row, ListsAdapter.Holder>(DIFF) {
        inner class Holder(view: View) : RecyclerView.ViewHolder(view) {
            val name: TextView = view.findViewById(R.id.tv_group_name)
            val details: TextView = view.findViewById(R.id.tv_group_details)
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context).inflate(R.layout.item_app_group, parent, false)
            view.findViewById<View>(R.id.switch_group_active).visibility = View.GONE
            return Holder(view)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val row = getItem(position)
            holder.name.text = row.list.name
            val apps = resources.getQuantityString(
                R.plurals.app_list_app_count, row.list.packages.size, row.list.packages.size
            )
            holder.details.text = if (row.usedBy == 0) apps else getString(
                R.string.app_list_details_used,
                apps,
                resources.getQuantityString(R.plurals.app_list_used_by, row.usedBy, row.usedBy)
            )
            holder.itemView.setOnClickListener { showOptions(row) }
        }
    }
}
