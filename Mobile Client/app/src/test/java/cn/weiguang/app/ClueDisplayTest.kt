package cn.weiguang.app

import cn.weiguang.app.data.clueDisplayText
import org.junit.Assert.assertEquals
import org.junit.Test

class ClueDisplayTest {
    @Test fun scheduleChangesShowNaturalLanguage() {
        val raw = """{"schedule_changes":[{"id":"meeting","title":"组会","at":"2026-12-10T18:00:00+08:00"},{"title":"核对设备"}]}"""
        assertEquals("组会 安排在 2026年12月10日 18:00\n核对设备", clueDisplayText(raw))
    }

    @Test fun unknownStructuredClueUsesReadableFallback() {
        assertEquals("这条线索暂无可显示的摘要。", clueDisplayText("""{"internal_status":409}"""))
    }

    @Test fun ordinaryNotesKeepTheirOriginalText() {
        for (note in listOf("周五在 A302 讨论", "[项目记录]", "{提纲} 上午开会", "午饭聊一下 {预算}")) {
            assertEquals(note, clueDisplayText(note))
        }
    }
}
