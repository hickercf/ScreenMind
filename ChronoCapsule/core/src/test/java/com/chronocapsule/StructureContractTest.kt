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

    @Test fun repeatedWordsAtDifferentKnownTimesAreNewEvidence() {
        CrossScreenDeduper.reset()
        val first=ScreenContract.Block("one","chat","小林","不来了","2026-11-13T12:00:00+08:00",null)
        assertEquals(1,CrossScreenDeduper.filter(listOf(first),"wx","项目组").blocks.size)
        assertTrue(CrossScreenDeduper.filter(listOf(first),"wx","项目组").blocks.isEmpty())
        assertEquals(1,CrossScreenDeduper.filter(listOf(first.copy(messageAt="2026-11-14T12:00:00+08:00")),"wx","项目组").blocks.size)
    }
}
