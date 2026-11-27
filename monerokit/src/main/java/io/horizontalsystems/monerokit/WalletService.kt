package io.horizontalsystems.monerokit

import android.content.Context
import android.util.Log
import com.google.common.net.HostAndPort
import io.horizontalsystems.monerokit.data.NodeInfo
import io.horizontalsystems.monerokit.data.TxData
import io.horizontalsystems.monerokit.model.PendingTransaction
import io.horizontalsystems.monerokit.model.TransactionInfo
import io.horizontalsystems.monerokit.model.Wallet
import io.horizontalsystems.monerokit.model.Wallet.Status
import io.horizontalsystems.monerokit.model.WalletListener
import io.horizontalsystems.monerokit.model.WalletManager
import io.horizontalsystems.monerokit.util.Helper
import io.horizontalsystems.monerokit.util.NetCipherHelper
import java.net.InetAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicBoolean

class WalletService(private val context: Context) {

    companion object {
        var running: Boolean = false
        private const val STATUS_UPDATE_INTERVAL = 120_000L // 120s
    }

    private var observer: Observer? = null
    private var listener: MyWalletListener? = null
    private val pendingSave = AtomicBoolean(false)

    private var daemonHeight: Long = 0
    private var lastDaemonStatusUpdate: Long = 0
    private var connectionStatus: Wallet.ConnectionStatus = Wallet.ConnectionStatus.ConnectionStatus_Disconnected

    // Whether the open wallet was given a daemon login. Set when the wallet is initialised.
    private var daemonLoginGiven = false

    // The id of the transaction that is built and not yet out, kept over repeated broadcasts
    private var builtTxId: String? = null
    private var broadcastTried = false

    // The wallet has one status for all its calls, so a transfer that was not built leaves
    // its error where the sync callback reads it. These two tell that error from the sync's own
    @Volatile
    private var buildingTransfer = false

    @Volatile
    private var transferError: String? = null

    private val sendsInProgress = AtomicInteger()

    // start() asks the daemon while it holds the monitor, so what lets go of it comes without
    // the monitor. This lock is what keeps the wallet open under that call instead
    private val startLock = Any()
    private var starts = 0

    // The number of the start that is asking its daemon, 0 when none is
    private var startAsking = 0

    /** Whether the last start was let go of by [cutStartShort]. It left no open wallet. */
    @Volatile
    var startCutShort = false
        private set

    // True while changeDaemon() has the wallet's daemon requests fail. A sync round that ends
    // then failed by the kit's doing, not the daemon's
    @Volatile
    private var daemonRequestsCut = false

    /**
     * Runs a send or a fee estimate. While one runs, a failed sync round is not reported: the
     * call moves the wallet off a daemon that fails, and the next round tells where it stands.
     */
    internal suspend fun <T> duringSend(call: suspend () -> T): T {
        sendsInProgress.incrementAndGet()
        try {
            return call()
        } finally {
            sendsInProgress.decrementAndGet()
        }
    }

    var wallet: Wallet? = null
        private set

    interface Observer {
        fun onRefreshed(wallet: Wallet, fullStatus: Status, full: Boolean): Boolean
        fun onInitialWalletState(balance: Balance, txs: List<TransactionInfo?>?)
    }

    fun setObserver(obs: Observer?) {
        observer = obs
    }

    fun getDaemonHeight(): Long = daemonHeight
    fun getConnectionStatus(): Wallet.ConnectionStatus = connectionStatus

    @Synchronized
    fun start(wallet: Wallet, trustNode: Boolean): Status? {
        running = true
        this.wallet = wallet

        synchronized(startLock) {
            startCutShort = false
            startAsking = ++starts
        }
        val walletStatus = try {
            initWallet(wallet, trustNode)
            wallet.fullStatus
        } finally {
            // the wallet may be closed from here on, so no cutStartShort() gets to it any more
            synchronized(startLock) { startAsking = 0 }
        }

        // One that came after the daemon had answered still stands: who cut it goes on as
        // if no wallet were open
        if (startCutShort || !walletStatus.isOk) {
            stop()
            return walletStatus
        }

        listener = MyWalletListener().apply { start() }
        return walletStatus
    }

    /** The start that is asking its daemon now, if one is. */
    internal fun startAskingDaemon(): Int? = synchronized(startLock) { startAsking.takeIf { it != 0 } }

