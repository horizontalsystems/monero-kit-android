package io.horizontalsystems.monerokit.storage

import io.horizontalsystems.monerokit.Balance
import io.horizontalsystems.monerokit.model.TransactionInfo
import io.horizontalsystems.monerokit.storage.entities.BalanceEntity
import io.horizontalsystems.monerokit.storage.entities.TransactionEntity

class MoneroStorage(private val db: MoneroDatabase) {

    fun getBalance(): Balance? = db.balanceDao().get()?.toBalance()

    fun updateBalance(balance: Balance) {
        db.balanceDao().insert(BalanceEntity.from(balance))
    }

    fun getTransactions(): List<TransactionInfo> =
        db.transactionDao().getAll().map { it.toTransactionInfo() }

    fun updateTransactions(transactions: List<TransactionInfo>) {
        db.transactionDao().deleteAll()
        db.transactionDao().insertAll(transactions.map { TransactionEntity.from(it) })
    }

    fun clearAll() {
        db.balanceDao().deleteAll()
        db.transactionDao().deleteAll()
    }
}
