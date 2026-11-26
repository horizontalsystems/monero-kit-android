package io.horizontalsystems.monerokit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

// The texts are the wallet API's own, from pending_transaction.cpp and wallet.cpp
class BroadcastFailureTest {

    @Test
    fun lostConnectionLeavesOpenWhetherTheDaemonHasIt() {
        val failure = broadcastFailure("no connection to daemon. Please make sure daemon is running.", "tx-1")

        assertEquals(
            Broadcast.Unanswered("tx-1", "Send Transaction failed: no connection to daemon. Please make sure daemon is running."),
            failure,
        )
    }

    @Test
    fun rejectionIsAClearNo() {
        val error = "transaction <aa11> was rejected by daemon with status: Failed. Reason: double spend"

        assertEquals(Broadcast.Refused(Broadcast.Why.Rejected, "Send Transaction failed: $error"), broadcastFailure(error, "aa11"))
    }

    @Test
    fun busyDaemonIsAClearNo() {
        val error = "daemon is busy. Please try again later."

        assertEquals(Broadcast.Refused(Broadcast.Why.DaemonBusy, "Send Transaction failed: $error"), broadcastFailure(error, "tx-1"))
    }

    // An error raised after the daemon answered, or one the wallet has no words for
    @Test
    fun anythingElseLeavesItOpen() {
        listOf("Unknown exception: Bad output index in selected transfers: 7", "Unhandled exception", "", null).forEach {
            assertTrue("$it", broadcastFailure(it, "tx-1") is Broadcast.Unanswered)
        }
    }

    @Test
    fun daemonFailuresWhileBuildingAreTold() {
        listOf(
            "internal error: Failed to get earliest fork height",
            "failed to get random outputs to mix: failed to get random outs",
            "no connection to daemon. Please make sure daemon is running.",
            "daemon is busy. Please try again later.",
            "RPC error: failed to get output distribution",
        ).forEach { assertTrue(it, isDaemonError(it)) }

        listOf(
            "not enough money to transfer, available only 1.000000000000, sent amount 2.000000000000",
            "Invalid destination address",
            "transaction would be too large",
            null,
        ).forEach { assertFalse("$it", isDaemonError(it)) }
    }

    @Test
    fun notEnoughMoneyIsTold() {
        assertTrue(isInsufficientFunds("not enough money to transfer, overall balance only 0.5, sent amount 1.0"))
        assertFalse(isInsufficientFunds("not enough outputs for specified ring size"))
        assertFalse(isInsufficientFunds(null))
    }

    @Test
    fun callIsMadeAgainOnAnotherDaemonOnce() = runBlocking {
        val second = DaemonNode("second.node:18081/mainnet/second", trusted = false)
        var movedTo: DaemonNode? = null
        var calls = 0
        val failover = DaemonFailover(otherDaemonsThatAnswer = { listOf(second) }, moveTo = { movedTo = it; true })

        val fee = failover.run {
            if (calls++ == 0) throw NodeUnreachableException("failed to get fee estimate")
            42L
        }

        assertEquals(42L, fee)
        assertEquals(second, movedTo)
    }

    @Test
    fun otherFailuresAreNotTakenForTheDaemons() = runBlocking {
        var asked = false
        val failover = DaemonFailover(otherDaemonsThatAnswer = { asked = true; emptyList() }, moveTo = { true })

        try {
            failover.run<Long> { throw IllegalStateException("Wallet is NULL") }
            fail("expected the failure")
        } catch (e: IllegalStateException) {
            assertFalse(asked)
        }
    }
}
