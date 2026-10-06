package neth.iecal.curbox.blockers.uihider

import java.security.MessageDigest

/**
 * Hashing/verification for UIHider script passwords. The password itself is never stored; only a
 * SHA-256 hash salted with the script id is persisted in [neth.iecal.curbox.data.models.UiHiderScript.passwordHash].
 */
object ScriptPassword {

    fun hash(scriptId: String, password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("$scriptId:$password".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }

    /** Returns true when [expectedHash] is null/empty (unprotected) or [password] matches it. */
    fun verify(scriptId: String, password: String, expectedHash: String?): Boolean {
        if (expectedHash.isNullOrEmpty()) return true
        val candidate = hash(scriptId, password).toByteArray(Charsets.UTF_8)
        val expected = expectedHash.toByteArray(Charsets.UTF_8)
        return candidate.size == expected.size && MessageDigest.isEqual(candidate, expected)
    }
}
