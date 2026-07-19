package io.horizontalsystems.monerokit.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import io.horizontalsystems.monerokit.storage.entities.AccountEntity

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY accountIndex")
    fun getAll(): List<AccountEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(accounts: List<AccountEntity>)

    @Query("DELETE FROM accounts")
    fun deleteAll()

    @Transaction
    fun replaceAll(accounts: List<AccountEntity>) {
        deleteAll()
        insertAll(accounts)
    }
}
