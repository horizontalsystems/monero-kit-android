package io.horizontalsystems.monerokit

import android.content.Context
import android.util.Log
import io.horizontalsystems.monerokit.KitManager.KitState
import io.horizontalsystems.monerokit.storage.MoneroDatabase
import io.horizontalsystems.monerokit.storage.MoneroStorage
import io.horizontalsystems.monerokit.MoneroKit.Companion.MONERO_LEGACY_MNEMONIC_COUNT
import io.horizontalsystems.monerokit.data.MoneroOutput
import io.horizontalsystems.monerokit.data.NodeInfo
import io.horizontalsystems.monerokit.data.Subaddress
import io.horizontalsystems.monerokit.util.NodePinger
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
import io.horizontalsystems.monerokit.util.RestoreHeight
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.UUID
import java.util.concurrent.Executors

class MoneroKit(
    private val context: Context,
    private val seed: Seed,
    private val restoreHeight: Long,
    private val walletId: String,
    private val walletService: WalletService,
    private val storage: MoneroStorage,
    private val node: String,
    private val trustNode: Boolean
) : WalletService.Observer {

    private val kitId = UUID.randomUUID().toString()
    private val accountIndex = 0
    private val lifecycleDispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
    private val lifecycleScope = CoroutineScope(lifecycleDispatcher + SupervisorJob())
    private var started = false
    private var synced = false

    private val _syncStateFlow = MutableStateFlow<SyncState>(SyncState.NotSynced(SyncError.NotStarted))
    val syncStateFlow = _syncStateFlow.asStateFlow()

    private val _balanceFlow = MutableStateFlow(storage.getBalance() ?: Balance(0, 0))
    val balanceFlow = _balanceFlow.asStateFlow()

    private val _lastBlockUpdatedFlow = MutableSharedFlow<Unit>(replay = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val lastBlockUpdatedFlow = _lastBlockUpdatedFlow.asSharedFlow()

    private val _allTransactionsFlow = MutableStateFlow(storage.getTransactions())
    val allTransactionsFlow: StateFlow<List<TransactionInfo>> = _allTransactionsFlow

    private var nodeInfo: NodeInfo? = null

    val receiveAddress: String
        get() {
            val wallet = walletService.wallet
            return if (wallet != null) {
                val lastUnusedSubaddress = getSubaddresses(wallet).drop(1).lastOrNull { it.txsCount == 0L }
                lastUnusedSubaddress?.address ?: walletService.wallet?.newSubaddress ?: ""
            } else if (seed is Seed.WatchOnly) {
                seed.address
            } else {
                getAddress(seed, accountIndex, 1)
            }
        }

    val balance: Balance
        get() = _balanceFlow.value

    val lastBlockHeight: Long?
        get() = if (walletService.getConnectionStatus() == ConnectionStatus_Connected)
            walletService.getDaemonHeight()
        else
            null

    fun start() { lifecycleScope.launch { _start() } }
    fun stop()  { lifecycleScope.launch { _stop() } }

    private suspend fun _start() {
        if (started) return
        started = true

        _syncStateFlow.update { SyncState.Connecting(true) }

        var kitState = KitManager.checkAndGetInitialState(kitId)
        while (kitState == KitState.Waiting) {
            delay(1000)
            if (!started) return  // stop() was called while waiting
            kitState = KitManager.checkAndGetState(kitId)
        }

        if (kitState != KitState.Running) return

        // Global lock (shared across ALL kits) taken only here, AFTER the
        // KitManager wait loop, so a concurrent stop() can still flip `started`
        // (polled by the loop above) to break us out of waiting — no deadlock.
        KitManager.lifecycleMutex.withLock {
            if (!started) return  // stop() superseded us before we opened anything
            _syncStateFlow.update { SyncState.Connecting(false) }
            startInternal()
        }
    }

    private suspend fun _stop() {
        if (!started) return
        started = false
        // Same lock _start() uses. _stop is otherwise atomic (stopInternal() is
        // blocking JNI, no suspension), but _start is not — it suspends inside
        // startInternal() (withContext(IO)). Without the lock, this _stop could run
        // in that gap and tear down a wallet mid-open. Holding the lock makes any
        // in-flight startInternal() finish first, then stopInternal() runs on a
        // fully-opened wallet, so close() never frees the daemon SSL context while
        // the wallet's native refresh/long-poll threads are live (X509_STORE_free
        // crash). stopInternal() always runs: every started wallet is stopped, no
        // skipping.
        KitManager.lifecycleMutex.withLock {
            stopInternal()
            KitManager.removeRunning(kitId)
        }
    }

    private suspend fun startInternal(): Boolean {
        try {
            createWalletIfNotExists()

            walletService.setObserver(this@MoneroKit)
            var wallet = walletService.openWallet(walletId, "")

            if (wallet == null || wallet.restoreHeight != restoreHeight) {
                wallet = recreateAndOpenWallet()
                if (wallet == null) {
                    _syncStateFlow.update { SyncState.NotSynced(SyncError.InvalidNode("Invalid wallet")) }
                    return false
                }
            }

            val selectedNode = if (nodeInfo != null) {
                nodeInfo
            } else {
                NodeInfo.fromString(node)
            }

            if (selectedNode == null) {
                _syncStateFlow.update { SyncState.NotSynced(SyncError.InvalidNode("Invalid node")) }
                return false
            }

            nodeInfo = selectedNode
            WalletManager.getInstance().setDaemon(selectedNode)

            val status = walletService.start(wallet, trustNode)

            if (status == null || !status.isOk) {
                _syncStateFlow.update { SyncState.NotSynced(SyncError.StartError(status?.toString() ?: "Wallet is NULL")) }
                return false
            }

            // a previous run may have died between freeze and thaw of a coin-controlled
            // send; the kit never freezes outputs durably, so thaw whatever is left over
            runCatching { wallet.thawAllCoins() }

            return true
        } catch (ex: Exception) {
            _syncStateFlow.update { SyncState.NotSynced(SyncError.StartError(ex.message ?: ex.javaClass.simpleName)) }
            return false
        }
    }

    private suspend fun recreateAndOpenWallet(): Wallet? {
        walletService.stop()
        storage.clearAll()
        deleteWalletFiles(context, walletId)
        createWalletIfNotExists()
        walletService.setObserver(this@MoneroKit)
        return walletService.openWallet(walletId, "")
    }

    private fun stopInternal() {
        try {
            walletService.stop()
        } catch (err: Throwable) {
            Log.e("MoneroKit", "error in service.stop()", err)
        }
    }

    fun saveState() {
        walletService.requestSave()
    }

    fun send(
        amount: Long,
        address: String,
        memo: String?,
        selectedOutputs: List<String>? = null
    ): String {
        val txData = buildTxData(amount, address, memo, selectedOutputs)

        walletService.createTransaction(txData)
        return walletService.sendTransaction(memo)
    }

    fun getUnspentOutputs(): List<MoneroOutput> {
        val wallet = walletService.wallet ?: return emptyList()

        return wallet.getCoinsInfos(true)
            .filter { it.isKeyImageKnown }
            .map { coin ->
                MoneroOutput(
                    keyImage = coin.keyImage,
                    amount = coin.amount,
                    txHash = coin.txHash,
                    subaddressIndex = coin.addressIndex,
                    blockHeight = coin.blockheight,
                    frozen = coin.isFrozen,
                    unlocked = coin.isUnlocked
                )
            }
    }

    fun estimateFee(
        amount: Long,
        address: String,
        memo: String?
    ): Long {
        val wallet = walletService.wallet ?: throw IllegalStateException("Wallet is NULL")
        val txData = buildTxData(amount, address, memo)

        return wallet.estimateTransactionFee(txData)
    }

    fun getSubaddresses(): List<Subaddress> {
        val wallet = walletService.wallet
        if (wallet == null) {
            if (seed is Seed.WatchOnly) {
                return listOf(Subaddress(0, 0, seed.address, ""))
            }
            return generateSubaddresses(seed, accountIndex, 2)
        }

        return getSubaddresses(wallet)
    }

    private fun getSubaddresses(wallet: Wallet): List<Subaddress> {
        val list = mutableListOf<Subaddress>()
        for (i in 0..wallet.numSubaddresses) {
            wallet.getSubaddressObject(i)?.let {
                list.add(it)
            }
        }
        return list
    }

    fun getSubaddress(accountIndex: Int, subaddressIndex: Int): Subaddress? {
        return walletService.wallet?.getSubaddressObject(accountIndex, subaddressIndex)
    }

    fun getKeys(): Keys? {
        val wallet = walletService.wallet ?: return null

        return Keys(
            privateSpendKey = wallet.secretSpendKey,
            publicSpendKey = wallet.publicSpendKey,
            privateViewKey = wallet.secretViewKey,
            publicViewKey = wallet.publicViewKey
        )
    }

    fun getTxKey(txHash: String): String? {
        val wallet = walletService.wallet ?: return null
        val key = wallet.getTxKey(txHash)
        return if (key.isNullOrEmpty()) null else key
    }

    private fun buildTxData(
        amount: Long,
        destination: String,
        memo: String?,
        selectedOutputs: List<String>? = null
    ) = TxData().apply {
        // with a manual selection, "spend everything" means the selection sum, not the
        // wallet balance: SWEEP_ALL then sweeps just the selected outputs because all
        // others get frozen during transaction creation
        val spendableAmount = if (selectedOutputs != null) {
            val selectedAmount = selectedOutputsAmount(selectedOutputs)
            require(amount <= selectedAmount) {
                "Amount $amount exceeds total of selected outputs $selectedAmount"
            }
            selectedAmount
        } else {
            balance.unlocked
        }
        this.amount = if (amount == spendableAmount) Wallet.SWEEP_ALL else amount
        this.destination = destination
        mixin = MIXIN
        priority = PendingTransaction.Priority.Priority_Medium
        if (!memo.isNullOrEmpty()) {
            userNotes = UserNotes(memo)
        }
        selectedKeyImages = selectedOutputs?.toTypedArray()
    }

    private fun selectedOutputsAmount(selectedOutputs: List<String>): Long {
        require(selectedOutputs.isNotEmpty()) { "No outputs selected" }
        val wallet = walletService.wallet ?: throw IllegalStateException("Wallet is NULL")

        val coinsByKeyImage = wallet.getCoinsInfos(true)
            .filter { it.isKeyImageKnown }
            .associateBy { it.keyImage }

        var total = 0L
        for (keyImage in selectedOutputs) {
            val coin = coinsByKeyImage[keyImage]
                ?: throw IllegalArgumentException("Unknown output: $keyImage")
            if (!coin.isSpendable || coin.isFrozen) {
                throw IllegalArgumentException("Output is locked or not spendable: $keyImage")
            }
            total += coin.amount
        }
        return total
    }

    private suspend fun createWalletIfNotExists() = withContext(Dispatchers.IO) {
        val walletFolder: File = Helper.getWalletRoot(context)
        if (!walletFolder.isDirectory) {
            return@withContext
        }
        val keysFile = File(walletFolder, "$walletId.keys")

        // walletExists() in the native layer checks only the .keys file
        if (keysFile.exists()) {
            return@withContext
        }

        val newWalletFile = File(walletFolder, walletId)
        val walletPassword = ""
        val success = when (seed) {
            is Seed.Bip39,
            is Seed.Electrum -> {
                val electrum = seed.toElectrum()
                val offset = electrum.passphrase
                val mnemonic = electrum.mnemonic.joinToString(" ")
                val newWallet = WalletManager.getInstance().recoveryWallet(newWalletFile, walletPassword, mnemonic, offset, restoreHeight)
                val success = checkAndCloseWallet(newWallet)

                val walletFile = File(walletFolder, walletId)
                walletFile.delete()

                success
            }

            is Seed.WatchOnly -> {
                val newWallet = WalletManager.getInstance().createWalletWithKeys(
                    /* aFile = */ newWalletFile,
                    /* password = */ walletPassword,
                    /* language = */ "",
                    /* restoreHeight = */ restoreHeight,
                    /* addressString = */ seed.address,
                    /* viewKeyString = */ seed.viewPrivateKey,
                    /* spendKeyString = */ ""
                )

                checkAndCloseWallet(newWallet)
            }
        }

        return@withContext
    }

    // Observer ====================================

    private var firstBlock: Long = 0

    override fun onRefreshed(wallet: Wallet, fullStatus: Wallet.Status, full: Boolean): Boolean {
        if (!fullStatus.isOk) {
            _syncStateFlow.update {
                SyncState.NotSynced(IllegalStateException(fullStatus.toString()))
            }
            return false
        }

        val historyAll: List<TransactionInfo?>? = wallet.history.all

        if (historyAll != null) {
            _allTransactionsFlow.update {
                historyAll.mapNotNull { it }
            }
        }

        if (wallet.isSynchronized && !synced) {
            synced = true
            walletService.requestSave()
        }

        if (!wallet.isSynchronized) {
            val daemonHeight: Long = walletService.getDaemonHeight()
            val walletHeight = wallet.getBlockChainHeight()
            val remainingBlocks = daemonHeight - walletHeight

            if (firstBlock == 0L) {
                firstBlock = walletHeight
            }

            val totalBlocks = daemonHeight - restoreHeight
            val progress: Double = if (totalBlocks > 0) {
                1 - remainingBlocks.toDouble() / totalBlocks
            } else {
                1.0
            }

            if (daemonHeight <= 0L || totalBlocks <= 0L || progress <= 0) {
                _syncStateFlow.update {
                    SyncState.Syncing(null, null)
                }
            } else if (remainingBlocks <= 0L) {
                _syncStateFlow.update {
                    SyncState.Syncing(1.0, 0)
                }
            } else {
                _syncStateFlow.update {
                    SyncState.Syncing(progress, remainingBlocks)
                }
            }
        } else {
            _syncStateFlow.update {
                SyncState.Synced
            }
        }

        _lastBlockUpdatedFlow.tryEmit(Unit)

        val newBalance = walletService.wallet.let { wallet ->
            Balance(wallet?.balance ?: 0L, wallet?.unlockedBalance ?: 0L)
        }
        _balanceFlow.update { newBalance }
        storage.updateBalance(newBalance)

        if (historyAll != null) {
            storage.updateTransactions(historyAll.mapNotNull { it })
        }

        return true
    }

    override fun onInitialWalletState(balance: Balance, txs: List<TransactionInfo?>?) {
        _balanceFlow.update { balance }
        storage.updateBalance(balance)

        txs?.let {
            val list = it.mapNotNull { tx -> tx }
            _allTransactionsFlow.update { list }
            storage.updateTransactions(list)
        }
    }

    private fun checkAndCloseWallet(aWallet: Wallet): Boolean {
        val walletStatus = aWallet.status
        if (!walletStatus.isOk) {
            throw IllegalStateException("Wallet recovery error: ${walletStatus.errorString}")
        }
        aWallet.close()
        return walletStatus.isOk
    }

    fun statusInfo(): Map<String, Any> {
        val statusInfo = LinkedHashMap<String, Any>()

        statusInfo["Node"] = nodeInfo?.name?.let { "$it (${if (trustNode) "trusted" else "untrusted"})" } ?: "NULL"
        statusInfo["Wallet Status"] = walletService.wallet?.status ?: "NULL"
        statusInfo["Sync State"] = _syncStateFlow.value.description
        statusInfo["Last Block Height"] = lastBlockHeight ?: 0L
        statusInfo["Wallet Height"] = walletService.wallet?.blockChainHeight ?: 0L
        statusInfo["Daemon Height"] = walletService.getDaemonHeight()
        statusInfo["Connection Status"] = walletService.getConnectionStatus()
        statusInfo["Kit started"] = started
        statusInfo["Service running"] = WalletService.running

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
            seed: Seed.Bip39,
            restoreDateOrHeight: String,
            walletId: String,
            node: String,
            trustNode: Boolean
        ): MoneroKit {
            return getInstance(context, seed.toElectrum(), restoreDateOrHeight, walletId, node, trustNode)
        }

        fun getInstance(
            context: Context,
            seed: Seed,
            restoreDateOrHeight: String,
            walletId: String,
            node: String,
            trustNode: Boolean
        ): MoneroKit {
            val walletService = WalletService(context)
            val restoreHeight = getHeight(restoreDateOrHeight)
            val db = MoneroDatabase.build(context, "Monero-$walletId")
            val storage = MoneroStorage(db)

            NetCipherHelper.createInstance(context)

            return MoneroKit(context, seed, restoreHeight, walletId, walletService, storage, node, trustNode)
        }

        fun validateAddress(address: String) {
            if (!Wallet.isAddressValid(address)) {
                throw IllegalArgumentException("Invalid address")
            }
        }

        fun validatePrivateViewKey(privateViewKey: String, address: String) {
            val error = Wallet.isPrivateViewKeyValid(privateViewKey, address)
            check(error == null) { error }
        }

        fun validatePrivateSpendKey(privateSpendKey: String, address: String) {
            val error = Wallet.isPrivateSpendKeyValid(privateSpendKey, address)
            check(error == null) { error }
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

        fun getAddress(seed: Seed, accountIndex: Int, addressIndex: Int): String {
            val electrumSeed = seed.toElectrum()
            val mnemonic = electrumSeed.mnemonic.joinToString(" ")
            val passphrase = electrumSeed.passphrase

            return WalletManager.getAddress(mnemonic, passphrase, accountIndex, addressIndex)
        }

        private fun generateSubaddresses(seed: Seed, accountIndex: Int, count: Int): List<Subaddress> {
            val electrumSeed = seed.toElectrum()
            val mnemonic = electrumSeed.mnemonic.joinToString(" ")
            val passphrase = electrumSeed.passphrase

            val subaddresses = mutableListOf<Subaddress>()
            for (i in 0 until count) {
                val address = WalletManager.getAddress(mnemonic, passphrase, accountIndex, i)
                val subaddress = Subaddress(accountIndex, i, address, "")
                subaddresses.add(subaddress)
            }
            return subaddresses
        }

        fun restoreHeightForNewWallet(): Long {
            return RestoreHeight.getInstance().getHeight(Calendar.getInstance().getTime())
        }

        fun restoreHeightForDate(date: Date): Long {
            return RestoreHeight.getInstance().getHeight(date)
        }

        fun dateForRestoreHeight(height: Long): Date? {
            return RestoreHeight.getInstance().getDate(height)
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

            return height
        }

        suspend fun pingNodes(context: Context, nodes: List<String>): List<NodePingResult> = withContext(Dispatchers.IO) {
            // pingNodes can be called without an active wallet (e.g. at app startup),
            // so make sure the OkHttp client is initialized before any request.
            NetCipherHelper.createInstance(context)
            val pairs = nodes.mapNotNull { serialized ->
                val nodeInfo = NodeInfo.fromString(serialized)
                if (nodeInfo == null) {
                    Log.w("MoneroKit/ping", "failed to parse: $serialized")
                }
                nodeInfo?.let { serialized to it }
            }
            NodePinger.execute(pairs.map { it.second }) { nodeInfo ->
                Log.d("MoneroKit/ping", "done: ${nodeInfo.host} valid=${nodeInfo.isValid} rt=${nodeInfo.getResponseTime().toInt()}ms h=${nodeInfo.getHeight()}")
            }
            pairs.map { (serialized, nodeInfo) ->
                NodePingResult(
                    serialized = serialized,
                    responseTime = nodeInfo.getResponseTime(),
                    height = nodeInfo.getHeight(),
                    isValid = nodeInfo.isValid
                )
            }
        }

        fun deleteWallet(context: Context, walletId: String): Boolean {
            context.deleteDatabase("Monero-$walletId")
            return deleteWalletFiles(context, walletId)
        }

        private fun deleteWalletFiles(context: Context, walletId: String): Boolean {
            val walletFile: File = Helper.getWalletFile(context, walletId)
            return deleteWallet(walletFile)
        }

        private fun deleteWallet(walletFile: File): Boolean {
            val dir = walletFile.getParentFile()
            val name = walletFile.getName()
            var success = true
            val cacheFile = File(dir, name)
            if (cacheFile.exists()) {
                success = cacheFile.delete()
            }
            success = File(dir, "$name.keys").delete() && success
            val addressFile = File(dir, "$name.address.txt")
            if (addressFile.exists()) {
                success = addressFile.delete() && success
            }
            return success
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

data class NodePingResult(
    val serialized: String,
    val responseTime: Double,   // milliseconds; Double.MAX_VALUE means unreachable
    val height: Long,
    val isValid: Boolean
) {
    companion object {
        const val PING_GOOD = 333.0    // ms
        const val PING_MEDIUM = 667.0  // ms
    }
}

data class Balance(
    val all: Long,
    val unlocked: Long
)

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

    data class WatchOnly(val address: String, val viewPrivateKey: String) : Seed()
}

fun Seed.toElectrum() = when (this) {
    is Seed.Bip39 -> {
        val moneroMnemonic = CakeWalletStyleConverter.getLegacySeedFromBip39(mnemonic, passphrase)
            ?: throw IllegalArgumentException("BIP39 mnemonic can't be converted to Monero Legacy Mnemonic")
        Seed.Electrum(moneroMnemonic, "")
    }

    is Seed.WatchOnly -> {
        throw IllegalArgumentException("WatchOnly can't be converted to Monero Legacy Mnemonic")
    }

    is Seed.Electrum -> this
}
