package cn.weiguang.app

import cn.weiguang.app.ui.ReadableText
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.ZoneId

class ReadableTextTest {
    @Test fun legacyScheduleShowsTheRecordedDateAndTitleWithoutInternalFields() {
        val stored = """{"schedule_changes":[{"id":"meeting","title":"组会","at":"2026-12-10T18:00:00+08:00"},{"id":"follow-up","title":"回访","status":"completed"}]}"""
        val expected = "组会，${ReadableText.scheduleTime("2026-12-10T18:00:00+08:00")}\n回访，已完成"
        assertEquals(expected, ReadableText.clue(stored))
        assertEquals(expected, ReadableText.editable("```json\n$stored\n```"))
    }

    @Test fun normalCluesKeepTheirWording() {
        val clue = "周五 18:30 在 A302 讨论项目。"
        assertEquals(clue, ReadableText.clue(clue))
    }

    @Test fun unreadableObjectsNeverFallBackToTheirRawText() {
        for (raw in listOf("""{"debug":["private"]}""", """[{"id":42}]""", """{"schedule_changes":""", "{")) {
            assertEquals(ReadableText.UnreadableClue, ReadableText.clue(raw))
            assertEquals("", ReadableText.editable(raw))
        }
    }

    @Test fun scheduleResultLocalizesTimeAndProgress() {
        val time = ReadableText.scheduleTime("2026-12-10T18:00:00+08:00")
        assertEquals("$time · 待完成",
            ReadableText.text("2026-12-10T18:00:00+08:00 · pending · 无先后约束"))
        assertEquals("$time · 待完成 · 在读书会之前", ReadableText.text("2026-12-10T18:00:00+08:00 · pending · 须早于：读书会"))
        assertEquals("组会改到${time}。", ReadableText.text("组会改到2026-12-10T18:00:00+08:00。"))
        assertEquals("待完成", ReadableText.text("pending"))
        assertEquals("2026年12月10日 10:00",
            ReadableText.dateTime("2026-12-10T18:00:00+08:00", ZoneId.of("UTC")))
        assertEquals("时间待确认", ReadableText.dateTime("unknown"))
    }

    @Test fun travellingReadersSeeTheInvitedTimeAndTheirOwnDate() {
        val input = "2027-02-08T01:05:12+05:45"
        val original = "2027年2月8日 01:05:12（UTC+05:45）"
        assertEquals(original, ReadableText.scheduleTime(input, ZoneId.of("Asia/Kathmandu")))
        assertEquals("$original\n本机时间 2027年2月7日 11:20:12", ReadableText.scheduleTime(input, ZoneId.of("America/Los_Angeles")))
        assertEquals("$original\n本机时间 2027年2月8日 03:20:12", ReadableText.scheduleTime(input, ZoneId.of("Asia/Taipei")))
        assertEquals("2027年2月8日 09:05:12", ReadableText.dateTime("2027-02-08T09:05:12"))
        assertEquals("2027年2月8日 09:05（协调世界时）", ReadableText.scheduleTime("2027-02-08T09:05:00Z",ZoneId.of("UTC")))
    }

    @Test fun technicalErrorsUseAnActionableFallback() {
        assertEquals("请稍后重试。", ReadableText.error("""{"detail":"invalid request"}""", "请稍后重试。"))
        assertEquals("请稍后重试。", ReadableText.error("请求失败（403），请检查输入或服务端状态", "请稍后重试。"))
        assertEquals("请填写事项名称", ReadableText.error("请填写事项名称", "请稍后重试。"))
    }
}
