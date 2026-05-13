package io.horizontalsystems.monerokit.storage.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.horizontalsystems.monerokit.Balance

@Entity(tableName = "balance")
data class BalanceEntity(
    @PrimaryKey val id: Int = 0,
    val all: Long,
    val unlocked: Long,
) {
    fun toBalance() = Balance(all, unlocked)

    companion object {
        fun from(balance: Balance) = BalanceEntity(all = balance.all, unlocked = balance.unlocked)
    }
}
