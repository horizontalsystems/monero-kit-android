package io.horizontalsystems.monerokit.storage.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.horizontalsystems.monerokit.model.TransactionInfo
import io.horizontalsystems.monerokit.model.Transfer

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey val hash: String,
    val direction: Int,
    val isPending: Boolean,
    val isFailed: Boolean,
    val amount: Long,
    val fee: Long,
    val blockheight: Long,
    val timestamp: Long,
    val paymentId: String?,
    val accountIndex: Int,
    val addressIndex: Int,
    val confirmations: Long,
    val unlockTime: Long,
    val subaddressLabel: String?,
    val txKey: String?,
    val notes: String?,
    val address: String?,
    // destinations as "amount:address" pairs joined by ';' (monero addresses are base58)
    val transfers: String?,
) {
    fun toTransactionInfo(): TransactionInfo {
        val tx = TransactionInfo(
            direction,
            isPending,
            isFailed,
            amount,
            fee,
            blockheight,
            hash,
            timestamp,
            paymentId,
            accountIndex,
            addressIndex,
            confirmations,
            unlockTime,
            subaddressLabel ?: "",
            decodeTransfers(transfers),
        )
        tx.txKey = txKey
        tx.notes = notes
        tx.address = address
        return tx
    }

    companion object {
        fun from(tx: TransactionInfo) = TransactionEntity(
            hash = tx.hash,
            direction = tx.direction.ordinal,
            isPending = tx.isPending,
            isFailed = tx.isFailed,
            amount = tx.amount,
            fee = tx.fee,
            blockheight = tx.blockheight,
            timestamp = tx.timestamp,
            paymentId = tx.paymentId,
            accountIndex = tx.accountIndex,
            addressIndex = tx.addressIndex,
            confirmations = tx.confirmations,
            unlockTime = tx.unlockTime,
            subaddressLabel = tx.subaddressLabel,
            txKey = tx.txKey,
            notes = tx.notes,
            address = tx.address,
            transfers = encodeTransfers(tx.transfers),
        )

        private fun encodeTransfers(transfers: List<Transfer>?): String? =
            transfers?.takeIf { it.isNotEmpty() }?.joinToString(";") { "${it.amount}:${it.address}" }

        private fun decodeTransfers(value: String?): List<Transfer>? =
            value?.split(";")?.mapNotNull { entry ->
                val amount = entry.substringBefore(":").toLongOrNull() ?: return@mapNotNull null
                Transfer(amount, entry.substringAfter(":"))
            }
    }
}
