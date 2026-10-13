package fyi.b612.lovehouse

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fyi.b612.lovehouse.feature.chat.AndroidConversationPersonaStore
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ConversationThreadAliasTest {
    @Test
    fun aliasMappingSurvivesStoreRecreation() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val alias = "alias-${System.nanoTime()}"
        val canonical = "7c814f9a-7588-4e35-b4b6-a216f172c012"
        val key = "thread_alias:$alias"
        try {
            AndroidConversationPersonaStore(context).setThreadAlias(alias, canonical)

            val reopened = AndroidConversationPersonaStore(context)
            assertEquals(canonical, reopened.canonicalThreadId(alias))
            assertEquals(alias, reopened.localAliasForCanonicalThread(canonical))
        } finally {
            context.getSharedPreferences("lovehouse_conversation_personas_v1", android.content.Context.MODE_PRIVATE)
                .edit().remove(key).commit()
        }
    }
}
