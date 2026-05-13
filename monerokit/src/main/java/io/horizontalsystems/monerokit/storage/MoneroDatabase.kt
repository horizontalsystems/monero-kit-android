package io.horizontalsystems.monerokit.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import io.horizontalsystems.monerokit.storage.dao.BalanceDao
import io.horizontalsystems.monerokit.storage.dao.TransactionDao
import io.horizontalsystems.monerokit.storage.entities.BalanceEntity
import io.horizontalsystems.monerokit.storage.entities.TransactionEntity

@Database(
    entities = [
        BalanceEntity::class,
        TransactionEntity::class,
    ],
    version = 1,
    exportSchema = false,
)
abstract class MoneroDatabase : RoomDatabase() {
    abstract fun balanceDao(): BalanceDao
    abstract fun transactionDao(): TransactionDao

    companion object {
        fun build(context: Context, dbName: String): MoneroDatabase {
            return Room.databaseBuilder(context, MoneroDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .fallbackToDestructiveMigration()
                .build()
        }
    }
}
