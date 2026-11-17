package cn.weiguang.app

import cn.weiguang.app.capture.CaptureSchedule
import org.junit.Assert.*
import org.junit.Test

class CaptureScheduleTest {
    @Test fun idleDoesNotPollScreens() {
        assertNull(CaptureSchedule().delay(1000))
    }
    @Test fun burstsCoalesceUntilPageSettles() {
        val gate = CaptureSchedule()
        gate.request(1000); gate.request(1200)
        assertEquals(350L, gate.delay(1200))
        assertEquals(0L, gate.delay(1550))
        gate.start(1550)
        assertNull(gate.delay(1550))
    }
    @Test fun continuousMessagesCannotPostponeCaptureForever() {
        val gate = CaptureSchedule()
        for (now in 0L..1200L step 100) gate.request(now)
        assertEquals(0L, gate.delay(1200))
    }
    @Test fun frameQueuedWhileBusyIsStillReadyAfterCompletion() {
        val gate = CaptureSchedule()
        gate.request(0); gate.start(350)
        gate.request(500); gate.request(700)
        assertEquals(1150L, gate.delay(700))
        assertEquals(0L, gate.delay(3000))
        gate.start(3000)
        assertNull(gate.delay(3000))
    }
    @Test fun cooldownIsBasedOnActualScreenshotStart() {
        val gate = CaptureSchedule()
        gate.request(0); gate.start(350)
        gate.request(400)
        assertEquals(1100L, gate.delay(750))
    }
    @Test fun minuteBudgetDefersLatestFrameInsteadOfDroppingIt() {
        val gate = CaptureSchedule(maxPerMinute = 2)
        gate.request(0); gate.start(350)
        gate.request(1500); gate.start(1850)
        gate.request(2000)
        assertEquals(57_350L, gate.delay(3000))
        assertEquals(0L, gate.delay(60_350))
        gate.start(60_350)
        assertNull(gate.delay(60_350))
    }
    @Test fun pauseCancelsPendingButDoesNotResetRateBudget() {
        val gate = CaptureSchedule()
        gate.request(0); gate.start(350)
        gate.request(400); gate.cancel()
        assertNull(gate.delay(1000))
        gate.request(1000)
        assertEquals(850L, gate.delay(1000))
    }
}
