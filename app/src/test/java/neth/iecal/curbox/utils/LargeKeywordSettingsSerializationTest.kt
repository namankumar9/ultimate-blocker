package neth.iecal.curbox.utils

import com.google.gson.Gson
import kotlinx.coroutines.runBlocking
import neth.iecal.curbox.data.models.AppUsageConfig
import neth.iecal.curbox.data.models.KeywordBlocker
import neth.iecal.curbox.data.models.KeywordGroup
import neth.iecal.curbox.data.models.ScheduledUsageConfig
import neth.iecal.curbox.data.models.Settings
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class LargeKeywordSettingsSerializationTest {
    @Test(timeout = 10000)
    fun savesAndReloadsLargeKeywordGroupWithoutLosingWordsOrEdits() = runBlocking {
        val keywords = List(100_000) { "domain$it.example" }
        val group = KeywordGroup(
            id = "large-group",
            name = "Large group",
            isActive = true,
            selectedKeywords = keywords,
            config = ScheduledUsageConfig(usage = AppUsageConfig(uniformLimit = 30))
        )
        val serializer = GsonSerializer(Gson(), Settings::class.java, Settings())
        suspend fun roundTrip(group: KeywordGroup): KeywordGroup {
            val output = ByteArrayOutputStream()
            serializer.writeTo(Settings(keywordBlockerConfig = KeywordBlocker(
                isActive = true, keywordGroups = listOf(group)
            )), output)
            return serializer.readFrom(ByteArrayInputStream(output.toByteArray()))
                .keywordBlockerConfig.keywordGroups.single()
        }
        assertEquals(group, roundTrip(group))
        val updated = group.copy(name = "Updated group", selectedKeywords = keywords.drop(1) + "new.example")
        assertEquals(updated, roundTrip(updated))
    }
}
