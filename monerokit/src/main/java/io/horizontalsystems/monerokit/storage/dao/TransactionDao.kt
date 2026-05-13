package io.horizontalsystems.monerokit.storage.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import io.horizontalsystems.monerokit.storage.entities.TransactionEntity

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAll(): List<TransactionEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    fun insertAll(transactions: List<TransactionEntity>)

    @Query("DELETE FROM transactions")
    fun deleteAll()
}
