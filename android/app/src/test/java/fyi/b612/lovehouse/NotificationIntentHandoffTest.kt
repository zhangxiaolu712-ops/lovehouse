package fyi.b612.lovehouse

import android.content.Intent
import fyi.b612.lovehouse.feature.events.NotificationIntentHandoff
import fyi.b612.lovehouse.feature.events.notificationEventId
import fyi.b612.lovehouse.feature.events.notificationEventTargetRoute
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class NotificationIntentHandoffTest {
    private val eventId = "11111111-1111-4111-8111-111111111111"
    private val deepLink = "lovehouse://event/$eventId?open_target=true"
    private val root = File(System.getProperty("user.dir"))

    @Before
    fun setUp() = NotificationIntentHandoff.resetForTest()

    @After
    fun tearDown() = NotificationIntentHandoff.resetForTest()

    @Test
    fun `cold start preserves event identity and open target until navigation consumes it`() {
        assertTrue(NotificationIntentHandoff.offer(Intent.ACTION_VIEW, deepLink))
        val target = NotificationIntentHandoff.pending.value!!

        assertEquals(eventId, target.eventId)
        assertEquals("events/$eventId?openTarget=true", notificationEventTargetRoute(target.eventId))

        NotificationIntentHandoff.consume(target.deliveryId)
        assertNull(NotificationIntentHandoff.pending.value)
    }

    @Test
    fun `warm background Home receives the same explicit target exactly once`() {
        assertTrue(NotificationIntentHandoff.offer(Intent.ACTION_VIEW, deepLink))
        val first = NotificationIntentHandoff.pending.value!!
        assertTrue(NotificationIntentHandoff.offer(Intent.ACTION_VIEW, deepLink))
        val duplicate = NotificationIntentHandoff.pending.value!!

        assertEquals(first.deliveryId, duplicate.deliveryId)
        NotificationIntentHandoff.consume(first.deliveryId)
        NotificationIntentHandoff.consume(first.deliveryId)
        assertNull(NotificationIntentHandoff.pending.value)

        assertTrue(NotificationIntentHandoff.offer(Intent.ACTION_VIEW, deepLink))
        assertNull(NotificationIntentHandoff.pending.value)
    }

    @Test
    fun `foreground other destination accepts a new notification after prior target was consumed`() {
        assertTrue(NotificationIntentHandoff.offer(Intent.ACTION_VIEW, deepLink))
        val first = NotificationIntentHandoff.pending.value!!
        NotificationIntentHandoff.consume(first.deliveryId)

        val otherEventId = "22222222-2222-4222-8222-222222222222"
        assertTrue(NotificationIntentHandoff.offer(
            Intent.ACTION_VIEW,
            "lovehouse://event/$otherEventId?open_target=true",
        ))
        assertEquals(otherEventId, NotificationIntentHandoff.pending.value?.eventId)
        assertTrue(NotificationIntentHandoff.pending.value!!.deliveryId > first.deliveryId)
    }

    @Test
    fun `launcher and malformed or non target deep links are not consumed`() {
        assertFalse(NotificationIntentHandoff.offer(Intent.ACTION_MAIN, null))
        assertFalse(NotificationIntentHandoff.offer(Intent.ACTION_VIEW, "lovehouse://event/$eventId"))
        assertFalse(NotificationIntentHandoff.offer(Intent.ACTION_VIEW, "lovehouse://event/$eventId?open_target=false"))
        assertFalse(NotificationIntentHandoff.offer(Intent.ACTION_VIEW, "lovehouse://event/$eventId?open_target=true&decision=approved"))
        assertNull(NotificationIntentHandoff.pending.value)
    }

    @Test
    fun `parser preserves only event id and cannot carry decision authority`() {
        assertEquals(eventId, notificationEventId(Intent.ACTION_VIEW, deepLink))
        assertNull(notificationEventId(
            Intent.ACTION_VIEW,
            "lovehouse://event/$eventId?open_target=true&decision=approved",
        ))
    }

    @Test
    fun `Activity and navigation keep authenticated resolver and actionable confirmation destination`() {
        val activity = source("MainActivity.kt")
        val navigation = source("core/navigation/LoveHouseNavHost.kt")
        val events = source("feature/events/ServerEventsScreen.kt")

        assertTrue(activity.contains("override fun onNewIntent"))
        assertTrue(activity.contains("NotificationIntentHandoff.offer"))
        assertTrue(activity.contains("setIntent(Intent(this, MainActivity::class.java)"))
        assertTrue(navigation.contains("NotificationIntentHandoff.pending.collectAsState()"))
        assertTrue(navigation.contains("notificationEventTargetRoute(target.eventId)"))
        assertTrue(navigation.contains("NotificationEventTargetResolver"))
        assertTrue(navigation.contains("onOpenConfirmation"))
        assertTrue(events.contains("repository.get(eventId)"))
        assertTrue(events.contains("ServerEventKind.ConfirmationRequired"))
    }

    @Test
    fun `reply notification retains existing authenticated Chat routing`() {
        val navigation = source("core/navigation/LoveHouseNavHost.kt")
        val events = source("feature/events/ServerEventsScreen.kt")

        assertTrue(navigation.contains("chatStore::localThreadIdForCanonicalThread"))
        assertTrue(navigation.contains("rehydrateThreadFromPersistence"))
        assertTrue(events.contains("NotificationEventTargetResolution.Chat"))
    }

    private fun source(path: String) =
        File(root, "src/main/java/fyi/b612/lovehouse/$path").readText()
}
