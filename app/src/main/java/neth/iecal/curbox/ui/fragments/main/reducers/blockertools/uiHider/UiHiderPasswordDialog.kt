package neth.iecal.curbox.ui.fragments.main.reducers.blockertools.uiHider

import android.content.Context
import android.text.InputType
import android.view.inputmethod.InputMethodManager
import android.widget.FrameLayout
import androidx.core.view.setPadding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import neth.iecal.curbox.R

/**
 * Password prompt shown before a password-protected UIHider script can be disabled (or deleted).
 * [onResult] receives the entered password, or null when the user cancels.
 */
object UiHiderPasswordDialog {

    fun show(
        context: Context,
        title: String,
        message: String? = null,
        confirmText: String = context.getString(R.string.ui_hider_unlock),
        onResult: (String?) -> Unit
    ) {
        val inputLayout = TextInputLayout(
            context, null, com.google.android.material.R.attr.textInputOutlinedStyle
        ).apply {
            hint = context.getString(R.string.ui_hider_password_hint)
            endIconMode = TextInputLayout.END_ICON_PASSWORD_TOGGLE
        }
        val input = TextInputEditText(inputLayout.context).apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
        }
        inputLayout.addView(input)

        val container = FrameLayout(context).apply {
            val horizontal = (20 * context.resources.displayMetrics.density).toInt()
            val vertical = (8 * context.resources.displayMetrics.density).toInt()
            setPadding(horizontal, vertical, horizontal, 0)
            addView(inputLayout)
        }

        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(title)
            .apply { if (message != null) setMessage(message) }
            .setView(container)
            .setPositiveButton(confirmText) { _, _ ->
                onResult(input.text?.toString().orEmpty())
            }
            .setNegativeButton(R.string.cancel) { _, _ -> onResult(null) }
            .setOnCancelListener { onResult(null) }
            .create()

        dialog.setOnShowListener {
            input.requestFocus()
            input.post {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.showSoftInput(input, InputMethodManager.SHOW_IMPLICIT)
            }
        }
        dialog.show()
    }
}
