package com.chronocapsule

import com.chronocapsule.contract.ScreenContract
import com.chronocapsule.dedup.CrossScreenDeduper
import com.chronocapsule.ocr.OcrBox
import com.chronocapsule.ocr.OcrLine
import com.chronocapsule.struct.ScreenStructurer
import org.junit.Assert.*
import org.junit.Test
import java.time.OffsetDateTime
import java.time.ZoneId

class StructureContractTest {
    @Test fun structuredChatPreservesSpeakerTimeAndNormalizedBounds() {
        val lines = listOf(
            OcrLine("小林",.99f,OcrBox(200,180,400,240)),
            OcrLine("12:00",.99f,OcrBox(430,380,600,420)),
            OcrLine("周五在A302讨论",.99f,OcrBox(100,550,650,610)))
        val result = ScreenStructurer.structure(lines,1080,1920)
        val payload = ScreenContract.build(result,1080,1920,"sess-test","evt-test",
            OffsetDateTime.parse("2026-11-13T13:00:00+08:00"),"com.tencent.mm","微信",ZoneId.of("Asia/Shanghai"))
        assertTrue(payload.validate().isEmpty())
        assertEquals(1,payload.blocks.size)
        assertEquals("小林",payload.blocks.single().speaker)
        assertEquals("2026-11-13T12:00:00+08:00",payload.blocks.single().messageAt)
        assertEquals("周五在A302讨论",payload.blocks.single().text)
        assertTrue(payload.blocks.single().bbox!!.all { it in 0f..1000f })
        assertEquals(payload,ScreenContract.Payload.fromJson(payload.toJson()))
    }

    @Test fun wrappedMessagesKeepTheirBubbleSpeakerAndTimestamp() {
        fun line(text: String, x: Int, y: Int, width: Int) =
            OcrLine(text, .99f, OcrBox(x, y, x + width, y + 27))
        val lines = listOf(
            line("阿字", 320, 108, 80), line("11月28日 15:35", 274, 195, 176),
            line("不好意思我周日临时要加班，周日那顿先", 83, 250, 505),
            line("取消哈", 83, 287, 84), line("11月28日 15:38", 274, 661, 176),
            line("他家双人套餐188，一人一半吧，我到时", 143, 720, 493),
            line("转你", 143, 757, 55), line("好", 84, 822, 28))
        val result = ScreenStructurer.structure(lines, 720, 1280)
        val payload = ScreenContract.build(result, 720, 1280, "test-session", "test-event",
            OffsetDateTime.parse("2026-11-28T16:00:00+08:00"))
        assertEquals(3, payload.blocks.size)
        assertEquals("不好意思我周日临时要加班，周日那顿先取消哈", payload.blocks[0].text)
        assertEquals("他家双人套餐188，一人一半吧，我到时转你", payload.blocks[1].text)
        assertEquals("我", payload.blocks[1].speaker)
        val renamed = payload.withConversationNames("小桃", "阿宁")
        assertEquals("阿宁", renamed.pageTitle)
        assertEquals(listOf("阿宁", "小桃", "阿宁"), renamed.blocks.map { it.speaker })
        assertEquals(payload.blocks.map { it.messageAt }, renamed.blocks.map { it.messageAt })
        assertEquals(payload.blocks.map { it.bbox }, renamed.blocks.map { it.bbox })
    }

    @Test fun repeatedWordsAtDifferentKnownTimesAreNewEvidence() {
        CrossScreenDeduper.reset()
        val first=ScreenContract.Block("one","chat","小林","不来了","2026-11-13T12:00:00+08:00",null)
        assertEquals(1,CrossScreenDeduper.filter(listOf(first),"wx","项目组").blocks.size)
        assertTrue(CrossScreenDeduper.filter(listOf(first),"wx","项目组").blocks.isEmpty())
        assertEquals(1,CrossScreenDeduper.filter(listOf(first.copy(messageAt="2026-11-14T12:00:00+08:00")),"wx","项目组").blocks.size)
    }
}
