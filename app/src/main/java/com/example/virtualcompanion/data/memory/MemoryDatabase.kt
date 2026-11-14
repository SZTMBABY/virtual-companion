package com.example.virtualcompanion.data.memory

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Message::class, Block::class, AcpRule::class, MessageFts::class],
    version = 1,
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
                // 记忆库是可重建的派生物（原文另有一份在 message 表），
                // 但 p5 还在迭代 schema，先允许破坏性迁移，稳定后改成 Migration。
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
                .also { instance = it }
        }
    }
}
