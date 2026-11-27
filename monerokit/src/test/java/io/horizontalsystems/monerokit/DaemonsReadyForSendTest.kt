package io.horizontalsystems.monerokit

import org.junit.Assert.assertEquals
import org.junit.Test

class DaemonsReadyForSendTest {

    private fun ping(name: String, ms: Double, height: Long, valid: Boolean = true) =
        NodePingResult(name, ms, height, valid)

    @Test
    fun fastestFirst() {
        val pings = listOf(ping("slow", 400.0, 1000), ping("fast", 90.0, 1000), ping("mid", 200.0, 1000))

        assertEquals(listOf("fast", "mid", "slow"), daemonsReadyForSend(pings, walletHeight = 1000))
    }

    @Test
    fun daemonThatDidNotAnswer_isLeftOut() {
        val pings = listOf(
            ping("invalid", 50.0, 1000, valid = false),
            ping("unreachable", Double.MAX_VALUE, 0),
            ping("ok", 300.0, 1000),
        )

        assertEquals(listOf("ok"), daemonsReadyForSend(pings, walletHeight = 1000))
    }

    @Test
    fun daemonBehindTheOthers_isLeftOut_evenWhenFastest() {
        val pings = listOf(ping("behind", 20.0, 1000 - MAX_BLOCKS_BEHIND - 1), ping("current", 300.0, 1000))

        assertEquals(listOf("current"), daemonsReadyForSend(pings, walletHeight = 0))
    }

    @Test
    fun daemonAFewBlocksBack_isKept() {
        val pings = listOf(ping("a-little-back", 20.0, 1000 - MAX_BLOCKS_BEHIND), ping("current", 300.0, 1000))

        assertEquals(listOf("a-little-back", "current"), daemonsReadyForSend(pings, walletHeight = 1000))
    }

    @Test
    fun onlyDaemonThatAnswers_isLeftOut_whenTheWalletIsAheadOfIt() {
        val pings = listOf(ping("behind", 20.0, 900))

        assertEquals(emptyList<String>(), daemonsReadyForSend(pings, walletHeight = 1000))
    }

    @Test
    fun walletBehindTheDaemons_doesNotLeaveThemOut() {
        val pings = listOf(ping("current", 20.0, 1000))

        assertEquals(listOf("current"), daemonsReadyForSend(pings, walletHeight = 400))
    }

    @Test
    fun noPings_noDaemons() {
        assertEquals(emptyList<String>(), daemonsReadyForSend(emptyList(), walletHeight = 1000))
    }
}
