package com.mythara.secret.observe.vault

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Database(
    entities = [LearningEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class LearningVaultDb : RoomDatabase() {
    abstract fun learnings(): LearningDao

    companion object {
        const val DATABASE_NAME = "mythara_learning_vault.db"
    }
}

/**
 * Hilt wiring for the vault. v1 uses plain Room (no SQLCipher); the
 * database file lives in the app's private data directory which is
 * already sandboxed by Android. SQLCipher will land in M8.2.2 once
 * the Gemma extractor is in place and the threat model warrants it.
 */
@Module
@InstallIn(SingletonComponent::class)
object LearningVaultModule {
    @Provides
    @Singleton
    fun provideLearningVaultDb(@ApplicationContext ctx: Context): LearningVaultDb =
        Room.databaseBuilder(ctx, LearningVaultDb::class.java, LearningVaultDb.DATABASE_NAME)
            // Falling back to destructive migration is fine pre-M9 — there
            // are no production installs of Mythara yet. Post-ship we'll
            // need real migrations.
            .fallbackToDestructiveMigration()
            .build()

    @Provides
    @Singleton
    fun provideLearningDao(db: LearningVaultDb): LearningDao = db.learnings()
}
