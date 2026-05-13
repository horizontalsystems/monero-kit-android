package io.horizontalsystems.monerokit.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.horizontalsystems.monerokit.storage.entities.BalanceEntity

@Dao
interface BalanceDao {
    @Query("SELECT * FROM balance WHERE id = 0")
    fun get(): BalanceEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insert(balance: BalanceEntity)

    @Query("DELETE FROM balance")
    fun deleteAll()
}
