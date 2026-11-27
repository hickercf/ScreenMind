package cn.weiguang.app

import cn.weiguang.app.data.ApkTransfer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okhttp3.OkHttpClient
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.Proxy
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketException
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

class ApkTransferTest {
    @get:Rule val temp = TemporaryFolder()
    private val payload = ByteArray(240_000) { ((it * 43) % 251).toByte() }
    private val transfer = ApkTransfer(OkHttpClient.Builder().proxy(Proxy.NO_PROXY).readTimeout(3, TimeUnit.SECONDS).build())

    private class Exchange(socket: Socket) {
        val range: String?
        val responseBody = socket.getOutputStream()
        var contentRange: String? = null

        init {
            val reader = socket.getInputStream().bufferedReader()
            val lines = generateSequence { reader.readLine()?.takeIf { it.isNotEmpty() } }.toList()
            range = lines.firstOrNull { it.startsWith("Range:", ignoreCase = true) }?.substringAfter(':')?.trim()
        }

        fun sendResponseHeaders(code: Int, length: Long) {
            val headers = buildString {
                append("HTTP/1.1 $code Response\r\nConnection: close\r\nContent-Length: $length\r\n")
                contentRange?.let { append("Content-Range: $it\r\n") }
                append("\r\n")
            }
            responseBody.write(headers.toByteArray(Charsets.US_ASCII))
        }
    }

    private fun withServer(handler: (Exchange) -> Unit, test: (String) -> Unit) {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        val worker = thread(isDaemon = true) {
            while (!server.isClosed) {
                val connection = try { server.accept() } catch (error: SocketException) {
                    if (server.isClosed) break else throw error
                }
                connection.use { handler(Exchange(it)) }
            }
        }
        try { test("http://127.0.0.1:${server.localPort}/apk") } finally {
            server.close()
            worker.join(3_000)
        }
    }

    private fun Exchange.sendPart(start: Int, end: Int = payload.size) {
        contentRange = "bytes $start-${end - 1}/${payload.size}"
        sendResponseHeaders(206, (end - start).toLong())
        responseBody.write(payload, start, end - start)
    }

    private fun Exchange.sendWhole() {
        sendResponseHeaders(200, payload.size.toLong())
        responseBody.write(payload)
    }

    private fun receive(url: String, file: File) = runBlocking {
        transfer.receive(url, payload.size.toLong(), file) {}
    }

    @Test fun connectionLostThenResumedAtTheFirstMissingByte() {
        val ranges = CopyOnWriteArrayList<String>()
        val partial = temp.newFile()
        withServer({ exchange ->
            val range = exchange.range
            ranges.add(range.orEmpty())
            if (range == null) {
                exchange.sendResponseHeaders(200, payload.size.toLong())
                exchange.responseBody.write(payload, 0, 63_000)
            } else exchange.sendPart(63_000)
        }) { url ->
            assertThrows(IOException::class.java) { receive(url, partial) }
            assertEquals(63_000L, partial.length())
            receive(url, partial)
            assertArrayEquals(payload, partial.readBytes())
            assertEquals(listOf("", "bytes=63000-"), ranges.toList())
        }
    }

    @Test fun pausingClosesAStalledConnectionAndKeepsItsBytes() {
        val partial = temp.newFile()
        val releaseServer = CountDownLatch(1)
        withServer({ exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.write(payload, 0, 32_000)
            exchange.responseBody.flush()
            releaseServer.await(5, TimeUnit.SECONDS)
        }) { url ->
            try {
                runBlocking {
                    val received = CompletableDeferred<Unit>()
                    val job = launch {
                        transfer.receive(url, payload.size.toLong(), partial) { if (it == 32_000L) received.complete(Unit) }
                    }
                    withTimeout(2_000) { received.await(); job.cancelAndJoin() }
                }
                assertArrayEquals(payload.copyOf(32_000), partial.readBytes())
            } finally { releaseServer.countDown() }
        }
    }

    @Test fun fullResponseToARangeReplacesThePrefix() {
        val partial = temp.newFile().apply { writeBytes(ByteArray(1_092)) }
        val ranges = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            ranges.add(exchange.range.orEmpty())
            exchange.sendWhole()
        }) { url ->
            receive(url, partial)
            assertEquals(listOf("bytes=1092-"), ranges.toList())
            assertArrayEquals(payload, partial.readBytes())
        }
    }

    @Test fun invalidAndUnsatisfiableRangesRetryOnceWithoutAPrefix() {
        for (status in listOf(206, 416)) {
            val partial = temp.newFile().apply { writeBytes(payload.copyOf(11_000)) }
            val ranges = CopyOnWriteArrayList<String>()
            withServer({ exchange ->
                val range = exchange.range
                ranges.add(range.orEmpty())
                when {
                    range == null -> exchange.sendWhole()
                    status == 416 -> exchange.sendResponseHeaders(416, 0)
                    else -> exchange.sendPart(4_000)
                }
            }) { url ->
                receive(url, partial)
                assertEquals(listOf("bytes=11000-", ""), ranges.toList())
                assertArrayEquals(payload, partial.readBytes())
            }
        }
    }

    @Test fun consecutiveShortRangesAndACompleteFileRequireOnlyMissingBytes() {
        val partial = temp.newFile().apply { writeBytes(payload.copyOf(100_000)) }
        val ranges = CopyOnWriteArrayList<String>()
        withServer({ exchange ->
            val range = requireNotNull(exchange.range)
            ranges.add(range)
            val start = range.removePrefix("bytes=").removeSuffix("-").toInt()
            exchange.sendPart(start, minOf(start + 80_000, payload.size))
        }) { url ->
            receive(url, partial)
            receive(url, partial)
            assertEquals(listOf("bytes=100000-", "bytes=180000-"), ranges.toList())
            assertArrayEquals(payload, partial.readBytes())
        }
    }
}
