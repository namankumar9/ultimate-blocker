package neth.iecal.curbox.utils

import androidx.datastore.core.DataStoreFactory
import com.google.gson.Gson
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.data.models.AppBlockerWarningScreenConfig
import neth.iecal.curbox.data.models.Settings
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SettingsUpgradeCompatibilityTest {
    @get:Rule val temporaryFolder = TemporaryFolder()
    private val gson = Gson()
    private fun serializer() = GsonSerializer(gson, Settings::class.java, Settings())

    @Test
    fun readsV4MessagesWithoutResettingOtherSettings() = runBlocking {
        val json = """{
            "isReelCounterOn":false,
            "blockedAppGroups":[{"id":"v4-app","name":"My apps","warningScreenConfig":{"message":"App warning"}}],
            "keywordBlockerConfig":{"keywordGroups":[{"id":"v4-keyword","name":"My websites","warningScreenConfig":{"message":"Website warning"}}]},
            "reelBlockerConfig":{"warningScreenConfig":{"message":"Reel warning"}}
        }"""
        val settings = serializer().readFrom(ByteArrayInputStream(json.toByteArray()))
        assertFalse(settings.isReelCounterOn)
        assertEquals("v4-app", settings.blockedAppGroups.single().id)
        assertEquals(listOf("App warning"), settings.blockedAppGroups.single().warningScreenConfig.message)
        assertEquals("v4-keyword", settings.keywordBlockerConfig.keywordGroups.single().id)
        assertEquals(listOf("Website warning"), settings.keywordBlockerConfig.keywordGroups.single().warningScreenConfig.message)
        assertEquals(listOf("Reel warning"), settings.reelBlockerConfig.warningScreenConfig.message)
        val output = ByteArrayOutputStream()
        serializer().writeTo(settings, output)
        assertEquals(settings, serializer().readFrom(ByteArrayInputStream(output.toByteArray())))
        val saved = JsonParser.parseString(output.toString("UTF-8")).asJsonObject
        assertTrue(saved.getAsJsonObject("reelBlockerConfig").getAsJsonObject("warningScreenConfig").get("message").isJsonArray)
    }

    @Test
    fun warningMessagesSupportBothFormatsWithPlainGson() {
        assertEquals(listOf(""), gson.fromJson("""{"message":""}""", AppBlockerWarningScreenConfig::class.java).message)
        val config = AppBlockerWarningScreenConfig(message = listOf("First", "Second"))
        assertEquals(config, gson.fromJson(gson.toJson(config), AppBlockerWarningScreenConfig::class.java))
        assertEquals(emptyList<String>(), gson.fromJson("""{"message":[]}""", AppBlockerWarningScreenConfig::class.java).message)
    }

    @Test
    fun unreadableExistingSettingsCannotBeOverwrittenByAnUpdate() = runBlocking {
        for (json in listOf("{broken", "", "null", """{"reelBlockerConfig":{"warningScreenConfig":{"message":42}}}""")) {
            val file = temporaryFolder.newFile()
            file.writeText(json)
            val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
            val store = DataStoreFactory.create(serializer = serializer(), scope = scope, produceFile = { file })
            try {
                try {
                    store.updateData { Settings() }
                    fail("Unreadable settings must block updates")
                } catch (_: IOException) {
                    assertEquals(json, file.readText())
                }
            } finally {
                scope.cancel()
            }
        }
    }

    @Test
    fun missingSettingsFileStillUsesDefaults() = runBlocking {
        val file = File(temporaryFolder.root, "new-settings.json")
        val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        try {
            val store = DataStoreFactory.create(serializer = serializer(), scope = scope, produceFile = { file })
            assertEquals(Settings(), store.data.first())
        } finally {
            scope.cancel()
        }
    }
}
