package com.ledger.collector.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [SmsMessageEntity::class, TransactionEntity::class],
    version = 2,
    exportSchema = false
)
abstract class LedgerDatabase : RoomDatabase() {
    abstract fun smsMessageDao(): SmsMessageDao
    abstract fun transactionDao(): TransactionDao

    companion object {
        fun build(context: Context): LedgerDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                LedgerDatabase::class.java,
                "ledger-collector.db"
            )
                // The transactions table is a server-backed cache; on schema change just
                // rebuild it (a refresh re-populates it). The SMS outbox is cheap to rebuild too.
                .fallbackToDestructiveMigration()
                .build()
    }
}
