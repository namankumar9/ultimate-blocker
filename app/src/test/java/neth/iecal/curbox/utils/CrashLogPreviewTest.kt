package neth.iecal.curbox.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.RandomAccessFile

class CrashLogPreviewTest {
    @get:Rule val temporaryFolder = TemporaryFolder()

    @Test
    fun keepsSmallLogIntact() {
        val file = temporaryFolder.newFile("crash_log.txt")
        file.writeText("First crash\nSecond crash")

        assertEquals("First crash\nSecond crash", CrashLogPreview.read(file))
    }

    @Test
    fun readsOnlyTailOfLargeLog() {
        val file = temporaryFolder.newFile("crash_log.txt")
        RandomAccessFile(file, "rw").use { log ->
            log.setLength(150_000_000L)
            log.seek(log.length() - 12)
            log.write("Recent crash".toByteArray())
        }

        val preview = CrashLogPreview.read(file)!!
        assertTrue(preview.startsWith("..."))
        assertTrue(preview.endsWith("Recent crash"))
        assertTrue(preview.length <= 50_003)
    }
}
