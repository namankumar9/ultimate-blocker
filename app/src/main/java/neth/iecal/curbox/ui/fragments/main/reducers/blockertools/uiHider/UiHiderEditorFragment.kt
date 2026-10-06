package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.uiHider

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import neth.iecal.curbox.R
import neth.iecal.curbox.blockers.uihider.ScriptPassword
import neth.iecal.curbox.data.models.UiHiderScript
import neth.iecal.curbox.databinding.FragmentUiHiderEditorBinding
import neth.iecal.curbox.hardcoded.isPresetUiHiderScript

class UiHiderEditorFragment : Fragment() {

    private var _binding: FragmentUiHiderEditorBinding? = null
    private val binding get() = _binding!!

    private val viewModel: UiHiderViewModel by activityViewModels()

    private var scriptId: String? = null
    private var existingIsEnabled: Boolean = true
    private var existingPasswordHash: String? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUiHiderEditorBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        scriptId = arguments?.getString(EXTRA_SCRIPT_ID)
        val isEditing = scriptId != null
        val isPreset = scriptId?.let { isPresetUiHiderScript(it) } == true

        if (isEditing) {
            existingIsEnabled = arguments?.getBoolean(EXTRA_IS_ENABLED, true) ?: true
            existingPasswordHash = arguments?.getString(EXTRA_PASSWORD_HASH)
            binding.editPackage.setText(arguments?.getString(EXTRA_PACKAGE_NAME).orEmpty())
            binding.editLabel.setText(arguments?.getString(EXTRA_LABEL).orEmpty())
            binding.editSource.setText(arguments?.getString(EXTRA_SOURCE).orEmpty())
        }

        // A protected script requires its current password before any change (including a new
        // password or removal) can be saved.
        if (isEditing && !existingPasswordHash.isNullOrEmpty()) {
            binding.layoutCurrentPassword.visibility = View.VISIBLE
            binding.layoutPassword.hint = getString(R.string.ui_hider_password_new_hint)
        }

        if (isPreset) {
            binding.editPackage.isEnabled = false
            binding.editLabel.isEnabled = false
            binding.editSource.isEnabled = false
            binding.editPassword.isEnabled = false
            binding.editCurrentPassword.isEnabled = false
            binding.textPresetNote.visibility = View.VISIBLE
            binding.btnSave.visibility = View.GONE
            binding.btnDelete.visibility = View.GONE
            return
        }

        binding.btnDelete.visibility = if (isEditing) View.VISIBLE else View.GONE

        binding.btnSave.setOnClickListener { save() }
        binding.btnDelete.setOnClickListener { confirmDelete() }
    }

    private fun save() {
        val id = scriptId ?: viewModel.newScriptId()
        val isEditing = scriptId != null
        val packageName = binding.editPackage.text?.toString()?.trim().orEmpty()
        val source = binding.editSource.text?.toString().orEmpty()
        val label = binding.editLabel.text?.toString()?.trim().orEmpty()
        val newPassword = binding.editPassword.text?.toString().orEmpty()
        val currentPassword = binding.editCurrentPassword.text?.toString().orEmpty()

        if (packageName.isEmpty()) {
            Toast.makeText(requireContext(), R.string.uihider_enter_package, Toast.LENGTH_SHORT).show()
            return
        }

        // Editing a password-protected script requires the current password, even when the
        // password field itself is left unchanged.
        if (isEditing && !ScriptPassword.verify(id, currentPassword, existingPasswordHash)) {
            Toast.makeText(requireContext(), R.string.ui_hider_current_password_required, Toast.LENGTH_SHORT).show()
            return
        }

        val error = viewModel.validate(source)
        if (error != null) {
            binding.textOutput.text = error
            binding.textOutput.visibility = View.VISIBLE
            return
        }

        // Blank password on a new script = unprotected; blank on an edit = keep current hash.
        val passwordHash = when {
            newPassword.isNotEmpty() -> ScriptPassword.hash(id, newPassword)
            isEditing -> existingPasswordHash
            else -> null
        }

        val script = UiHiderScript(
            id = id,
            packageName = packageName,
            label = label.ifEmpty { packageName.substringAfterLast('.') },
            source = source,
            isEnabled = existingIsEnabled,
            passwordHash = passwordHash
        )
        // Finish only after the write lands; finishing earlier cancels the ViewModel scope
        // and with it the save.
        binding.btnSave.isEnabled = false
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.upsertScript(script)
            requireActivity().finish()
        }
    }

    /** Deleting a script disables its protection too, so protected scripts ask for the password first. */
    private fun confirmDelete() {
        val id = scriptId ?: return
        if (existingPasswordHash.isNullOrEmpty()) {
            deleteNow(id)
            return
        }
        UiHiderPasswordDialog.show(
            requireContext(),
            title = getString(R.string.ui_hider_password_dialog_title),
            message = getString(R.string.ui_hider_password_delete_message),
            confirmText = getString(R.string.delete)
        ) { password ->
            when {
                password == null -> Unit
                ScriptPassword.verify(id, password, existingPasswordHash) -> deleteNow(id)
                else -> Toast.makeText(
                    requireContext(), R.string.ui_hider_wrong_password, Toast.LENGTH_SHORT
                ).show()
            }
        }
    }

    private fun deleteNow(id: String) {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.deleteScript(id)
            requireActivity().finish()
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "ui_hider_editor"
        const val EXTRA_SCRIPT_ID = "scriptId"
        const val EXTRA_PACKAGE_NAME = "packageName"
        const val EXTRA_LABEL = "label"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_IS_ENABLED = "isEnabled"
        const val EXTRA_PASSWORD_HASH = "passwordHash"
    }
}
