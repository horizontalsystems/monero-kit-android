package io.horizontalsystems.monerokit

import android.content.Context
import android.util.Log
import io.horizontalsystems.monerokit.MoneroKit.Companion.MONERO_LEGACY_MNEMONIC_COUNT
import io.horizontalsystems.monerokit.data.NodeInfo
import io.horizontalsystems.monerokit.data.Subaddress
import io.horizontalsystems.monerokit.data.TxData
import io.horizontalsystems.monerokit.data.UserNotes
import io.horizontalsystems.monerokit.model.NetworkType
import io.horizontalsystems.monerokit.model.PendingTransaction
import io.horizontalsystems.monerokit.model.TransactionInfo
import io.horizontalsystems.monerokit.model.Wallet
import io.horizontalsystems.monerokit.model.Wallet.ConnectionStatus.ConnectionStatus_Connected
import io.horizontalsystems.monerokit.model.WalletManager
import io.horizontalsystems.monerokit.util.Helper
import io.horizontalsystems.monerokit.util.NetCipherHelper
import io.horizontalsystems.monerokit.util.NodeHelper
import io.horizontalsystems.monerokit.util.RestoreHeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar

class MoneroKit(
    private val context: Context,
    private val seed: Seed.Electrum,
    private val restoreHeight: Long,
    private val walletId: String,
    private val walletService: WalletService,
    private val node: String?
) : WalletService.Observer {

    private var scope: CoroutineScope? = null

    private var started = false
    private var synced = false

    private val _syncStateFlow = MutableStateFlow<SyncState>(SyncState.NotSynced(SyncError.NotStarted))
    val syncStateFlow = _syncStateFlow.asStateFlow()

    private val _balanceFlow = MutableStateFlow<Long>(0)
    val balanceFlow = _balanceFlow.asStateFlow()

    private val _lastBlockUpdatedFlow = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val lastBlockUpdatedFlow = _lastBlockUpdatedFlow.asSharedFlow()

    private val _allTransactionsFlow = MutableStateFlow<List<TransactionInfo>>(emptyList())
    val allTransactionsFlow: StateFlow<List<TransactionInfo>> = _allTransactionsFlow

    private var nodeInfo: NodeInfo? = null

    val receiveAddress: String
        get() = try {
            walletService.getWallet()?.newSubaddress ?: throw IllegalStateException("Wallet is NULL")
        } catch (_: Exception) {
            ""
        }

    val balance: Long
        get() = _balanceFlow.value

    val lastBlockHeight: Long?
        get() = if (walletService.getConnectionStatus() == ConnectionStatus_Connected)
            walletService.getDaemonHeight()
        else
            null

    fun start() {
        if (started) return
        started = true

        _syncStateFlow.update {
            SyncState.Syncing()
        }

        scope = CoroutineScope(Dispatchers.IO)

        scope?.launch {
            createWalletIfNotExists()

            val selectedNode = if (nodeInfo != null) {
                nodeInfo
            } else if (node != null) {
                NodeInfo.fromString(node)
            } else {
                val nodes = NodeHelper.getOrPopulateFavourites()
                NodeHelper.autoselect(nodes)
            }

            Log.e("eee", "selected node: ${selectedNode?.host}")
            if (selectedNode == null) {
                started = false
                _syncStateFlow.update { SyncState.NotSynced(SyncError.InvalidNode("Invalid node")) }
                return@launch
            }

            nodeInfo = selectedNode
            WalletManager.getInstance().setDaemon(selectedNode)

            walletService.setObserver(this@MoneroKit)
            val status = walletService.start(walletId, "")

            Log.e("eee", "status after start: $status")
            if (status == null || !status.isOk) {
                started = false
                _syncStateFlow.update { SyncState.NotSynced(SyncError.StartError(status?.toString() ?: "Wallet is NULL")) }
                return@launch
            }
        }
    }

    fun stop() {
        if (!started) return
        started = false

        Log.e("eee", "kit.stop() before launch scope = $scope")
        scope?.launch {
            Log.e("eee", "kit.stop() before service.stop()")
            walletService.stop()
            Log.e("eee", "kit.stop() after service.stop()")
            scope?.cancel()
            Log.e("eee", "kit.stop() after cancel ")
            scope = null
        }
        Log.e("eee", "kit.stop() after launch ")
    }

    fun saveState() {
        walletService.storeWallet()
    }

    fun send(
        amount: Long,
        address: String,
        memo: String?
    ) {
        val txData = buildTxData(amount, address, memo)

        walletService.createTransaction(txData)
        walletService.sendTransaction(memo)
    }

    fun estimateFee(
        amount: Long,
        address: String,
        memo: String?
    ): Long {
        val wallet = walletService.getWallet() ?: throw IllegalStateException("Wallet is NULL")
        val txData = buildTxData(amount, address, memo)

        return wallet.estimateTransactionFee(txData)
    }

    fun getSubaddresses(): List<Subaddress> {
        val wallet = walletService.getWallet() ?: return emptyList()
        val list = mutableListOf<Subaddress>()
        for (i in 0..wallet.numSubaddresses) {
            wallet.getSubaddressObject(i)?.let {
                list.add(it)
            }
        }
        return list
    }

    fun getSubaddress(accountIndex: Int, subaddressIndex: Int): Subaddress? {
        return walletService.getWallet()?.getSubaddressObject(accountIndex, subaddressIndex)
    }

    fun getKeys(): Keys? {
        val wallet = walletService.getWallet() ?: return null

        return Keys(
            privateSpendKey = wallet.secretSpendKey,
            publicSpendKey = wallet.publicSpendKey,
            privateViewKey = wallet.secretViewKey,
            publicViewKey = wallet.publicViewKey
        )
    }

    private fun buildTxData(
        amount: Long,
        destination: String,
        memo: String?
    ) = TxData().apply {
        this.amount = amount
        this.destination = destination
        mixin = MIXIN
        priority = PendingTransaction.Priority.Priority_Medium
        if (!memo.isNullOrEmpty()) {
            userNotes = UserNotes(memo)
        }
    }


    suspend fun restoreHeightForNewWallet(): Long {
        // val currentNode: NodeInfo? = getNode() //
        // get it from the connected node if we have one

        val height: Long = -1 // if (currentNode != null) currentNode.getHeight() else -1

        val restoreHeight: Long = if (height > -1) height
        else {
            // Go back 4 days if we don't have a precise restore height
            val restoreDate = Calendar.getInstance()
            restoreDate.add(Calendar.DAY_OF_MONTH, -4)

            RestoreHeight.getInstance().getHeight(restoreDate.getTime())
        }

        return restoreHeight
    }

    private suspend fun createWalletIfNotExists() = withContext(Dispatchers.IO) {
        // check if the wallet we want to create already exists
        val walletFolder: File = Helper.getWalletRoot(context)
        if (!walletFolder.isDirectory) {
            Timber.e("Wallet dir " + walletFolder.absolutePath + "is not a directory")
            return@withContext
        }
        val cacheFile = File(walletFolder, walletId)
        val keysFile = File(walletFolder, "$walletId.keys")
        val addressFile = File(walletFolder, "$walletId.address.txt")

        if (cacheFile.exists() || keysFile.exists() || addressFile.exists()) {
            Timber.e("Some wallet files already exist for %s", cacheFile.absolutePath)
            return@withContext
        }

        val newWalletFile = File(walletFolder, walletId)
        val walletPassword = ""
        val offset = seed.passphrase
        val mnemonic = seed.mnemonic.joinToString(" ")
        val newWallet = WalletManager.getInstance().recoveryWallet(newWalletFile, walletPassword, mnemonic, offset, restoreHeight)
        val success = checkAndCloseWallet(newWallet)

        val walletFile = File(walletFolder, walletId)
        walletFile.delete()

        if (success) {
            Timber.i("Created wallet in %s", newWalletFile.absolutePath)
            return@withContext
        } else {
            Timber.e("Could not create wallet in %s", newWalletFile.absolutePath)
            return@withContext
        }
    }

    // Observer ====================================

    private var firstBlock: Long = 0

    override fun onRefreshed(wallet: Wallet, full: Boolean): Boolean {
        Log.e("eee", "observer.onRefreshed()\n - wallet: ${wallet.fullStatus}\n - full: $full")

        val historyAll: List<TransactionInfo?>? = wallet.history.all
        Log.e("eee", "historyAll: ${historyAll?.count()}")

        if (historyAll != null) {
            _allTransactionsFlow.update {
                historyAll.mapNotNull { it }
            }
        }


        if (wallet.isSynchronized) {
            Log.e("eee", "wallet is synced, first sync = ${!synced}")
            if (!synced) { // first sync
                onProgress(-1)
                walletService.storeWallet() // save on first sync
                synced = true
            }
        }

        if (!wallet.isSynchronized) {
            val daemonHeight: Long = walletService.getDaemonHeight()
            val walletHeight = wallet.getBlockChainHeight()
            val remainingBlocks = daemonHeight - walletHeight

            if (firstBlock == 0L) {
                firstBlock = walletHeight
            }

            Timber.i(
                "firstBlock: %d, daemonHeight: %d, walletHeight: %d, remainingBlocks: %d",
                firstBlock,
                daemonHeight,
                walletHeight,
                remainingBlocks
            )
            val totalBlocks = daemonHeight - firstBlock
            val progress: Double = if (totalBlocks > 0) {
                1 - remainingBlocks.toDouble() / totalBlocks
            } else {
                1.0
            }

            _syncStateFlow.update {
                SyncState.Syncing(progress)
            }
        } else {
            _syncStateFlow.update {
                SyncState.Synced
            }
        }

        _lastBlockUpdatedFlow.tryEmit(Unit)

        _balanceFlow.update {
            walletService.getWallet()?.balance ?: 0L
        }

        return true
    }

    override fun onInitialTransactions(txs: List<TransactionInfo?>?) {
        txs?.let {
            _allTransactionsFlow.update {
                txs.mapNotNull { it }
            }
        }
    }

    override fun onProgress(n: Int) {
        Log.e("eee", "observer.onProgress()\n - n: $n")
    }

    override fun onWalletStored(success: Boolean) {
        Log.e("eee", "observer.onWalletStored()\n - success: $success")
    }

    override fun onTransactionCreated(pendingTransaction: PendingTransaction) {
        Log.e("eee", "observer.onTransactionCreated()\n - pendingTransaction.firstTxId : ${pendingTransaction.firstTxId}")
    }

    override fun onTransactionSent(txid: String) {
        Log.e("eee", "observer.onTransactionSent()\n - txid: $txid")
    }

    override fun onSendTransactionFailed(error: String) {
        Log.e("eee", "observer.onSendTransactionFailed()\n - error: $error")
    }

    override fun onWalletStarted(walletStatus: Wallet.Status?) {
        Log.e("eee", "observer.onWalletStarted()\n - walletStatus: $walletStatus")
    }

    override fun onWalletOpen(device: Wallet.Device) {
        Log.e("eee", "observer.onWalletOpen()\n - device: $device")
    }

    private fun checkAndCloseWallet(aWallet: Wallet): Boolean {
        val walletStatus = aWallet.status
        if (!walletStatus.isOk) {
            Timber.tag("eee").e(walletStatus.errorString)
            throw IllegalStateException("Wallet recovery error: ${walletStatus.errorString}")
        }
        aWallet.close()
        return walletStatus.isOk
    }

    fun statusInfo(): Map<String, Any> {
        val statusInfo = LinkedHashMap<String, Any>()

        statusInfo["Wallet Status"] = walletService.getWallet()?.fullStatus ?: "NULL"
        statusInfo["Last Block Height"] = lastBlockHeight ?: 0L
        statusInfo["Sync State"] = _syncStateFlow.value
        statusInfo["Daemon Height"] = walletService.getDaemonHeight()
        statusInfo["Connection Status"] = walletService.getConnectionStatus()
        statusInfo["Kit started"] = started
        statusInfo["Service running"] = WalletService.running
        statusInfo["Node"] = nodeInfo?.name ?: "NULL"

        return statusInfo
    }

    sealed class SyncError : Error() {
        object NotStarted : SyncError() {
            override val message = "Not Started"
        }

        data class InvalidNode(override val message: String) : SyncError()
        data class StartError(override val message: String) : SyncError()
    }

    companion object {
        const val MIXIN: Int = 0
        const val MONERO_LEGACY_MNEMONIC_COUNT = 25

        fun getInstance(
            context: Context,
            seed: Seed,
            restoreDateOrHeight: String,
            walletId: String,
            node: String?
        ): MoneroKit {
            val walletService = WalletService(context)
            val restoreHeight = getHeight(restoreDateOrHeight)

            Log.e("eee", "computed restoreHeight = $restoreHeight")

            NetCipherHelper.createInstance(context)

            return MoneroKit(context, seed.toElectrum(), restoreHeight, walletId, walletService, node)
        }

        fun validateAddress(address: String) {
            if (!Wallet.isAddressValid(address)) {
                throw IllegalArgumentException("Invalid address")
            }
        }

        fun getKeys(seed: Seed): Keys {
            val electrumSeed = seed.toElectrum()
            val mnemonic = electrumSeed.mnemonic.joinToString(" ")
            val passphrase = electrumSeed.passphrase

            val privateSpendKey = WalletManager.getPrivateSpendKey(mnemonic, passphrase)
            val publicSpendKey = WalletManager.getPublicSpendKey(mnemonic, passphrase)
            val privateViewKey = WalletManager.getPrivateViewKey(mnemonic, passphrase)
            val publicViewKey = WalletManager.getPublicViewKey(mnemonic, passphrase)

            return Keys(privateSpendKey, publicSpendKey, privateViewKey, publicViewKey)
        }

        private fun getHeight(input: String): Long {
            val trimmed = input.trim()
            if (trimmed.isEmpty()) return -1

            val walletManager = WalletManager.getInstance()
            val restoreHeight = RestoreHeight.getInstance()

            var height = -1L

            if (walletManager.networkType == NetworkType.NetworkType_Mainnet) {
                // Try parsing as date (yyyy-MM-dd)
                height = runCatching {
                    SimpleDateFormat("yyyy-MM-dd").apply { isLenient = false }.parse(trimmed)?.let { restoreHeight.getHeight(it) }
                }.getOrNull() ?: -1

                // Try parsing as date (yyyyMMdd) if previous failed
                if (height < 0 && trimmed.length == 8) {
                    height = runCatching {
                        SimpleDateFormat("yyyyMMdd").apply { isLenient = false }.parse(trimmed)?.let { restoreHeight.getHeight(it) }
                    }.getOrNull() ?: -1
                }
            }

            // If still invalid, try numeric height
            if (height < 0) {
                height = trimmed.toLongOrNull() ?: -1
            }

            Timber.d("Using Restore Height = %d", height)
            return height
        }

    }
}

