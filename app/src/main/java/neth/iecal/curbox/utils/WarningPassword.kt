package neth.iecal.curbox.utils

import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Hashing for the warning screen password unlock. Only "salt:hash" is stored in
 * [neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig.passwordHash], so settings
 * backups never contain the password itself.
 */
object WarningPassword {
    const val MIN_LENGTH = 4

    fun hash(password: String): String {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.toHex()
        return "$salt:${digest(salt, password)}"
    }

    fun verify(password: String, storedHash: String): Boolean {
        val parts = storedHash.split(':')
        if (parts.size != 2 || parts[0].isEmpty() || parts[1].isEmpty()) return false
        val candidate = digest(parts[0], password).toByteArray(Charsets.UTF_8)
        return MessageDigest.isEqual(candidate, parts[1].toByteArray(Charsets.UTF_8))
    }

    fun isValidHash(storedHash: String): Boolean {
        val parts = storedHash.split(':')
        return parts.size == 2 && parts.all { it.isNotEmpty() && it.all { c -> c in HEX } }
    }

    private fun digest(salt: String, password: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest("$salt:$password".toByteArray(Charsets.UTF_8))
            .toHex()

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    private const val HEX = "0123456789abcdef"
}
