package neth.iecal.curbox.api

import android.content.BroadcastReceiver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.IntentFilter
import android.os.ParcelFileDescriptor
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class ApiSecurityTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    // Do not read or modify real API authorizations on the device.
    private val context = object : ContextWrapper(instrumentation.targetContext) {
        override fun getApplicationContext(): Context = this
        override fun getSharedPreferences(name: String, mode: Int) =
            super.getSharedPreferences("security_test_$name", mode)
    }
    private val preferences get() = context.getSharedPreferences("AppPreferences", Context.MODE_PRIVATE)

    @Before fun prepare() {
        preferences.edit().clear().commit()
    }

    @After fun cleanUp() {
        preferences.edit().clear().commit()
    }

    @Test fun legacyPackageOnlyGrantCannotAuthorize() {
        preferences.edit().putBoolean("apiEnabled", true)
            .putString("apiAuthorizedPackages", "{\"${context.packageName}\":123}").commit()
        assertFalse(ApiAuthStore.isAnyGranted(context, arrayOf(context.packageName)))
    }

    @Test fun grantRequiresMatchingSignerAndRejectsChangedPin() {
        val certificates = ApiPackageIdentity.signingCertificates(context, context.packageName)
        ApiAuthStore.grant(context, context.packageName, certificates)
        ApiAuthStore.setApiEnabled(context, true)
        assertTrue(ApiAuthStore.isAnyGranted(context, arrayOf(context.packageName)))
        assertFalse(ApiAuthStore.isAnyGranted(context, arrayOf("other.package")))
        preferences.edit().putString("apiAuthorizedSigningCertificates",
            "{\"${context.packageName}\":[\"wrong signer\"]}").commit()
        assertFalse(ApiAuthStore.isAnyGranted(context, arrayOf(context.packageName)))
    }

    @Test fun dialogSignerMismatchDoesNotGrant() {
        try {
            ApiAuthStore.grant(context, context.packageName, listOf("replacement signer"))
            fail("A changed identity must not be approved")
        } catch (_: IllegalArgumentException) {
            assertTrue(ApiAuthStore.grantedPackages(context).isEmpty())
        }
    }

    @Test fun revocationAndDisableInvalidateOutstandingChecks() {
        val certificates = ApiPackageIdentity.signingCertificates(context, context.packageName)
        ApiAuthStore.grant(context, context.packageName, certificates)
        ApiAuthStore.setApiEnabled(context, true)
        val before = ApiAuthStore.revision(context)
        ApiAuthStore.revoke(context, context.packageName)
        assertFalse(ApiAuthStore.isAnyGranted(context, arrayOf(context.packageName)))
        ApiAuthStore.grant(context, context.packageName, certificates)
        assertNotEquals(before, ApiAuthStore.revision(context))
        val restored = ApiAuthStore.revision(context)
        ApiAuthStore.setApiEnabled(context, false)
        ApiAuthStore.setApiEnabled(context, true)
        assertNotEquals(restored, ApiAuthStore.revision(context))
    }

    @Test fun privateReceiverAcceptsOwnAppButRejectsExternalUid() {
        val action = "${context.packageName}.SECURITY_TEST_${UUID.randomUUID()}"
        val received = CountDownLatch(1)
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { received.countDown() }
        }
        ContextCompat.registerReceiver(context, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        try {
            val output = instrumentation.uiAutomation.executeShellCommand(
                "am broadcast -a $action -p ${context.packageName} --receiver-foreground"
            )
            ParcelFileDescriptor.AutoCloseInputStream(output).use { it.readBytes() }
            assertFalse("An external sender must not grant cooldown", received.await(500, TimeUnit.MILLISECONDS))
            context.sendBroadcast(Intent(action).setPackage(context.packageName))
            assertTrue("Own app broadcasts must still work", received.await(5, TimeUnit.SECONDS))
        } finally {
            context.unregisterReceiver(receiver)
        }
    }
}
