package io.horizontalsystems.monerokit

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.net.HostAndPort
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread

/**
 * A daemon that takes a request and never answers it holds the wallet's daemon locks until
 * the request times out, three and a half minutes later. Moving the wallet to another daemon
 * and closing it must not wait for that.
 *
 * The daemons are the test's own, on the device's loopback. The last test passes a real
 * daemon through one of them and needs its address:
 * `-Pandroid.testInstrumentationRunnerArguments.upstream=host:port`
 */
@RunWith(AndroidJUnit4::class)
class SilentDaemonTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val daemons = mutableListOf<TestDaemon>()
    private val watchers = mutableListOf<Thread>()
    private var kit: MoneroKit? = null

    @After
    fun tearDown() {
        watchers.forEach { it.interrupt() }
        // a closed connection lets go of the wallet whatever the kit does about a silent one
        daemons.forEach { it.close() }
        kit?.stop()
        await("the wallet to close", 300_000) { !WalletService.running }
    }

    @Test
    fun nodeChange_doesNotWaitForARequestTheDaemonNeverAnswers() {
        leavesWithoutWaiting(SEED, next = TestDaemon.Mode.ThereButSilentOnSync)
    }

    // Only a wallet that has not synced yet asks the daemon it is moved to before the move is
    // over. A watch-only wallet never does, so it gets onto a daemon that is silent from the
    // first byte, and is left waiting in the TLS handshake there
    @Test
    fun nodeChange_doesNotWaitForAHandshakeTheDaemonNeverAnswers() {
        leavesWithoutWaiting(watchOnly(), next = TestDaemon.Mode.SilentFromTheFirstByte)
    }

    // An address nothing answers from, not even with a refusal: the connect itself waits.
    // A watch-only wallet, so that the move onto that address does not ask it first
    @Test
    fun nodeChange_doesNotWaitForAConnectThatNeverCompletes() {
        assumeTrue("$NOWHERE refuses at once on this network", connectHangs())
        val first = daemon()
        val third = daemon()
        val kit = startOn(first, seed = watchOnly())
        awaitLeftWaiting(kit, first)

        runBlocking { kit.setNode("$NOWHERE/mainnet/nowhere", false) }
        // the sync round on the new address starts at once and goes into the connect
        Thread.sleep(1_500)

        val took = timed("setNode") { runBlocking { kit.setNode(third.node, false) } }
        assertTrue("leaving a connect that never completes took $took ms", took < PROMPT_MS)

        awaitLeftWaiting(kit, third)
        stopsWithoutWaiting(kit)
    }

    @Test
    fun stop_doesNotWaitForARequestTheDaemonNeverAnswers() {
        val daemon = daemon()
        val kit = startOn(daemon)
        awaitLeftWaiting(kit, daemon)

        stopsWithoutWaiting(kit)
    }

    // A wallet that has synced nothing asks its daemon while it is initialised. Any other
    // asks only for its status afterwards; a watch-only wallet stands in for those
    @Test
    fun nodeChange_doesNotWaitForAStartThatWaitsForItsDaemon() {
        leavesAStartWithoutWaiting(SEED)
    }

    @Test
    fun nodeChange_doesNotWaitForAStartThatWaitsForItsDaemon_watchOnly() {
        leavesAStartWithoutWaiting(watchOnly())
    }

    @Test
    fun stop_doesNotWaitForAStartThatWaitsForItsDaemon() {
        val daemon = daemon().apply { mode = TestDaemon.Mode.Silent }
        val kit = startOn(daemon)
        awaitLeftWaiting(kit, daemon)

        stopsWithoutWaiting(kit)
    }

    @Test
    fun sync_goesOnOnTheNextDaemon_afterOneWentSilentHalfWay() {
        val upstream = upstream()
        val first = daemon(upstream).apply { mode = TestDaemon.Mode.Relay }
        val second = daemon(upstream).apply { mode = TestDaemon.Mode.Relay }

        val kit = startOn(first, restoreFrom = argument("restore") ?: "2026-10-01")
        Log.i(TAG, "syncing through $upstream")
        val states = watch(kit)

        await("blocks to come in", 180_000) { started(kit) && progressed(states) }

        first.mode = TestDaemon.Mode.Silent
        awaitLeftWaiting(kit, first)
        val left = states.mapNotNull { remaining(it) }.last()
        Log.i(TAG, "the daemon went silent with $left blocks left")
        states.clear()

        val took = timed("setNode") { runBlocking { kit.setNode(second.node, false) } }
        assertTrue("the node change took $took ms", took < PROMPT_MS)

        await("blocks to come in from the next daemon", 120_000) {
            states.any { it is SyncState.Synced || (remaining(it) ?: Long.MAX_VALUE) < left }
        }
        Log.i(TAG, "after the node change: ${states.map { it.description }}")
        val failures = states.filterIsInstance<SyncState.NotSynced>()
        assertTrue("the node change was told as a failed sync: $failures", failures.isEmpty())

        second.mode = TestDaemon.Mode.Silent
        awaitLeftWaiting(kit, second)
        stopsWithoutWaiting(kit)
    }

    private fun leavesAStartWithoutWaiting(seed: Seed) {
        val first = daemon().apply { mode = TestDaemon.Mode.Silent }
        val second = daemon()
        val kit = startOn(first, seed = seed)
        val states = watch(kit)

        awaitLeftWaiting(kit, first)
        val took = timed("setNode") { runBlocking { kit.setNode(second.node, false) } }
        assertTrue("leaving a start on a silent daemon took $took ms", took < PROMPT_MS)

        // the wallet comes up on the daemon it was moved to
        awaitLeftWaiting(kit, second)
        Log.i(TAG, "after the node change: ${states.map { it.description }}")
        val failures = states.filterIsInstance<SyncState.NotSynced>().filter { it.error !is MoneroKit.SyncError.NotStarted }
        assertTrue("the node change was told as a failed start: $failures", failures.isEmpty())

        stopsWithoutWaiting(kit)
    }

    private fun leavesWithoutWaiting(seed: Seed, next: TestDaemon.Mode) {
        val first = daemon()
        val second = daemon().apply { mode = next }
        val third = daemon().apply { mode = next }
        val kit = startOn(first, seed = seed)

        awaitLeftWaiting(kit, first)
        val took = timed("setNode") { runBlocking { kit.setNode(second.node, false) } }
        assertTrue("leaving a silent daemon took $took ms", took < PROMPT_MS)

        // the wallet is on the daemon it was moved to, and is left waiting there in turn
        awaitLeftWaiting(kit, second)
        val tookAgain = timed("setNode") { runBlocking { kit.setNode(third.node, false) } }
        assertTrue("leaving the second silent daemon took $tookAgain ms", tookAgain < PROMPT_MS)

        awaitLeftWaiting(kit, third)
        stopsWithoutWaiting(kit)
    }

    private fun stopsWithoutWaiting(kit: MoneroKit) {
        val took = timed("stop") {
            kit.stop()
            await("the wallet to close", 300_000) { !WalletService.running }
        }
        assertTrue("closing the wallet on a silent daemon took $took ms", took < PROMPT_MS)
    }

    private fun startOn(daemon: TestDaemon, seed: Seed = SEED, restoreFrom: String = "2026-10-01"): MoneroKit {
        val kit = MoneroKit.getInstance(
            context, seed, restoreFrom, "silent-daemon-${System.nanoTime()}", daemon.node, false
        )
        this.kit = kit
        kit.start()
        return kit
    }

    // False while it starts. A wallet that could not be opened on its daemon fails the test
    private fun started(kit: MoneroKit): Boolean = when (val state = kit.syncStateFlow.value) {
        is SyncState.Connecting -> false
        is SyncState.NotSynced -> when (val error = state.error) {
            is MoneroKit.SyncError.NotStarted -> false
            is MoneroKit.SyncError -> throw AssertionError("the wallet did not start: ${error.message}")
            else -> true
        }
        else -> true
    }

    // Until the daemon has taken a request it does not answer and the wallet waits for it
    private fun awaitLeftWaiting(kit: MoneroKit, daemon: TestDaemon) {
        await("the wallet to ask the daemon on ${daemon.port}", 60_000) {
            started(kit)
            daemon.withheld.get() > 0
        }
        Log.i(TAG, "the daemon on ${daemon.port} keeps: ${daemon.unanswered}")
        // the request is out; let the wallet get to where it waits for the answer
        Thread.sleep(SETTLE_MS)
    }

    // Every state the kit tells from here on
    private fun watch(kit: MoneroKit): MutableList<SyncState> {
        val states = CopyOnWriteArrayList<SyncState>()
        watchers += thread(isDaemon = true) {
            try {
                runBlocking { kit.syncStateFlow.collect { states += it } }
            } catch (e: InterruptedException) {
                // the test is over
            }
        }
        return states
    }

    private fun watchOnly() = Seed.WatchOnly(MoneroKit.getAddress(SEED, 0, 0), MoneroKit.getKeys(SEED).privateViewKey)

    private fun daemon(upstream: InetSocketAddress? = null) = TestDaemon(upstream).also { daemons += it }

    private fun upstream(): InetSocketAddress {
        val given = argument("upstream")
        assumeTrue("no upstream daemon given", given != null)
        val hostAndPort = HostAndPort.fromString(given!!).withDefaultPort(18081)
        val address = InetSocketAddress(hostAndPort.host, hostAndPort.port)
        val answers = runCatching { Socket().use { it.connect(address, 10_000) } }.isSuccess
        assumeTrue("$given is not reachable from the device", answers)
        return address
    }

    private fun connectHangs(): Boolean = try {
        Socket().use { it.connect(InetSocketAddress(NOWHERE.substringBefore(':'), 18081), 3_000) }
        false
    } catch (e: java.net.SocketTimeoutException) {
        true
    } catch (e: java.io.IOException) {
        false
    }

    private fun argument(name: String): String? = InstrumentationRegistry.getArguments().getString(name)

    private fun remaining(state: SyncState?): Long? = (state as? SyncState.Syncing)?.remainingBlocks

    private fun progressed(states: List<SyncState>): Boolean =
        states.mapNotNull { remaining(it) }.distinct().size > 2

    // Runs [block] and says what the kit's threads were doing if it was not over soon
    private fun timed(what: String, block: () -> Unit): Long {
        val started = System.currentTimeMillis()
        val watcher = thread(isDaemon = true) {
            try {
                Thread.sleep(PROMPT_MS)
                Log.w(TAG, "$what is not over after $PROMPT_MS ms:\n${kitThreads()}")
            } catch (e: InterruptedException) {
                // over in time
            }
        }
        block()
        watcher.interrupt()
        return (System.currentTimeMillis() - started).also { Log.i(TAG, "$what took $it ms") }
    }

    private fun kitThreads(): String = Thread.getAllStackTraces()
        .filter { (thread, stack) ->
            thread != Thread.currentThread() && stack.any { it.className.startsWith("io.horizontalsystems.monerokit.") && "Test" !in it.className }
        }
        .entries.joinToString("\n") { (thread, stack) ->
            "  ${thread.name} (${thread.state})\n" + stack.take(12).joinToString("\n") { "      at $it" }
        }

    private fun await(what: String, timeoutMs: Long, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            if (System.currentTimeMillis() > deadline) fail("waited $timeoutMs ms for $what")
            Thread.sleep(50)
        }
    }

    private companion object {
        const val TAG = "SilentDaemonTest"

        // What "without waiting" is held to. A request times out after 210 000 ms and a
        // handshake after 20 000. A node change itself takes 0.5 to 4 s on an emulator: the
        // engine makes a new TLS client certificate, with a 4096-bit RSA key, for every daemon
        // it is given
        const val PROMPT_MS = 10_000L
        const val SETTLE_MS = 500L

        // TEST-NET-1, which is routed nowhere
        const val NOWHERE = "192.0.2.1:18081"

        // The BIP39 test vector: a wallet nobody keeps funds in
        val SEED = Seed.Bip39(List(11) { "abandon" } + "about", "")
    }
}
