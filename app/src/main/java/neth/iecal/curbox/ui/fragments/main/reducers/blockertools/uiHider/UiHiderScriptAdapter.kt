package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.uiHider

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import neth.iecal.curbox.data.models.UiHiderScript
import neth.iecal.curbox.databinding.ItemUiHiderScriptBinding

class UiHiderScriptAdapter(
    private val onClick: (UiHiderScript) -> Unit,
    /**
     * Invoked when the user flips a script's switch. Returning false means the change was
     * rejected (e.g. wrong/missing password for a protected script) and the switch snaps back.
     */
    private val onToggle: (UiHiderScript, Boolean) -> Boolean
) : ListAdapter<UiHiderScript, UiHiderScriptAdapter.ViewHolder>(DiffCallback) {

    inner class ViewHolder(val binding: ItemUiHiderScriptBinding) :
        RecyclerView.ViewHolder(binding.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val binding = ItemUiHiderScriptBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val script = getItem(position)
        holder.binding.run {
            tvScriptTitle.text = script.label.ifBlank {
                script.packageName.substringAfterLast('.')
            }
            tvScriptSubtitle.text = script.packageName

            bindSwitch(this, script)

            root.setOnClickListener { onClick(script) }
        }
    }

    private fun bindSwitch(binding: ItemUiHiderScriptBinding, script: UiHiderScript) {
        binding.switchScriptEnabled.setOnCheckedChangeListener(null)
        binding.switchScriptEnabled.isChecked = script.isEnabled
        binding.switchScriptEnabled.setOnCheckedChangeListener { _, checked ->
            val accepted = onToggle(script, checked)
            if (!accepted) {
                // Rejected (e.g. protected script without a correct password): snap back.
                bindSwitch(binding, script)
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<UiHiderScript>() {
        override fun areItemsTheSame(oldItem: UiHiderScript, newItem: UiHiderScript) =
            oldItem.id == newItem.id

        override fun areContentsTheSame(oldItem: UiHiderScript, newItem: UiHiderScript) =
            oldItem == newItem
    }
}
