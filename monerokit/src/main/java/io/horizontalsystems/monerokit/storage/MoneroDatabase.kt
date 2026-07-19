package io.horizontalsystems.monerokit.storage

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import io.horizontalsystems.monerokit.storage.dao.AccountDao
import io.horizontalsystems.monerokit.storage.dao.BalanceDao
import io.horizontalsystems.monerokit.storage.dao.TransactionDao
import io.horizontalsystems.monerokit.storage.entities.AccountEntity
import io.horizontalsystems.monerokit.storage.entities.BalanceEntity
import io.horizontalsystems.monerokit.storage.entities.TransactionEntity

@Database(
    entities = [
        BalanceEntity::class,
        TransactionEntity::class,
        AccountEntity::class,
    ],
    version = 2,
    exportSchema = false,
)
abstract class MoneroDatabase : RoomDatabase() {
    abstract fun balanceDao(): BalanceDao
    abstract fun transactionDao(): TransactionDao
    abstract fun accountDao(): AccountDao

    companion object {
        fun build(context: Context, dbName: String): MoneroDatabase {
            return Room.databaseBuilder(context, MoneroDatabase::class.java, dbName)
                .allowMainThreadQueries()
                .addMigrations(MIGRATION_1_2)
                .fallbackToDestructiveMigration()
                .build()
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE TABLE IF NOT EXISTS `accounts` (`accountIndex` INTEGER NOT NULL, `label` TEXT NOT NULL, `all` INTEGER NOT NULL, `unlocked` INTEGER NOT NULL, PRIMARY KEY(`accountIndex`))"
                )
            }
        }
    }
}
