package com.example.virtualcompanion.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Message::class, AcpRule::class, KvEntry::class],
    version = 2,
    exportSchema = false,
)
abstract class MemoryDatabase : RoomDatabase() {

    abstract fun dao(): MemoryDao

    companion object {
        private const val NAME = "memory.db"

        @Volatile
        private var instance: MemoryDatabase? = null

        fun get(context: Context): MemoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(
                context.applicationContext,
                MemoryDatabase::class.java,
                NAME,
            )
                // p5-8 把记忆引擎换成 billion-context 内核后，block / message_fts
                // 两张表已无用途（分层与检索都在内核 state 里），直接破坏性重建。
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}
