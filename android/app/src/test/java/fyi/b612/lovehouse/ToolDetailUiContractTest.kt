package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.chat.ToolDetailEnvelope
import fyi.b612.lovehouse.feature.chat.ToolDetailField
import fyi.b612.lovehouse.feature.chat.ToolDetailValue
import fyi.b612.lovehouse.feature.chat.toolDetailPresentation
import fyi.b612.lovehouse.feature.chat.toolDetailValueText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDetailUiContractTest {
    @Test
    fun `unknown MCP uses generic nested request and response without tool-name renderer`() {
        val arguments = ToolDetailValue.ObjectValue(
            listOf(
                ToolDetailField("city", ToolDetailValue.Text("Shanghai")),
                ToolDetailField("days", ToolDetailValue.NumberValue(3.0)),
                ToolDetailField(
                    "options",
                    ToolDetailValue.ObjectValue(
                        listOf(ToolDetailField("units", ToolDetailValue.Text("metric"))),
                    ),
                ),
            ),
        )
        val result = ToolDetailValue.ObjectValue(
            listOf(
                ToolDetailField(
                    "forecast",
                    ToolDetailValue.ListValue(
                        listOf(
                            ToolDetailValue.ObjectValue(
                                listOf(ToolDetailField("temperature", ToolDetailValue.NumberValue(24.5))),
                            ),
                        ),
                    ),
                ),
            ),
        )

        val presentation = toolDetailPresentation(generic(arguments = arguments, result = result))

        assertEquals(arguments, presentation.request)
        assertEquals(result, presentation.response)
        assertFalse(presentation.isError)
    }

    @Test
    fun `generic values preserve null boolean number short and long text`() {
        val longText = "x".repeat(2_000)
        val arguments = ToolDetailValue.ListValue(
            listOf(
                ToolDetailValue.NullValue,
                ToolDetailValue.BooleanValue(true),
                ToolDetailValue.NumberValue(3.0),
                ToolDetailValue.Text("short"),
                ToolDetailValue.Text(longText),
            ),
        )

        val presentation = toolDetailPresentation(generic(arguments = arguments))
        val values = (presentation.request as ToolDetailValue.ListValue).items

        assertEquals("null", toolDetailValueText(values[0]))
        assertEquals("true", toolDetailValueText(values[1]))
        assertEquals("3", toolDetailValueText(values[2]))
        assertEquals("short", toolDetailValueText(values[3]))
        assertEquals(longText, toolDetailValueText(values[4]))
        assertNull(toolDetailValueText(ToolDetailValue.ObjectValue(emptyList())))
    }

    @Test
    fun `command maps request and response by typed fields`() {
        val detail = ToolDetailEnvelope.Command(
            schemaVersion = 1,
            callId = "command-1",
            createdAt = CREATED_AT,
            truncated = false,
            originalLength = 20,
            command = "printf safe",
            output = "safe output",
            exitCode = 0,
            status = "completed",
        )

        val presentation = toolDetailPresentation(detail)
        val requestFields = (presentation.request as ToolDetailValue.ObjectValue).fields
        val responseFields = (presentation.response as ToolDetailValue.ObjectValue).fields

        assertEquals(listOf("command"), requestFields.map { it.key })
        assertEquals(listOf("output", "exit_code", "status"), responseFields.map { it.key })
        assertFalse(presentation.isError)
    }

    @Test
    fun `tool errors and truncation remain presentation state`() {
        val genericError = toolDetailPresentation(generic(isError = true, truncated = true))
        val commandError = toolDetailPresentation(
            ToolDetailEnvelope.Command(1, "command-1", CREATED_AT, false, 10, "false", "", 1, "failed"),
        )

        assertTrue(genericError.isError)
        assertTrue(genericError.truncated)
        assertTrue(commandError.isError)
    }

    private fun generic(
        arguments: ToolDetailValue? = null,
        result: ToolDetailValue? = null,
        isError: Boolean? = false,
        truncated: Boolean = false,
    ) = ToolDetailEnvelope.GenericTool(
        schemaVersion = 1,
        callId = "future-call-1",
        createdAt = CREATED_AT,
        truncated = truncated,
        originalLength = 100,
        arguments = arguments,
        result = result,
        isError = isError,
    )

    private companion object {
        const val CREATED_AT = "2026-10-01T00:00:00.000Z"
    }
}
