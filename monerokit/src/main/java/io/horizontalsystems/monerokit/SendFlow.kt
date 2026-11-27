package io.horizontalsystems.monerokit

/** A daemon call that comes before the broadcast got no answer: nothing was sent. */
internal class NodeUnreachableException(message: String?, cause: Throwable? = null) : Exception(message, cause)

/** The transfer was not built, for a reason that is not the daemon's. Nothing was sent. */
internal class TransferNotBuiltException(val reason: SendResult.Reason, message: String?) : Exception(message)

/** What one broadcast of the built transaction came to. */
internal sealed class Broadcast {
    /** The daemon took the transaction and the wallet recorded it. */
    data class Taken(val hash: String) : Broadcast()

    /** A clear answer that the transaction was not taken. */
    data class Refused(val why: Why, val message: String) : Broadcast()

    /** No clear answer: the daemon may have the transaction. */
    data class Unanswered(val hash: String?, val message: String) : Broadcast()

    enum class Why {
        /** The daemon checked the transaction and turned it down. */
        Rejected,

        /** The daemon is not ready to take any transaction. */
        DaemonBusy,
        WalletClosed,
    }
}

// wallet2 reports a node that stopped answering as an "internal error: Failed to get ..."
// (height, fork height, fee estimate, output distribution), a connection loss or a busy
// daemon; everything else (bad address, not enough money, tx too big) is final
internal fun isDaemonError(error: String?): Boolean {
    val e = error?.lowercase() ?: return false
    return e.contains("failed to get")
        || e.contains("failed to connect")
        || e.contains("connection to daemon")
        || e.contains("daemon is busy")
        || e.contains("rpc error")
}

internal fun isInsufficientFunds(error: String?): Boolean =
    error?.lowercase()?.contains("not enough money") == true

/** What a wallet status that is not OK, seen when a sync round ends, is about. */
internal enum class StatusError {
    /** The sync failed: the caller is to hear of it. */
    OfTheSync,

    /** A transfer that was not built left it there. It says nothing about the sync. */
    OfATransfer,

    /**
     * The sync failed while a send or a fee estimate is under way. That call deals with a
     * daemon that fails by itself, so the sync's state is told once it is over.
     */
    HeldBack,
}

/**
 * The wallet has one status for all its calls: a transfer that was not built leaves its error
 * there until another call clears it, and the sync round that ends next would read it as its own.
 *
 * @param error the status's words.
 * @param transferError the words of the last transfer that was not built, if any.
 * @param sendInProgress whether a send or a fee estimate is under way.
 */
internal fun statusError(
    error: String?,
    buildingTransfer: Boolean,
    transferError: String?,
    sendInProgress: Boolean,
): StatusError = when {
    buildingTransfer || error == transferError -> StatusError.OfATransfer
    sendInProgress -> StatusError.HeldBack
    else -> StatusError.OfTheSync
}

/**
 * Reads the wallet's words for a broadcast that did not go through. Only the two answers a
 * daemon gives for a transaction it did not take are read as such. Anything else, a lost
 * connection above all, leaves open whether the daemon has it.
 *
 * @param hash the id of the transaction that was being broadcast.
 */
internal fun broadcastFailure(error: String?, hash: String?): Broadcast {
    val message = "Send Transaction failed: ${error.orEmpty()}"
    val e = error.orEmpty().lowercase()
    return when {
        e.contains("was rejected by daemon") -> Broadcast.Refused(Broadcast.Why.Rejected, message)
        e.contains("daemon is busy") -> Broadcast.Refused(Broadcast.Why.DaemonBusy, message)
        else -> Broadcast.Unanswered(hash, message)
    }
}

// Monero adds a block every two minutes; a daemon a block or two back is only slow to hear.
// A ping reports the top block's index, one less than the block count the wallet goes by.
internal const val MAX_BLOCKS_BEHIND = 3L

/**
 * The daemons a send may move to, fastest first: those that answered a ping and are not behind.
 * Behind is measured against the highest block any of them reports and against the wallet's own
 * height, so a daemon that is the only one to answer is still left out when the wallet is ahead
 * of it.
 *
 * @param walletHeight the height the wallet has synced to, 0 when it is not known.
 */
internal fun daemonsReadyForSend(pings: List<NodePingResult>, walletHeight: Long): List<String> {
    val answered = pings.filter { it.isValid && it.responseTime < Double.MAX_VALUE }
    val best = maxOf(walletHeight, answered.maxOfOrNull { it.height } ?: 0L)
    return answered
        .filter { it.height >= best - MAX_BLOCKS_BEHIND }
        .sortedBy { it.responseTime }
        .map { it.serialized }
}