    /**
     * Lets go of a start that waits for its daemon: it ends as a failed one and leaves no
     * open wallet. A daemon that takes the start's request and never answers it keeps
     * start(), and with it the monitor, until the request times out minutes later.
     *
     * Not synchronized, for that reason. init() lets daemon requests through again, so a
     * call that comes before it is lost: the caller repeats it for as long as it is true.
     *
     * @param start what [startAskingDaemon] gave.
     * @return whether that start is still asking its daemon.
     */
    internal fun cutStartShort(start: Int): Boolean = synchronized(startLock) {
        if (startAsking != start) return false
        startCutShort = true
        wallet?.interruptDaemon()
        true
    }

    fun requestSave() {
        pendingSave.set(true)
    }

    @Synchronized
    fun stop() {
        setObserver(null)
        listener?.stop()    // pauseRefresh() (no new iterations); listener freed later in close()
        wallet?.stopSync()  // interrupt the in-flight refresh so it releases the lock fast
        // stopSync() is only seen between two daemon requests. A request the daemon never
        // answers holds the refresh lock until it times out, minutes later, and the save
        // and the close below would wait for it. This makes it fail now. The wallet is
        // still open here, and nothing closes it but this method.
        wallet?.interruptDaemon()
        if (pendingSave.getAndSet(false)) {
            // storeBlocking() acquires the native refresh lock — now free since the
            // refresh was interrupted — so the save never races refresh mutation.
            wallet?.storeBlocking()
        }
        wallet?.close()
        wallet = null
        listener = null
        running = false
    }

    fun openWallet(walletName: String, walletPassword: String): Wallet? {
        val path = Helper.getWalletFile(context, walletName).absolutePath
        val walletMgr = WalletManager.getInstance()

        return if (walletMgr.walletExists(path)) {
            val wallet = walletMgr.openWallet(path, walletPassword)
            if (!wallet.status.isOk) {
                walletMgr.close(wallet)
                null
            } else {
                try {
                    wallet.refreshHistory()
                    observer?.onInitialWalletState(Balance(wallet.balanceAll, wallet.unlockedBalanceAll), wallet.history.all)
                } catch (err: Throwable) {
                    Log.e("WalletService", "error in onInitialWalletState", err)
                }
                this.wallet = wallet
                wallet
            }
        } else {
            null
        }
    }

    private fun initWallet(wallet: Wallet, trustNode: Boolean) {
        // init already talks to the daemon, so the proxy has to be in place before it runs
        wallet.init(0, currentProxy())
        wallet.setTrustedDaemon(trustNode)
        daemonLoginGiven = WalletManager.getInstance().daemonUsername.isNotEmpty()
    }

    internal enum class DaemonChange {
        Moved,

        /** No wallet is open; the daemon is used from the next start. */
        NoWallet,

        /** The open wallet cannot take this daemon; it has to be opened again on it. */
        NeedsReopen,
    }

    /**
     * Moves the open wallet to another daemon. The wallet keeps what it has synced and goes on
     * from there; nothing is closed. This is the call the wallet was initialised with at start,
     * made again with the new address, between two refreshes.
     *
     * It does not wait for the daemon being left. A request that daemon never answers holds
     * the wallet's daemon locks until it times out, three and a half minutes later, and
     * init() takes those locks. So the wallet's daemon requests are made to fail first: the
     * one in flight, and whatever is asked until init() has set the new daemon. A send or a
     * fee estimate that was waiting on the daemon being left sees it as a connection that
     * dropped, which it already answers by moving on.
     */
    @Synchronized
    internal fun changeDaemon(node: NodeInfo, trustNode: Boolean): DaemonChange {
        val wallet = wallet ?: return DaemonChange.NoWallet

        // The engine keeps the login it was last given and has no call to drop it, so a daemon
        // that has none would be offered the previous daemon's
        val hasLogin = node.hasLogin()
        if (daemonLoginGiven && !hasLogin) return DaemonChange.NeedsReopen

        wallet.pauseRefresh()
        try {
            WalletManager.getInstance().setDaemon(node)
            // init() is what lets the requests through again, so nothing that can fail
            // comes between the two
            daemonRequestsCut = true
            wallet.interruptDaemon()
            if (!wallet.init(0, currentProxy())) return DaemonChange.NeedsReopen
            wallet.setTrustedDaemon(trustNode)
        } catch (e: RuntimeException) {
            Log.e("WalletService", "changeDaemon failed", e)
            return DaemonChange.NeedsReopen
        } finally {
            daemonRequestsCut = false
            wallet.startRefresh()
        }

        daemonLoginGiven = hasLogin
        // what is known of the previous daemon says nothing about this one
        lastDaemonStatusUpdate = 0
        listener?.updated = true
        return DaemonChange.Moved
    }

