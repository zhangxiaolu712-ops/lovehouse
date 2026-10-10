package fyi.b612.lovehouse.feature.settings

import java.io.File
import java.io.IOException
import java.net.SocketTimeoutException
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiCapabilityWiringTest {
    @Test
    fun `catalog preserves unselected entries and parses missing state`() = runBlocking {
        val api = RecordingApi().apply {
            responses += JSONObject().put("catalog", catalogJson())
        }
        val repository = AppBackendApiConnectionRepository(api)

        val catalog = repository.capabilityCatalog("connection-1")

        assertEquals(2, catalog.capabilities.size)
        assertTrue(catalog.capabilities[0].selected)
        assertFalse(catalog.capabilities[1].selected)
        assertEquals(ApiCapabilityAvailability.Missing, catalog.capabilities[1].availability)
        assertEquals("GET /first", catalog.capabilities[0].sourceKey)
        assertEquals("GET /api/api-connections/connection-1/capabilities", api.calls.single().methodPath)
    }

    @Test
    fun `URL and document discovery serialize explicit sources only`() = runBlocking {
        val api = RecordingApi().apply {
            responses += JSONObject().put("preview", previewJson())
            responses += JSONObject().put("preview", previewJson())
        }
        val repository = AppBackendApiConnectionRepository(api)

        repository.previewCapabilities("connection-1", ApiDiscoverySource.Url(
            "https://docs.example.invalid/openapi.json",
            "X-Api-Key",
        ))
        repository.previewCapabilities("connection-1", ApiDiscoverySource.JsonDocument(
            "fixture.json",
            """{"openapi":"3.1.0","paths":{}}""",
        ))

        val url = JSONObject(api.calls[0].body!!).getJSONObject("source")
        assertEquals("url", url.getString("type"))
        assertEquals("X-Api-Key", url.getString("api_key_header_name"))
        val document = JSONObject(api.calls[1].body!!).getJSONObject("source")
        assertEquals("document", document.getString("type"))
        assertEquals("3.1.0", document.getJSONObject("document").getString("openapi"))
        assertFalse(api.calls.any { it.path.contains("openapi.json") })
    }

    @Test
    fun `preview selection commits full normalized catalog by preview id`() = runBlocking {
        val api = RecordingApi().apply {
            responses += JSONObject().put("preview", previewJson())
            responses += JSONObject().put("catalog", catalogJson())
        }
        val repository = AppBackendApiConnectionRepository(api)
        val preview = repository.previewCapabilities("connection-1", ApiDiscoverySource.Preset)

        repository.commitCapabilities("connection-1", preview.previewId, setOf("api_first"))

        val body = JSONObject(api.calls[1].body!!)
        assertEquals("preview-1", body.getString("preview_id"))
        assertEquals(listOf("api_first"), body.getJSONArray("selected_capability_ids").strings())
        assertEquals(
            "POST /api/api-connections/connection-1/capability-discovery/commit",
            api.calls[1].methodPath,
        )
    }

    @Test
    fun `manual capability and selection use authoritative Backend routes`() = runBlocking {
        val api = RecordingApi().apply {
            responses += JSONObject().put("catalog", catalogJson())
            responses += JSONObject().put("catalog", catalogJson())
            responses += JSONObject().put("catalog", catalogJson())
        }
        val repository = AppBackendApiConnectionRepository(api)
        repository.createCustomCapability("connection-1", ApiCustomCapabilityInput(
            displayName = "Custom", description = "Owner entry", method = "POST", path = "/custom",
            inputSchemaJson = """{"type":"object"}""", selected = true,
        ))
        repository.selectCapabilities("connection-1", setOf("api_first"))
        repository.deleteCustomCapability("connection-1", "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa")

        assertEquals("POST /api/api-connections/connection-1/capabilities/custom", api.calls[0].methodPath)
        assertTrue(JSONObject(api.calls[0].body!!).getBoolean("selected"))
        assertEquals("PUT /api/api-connections/connection-1/capability-selections", api.calls[1].methodPath)
        assertEquals("DELETE /api/api-connections/connection-1/capabilities/custom/aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa", api.calls[2].methodPath)
    }

    @Test
    fun `model discovery and selection use a separate authoritative resource catalog`() = runBlocking {
        val api = RecordingApi().apply {
            responses += JSONObject().put("preview", resourcePreviewJson())
            responses += JSONObject().put("catalog", resourceCatalogJson())
            responses += JSONObject().put("catalog", resourceCatalogJson())
        }
        val repository = AppBackendApiConnectionRepository(api)

        val preview = repository.previewResources("connection-1", ApiResourceDiscoverySource.OpenAiModels(
            "https://gateway.example.invalid/v1/models",
            "X-Api-Key",
        ))
        repository.commitResources("connection-1", preview.previewId, setOf("resource_first"))
        repository.selectResources("connection-1", setOf("resource_first"))

        val source = JSONObject(api.calls[0].body!!).getJSONObject("source")
        assertEquals("openai_models", source.getString("type"))
        assertEquals("https://gateway.example.invalid/v1/models", source.getString("url"))
        assertEquals("X-Api-Key", source.getString("api_key_header_name"))
        assertEquals("vendor/model-alpha", preview.resources[0].providerResourceId)
        assertEquals("POST /api/api-connections/connection-1/resource-discovery/commit", api.calls[1].methodPath)
        assertEquals("PUT /api/api-connections/connection-1/resource-selections", api.calls[2].methodPath)
    }

    @Test
    fun `automatic model discovery uses authoritative connection protocol without client URL guessing`() = runBlocking {
        val api = RecordingApi().apply {
            responses += JSONObject().put("preview", resourcePreviewJson())
        }
        val repository = AppBackendApiConnectionRepository(api)

        repository.previewResources("connection-1", ApiResourceDiscoverySource.ConnectionModels)

        val source = JSONObject(api.calls.single().body!!).getJSONObject("source")
        assertEquals("connection_models", source.getString("type"))
        assertFalse(source.has("url"))
        assertEquals("POST /api/api-connections/connection-1/resource-discovery/preview", api.calls.single().methodPath)
    }

    @Test
    fun `large mixed model directory supports search filter and independent identity`() {
        val values = (0 until 1_200).map { index ->
            ApiResource(
                id = "resource-$index",
                type = "MODEL",
                source = if (index == 1199) ApiResourceSource.Custom else ApiResourceSource.Discovered,
                providerResourceId = "vendor-${index % 11}/model-$index",
                displayName = "model-$index",
                description = null,
                providerOwner = "vendor-${index % 11}",
                availability = if (index == 1198) ApiResourceAvailability.Missing else ApiResourceAvailability.Available,
                selected = index == 777,
            )
        }
        assertEquals(listOf("resource-777"), filterApiResources(values, setOf("resource-777"), "model-777", 1).map { it.id })
        assertEquals(listOf("resource-1199"), filterApiResources(values, emptySet(), "", 3).map { it.id })
        assertEquals(listOf("resource-1198"), filterApiResources(values, emptySet(), "", 4).map { it.id })
    }

    @Test
    fun `search and filters keep selection source and missing semantics separate`() {
        val values = listOf(
            capability("one", "天气", ApiCapabilitySource.Discovered, ApiCapabilityAvailability.Available),
            capability("two", "手动健康检查", ApiCapabilitySource.Custom, ApiCapabilityAvailability.Available),
            capability("three", "旧预报", ApiCapabilitySource.Discovered, ApiCapabilityAvailability.Missing),
        )
        assertEquals(listOf("one"), filterApiCapabilities(values, setOf("one"), "天气", 0).map { it.id })
        assertEquals(listOf("one"), filterApiCapabilities(values, setOf("one"), "", 1).map { it.id })
        assertEquals(listOf("two"), filterApiCapabilities(values, emptySet(), "", 3).map { it.id })
        assertEquals(listOf("three"), filterApiCapabilities(values, emptySet(), "", 4).map { it.id })
    }

    @Test
    fun `API capability UI uses Backend and never revives local probe or persistence`() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val ui = File(root, "src/main/java/fyi/b612/lovehouse/feature/settings/ToolCenterApiCapabilities.kt").readText()
        val repository = File(root, "src/main/java/fyi/b612/lovehouse/feature/settings/ApiConnectionRepository.kt").readText()

        assertTrue(ui.contains("repository.previewCapabilities"))
        assertTrue(ui.contains("repository.commitCapabilities"))
        assertTrue(ui.contains("repository.createCustomCapability"))
        val resourceUi = File(root, "src/main/java/fyi/b612/lovehouse/feature/settings/ToolCenterApiResources.kt").readText()
        assertTrue(resourceUi.contains("repository.previewResources"))
        assertTrue(resourceUi.contains("repository.commitResources"))
        assertTrue(resourceUi.contains("从 Base URL 定位模型列表"))
        assertFalse(ui.contains("HttpToolConnectionProbe"))
        assertFalse(ui.contains("ToolConnectionStore"))
        assertFalse(repository.contains("SharedPreferences"))
        assertFalse(repository.contains("DataStore"))
    }

    @Test
    fun `new connection stays on page and enters capability setup with authoritative id`() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val ui = File(root, "src/main/java/fyi/b612/lovehouse/feature/settings/ToolCenterApi.kt").readText()

        assertTrue(ui.contains("onCreated = { createdConnection = it }"))
        assertTrue(ui.contains("连接已创建，可以继续发现能力"))
        assertTrue(ui.contains("ApiCapabilityCatalogSection(existing, repository, ui, autoDiscoveryRequest)"))
        assertTrue(ui.contains("ApiModelResourceSection(existing, repository, ui, autoDiscoveryRequest)"))
        assertTrue(ui.contains("TcSaveButton(\"自动发现\""))
        assertTrue(ui.contains("\"稍后设置\" else \"完成\""))
    }

    @Test
    fun `only explicit connection not found is reported as deleted`() {
        val capabilityPath = "/api/api-connections/connection-1/capabilities"

        assertEquals(
            "这个 API 连接已不存在，请刷新列表",
            apiConnectionErrorText(404, "API_CONNECTION_NOT_FOUND", capabilityPath),
        )
        assertEquals(
            "当前 App Backend 尚未提供能力目录，请等待服务端更新后重试",
            apiConnectionErrorText(404, "", capabilityPath),
        )
        assertEquals(
            "App Backend 接口不存在或版本不匹配，请稍后重试",
            apiConnectionErrorText(404, "", "/api/unknown"),
        )
        assertEquals(
            "请先登录 LoveHouse App Account",
            apiConnectionErrorText(401, "APP_AUTH_REQUIRED", capabilityPath),
        )
        assertEquals(
            "连接 App Backend 超时，请检查网络后重试",
            apiConnectionNetworkErrorText(SocketTimeoutException()),
        )
        assertEquals(
            "无法连接 App Backend，请检查网络后重试",
            apiConnectionNetworkErrorText(IOException()),
        )
    }

    private class RecordingApi : ApiConnectionApi {
        val calls = mutableListOf<Call>()
        val responses = ArrayDeque<JSONObject>()
        override suspend fun request(method: String, path: String, body: String?, headers: Map<String, String>): JSONObject {
            calls += Call(method, path, body)
            return responses.removeFirst()
        }
    }

    private data class Call(val method: String, val path: String, val body: String?) {
        val methodPath: String get() = "$method $path"
    }

    private fun capability(
        id: String,
        name: String,
        source: ApiCapabilitySource,
        availability: ApiCapabilityAvailability,
    ) = ApiCapability(id, source, id, name, null, "GET", "/$id", null, null, availability, false)

    private fun capabilityJson(id: String, name: String, selected: Boolean, availability: String = "AVAILABLE") = JSONObject()
        .put("capability_id", id)
        .put("source_kind", "DISCOVERED")
        .put("source_key", "GET /${id.removePrefix("api_")}")
        .put("display_name", name)
        .put("description", "safe description")
        .put("protocol_kind", "HTTP")
        .put("http_method", "GET")
        .put("relative_path", "/${id.removePrefix("api_")}")
        .put("input_schema", JSONObject().put("type", "object"))
        .put("output_schema", JSONObject().put("type", "object"))
        .put("availability", availability)
        .put("selected", selected)

    private fun catalogJson() = JSONObject()
        .put("connection_id", "connection-1")
        .put("revision", 2)
        .put("capabilities", JSONArray()
            .put(capabilityJson("api_first", "Weather", true))
            .put(capabilityJson("api_second", "Old", false, "MISSING")))
        .put("last_discovery", JSONObject().put("revision", 2).put("changes", JSONObject()
            .put("added", JSONArray()).put("changed", JSONArray()).put("removed", JSONArray())))

    private fun previewJson() = JSONObject()
        .put("preview_id", "preview-1")
        .put("revision", 2)
        .put("expires_at", "2026-10-10T01:00:00.000Z")
        .put("changes", JSONObject().put("added", JSONArray().put("api_second"))
            .put("changed", JSONArray()).put("removed", JSONArray()))
        .put("capabilities", JSONArray()
            .put(capabilityJson("api_first", "Weather", true))
            .put(capabilityJson("api_second", "Forecast", false)))

    private fun resourceJson(id: String, providerId: String, selected: Boolean, availability: String = "AVAILABLE") =
        JSONObject()
            .put("resource_id", id)
            .put("resource_type", "MODEL")
            .put("source_kind", "DISCOVERED")
            .put("source_key", "MODEL:$providerId")
            .put("provider_resource_id", providerId)
            .put("display_name", providerId)
            .put("description", JSONObject.NULL)
            .put("provider_owner", providerId.substringBefore('/'))
            .put("availability", availability)
            .put("selected", selected)

    private fun resourceCatalogJson() = JSONObject()
        .put("connection_id", "connection-1")
        .put("revision", 1)
        .put("resources", JSONArray().put(resourceJson("resource_first", "vendor/model-alpha", true)))

    private fun resourcePreviewJson() = JSONObject()
        .put("preview_id", "preview-resource")
        .put("revision", 1)
        .put("expires_at", "2026-10-10T01:00:00.000Z")
        .put("changes", JSONObject().put("added", JSONArray().put("resource_first"))
            .put("changed", JSONArray()).put("removed", JSONArray()))
        .put("resources", JSONArray().put(resourceJson("resource_first", "vendor/model-alpha", false)))

    private fun JSONArray.strings(): List<String> = buildList {
        for (index in 0 until length()) add(getString(index))
    }
}
