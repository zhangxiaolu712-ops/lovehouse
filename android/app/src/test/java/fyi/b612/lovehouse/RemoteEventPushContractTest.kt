package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.events.InstallationIdentityStore
import fyi.b612.lovehouse.feature.events.NotificationEventTargetResolution
import fyi.b612.lovehouse.feature.events.PushInstallationApi
import fyi.b612.lovehouse.feature.events.PushTokenSource
import fyi.b612.lovehouse.feature.events.RemoteEventPushCoordinator
import fyi.b612.lovehouse.feature.events.ServerEvent
import fyi.b612.lovehouse.feature.events.ServerEventDecision
import fyi.b612.lovehouse.feature.events.ServerEventDecisionResult
import fyi.b612.lovehouse.feature.events.ServerEventFeedState
import fyi.b612.lovehouse.feature.events.ServerEventKind
import fyi.b612.lovehouse.feature.events.ServerEventLoadResult
import fyi.b612.lovehouse.feature.events.ServerEventRepository
import fyi.b612.lovehouse.feature.events.eventNotificationDeepLink
import fyi.b612.lovehouse.feature.events.pushInstallationEndpoint
import fyi.b612.lovehouse.feature.events.pushNotificationTarget
import fyi.b612.lovehouse.feature.events.resolveNotificationEventTarget
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteEventPushContractTest {
    private val root = File(System.getProperty("user.dir"))
    private fun source(path: String) = File(root, "src/main/java/fyi/b612/lovehouse/$path").readText()

    @Test
    fun `authenticated lifecycle registers rotates and disables one installation endpoint`() = runBlocking {
        val calls = mutableListOf<String>()
        var token = "token-one"
        var cookie: String? = "lovehouse_app_session=session-one"
        val api = object : PushInstallationApi {
            override suspend fun upsert(installationId: String, transport: String, pushToken: String, cookieHeader: String) {
                calls += "upsert:$installationId:$transport:$pushToken:$cookieHeader"
            }
            override suspend fun disable(installationId: String, transport: String, cookieHeader: String) {
                calls += "disable:$installationId:$transport:$cookieHeader"
            }
        }
        val coordinator = RemoteEventPushCoordinator(
            installationStore = object : InstallationIdentityStore { override fun id() = INSTALLATION_ID },
            tokenSource = object : PushTokenSource { override suspend fun currentToken() = token },
            api = api,
            sessionCookie = { cookie },
        )

        coordinator.refreshRegistration()
        token = "token-two"
        coordinator.registerRefreshedToken(token)
        coordinator.beforeLogout(cookie!!)
        cookie = null
        coordinator.registerRefreshedToken("token-three")

        assertEquals(3, calls.size)
        assertTrue(calls[0].contains("token-one"))
        assertTrue(calls[1].contains("token-two"))
        assertTrue(calls[2].startsWith("disable:"))
        assertFalse(calls.any { it.contains("token-three") })
    }

    @Test
    fun `payload parser accepts only event identity and kind`() {
        assertEquals(
            "reply_completed",
            pushNotificationTarget(mapOf("event_id" to INSTALLATION_ID, "kind" to "reply_completed"))?.kind,
        )
        assertNull(pushNotificationTarget(mapOf(
            "event_id" to INSTALLATION_ID,
            "kind" to "reply_completed",
            "content" to "must-not-be-accepted",
        )))
        assertNull(pushNotificationTarget(mapOf("event_id" to "not-an-id", "kind" to "reply_completed")))
        assertNull(pushNotificationTarget(mapOf("event_id" to INSTALLATION_ID, "kind" to "future_kind")))
    }

    @Test
    fun `notification click reuses authenticated Event route and target mapping`() {
        val deepLink = eventNotificationDeepLink(INSTALLATION_ID)
        val navigation = source("core/navigation/LoveHouseNavHost.kt")
        val events = source("feature/events/ServerEventsScreen.kt")

        assertEquals("lovehouse://event/$INSTALLATION_ID?open_target=true", deepLink)
        assertTrue(navigation.contains("dependencies.serverEvents"))
        assertTrue(navigation.contains("chatStore::localThreadIdForCanonicalThread"))
        assertTrue(navigation.contains("NotificationEventTargetResolver"))
        assertTrue(navigation.contains("rehydrateThreadFromPersistence"))
        assertTrue(events.contains("repository.get(eventId)"))
        assertTrue(events.contains("event.kind != ServerEventKind.ReplyCompleted"))
    }

    @Test
    fun `notification resolver authenticates Event maps canonical thread and rehydrates before Chat`() = runBlocking {
        val canonicalThreadId = "7c814f9a-7588-4e35-b4b6-a216f172c012"
        val localThreadId = "agent-codex"
        val repository = RecordingEventRepository(replyCompleted(canonicalThreadId))
        var rehydrated: Pair<String, String>? = null

        val result = resolveNotificationEventTarget(
            eventId = INSTALLATION_ID,
            repository = repository,
            resolveLocalThreadId = { requested -> localThreadId.takeIf { requested == canonicalThreadId } },
            rehydrateThread = { local, canonical ->
                rehydrated = local to canonical
                true
            },
        )

        assertEquals(1, repository.getCalls)
        assertEquals(localThreadId to canonicalThreadId, rehydrated)
        assertEquals(
            NotificationEventTargetResolution.Chat(canonicalThreadId, localThreadId),
            result,
        )
    }

    @Test
    fun `notification resolver safely falls back when canonical mapping is missing`() = runBlocking {
        val repository = RecordingEventRepository(replyCompleted("missing-canonical-thread"))
        var rehydrateCalls = 0

        val result = resolveNotificationEventTarget(
            eventId = INSTALLATION_ID,
            repository = repository,
            resolveLocalThreadId = { null },
            rehydrateThread = { _, _ -> rehydrateCalls += 1; true },
        )

        assertEquals(1, repository.getCalls)
        assertEquals(0, rehydrateCalls)
        assertEquals(NotificationEventTargetResolution.Fallback, result)
    }

    @Test
    fun `ordinary Event detail remains separate from invisible notification resolver`() {
        val navigation = source("core/navigation/LoveHouseNavHost.kt")
        val screen = source("feature/events/ServerEventsScreen.kt")

        assertTrue(navigation.contains("if (openTarget)"))
        assertTrue(navigation.contains("NotificationEventTargetResolver"))
        assertTrue(navigation.contains("ServerEventDetailScreen"))
        assertTrue(screen.contains("EventTopBar(\"动态详情\""))
        assertTrue(screen.contains("Box(modifier.fillMaxSize())"))
    }

    @Test
    fun `installation endpoint and local identity remain provider neutral`() {
        assertEquals(
            "https://app.b612.fyi/api/installations/$INSTALLATION_ID/push-endpoints/fcm",
            pushInstallationEndpoint("https://app.b612.fyi/", INSTALLATION_ID, "fcm"),
        )
        val push = source("feature/events/RemoteEventPush.kt")
        assertTrue(push.contains("UUID.randomUUID()"))
        assertFalse(push.contains("Settings.Secure"))
        assertFalse(push.contains("ANDROID_ID"))
        assertFalse(push.contains("Build.SERIAL"))
        assertFalse(push.contains("IMEI"))
    }

    @Test
    fun `FCM credentials are injected and token is never logged`() {
        val gradle = File(root, "build.gradle.kts").readText()
        val push = source("feature/events/RemoteEventPush.kt")
        val service = source("feature/events/LoveHouseFirebaseMessagingService.kt")

        assertTrue(gradle.contains("LOVEHOUSE_FIREBASE_APPLICATION_ID"))
        assertTrue(gradle.contains("LOVEHOUSE_FIREBASE_PROJECT_ID"))
        assertFalse(File(root, "google-services.json").exists())
        assertFalse(push.contains("Log."))
        assertFalse(service.contains("Log."))
        assertFalse(service.contains("println("))
    }

    @Test
    fun `App Account lifecycle is the only registration ownership source`() {
        val account = source("feature/settings/AppAccountRepository.kt")
        val dependencies = source("app/AppDependencies.kt")

        assertTrue(account.contains("pushLifecycle.onAuthenticated"))
        assertTrue(account.contains("pushLifecycle.beforeLogout"))
        assertTrue(dependencies.contains("pushLifecycle = remoteEventPush"))
        assertFalse(account.contains("device_id"))
        assertFalse(account.contains("persona_id"))
    }

    private companion object {
        const val INSTALLATION_ID = "11111111-1111-4111-8111-111111111111"

        fun replyCompleted(threadId: String) = ServerEvent(
            id = INSTALLATION_ID,
            kind = ServerEventKind.ReplyCompleted,
            rawKind = "reply_completed",
            source = "chat",
            threadId = threadId,
            safeSummary = "聊天回复已完成",
            status = "active",
            createdAt = "2026-09-28T00:00:00.000Z",
            expiresAt = null,
            decidedAt = null,
        )
    }

    private class RecordingEventRepository(event: ServerEvent) : ServerEventRepository {
        override val feed: StateFlow<ServerEventFeedState> = MutableStateFlow(ServerEventFeedState.Initial)
        private val result = ServerEventLoadResult.Found(event)
        var getCalls = 0
            private set

        override suspend fun refresh() = Unit
        override suspend fun get(eventId: String): ServerEventLoadResult {
            getCalls += 1
            return result
        }
        override suspend fun decide(
            eventId: String,
            decision: ServerEventDecision,
        ): ServerEventDecisionResult = error("notification resolver must not decide")
    }
}
