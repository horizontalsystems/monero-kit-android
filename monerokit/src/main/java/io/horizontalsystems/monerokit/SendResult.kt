package io.horizontalsystems.monerokit

/**
 * A daemon the wallet can sync from and send through.
 *
 * @param node the daemon in the form [MoneroKit.getInstance] takes it.
 */
data class DaemonNode(val node: String, val trusted: Boolean)

/**
 * How a send ended. The kit never leaves the caller to work this out from an error's text.
 */
sealed class SendResult {
    /** The transfer is in the network. */
    data class Sent(val transactionHash: String) : SendResult()

    /** Nothing was sent. Sending again cannot pay twice. */
    data class NotSent(val reason: Reason, val message: String?) : SendResult()

    /**
     * The transfer may be in the network: it was handed to a daemon and no daemon the kit could
     * ask confirmed it. Sending again can pay twice.
     *
     * @param transactionHash the transfer's id, to look it up later.
     */
    data class Unknown(val transactionHash: String?, val message: String?) : SendResult()

    enum class Reason {
        /** No daemon the kit may use answered the calls that come before the broadcast. */
        NodeUnreachable,
        InsufficientFunds,

        /** The wallet is not open: it is still opening, or it was closed during the send. */
        WalletClosed,

        /** The daemon refused the transaction, or the wallet could not build it. */
        Failed,
    }
}
