package cn.weiguang.app.capture

/** Coalesce changes without starving a busy chat; keep the latest request at the rate limit. */
class CaptureSchedule(
    private val settleMs: Long = 350,
    private val maxWaitMs: Long = 1200,
    private val minIntervalMs: Long = 1500,
    private val maxPerMinute: Int = 20
) {
    private var firstRequest: Long? = null
    private var settledAt = 0L
    private val started = ArrayDeque<Long>()

    fun request(now: Long) {
        val first = firstRequest ?: now.also { firstRequest = it }
        settledAt = minOf(now + settleMs, first + maxWaitMs)
    }

    fun delay(now: Long): Long? {
        if (firstRequest == null) return null
        while (started.isNotEmpty() && now - started.first() >= 60_000) started.removeFirst()
        val intervalAt = started.lastOrNull()?.plus(minIntervalMs) ?: now
        val budgetAt = if (started.size >= maxPerMinute) started.first() + 60_000 else now
        return (maxOf(settledAt, intervalAt, budgetAt) - now).coerceAtLeast(0)
    }

    fun start(now: Long) {
        check(delay(now) == 0L)
        started.addLast(now)
        cancel()
    }

    fun cancel() { firstRequest = null }
}
