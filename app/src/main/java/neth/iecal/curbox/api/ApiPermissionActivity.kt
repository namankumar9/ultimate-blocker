package neth.iecal.curbox.api

import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.view.MotionEvent
import androidx.appcompat.app.AlertDialog
import neth.iecal.curbox.CrashLogger
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import neth.iecal.curbox.R

/**
 * The Allow or Deny screen a client opens to ask the user for access, like the Shizuku permission
 * prompt. A binder cannot draw UI, so clients launch this with startActivityForResult and the
 * matching action. On allow we remember the requesting package in [ApiAuthStore].
 */
class ApiPermissionActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val requester = callingPackage
        if (requester == null) {
            setResult(Activity.RESULT_CANCELED)
            finish()
            return
        }

        if (ApiAuthStore.isApiEnabled(this) && requester in ApiAuthStore.grantedPackages(this)) {
            setResult(Activity.RESULT_OK)
            finish()
            return
        }

        val appLabel = labelFor(requester)
        // Do not approve a replacement installed while the permission dialog is open.
        val expectedCertificates = runCatching {
            ApiPackageIdentity.signingCertificates(this, requester)
        }.getOrNull()
        if (expectedCertificates.isNullOrEmpty()) {
            finish()
            return
        }

        val dialog = MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.api_permission_title))
            .setMessage(getString(R.string.api_permission_message, appLabel, requester))
            .setCancelable(false)
            .setPositiveButton(R.string.api_permission_allow) { _, _ ->
                try {
                    require(expectedCertificates == ApiPackageIdentity.signingCertificates(this, requester))
                    ApiAuthStore.grant(this, requester, expectedCertificates)
                    ApiAuthStore.setApiEnabled(this, true)
                    setResult(Activity.RESULT_OK)
                } catch (e: Exception) {
                    CrashLogger(applicationContext).logNonFatalError(e)
                    setResult(Activity.RESULT_CANCELED)
                }
                finish()
            }
            .setNegativeButton(R.string.api_permission_deny) { _, _ ->
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
            .setOnCancelListener {
                setResult(Activity.RESULT_CANCELED)
                finish()
            }
            .create()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            dialog.window?.setHideOverlayWindows(true)
        }
        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).apply {
                filterTouchesWhenObscured = true
                setOnTouchListener { _, event ->
                    event.flags and (MotionEvent.FLAG_WINDOW_IS_OBSCURED or
                        MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED) != 0
                }
            }
        }
        dialog.show()
    }

    private fun labelFor(packageName: String): String {
        return try {
            val info = packageManager.getApplicationInfo(packageName, 0)
            packageManager.getApplicationLabel(info).toString()
                .filterNot { it.isISOControl() || Character.getType(it) == Character.FORMAT.toInt() }
                .take(100)
        } catch (e: Exception) {
            packageName
        }
    }
}