    @Synchronized
    internal fun setTrustedDaemon(trustNode: Boolean) {
        wallet?.setTrustedDaemon(trustNode)
    }

    /**
     * Drops the wallet's daemon connection and opens a new one. epee does not mark a socket
     * the other side closed (recv returns an empty buffer, m_connected stays true), so the
     * wallet would go on using it. setProxy() with the proxy the wallet was opened with is the
     * one exposed call that ends in http client disconnect(); getConnectionStatus() then runs
     * check_connection(), which opens a new socket and invalidates the RPC caches.
     *
     * While an in-app Tor is still reconnecting the JVM proxy points at the blocked port, so
     * this fails closed instead of going out directly.
     *
     * Not synchronized: the connection check can wait for the daemon, and stop() must not
     * wait for it.
     *
     * @return whether the daemon answers.
     */
    internal fun reconnect(): Boolean {
        val wallet = wallet ?: return false
        return try {
            wallet.setProxy(currentProxy())
            wallet.connectionStatus == Wallet.ConnectionStatus.ConnectionStatus_Connected
        } catch (e: RuntimeException) {
            Log.e("WalletService", "reconnect failed", e)
            false
        }
    }

    // Orbot's proxy when it is on, else the JVM SOCKS proxy the in-app Tor installs, else
    // none. Every place that hands wallet2 a proxy must make this same choice, or a
    // reconnect goes out directly while the wallet was opened behind Tor.
    private fun currentProxy(): String = NetCipherHelper.getProxy().ifEmpty { systemSocksProxy() }

    // wallet2 opens its own daemon connections in native code, so the JVM proxy settings an
    // embedded Tor installs only reach it through init, read once when the wallet opens
    private fun systemSocksProxy(): String {
        val host = System.getProperty("socksProxyHost") ?: return ""
        val port = System.getProperty("socksProxyPort") ?: "1080"
        // wallet2 only takes a numeric proxy address, with IPv6 in brackets. Resolving the proxy's
        // own name is fine (only node names must stay with the proxy); if that or the port is bad,
        // the raw value is kept so the wallet fails to connect instead of going out directly
        return try {
            HostAndPort.fromParts(InetAddress.getByName(host).hostAddress, port.toInt()).toString()
        } catch (e: Exception) {
            "$host:$port"
        }
    }

    private fun updateDaemonState(wallet: Wallet, height: Long) {
        val now = System.currentTimeMillis()
        if (height > 0) {
            daemonHeight = height
            connectionStatus = Wallet.ConnectionStatus.ConnectionStatus_Connected
            lastDaemonStatusUpdate = now
        } else if (now - lastDaemonStatusUpdate > STATUS_UPDATE_INTERVAL) {
            lastDaemonStatusUpdate = now
            daemonHeight = wallet.daemonBlockChainHeight
            connectionStatus = if (daemonHeight > 0)
                Wallet.ConnectionStatus.ConnectionStatus_Connected
            else Wallet.ConnectionStatus.ConnectionStatus_Disconnected
        }
    }

