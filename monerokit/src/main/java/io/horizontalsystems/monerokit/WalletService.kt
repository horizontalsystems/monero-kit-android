package io.horizontalsystems.monerokit

import android.content.Context
import android.util.Log
import io.horizontalsystems.monerokit.data.TxData
import io.horizontalsystems.monerokit.model.PendingTransaction
import io.horizontalsystems.monerokit.model.TransactionInfo
import io.horizontalsystems.monerokit.model.Wallet
import io.horizontalsystems.monerokit.model.Wallet.Status
import io.horizontalsystems.monerokit.model.WalletListener
import io.horizontalsystems.monerokit.model.WalletManager
import io.horizontalsystems.monerokit.util.Helper
import io.horizontalsystems.monerokit.util.NetCipherHelper
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

        initWallet(wallet, trustNode)

        val walletStatus = wallet.fullStatus

        if (!walletStatus.isOk) {
            stop()
            return walletStatus
        }

        listener = MyWalletListener().apply { start() }
        return walletStatus
    }

    fun requestSave() {
        pendingSave.set(true)
    }

    @Synchronized
    fun stop() {
        setObserver(null)
        listener?.stop()    // pauseRefresh() (no new iterations); listener freed later in close()
        wallet?.stopSync()  // interrupt the in-flight refresh so it releases the lock fast
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
                    observer?.onInitialWalletState(Balance(wallet.balance, wallet.unlockedBalance), wallet.history.all)
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
        wallet.init(0)
        wallet.setTrustedDaemon(trustNode)
        wallet.setProxy(NetCipherHelper.getProxy())
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

            val walletFullStatus = wallet.fullStatus
            if (!walletFullStatus.isOk) {
                observer?.onRefreshed(wallet, walletFullStatus, false)
                return
            }

            wallet.setSynchronized() // TODO sometimes called even if sync is not complete
            if (updated) {
                updateDaemonState(wallet, wallet.blockChainHeight)
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

    fun createTransaction(txData: TxData) {
        val wallet = wallet ?: throw IllegalStateException("Create Transaction failed: Wallet is NULL")

        wallet.disposePendingTransaction()
        txData.createPocketChange(wallet)

        val pendingTransaction = wallet.createTransaction(txData)
        val status = pendingTransaction.status
        if (status !== PendingTransaction.Status.Status_Ok) {
            throw IllegalStateException("Create Transaction failed: ${pendingTransaction.getErrorString()}")
        }
    }

    fun sendTransaction(notes: String?): String {
        val wallet = wallet ?: throw IllegalStateException("Send Transaction failed: Wallet is NULL")

        val pendingTransaction = wallet.pendingTransaction
        requireNotNull(pendingTransaction) { "PendingTransaction is null" }
        if (pendingTransaction.status !== PendingTransaction.Status.Status_Ok) {
            wallet.disposePendingTransaction()
            throw IllegalStateException("Send Transaction failed: ${pendingTransaction.getErrorString()}")
        }
        val txId = pendingTransaction.getFirstTxId()
        val success = pendingTransaction.commit("", true)

        if (success) {
            wallet.disposePendingTransaction()
            if (!notes.isNullOrEmpty()) {
                wallet.setUserNote(txId, notes)
            }
            wallet.storeBlocking()
            listener?.updated = true
            return txId
        } else {
            val error = pendingTransaction.getErrorString()
            wallet.disposePendingTransaction()
            throw IllegalStateException("Send Transaction failed: $error")
        }
    }
}
