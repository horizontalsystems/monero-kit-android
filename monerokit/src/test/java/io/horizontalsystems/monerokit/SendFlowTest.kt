package io.horizontalsystems.monerokit

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class SendFlowTest {

    private val own = DaemonNode("own.node:18081/mainnet/own", trusted = true)
    private val second = DaemonNode("second.node:18081/mainnet/second", trusted = false)
    private val third = DaemonNode("third.node:18081/mainnet/third", trusted = false)
    private val fourth = DaemonNode("fourth.node:18081/mainnet/fourth", trusted = false)

    private val taken = Broadcast.Taken("tx-1")
    private val unanswered = Broadcast.Unanswered("tx-1", "Send Transaction failed: no connection to daemon")
    private val rejected = Broadcast.Refused(Broadcast.Why.Rejected, "Send Transaction failed: double spend")
    private val busy = Broadcast.Refused(Broadcast.Why.DaemonBusy, "Send Transaction failed: daemon is busy")

    // The daemon the wallet is on, and every one it was on while sending
    private var daemon = own
    private val buildsOn = mutableListOf<DaemonNode>()
    private val broadcastsOn = mutableListOf<DaemonNode>()
    private var discards = 0

    private var others = listOf(second, third)
    private var unreachableForBuilding = setOf<DaemonNode>()
    private var notBuilt: TransferNotBuiltException? = null
    private var answers = mapOf<DaemonNode, Broadcast>(own to taken)
    private var cannotMoveTo = setOf<DaemonNode>()

    private var time = 0L
    private var broadcastTakesMs = 1_000L

    private val flow = SendFlow(
        build = {
            buildsOn += daemon
            notBuilt?.let { throw it }
            if (daemon in unreachableForBuilding) throw NodeUnreachableException("node is unreachable")
        },
        broadcast = {
            broadcastsOn += daemon
            time += broadcastTakesMs
            answers[daemon] ?: unanswered
        },
        discard = { discards++ },
        currentDaemon = { daemon },
        otherDaemonsThatAnswer = { others },
        moveTo = { target ->
            if (target in cannotMoveTo) false else true.also { daemon = target }
        },
        now = { time },
    )

    @Test
    fun transferTheDaemonTakesIsSent() = runBlocking {
        assertEquals(SendResult.Sent("tx-1"), flow.run())
        assertEquals(listOf(own), buildsOn)
        assertEquals(listOf(own), broadcastsOn)
    }

    // Nothing was sent yet, so the transfer is built again on a daemon that answers
    @Test
    fun daemonThatFailsTheBuildIsLeftAndTheTransferBuiltAgain() = runBlocking {
        unreachableForBuilding = setOf(own)
        answers = mapOf(second to taken)

        assertEquals(SendResult.Sent("tx-1"), flow.run())
        assertEquals(listOf(own, second), buildsOn)
        assertEquals(listOf(second), broadcastsOn)
        assertEquals(second, daemon)
    }

    @Test
    fun transferIsBuiltAgainOnlyOnce() = runBlocking {
        unreachableForBuilding = setOf(own, second, third)

        assertEquals(SendResult.NotSent(SendResult.Reason.NodeUnreachable, "node is unreachable"), flow.run())
        assertEquals(listOf(own, second), buildsOn)
        assertEquals(emptyList<DaemonNode>(), broadcastsOn)
    }

    @Test
    fun buildFailureStandsWhenThereIsNoOtherDaemon() = runBlocking {
        unreachableForBuilding = setOf(own)
        others = emptyList()

        assertEquals(SendResult.NotSent(SendResult.Reason.NodeUnreachable, "node is unreachable"), flow.run())
        assertEquals(own, daemon)
    }

    @Test
    fun buildFailureStandsWhenTheWalletCannotBeMoved() = runBlocking {
        unreachableForBuilding = setOf(own)
        cannotMoveTo = setOf(second)

        assertEquals(SendResult.NotSent(SendResult.Reason.NodeUnreachable, "node is unreachable"), flow.run())
        assertEquals(listOf(own), buildsOn)
    }

    // Not enough money is the same on every daemon
    @Test
    fun transferThatCannotBeBuiltIsNotTriedOnAnotherDaemon() = runBlocking {
        notBuilt = TransferNotBuiltException(SendResult.Reason.InsufficientFunds, "not enough money")

        assertEquals(SendResult.NotSent(SendResult.Reason.InsufficientFunds, "not enough money"), flow.run())
        assertEquals(listOf(own), buildsOn)
        assertEquals(own, daemon)
    }

    // The same signed transaction goes to another daemon; the transfer is not built again
    @Test
    fun broadcastWithoutAnAnswerIsRepeatedOnAnotherDaemon() = runBlocking {
        answers = mapOf(second to taken)

        assertEquals(SendResult.Sent("tx-1"), flow.run())
        assertEquals(listOf(own), buildsOn)
        assertEquals(listOf(own, second), broadcastsOn)
        assertEquals(second, daemon)
    }

    @Test
    fun otherDaemonsAreTriedInOrderThenTheOwnOnceMore() = runBlocking {
        answers = emptyMap()

        assertEquals(SendResult.Unknown("tx-1", unanswered.message), flow.run())
        assertEquals(listOf(own, second, third, own), broadcastsOn)
        assertEquals(own, daemon)
    }

    @Test
    fun atMostTwoOtherDaemonsAreTried() = runBlocking {
        others = listOf(second, third, fourth)
        answers = emptyMap()

        flow.run()
        assertEquals(listOf(own, second, third, own), broadcastsOn)
    }

    // A dropped connection fails at once and a new one may work
    @Test
    fun ownDaemonTakesTheTransferOnTheSecondTry() = runBlocking {
        others = emptyList()
        var tries = 0
        val flow = SendFlow(
            build = {},
            broadcast = { if (tries++ == 0) unanswered else taken },
            discard = {},
            currentDaemon = { own },
            otherDaemonsThatAnswer = { emptyList() },
            moveTo = { true },
            now = { 0L },
        )

        assertEquals(SendResult.Sent("tx-1"), flow.run())
        assertEquals(2, tries)
    }

    // It kept the wallet waiting for its answer once already
    @Test
    fun ownDaemonThatHungIsNotTriedAgain() = runBlocking {
        broadcastTakesMs = 210_000
        answers = emptyMap()

        assertEquals(SendResult.Unknown("tx-1", unanswered.message), flow.run())
        assertEquals(listOf(own, second, third), broadcastsOn)
    }

    @Test
    fun daemonTheWalletCannotBeMovedToIsPassedOver() = runBlocking {
        cannotMoveTo = setOf(second)
        answers = mapOf(third to taken)

        assertEquals(SendResult.Sent("tx-1"), flow.run())
        assertEquals(listOf(own, third), broadcastsOn)
    }

    // The daemon checked the transaction and said no: nothing is out, no other daemon is asked
    @Test
    fun transferTheDaemonRejectsIsNotSent() = runBlocking {
        answers = mapOf(own to rejected)

        assertEquals(SendResult.NotSent(SendResult.Reason.Failed, rejected.message), flow.run())
        assertEquals(listOf(own), broadcastsOn)
        assertEquals(own, daemon)
    }

    // A daemon that lags refuses a transaction another one took, so the refusal settles nothing
    @Test
    fun rejectionAfterABroadcastWithoutAnAnswerLeavesItUnknown() = runBlocking {
        answers = mapOf(second to rejected, third to rejected)

        assertEquals(SendResult.Unknown("tx-1", unanswered.message), flow.run())
    }

    @Test
    fun busyDaemonIsLeftForAnotherOne() = runBlocking {
        answers = mapOf(own to busy, second to taken)

        assertEquals(SendResult.Sent("tx-1"), flow.run())
        assertEquals(listOf(own, second), broadcastsOn)
    }

    // Every daemon gave a clear answer, so nothing is out
    @Test
    fun transferNoDaemonWasReadyForIsNotSent() = runBlocking {
        answers = mapOf(own to busy, second to busy, third to busy)

        assertEquals(SendResult.NotSent(SendResult.Reason.NodeUnreachable, busy.message), flow.run())
    }

    @Test
    fun walletClosedWhileSettlingLeavesItUnknown() = runBlocking {
        val closed = Broadcast.Refused(Broadcast.Why.WalletClosed, "Send Transaction failed: Wallet is NULL")
        answers = mapOf(second to closed)

        assertEquals(SendResult.Unknown("tx-1", unanswered.message), flow.run())
        assertEquals(listOf(own, second), broadcastsOn)
    }

    @Test
    fun walletClosedBeforeTheBroadcastIsNotSent() = runBlocking {
        val closed = Broadcast.Refused(Broadcast.Why.WalletClosed, "Send Transaction failed: Wallet is NULL")
        answers = mapOf(own to closed)

        assertEquals(SendResult.NotSent(SendResult.Reason.WalletClosed, closed.message), flow.run())
    }

    @Test
    fun builtTransferIsDroppedHoweverTheSendEnds() = runBlocking {
        answers = emptyMap()
        flow.run()

        assertEquals(1, discards)
    }

    @Test
    fun nothingIsDroppedWhenNothingWasBuilt() = runBlocking {
        notBuilt = TransferNotBuiltException(SendResult.Reason.Failed, "bad address")
        flow.run()

        assertEquals(0, discards)
    }
}
