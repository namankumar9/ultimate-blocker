package neth.iecal.curbox.utils

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WarningPasswordTest {
    @Test
    fun `correct password verifies and wrong one does not`() {
        val hash = WarningPassword.hash("open sesame")
        assertTrue(WarningPassword.verify("open sesame", hash))
        assertFalse(WarningPassword.verify("open sesam", hash))
        assertFalse(WarningPassword.verify("", hash))
    }

    @Test
    fun `hash is salted and never contains the password`() {
        val first = WarningPassword.hash("open sesame")
        val second = WarningPassword.hash("open sesame")
        assertNotEquals(first, second)
        assertFalse(first.contains("open sesame"))
        assertTrue(WarningPassword.isValidHash(first))
    }

    @Test
    fun `empty or malformed stored hash never verifies`() {
        assertFalse(WarningPassword.verify("", ""))
        assertFalse(WarningPassword.verify("anything", "nosalt"))
        assertFalse(WarningPassword.isValidHash(""))
        assertFalse(WarningPassword.isValidHash("abc:XYZ"))
    }
}
