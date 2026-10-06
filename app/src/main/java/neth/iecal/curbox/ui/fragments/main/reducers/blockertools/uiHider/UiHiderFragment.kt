package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.uiHider

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import neth.iecal.curbox.utils.ViewUtils
import neth.iecal.curbox.R
import neth.iecal.curbox.data.models.UiHiderScript
import neth.iecal.curbox.databinding.FragmentUiHiderBinding
import neth.iecal.curbox.hardcoded.allScripts
import neth.iecal.curbox.services.AppBlockerService
import neth.iecal.curbox.services.NodePickerService
import neth.iecal.curbox.ui.activity.FragmentActivity
import neth.iecal.curbox.utils.PermissionUtils

class UiHiderFragment : Fragment() {

    private var _binding: FragmentUiHiderBinding? = null
    private val binding get() = _binding!!

    private val viewModel: UiHiderViewModel by activityViewModels()
    private var isUpdatingUi = false

    private val adapter = UiHiderScriptAdapter(
        onClick = { openEditor(it) },
        onToggle = { script, checked -> onScriptToggled(script, checked) }
    )

    private val masterSwitchListener =
        android.widget.CompoundButton.OnCheckedChangeListener { _, isChecked ->
            if (isUpdatingUi) return@OnCheckedChangeListener
            onMasterSwitchToggled(isChecked)
        }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentUiHiderBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.rvScripts.layoutManager = LinearLayoutManager(requireContext())
        binding.rvScripts.adapter = adapter

        setMasterSwitchChecked(binding.switchEnableUiHider.isChecked)
        binding.btnAddScript.setOnClickListener { openEditor(null) }
        binding.btnStartNodePicker.setOnClickListener { startNodePicker() }

        binding.btnHelp.setOnClickListener {
            ViewUtils.showHelpPopup(it, "Hide specific UI elements within apps using custom scripts.", "https://curbox.app/docs/reducers/hide-ui-elements/")
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.config.collectLatest { config ->
                isUpdatingUi = true
                setMasterSwitchChecked(config.isActive)
                adapter.submitList(config.allScripts())
                isUpdatingUi = false
            }
        }
    }

    private fun setMasterSwitchChecked(checked: Boolean) {
        binding.switchEnableUiHider.setOnCheckedChangeListener(null)
        binding.switchEnableUiHider.isChecked = checked
        binding.switchEnableUiHider.setOnCheckedChangeListener(masterSwitchListener)
    }

    /**
     * Turning UIHider off disables every script at once, so when any enabled script is password
     * protected the user must enter a matching password first.
     */
    private fun onMasterSwitchToggled(isChecked: Boolean) {
        if (isChecked || viewModel.protectedEnabledScriptIds().isEmpty()) {
            viewModel.setIsActive(isChecked)
            return
        }
        setMasterSwitchChecked(true)
        UiHiderPasswordDialog.show(
            requireContext(),
            title = getString(R.string.ui_hider_password_dialog_title),
            message = getString(R.string.ui_hider_password_master_message)
        ) { password ->
            when {
                password == null -> Unit // cancelled; switch stays on
                viewModel.isAnyProtectedPasswordCorrect(password) ->
                    viewModel.setIsActive(false)
                else -> showWrongPassword()
            }
        }
    }

    /**
     * Handles a per-script toggle. Returns true when the change was applied (or needs no
     * password); false when it was rejected and the switch must snap back. Disabling a
     * password-protected script prompts for its password first.
     */
    private fun onScriptToggled(script: UiHiderScript, checked: Boolean): Boolean {
        if (checked || script.passwordHash.isNullOrEmpty()) {
            viewModel.setScriptEnabled(script.id, checked)
            return true
        }
        UiHiderPasswordDialog.show(
            requireContext(),
            title = getString(R.string.ui_hider_password_dialog_title),
            message = getString(R.string.ui_hider_password_dialog_message, script.label)
        ) { password ->
            when {
                password == null -> Unit // cancelled; switch stays on
                viewModel.isScriptPasswordCorrect(script.id, password) ->
                    viewModel.setScriptEnabled(script.id, false)
                else -> showWrongPassword()
            }
        }
        return false
    }

    private fun showWrongPassword() {
        Toast.makeText(requireContext(), R.string.ui_hider_wrong_password, Toast.LENGTH_SHORT).show()
    }

    private fun startNodePicker() {
        val context = requireContext()
        if (!PermissionUtils.isAccessibilityServiceEnabled(context, AppBlockerService::class.java)) {
            Toast.makeText(context, R.string.node_picker_need_accessibility, Toast.LENGTH_LONG).show()
            return
        }
        if (!PermissionUtils.hasOverlayPermission(context)) {
            Toast.makeText(context, R.string.node_picker_need_overlay, Toast.LENGTH_LONG).show()
            return
        }
        NodePickerService.start(context)
        Toast.makeText(context, R.string.node_picker_started_hint, Toast.LENGTH_LONG).show()
    }

    private fun openEditor(script: UiHiderScript?) {
        val intent = Intent(requireContext(), FragmentActivity::class.java).apply {
            putExtra("fragment", UiHiderEditorFragment.FRAGMENT_ID)
            if (script != null) {
                putExtra(UiHiderEditorFragment.EXTRA_SCRIPT_ID, script.id)
                putExtra(UiHiderEditorFragment.EXTRA_PACKAGE_NAME, script.packageName)
                putExtra(UiHiderEditorFragment.EXTRA_LABEL, script.label)
                putExtra(UiHiderEditorFragment.EXTRA_SOURCE, script.source)
                putExtra(UiHiderEditorFragment.EXTRA_IS_ENABLED, script.isEnabled)
                putExtra(UiHiderEditorFragment.EXTRA_PASSWORD_HASH, script.passwordHash)
            }
        }
        startActivity(intent)
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }

    companion object {
        const val FRAGMENT_ID = "ui_hider"
    }
}
