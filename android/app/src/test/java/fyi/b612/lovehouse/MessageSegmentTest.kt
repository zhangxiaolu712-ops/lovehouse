package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.chat.naturalMessageSegments
import org.junit.Assert.assertEquals
import org.junit.Test

class MessageSegmentTest {
    @Test fun paragraphSegmentsStayInsideOneMessage() {
        assertEquals(listOf("第一段", "第二段\n仍属于第二段"), "第一段\n\n第二段\n仍属于第二段".naturalMessageSegments())
    }

    @Test fun plainAndBlankMessagesRemainStable() {
        assertEquals(listOf("短消息"), "短消息".naturalMessageSegments())
        assertEquals(listOf(""), "".naturalMessageSegments())
    }
}