    /** Wallet listener handling blockchain updates */
    private inner class MyWalletListener : WalletListener {
        var updated = true
        private var lastBlockTime = 0L
        private var lastTxCount = 0

        fun start() {
            val wallet = wallet ?: throw IllegalStateException("No wallet!")
            wallet.setListener(this)
            wallet.startRefresh()
        }

        fun stop() {
            val wallet = wallet ?: return
            wallet.pauseRefresh()
            // Do NOT setListener(null) here: that deletes the native listener
            // while the in-flight refresh thread may still be invoking callbacks
            // on it (use-after-free). Listener teardown is deferred to
            // wallet.close(), which runs after stopSync() has interrupted the
            // refresh, so the listener is freed only once nothing can call it.
        }

        override fun moneySpent(txId: String, amount: Long) {}
        override fun moneyReceived(txId: String, amount: Long) {}
        override fun unconfirmedMoneyReceived(txId: String, amount: Long) {}

        override fun newBlock(height: Long) {
            val wallet = wallet ?: return

            // don't flood with an update for every block ...
            if (lastBlockTime < System.currentTimeMillis() - 2000) {
                lastBlockTime = System.currentTimeMillis()
                if (observer != null) {
                    var fullRefresh = false
                    updateDaemonState(wallet, if (wallet.isSynchronized) height else 0)
                    if (!wallet.isSynchronized) {
                        updated = true
                        // we want to see our transactions as they come in
                        wallet.refreshHistory()
                        val txCount = wallet.getHistory().getCount()
                        if (txCount > lastTxCount) {
                            // update the transaction list only if we have more than before
                            lastTxCount = txCount
                            fullRefresh = true
                        }
                    }
                    observer?.onRefreshed(wallet, Status(), fullRefresh)
                }
            }
        }

        override fun updated() {
            updated = true
        }

        override fun refreshed() {
            val wallet = wallet ?: return

            // A round that ends while changeDaemon() has the daemon requests fail was cut short
            // by it and says nothing about the daemon the wallet is on now. Reading the status
            // asks the daemon, which would keep this thread until the change is over and make
            // it miss the call that starts the next round, the one that tells where the
            // wallet stands
            if (daemonRequestsCut) return

            val walletStatus = wallet.fullStatus
            // the change may have begun while the status was read
            val cutShort = daemonRequestsCut
            if (!walletStatus.isOk) {
                val about = statusError(
                    walletStatus.errorString, buildingTransfer, transferError, sendsInProgress.get() > 0
                )
                when (about) {
                    StatusError.OfTheSync -> {
                        if (!cutShort) observer?.onRefreshed(wallet, walletStatus, false)
                        return
                    }
                    StatusError.HeldBack -> return
                    // The daemon height asked for below clears it once the daemon answers
                    StatusError.OfATransfer -> Unit
                }
            }
            val walletFullStatus = if (walletStatus.isOk) walletStatus else Status()

            // refreshed() also fires when refresh() was interrupted before reaching
            // the chain tip (e.g. stopSync() during the initial scan). Only mark the
            // wallet synchronized once it has actually scanned up to the daemon
            // height; otherwise a partially-scanned wallet gets flagged "synced"
            // (zero balance / no transactions). setSynchronized() drives
            // Wallet.isSynchronized(), which is the only signal MoneroKit uses to
            // report Synced. Both getters return 0 on error -> treated as not-at-tip.
            val walletHeight = wallet.blockChainHeight
            val daemonHeight = wallet.daemonBlockChainHeight
            if (daemonHeight > 0 && walletHeight >= daemonHeight) {
                wallet.setSynchronized()
            }
            if (updated) {
                updateDaemonState(wallet, daemonHeight)
                wallet.refreshHistory()
                observer?.let {
                    updated = !it.onRefreshed(wallet, walletFullStatus, true)
                }
            }

            // Drain any pending save request. Called from the C++ callback thread,
            // so store() is safe here — the refresh thread is blocked in this callback.
            if (pendingSave.getAndSet(false)) {
                wallet.store()
            }
        }
    }

    /**
     * The fee the wallet works out from what the daemon tells it. A daemon failure is answered
     * with one reconnect and a second try, as in [createTransaction].
     */
    internal fun estimateFee(txData: TxData): Long {
        val wallet = wallet ?: throw IllegalStateException("Wallet is NULL")
        try {
            return wallet.estimateTransactionFee(txData)
        } catch (e: RuntimeException) {
            if (!isDaemonError(e.message)) throw e
            Log.w("WalletService", "estimateFee: daemon error, reconnecting and retrying: ${e.message}")
            if (!reconnect()) throw NodeUnreachableException(e.message, e)
        }
        try {
            return wallet.estimateTransactionFee(txData)
        } catch (e: RuntimeException) {
            if (isDaemonError(e.message)) throw NodeUnreachableException(e.message, e)
            throw e
        }
    }

