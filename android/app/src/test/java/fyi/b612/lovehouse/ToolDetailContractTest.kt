package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.chat.ToolDetailEnvelope
import fyi.b612.lovehouse.feature.chat.ToolDetailValue
import fyi.b612.lovehouse.feature.chat.buildChatPayload
import fyi.b612.lovehouse.feature.chat.ClaudeRuntime
import fyi.b612.lovehouse.feature.chat.parseToolDetailEnvelope
import fyi.b612.lovehouse.feature.chat.ChatProcessEvent
import fyi.b612.lovehouse.feature.chat.ChatProcessKind
import fyi.b612.lovehouse.feature.chat.ChatProcessStatus
import fyi.b612.lovehouse.feature.chat.mergeProcessEvent
import fyi.b612.lovehouse.feature.chat.toolDetailProcessEventId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDetailContractTest {
    @Test
    fun `typed command detail parses and associates only by call id`() {
        val json = """{"call_id":"command-1","tool_detail":{"schema_version":1,"call_id":"command-1","detail_kind":"command","created_at":"2026-10-01T00:00:00.000Z","truncated":false,"original_length":12,"command":"pwd","output":"/tmp","exit_code":0,"status":"completed"}}"""
        val detail = parseToolDetailEnvelope(json, "command-1") as ToolDetailEnvelope.Command

        assertEquals("pwd", detail.command)
        assertEquals("/tmp", detail.output)
        assertEquals(0, detail.exitCode)
        assertEquals("tool-call:command-1", toolDetailProcessEventId(detail))
        assertNull(parseToolDetailEnvelope(json, "different-call"))
    }

    @Test
    fun `typed generic detail preserves safe structure without raw maps`() {
        val json = """{"call_id":"tool-1","tool_detail":{"schema_version":1,"call_id":"tool-1","detail_kind":"generic_tool","created_at":"2026-10-01T00:00:00.000Z","truncated":true,"original_length":42,"arguments":{"type":"object","fields":[{"key":"path","value":{"type":"text","text":"/tmp/a"}}]},"result":{"type":"text","text":"done"},"is_error":false}}"""
        val detail = parseToolDetailEnvelope(json, "tool-1") as ToolDetailEnvelope.GenericTool
        val arguments = detail.arguments as ToolDetailValue.ObjectValue

        assertEquals("path", arguments.fields.single().key)
        assertEquals(ToolDetailValue.Text("/tmp/a"), arguments.fields.single().value)
        assertEquals(ToolDetailValue.Text("done"), detail.result)
        assertEquals(false, detail.isError)
        assertTrue(detail.truncated)
    }

    @Test
    fun `unknown malformed oversized and missing-call details are ignored safely`() {
        val unknown = """{"call_id":"a","tool_detail":{"schema_version":1,"call_id":"a","detail_kind":"future","created_at":"now","truncated":false,"original_length":0}}"""
        val malformed = """{"call_id":"a","tool_detail":{"schema_version":1,"call_id":"a","detail_kind":"command","created_at":"now","truncated":"no","original_length":0}}"""
        val missingCall = """{"tool_detail":{"schema_version":1,"call_id":"a","detail_kind":"command","created_at":"now","truncated":false,"original_length":0}}"""
        val oversized = """{"call_id":"a","tool_detail":{"schema_version":1,"call_id":"a","detail_kind":"command","created_at":"now","truncated":false,"original_length":9000,"command":"${"x".repeat(8193)}"}}"""

        assertNull(parseToolDetailEnvelope(unknown, "a"))
        assertNull(parseToolDetailEnvelope(malformed, "a"))
        assertNull(parseToolDetailEnvelope(missingCall, null))
        assertNull(parseToolDetailEnvelope(oversized, "a"))
    }

    @Test
    fun `tool detail remains local and never enters provider payload`() {
        val marker = "TOOL_DETAIL_MUST_NOT_ENTER_PROVIDER_CONTEXT"
        val json = """{"call_id":"a","tool_detail":{"schema_version":1,"call_id":"a","detail_kind":"command","created_at":"2026-10-01T00:00:00.000Z","truncated":false,"original_length":44,"output":"$marker"}}"""
        val detail = parseToolDetailEnvelope(json, "a")
        val payload = buildChatPayload(ClaudeRuntime, "next turn", emptySet())

        assertEquals(marker, (detail as ToolDetailEnvelope.Command).output)
        assertFalse(payload.contains(marker))
        assertFalse(payload.contains("tool_detail"))
        assertFalse(payload.contains("recent_history"))
    }

    @Test
    fun `interleaved same-name calls update only their call-id detail`() {
        val first = parseToolDetailEnvelope(commandJson("a", "first"), "a")!!
        val second = parseToolDetailEnvelope(commandJson("b", "second"), "b")!!
        var events = emptyList<ChatProcessEvent>()
        events = mergeProcessEvent(events, ChatProcessEvent(
            "tool-call:a", ChatProcessKind.ToolCall, "shell", ChatProcessStatus.Running, toolDetail = first,
        ))
        events = mergeProcessEvent(events, ChatProcessEvent(
            "tool-call:b", ChatProcessKind.ToolCall, "shell", ChatProcessStatus.Running, toolDetail = second,
        ))
        events = mergeProcessEvent(events, ChatProcessEvent(
            "tool-call:a", ChatProcessKind.ToolResult, "shell", ChatProcessStatus.Succeeded,
        ))

        assertEquals(listOf("a", "b"), events.map { it.toolDetail?.callId })
        assertEquals("first", (events[0].toolDetail as ToolDetailEnvelope.Command).output)
        assertEquals("second", (events[1].toolDetail as ToolDetailEnvelope.Command).output)
    }

    private fun commandJson(callId: String, output: String): String =
        """{"call_id":"$callId","tool_detail":{"schema_version":1,"call_id":"$callId","detail_kind":"command","created_at":"2026-10-01T00:00:00.000Z","truncated":false,"original_length":${output.length},"output":"$output"}}"""
}
