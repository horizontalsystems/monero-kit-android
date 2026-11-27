package io.horizontalsystems.monerokit

import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A daemon on the device's own loopback that can be told to stop answering.
 *
 * @param upstream a real daemon the connections are passed on to while [mode] is [Mode.Relay].
 */
internal class TestDaemon(private val upstream: InetSocketAddress? = null) : Closeable {

    enum class Mode {
        /**
         * Answers what a wallet asks to see that a daemon is there and synced, and nothing
         * else: the first request of a sync is taken and never answered.
         */
        ThereButSilentOnSync,

        /** Requests are taken and never answered. */
        Silent,

        /** Nothing is answered, a TLS hello included: the wallet waits in the handshake. */
        SilentFromTheFirstByte,

        /** Bytes go to [upstream] and back. */
        Relay,
    }

    @Volatile
    var mode = Mode.ThereButSilentOnSync

    // By number: Android's loopback address is the IPv6 one, and the wallet is given 127.0.0.1
    private val server = ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"))
    private val sockets = CopyOnWriteArrayList<Socket>()

    val port: Int get() = server.localPort

    /** As the kit takes a node. */
    val node: String get() = "127.0.0.1:$port/mainnet/test-$port"

    /** Connections on which bytes were taken and kept: each has the wallet waiting. */
    val withheld = AtomicInteger()

    /** What was asked and not answered, for the test's log. */
    val unanswered = CopyOnWriteArrayList<String>()

    init {
        thread(isDaemon = true, name = "test-daemon-$port") {
            while (!server.isClosed) {
                val client = try {
                    server.accept()
                } catch (e: IOException) {
                    break
                }
                sockets += client
                thread(isDaemon = true, name = "test-daemon-$port-conn") {
                    try {
                        if (upstream != null) relay(client, upstream) else serve(client)
                    } catch (e: IOException) {
                        // either side went away
                    } finally {
                        client.closeQuietly()
                    }
                }
            }
        }
    }

    // A plain HTTP daemon: a wallet that tries TLS first is turned away and comes back without
    private fun serve(client: Socket) {
        val counted = AtomicBoolean()
        val input = client.getInputStream().buffered()

        input.mark(1)
        val first = input.read()
        input.reset()
        if (first < 0) return
        if (first == TLS_HANDSHAKE) {
            Log.i(TAG, "$port: TLS hello, $mode")
            if (mode != Mode.SilentFromTheFirstByte) return
            keep(counted, "TLS hello")
            while (input.read() >= 0) Unit
            return
        }

        while (true) {
            val request = readRequest(input) ?: return
            val answer = if (mode == Mode.ThereButSilentOnSync) answerTo(request) else null
            Log.i(TAG, "$port: ${request.lineSequence().first()} ${method(request).orEmpty()} -> ${if (answer == null) "kept" else "answered"}")
            if (answer == null) {
                keep(counted, request.lineSequence().first() + " " + method(request).orEmpty())
                continue
            }
            client.getOutputStream().apply {
                write("HTTP/1.1 200 Ok\r\nContent-Type: application/json\r\nContent-Length: ${answer.length}\r\n\r\n$answer".toByteArray())
                flush()
            }
        }
    }

    private fun answerTo(request: String): String? = when (method(request)) {
        "get_version" -> result(
            """"version":${3 shl 16 or 16},"release":true,"current_height":$HEIGHT,"target_height":$HEIGHT,""" +
                    """"hard_forks":[${(1..MAINNET_HARD_FORKS).joinToString(",") { """{"hf_version":$it,"height":$it}""" }}]"""
        )

        "get_info" -> result(
            """"height":$HEIGHT,"target_height":$HEIGHT,"mainnet":true,"nettype":"mainnet",""" +
                    """"block_size_limit":600000,"block_weight_limit":600000,"synchronized":true,""" +
                    """"adjusted_time":${System.currentTimeMillis() / 1000},"credits":0,"top_hash":"""""
        )

        else -> null
    }

    private fun result(fields: String) = """{"id":"0","jsonrpc":"2.0","result":{$fields,"status":"OK","untrusted":false}}"""

    private fun method(request: String): String? =
        Regex(""""method"\s*:\s*"([a-z_]+)"""").find(request)?.groupValues?.get(1)

    // Headers and body of one request, or null when the wallet closed the connection
    private fun readRequest(input: InputStream): String? {
        val head = ByteArrayOutputStream()
        while (!head.toString("ISO-8859-1").endsWith("\r\n\r\n")) {
            val byte = input.read()
            if (byte < 0) return null
            head.write(byte)
        }
        val headers = head.toString("ISO-8859-1")
        val length = Regex("(?i)content-length:\\s*(\\d+)").find(headers)?.groupValues?.get(1)?.toInt() ?: 0
        val body = ByteArray(length)
        var read = 0
        while (read < length) {
            val n = input.read(body, read, length - read)
            if (n < 0) return null
            read += n
        }
        return headers + String(body, Charsets.ISO_8859_1)
    }

    private fun relay(client: Socket, upstream: InetSocketAddress) {
        val counted = AtomicBoolean()
        val remote = Socket()
        try {
            remote.connect(upstream, 15_000)
            sockets += remote
            relayBack(remote, client, counted)

            val input = client.getInputStream()
            val buffer = ByteArray(16 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) return
                if (mode != Mode.Relay) {
                    keep(counted, "request to $upstream")
                    continue
                }
                remote.getOutputStream().apply { write(buffer, 0, read); flush() }
            }
        } finally {
            remote.closeQuietly()
        }
    }

    private fun relayBack(remote: Socket, client: Socket, counted: AtomicBoolean) {
        thread(isDaemon = true, name = "test-daemon-$port-back") {
            try {
                val input = remote.getInputStream()
                val buffer = ByteArray(64 * 1024)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    if (mode != Mode.Relay) {
                        keep(counted, "answer from $upstream")
                        continue
                    }
                    client.getOutputStream().apply { write(buffer, 0, read); flush() }
                }
            } catch (e: IOException) {
                // either side went away
            } finally {
                // while it is silent the wallet is left waiting, as on a daemon that hangs
                if (mode == Mode.Relay) client.closeQuietly()
                remote.closeQuietly()
            }
        }
    }

    private fun keep(counted: AtomicBoolean, what: String) {
        if (!counted.compareAndSet(false, true)) return
        unanswered += what
        withheld.incrementAndGet()
    }

    override fun close() {
        server.closeQuietly()
        sockets.forEach { it.closeQuietly() }
    }

    private fun Closeable.closeQuietly() {
        try {
            close()
        } catch (e: IOException) {
            // closing
        }
    }

    private companion object {
        const val TAG = "TestDaemon"
        const val TLS_HANDSHAKE = 0x16

        // Above the chain's, so a wallet restored from any date has blocks to ask for
        const val HEIGHT = 5_000_000L
        const val MAINNET_HARD_FORKS = 16
    }
}