    /**
     * Builds and signs the transaction and keeps it in the wallet. Nothing is sent.
     *
     * @throws NodeUnreachableException when the daemon did not answer what building needs.
     * @throws TransferNotBuiltException for every other reason.
     */
    internal fun createTransaction(txData: TxData) {
        val wallet = wallet
            ?: throw TransferNotBuiltException(SendResult.Reason.WalletClosed, "Create Transaction failed: Wallet is NULL")

        builtTxId = null
        broadcastTried = false
        val error = try {
            build(wallet, txData) ?: return
        } catch (e: RuntimeException) {
            throw TransferNotBuiltException(SendResult.Reason.Failed, "Create Transaction failed: ${e.message}")
        }
        if (!isDaemonError(error)) throw notBuilt(error)

        // Building a transaction is the first thing after the fee estimate (served from
        // wallet2's 30 s caches) that talks to the node again, so a daemon connection the
        // node or the network dropped in the meantime is noticed only here: the first RPC
        // fails ("internal error: Failed to get earliest fork height") and wallet2 gives up.
        // Behind the in-app Tor this is the common case: Tor sleeps while the app is in the
        // background and reconnects on return, closing every stream the wallet had. While
        // Tor is still reconnecting the retry fails closed (see reconnect()); the adapter
        // reopens the wallet on the new SOCKS port once Tor reports Connected.
        Log.w("WalletService", "createTransaction: daemon error, reconnecting and retrying: $error")
        if (!reconnect()) {
            throw NodeUnreachableException("Create Transaction failed: node is unreachable ($error)")
        }

        val retryError = try {
            build(wallet, txData) ?: return
        } catch (e: RuntimeException) {
            throw TransferNotBuiltException(SendResult.Reason.Failed, "Create Transaction failed: ${e.message}")
        }
        if (isDaemonError(retryError)) {
            throw NodeUnreachableException("Create Transaction failed: node is not responding ($retryError)")
        }
        throw notBuilt(retryError)
    }

    // Null when the transaction is built; else the wallet's words for why it is not
    private fun build(wallet: Wallet, txData: TxData): String? {
        wallet.disposePendingTransaction()
        txData.createPocketChange(wallet)

        buildingTransfer = true
        try {
            val pendingTransaction = wallet.createTransaction(txData)
            if (pendingTransaction.status === PendingTransaction.Status.Status_Ok) {
                transferError = null
                return null
            }

            val error = pendingTransaction.getErrorString().orEmpty()
            transferError = error
            wallet.disposePendingTransaction()
            return error
        } finally {
            buildingTransfer = false
        }
    }

    private fun notBuilt(error: String) = TransferNotBuiltException(
        if (isInsufficientFunds(error)) SendResult.Reason.InsufficientFunds else SendResult.Reason.Failed,
        "Create Transaction failed: $error"
    )

    /**
     * Hands the built transaction to the wallet's daemon. It can be called again for the same
     * transaction after an answer that is not [Broadcast.Taken], also once the wallet has been
     * moved to another daemon: the wallet keeps the signed transaction until it is taken or
     * [discardTransaction] drops it, and a daemon that has it already answers that it took it.
     */
    internal fun broadcastTransaction(notes: String?): Broadcast {
        val wallet = wallet ?: return walletClosed()
        val pendingTransaction = wallet.pendingTransaction
            ?: return Broadcast.Refused(Broadcast.Why.Rejected, "Send Transaction failed: no transaction was built")

        try {
            // A broadcast that failed leaves the status at error for good, so it tells
            // nothing after the first try
            if (!broadcastTried && pendingTransaction.status !== PendingTransaction.Status.Status_Ok) {
                return Broadcast.Refused(
                    Broadcast.Why.Rejected,
                    "Send Transaction failed: ${pendingTransaction.getErrorString()}"
                )
            }
            if (builtTxId == null) builtTxId = pendingTransaction.getFirstTxIdJ()
            broadcastTried = true

            // The wallet drops each transaction the daemon took, and commit() goes on
            // answering false once a try has failed, so none left is what says all are out
            val taken = pendingTransaction.commit("", true) || pendingTransaction.getTxCount() == 0L
            if (!taken) return broadcastFailure(pendingTransaction.getErrorString(), builtTxId)
        } catch (e: RuntimeException) {
            Log.e("WalletService", "broadcast failed", e)
            return if (this.wallet == null) walletClosed() else broadcastFailure(e.message, builtTxId)
        }

        // The transaction is out. Nothing that goes wrong from here on may be reported as a
        // failed send, which the caller would answer by sending again.
        val txId = builtTxId.orEmpty()
        runCatching {
            wallet.disposePendingTransaction()
            if (!notes.isNullOrEmpty() && txId.isNotEmpty()) {
                wallet.setUserNote(txId, notes)
            }
            wallet.storeBlocking()
        }.onFailure { Log.e("WalletService", "after the broadcast", it) }
        listener?.updated = true
        return Broadcast.Taken(txId)
    }

    private fun walletClosed() =
        Broadcast.Refused(Broadcast.Why.WalletClosed, "Send Transaction failed: Wallet is NULL")

    internal fun discardTransaction() {
        builtTxId = null
        broadcastTried = false
        wallet?.disposePendingTransaction()
    }
}
