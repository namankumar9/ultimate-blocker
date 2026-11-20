package neth.iecal.curbox.utils

import android.content.Context
import android.os.SystemClock
import android.text.InputType
import android.view.LayoutInflater
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputLayout
import neth.iecal.curbox.R
import neth.iecal.curbox.databinding.DialogAddKeywordBinding

/**
 * Asks for an App Pause group's edit password before a weaker change. The typed password is
 * checked here for quick feedback and again where the change is saved, against the live group.
 */
object GroupPasswordPrompt {
    private const val MAX_TRIES = 5
    private const val LOCKOUT_MS = 30_000L

    // Shared by every prompt in this process, so closing the dialog does not reset the count.
    private var wrongTries = 0
    private var lockedUntil = 0L

    /**
     * @param onPassword gets the correct password.
     * @param onWait when not null, offers to save the change to wait out the delay instead.
     */
    fun show(
        context: Context,
        inflater: LayoutInflater,
        passwordHash: String,
        message: String,
        onPassword: (String) -> Unit,
        onWait: (() -> Unit)? = null,
        onCancel: (() -> Unit)? = null,
    ) {
        val binding = DialogAddKeywordBinding.inflate(inflater)
        binding.wHint.hint = context.getString(R.string.group_password_hint)
        binding.wHint.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        binding.keywordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        val builder = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.group_password_prompt_title)
            .setMessage(message)
            .setView(binding.root)
            .setPositiveButton(R.string.group_password_confirm, null)
            .setNegativeButton(R.string.cancel) { _, _ -> onCancel?.invoke() }
            .setOnCancelListener { onCancel?.invoke() }
        if (onWait != null) builder.setNeutralButton(R.string.group_password_wait_instead) { _, _ -> onWait() }
        val dialog = builder.show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val now = SystemClock.elapsedRealtime()
            if (now < lockedUntil) {
                binding.wHint.error = context.getString(
                    R.string.group_password_locked, ((lockedUntil - now) / 1000 + 1).toInt()
                )
                return@setOnClickListener
            }
            val password = binding.keywordInput.text?.toString().orEmpty()
            if (WarningPassword.verify(password, passwordHash)) {
                wrongTries = 0
                dialog.dismiss()
                onPassword(password)
            } else {
                wrongTries++
                if (wrongTries >= MAX_TRIES) {
                    wrongTries = 0
                    lockedUntil = now + LOCKOUT_MS
                }
                binding.wHint.error = context.getString(R.string.anti_uninstall_wrong_password)
            }
        }
    }

    /** Asks for a new password twice, then gives its hash. */
    fun pickNew(context: Context, inflater: LayoutInflater, onHash: (String) -> Unit) {
        val first = DialogAddKeywordBinding.inflate(inflater)
        val second = DialogAddKeywordBinding.inflate(inflater)
        listOf(first to R.string.group_password_new_hint, second to R.string.group_password_repeat_hint).forEach { (b, hint) ->
            b.wHint.hint = context.getString(hint)
            b.wHint.endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
            b.keywordInput.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        val content = android.widget.LinearLayout(context).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            addView(first.root)
            addView(second.root)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.group_password_set_title)
            .setMessage(R.string.group_password_set_message)
            .setView(content)
            .setPositiveButton(R.string.save, null)
            .setNegativeButton(R.string.cancel, null)
            .show()
        dialog.getButton(android.content.DialogInterface.BUTTON_POSITIVE).setOnClickListener {
            val password = first.keywordInput.text?.toString().orEmpty()
            when {
                password.length < WarningPassword.MIN_LENGTH ->
                    first.wHint.error = context.getString(R.string.group_password_too_short, WarningPassword.MIN_LENGTH)
                password != second.keywordInput.text?.toString() ->
                    second.wHint.error = context.getString(R.string.group_password_mismatch)
                else -> {
                    dialog.dismiss()
                    onHash(WarningPassword.hash(password))
                }
            }
        }
    }
}
