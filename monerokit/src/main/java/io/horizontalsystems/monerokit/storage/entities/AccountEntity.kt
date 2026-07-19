package io.horizontalsystems.monerokit.storage.entities

import androidx.room.Entity
import androidx.room.PrimaryKey
import io.horizontalsystems.monerokit.Balance
import io.horizontalsystems.monerokit.data.MoneroAccount

@Entity(tableName = "accounts")
data class AccountEntity(
    @PrimaryKey val accountIndex: Int,
    val label: String,
    val all: Long,
    val unlocked: Long,
) {
    fun toMoneroAccount() = MoneroAccount(accountIndex, label, Balance(all, unlocked))

    companion object {
        fun from(account: MoneroAccount) = AccountEntity(
            accountIndex = account.index,
            label = account.label,
            all = account.balance.all,
            unlocked = account.balance.unlocked,
        )
    }
}