fun ByteArray?.toRawHexString(): String {
    return this?.joinToString(separator = "") {
        it.toInt().and(0xff).toString(16).padStart(2, '0')
    } ?: ""
}

fun ByteArray?.toHexString(): String {
    val rawHex = this?.toRawHexString() ?: return ""
    return "0x$rawHex"
}

data class Keys(
    val privateSpendKey: String,
    val publicSpendKey: String,
    val privateViewKey: String,
    val publicViewKey: String
)

sealed class Seed {
    data class Electrum(val mnemonic: List<String>, val passphrase: String) : Seed() {
        init {
            check(mnemonic.size == MONERO_LEGACY_MNEMONIC_COUNT) { "Illegal Electrum Seed" }
        }
    }

    data class Bip39(val mnemonic: List<String>, val passphrase: String) : Seed() {
        init {
            check(mnemonic.size in listOf(12, 18, 24)) { "Illegal Bip39 Seed" }
        }
    }
}

fun Seed.toElectrum() = when (this) {
    is Seed.Bip39 -> {
        val moneroMnemonic = CakeWalletStyleConverter.getLegacySeedFromBip39(mnemonic, passphrase)
            ?: throw IllegalArgumentException("BIP39 mnemonic can't be converted to Monero Legacy Mnemonic")
        Seed.Electrum(moneroMnemonic, "")
    }

    is Seed.Electrum -> this
}
