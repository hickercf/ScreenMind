package cn.weiguang.app

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import cn.weiguang.app.ui.PlanDeck
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PlanDeckTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun swipeAndKeepConfirmedSeparateUntilTheNextVersionArrives() {
        fun cards(count: Int, fare: String) = JSONArray().put(card("venue", "聚会安排", "湖畔餐厅"))
            .put(card("attendees", "参与情况", "${count} 人参加"))
            .put(card("budget", "人均费用", fare))
            .put(card("duties", "分工进度", "蛋糕待完成"))
        val previous = cards(8, "¥86.00")
        val revised = cards(7, "¥98.29")
        val initial = JSONObject().put("work_id", "work_test").put("title", "周末聚会")
            .put("version", 1).put("confirmed", previous)
            .put("candidate", JSONObject().put("task_id", "task_test").put("revision", 1)
                .put("summary", "小林退出，蛋糕待重新分配").put("cards", revised))
        val plan = mutableStateOf(initial)
        var opened: String? = null
        compose.setContent { MaterialTheme { PlanDeck(plan.value) { opened = it.getString("id") } } }
        compose.onNodeWithTag("plan-page-count").assertTextEquals("1 / 4")
        compose.onNodeWithTag("plan-pager").performTouchInput {
            swipe(Offset(width * .80f, centerY), Offset(width * .28f, centerY), durationMillis = 420)
        }
        compose.onNodeWithTag("plan-page-count").assertTextEquals("2 / 4")
        compose.onNodeWithText("7 人参加").assertIsDisplayed()
        compose.onNodeWithText("下一张").performClick()
        compose.onNodeWithText("¥98.29").assertIsDisplayed()
        compose.onNodeWithTag("plan-confirmed").performClick()
        compose.onNodeWithText("¥86.00").assertIsDisplayed()
        compose.onNodeWithTag("plan-candidate").performClick()
        compose.onNodeWithText("查看依据与确认").performClick()
        compose.runOnIdle { assertEquals("task_test", opened) }
        compose.runOnIdle {
            plan.value = JSONObject(initial.toString()).put("version", 2).put("confirmed", revised)
                .put("candidate", JSONObject.NULL)
        }
        compose.onNodeWithText("已确认 · v2").assertIsDisplayed()
        compose.onNodeWithTag("plan-candidate").assertDoesNotExist()
        compose.onNodeWithText("¥98.29").assertIsDisplayed()
    }

    private fun card(id: String, title: String, value: String) =
        JSONObject().put("id", id).put("title", title).put("value", value)
}