/**
 * A call that needs the daemon, with what the kit does when the daemon fails it: the wallet
 * moves to another daemon that answers and the call is made again there, once.
 *
 * @param otherDaemonsThatAnswer the other daemons the kit may use that are ready, best first.
 * @param moveTo moves the open wallet to a daemon; false when the wallet is not on it after.
 */
internal class DaemonFailover(
    private val otherDaemonsThatAnswer: suspend () -> List<DaemonNode>,
    private val moveTo: suspend (DaemonNode) -> Boolean,
) {
    suspend fun <T> run(call: suspend () -> T): T =
        try {
            call()
        } catch (error: NodeUnreachableException) {
            val other = otherDaemonsThatAnswer().firstOrNull() ?: throw error
            if (!moveTo(other)) throw error
            call()
        }
}

/**
 * One send, start to end, with what the kit does about a daemon that fails.
 *
 * Before the broadcast nothing was sent, so the wallet moves to another daemon and the transfer
 * is built again there, once. After a broadcast that did not end in a clear answer the wallet
 * keeps the signed transaction and broadcasts that same one through other daemons: a daemon
 * answers a transaction it already has as taken, so this cannot pay twice, and the wallet
 * records the transfer as it does for any send. Nothing is built a second time once a
 * broadcast may have gone out.
 *
 * @param build builds and signs the transfer through the wallet's daemon.
 * @param broadcast hands the built transfer to the wallet's daemon.
 * @param discard drops the built transfer.
 * @param currentDaemon the daemon the wallet is on.
 * @param moveTo moves the open wallet to a daemon, or reconnects it when it is on that daemon
 * already; false when the daemon does not answer the wallet.
 */
internal class SendFlow(
    private val build: suspend () -> Unit,
    private val broadcast: suspend () -> Broadcast,
    private val discard: () -> Unit,
    private val currentDaemon: () -> DaemonNode,
    private val otherDaemonsThatAnswer: suspend () -> List<DaemonNode>,
    private val moveTo: suspend (DaemonNode) -> Boolean,
    private val now: () -> Long = System::currentTimeMillis,
) {
    suspend fun run(): SendResult {
        try {
            DaemonFailover(otherDaemonsThatAnswer, moveTo).run(build)
        } catch (error: NodeUnreachableException) {
            return SendResult.NotSent(SendResult.Reason.NodeUnreachable, error.message)
        } catch (error: TransferNotBuiltException) {
            return SendResult.NotSent(error.reason, error.message)
        }

        return try {
            broadcastUntilSettled()
        } finally {
            discard()
        }
    }

    private suspend fun broadcastUntilSettled(): SendResult {
        val ownDaemon = currentDaemon()
        val startedAt = now()
        val first = broadcast()
        if (first is Broadcast.Taken) return SendResult.Sent(first.hash)
        // The daemon checked this transaction and turned it down. Another daemon is not asked:
        // nothing is out, and the caller is to see the reason.
        if (first is Broadcast.Refused && first.why != Broadcast.Why.DaemonBusy) return notSent(first)

        // A daemon that failed at once is worth one more try after the others: a connection
        // the network dropped is the usual cause. One that kept the wallet waiting is not.
        val ownFailedFast = now() - startedAt < OWN_DAEMON_RETRY_WINDOW_MS
        val others = otherDaemonsThatAnswer().take(MAX_OTHER_DAEMONS)

        var last: Broadcast = first
        var unanswered = first as? Broadcast.Unanswered
        for (daemon in if (ownFailedFast) others + ownDaemon else others) {
            if (!moveTo(daemon)) continue
            val next = broadcast()
            last = next
            when (next) {
                is Broadcast.Taken -> return SendResult.Sent(next.hash)
                is Broadcast.Unanswered -> unanswered = unanswered ?: next
                // Not proof that nothing is out when an earlier daemon gave no answer: a
                // daemon a few blocks behind refuses a transaction another one has taken
                is Broadcast.Refused -> if (next.why == Broadcast.Why.WalletClosed) break
            }
        }

        return unanswered?.let { SendResult.Unknown(it.hash, it.message) } ?: notSent(last as Broadcast.Refused)
    }

    private fun notSent(refusal: Broadcast.Refused): SendResult {
        val reason = when (refusal.why) {
            Broadcast.Why.Rejected -> SendResult.Reason.Failed
            Broadcast.Why.DaemonBusy -> SendResult.Reason.NodeUnreachable
            Broadcast.Why.WalletClosed -> SendResult.Reason.WalletClosed
        }
        return SendResult.NotSent(reason, refusal.message)
    }

    companion object {
        // The wallet waits minutes for a daemon that accepts a request and never answers it
        private const val OWN_DAEMON_RETRY_WINDOW_MS = 20_000L
        private const val MAX_OTHER_DAEMONS = 2
    }
}
