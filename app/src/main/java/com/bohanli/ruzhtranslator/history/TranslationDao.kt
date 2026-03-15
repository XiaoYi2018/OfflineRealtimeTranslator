package com.bohanli.ruzhtranslator.history

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update

@Dao
interface TranslationDao {

    @Insert
    suspend fun insert(record: TranslationRecord): Long

    @Update
    suspend fun update(record: TranslationRecord)

    // ---- History queries (isHistory = 1) ----

    @Query("SELECT * FROM translation_records WHERE isHistory = 1 ORDER BY createTime DESC")
    suspend fun getAllHistory(): List<TranslationRecord>

    @Query("SELECT * FROM translation_records WHERE isHistory = 1 AND createTime >= :sinceMs ORDER BY createTime DESC")
    suspend fun getHistorySince(sinceMs: Long): List<TranslationRecord>

    @Query("SELECT * FROM translation_records WHERE isHistory = 1 ORDER BY createTime ASC")
    suspend fun getAllHistoryOldestFirst(): List<TranslationRecord>

    @Query("SELECT * FROM translation_records WHERE isHistory = 1 AND createTime >= :sinceMs ORDER BY createTime ASC")
    suspend fun getHistorySinceOldestFirst(sinceMs: Long): List<TranslationRecord>

    @Query("SELECT COUNT(*) FROM translation_records WHERE isHistory = 1")
    suspend fun getHistoryCount(): Int

    // ---- Favorites queries (isFavorite = 1) ----

    @Query("SELECT * FROM translation_records WHERE isFavorite = 1 ORDER BY createTime DESC")
    suspend fun getAllFavorites(): List<TranslationRecord>

    @Query("SELECT * FROM translation_records WHERE isFavorite = 1 AND createTime >= :sinceMs ORDER BY createTime DESC")
    suspend fun getFavoritesSince(sinceMs: Long): List<TranslationRecord>

    @Query("SELECT * FROM translation_records WHERE isFavorite = 1 ORDER BY createTime ASC")
    suspend fun getAllFavoritesOldestFirst(): List<TranslationRecord>

    @Query("SELECT * FROM translation_records WHERE isFavorite = 1 AND createTime >= :sinceMs ORDER BY createTime ASC")
    suspend fun getFavoritesSinceOldestFirst(sinceMs: Long): List<TranslationRecord>

    @Query("SELECT COUNT(*) FROM translation_records WHERE isFavorite = 1")
    suspend fun getFavoritesCount(): Int

    // ---- Soft delete from history: clear isHistory flag ----

    @Query("UPDATE translation_records SET isHistory = 0 WHERE id IN (:ids)")
    suspend fun clearHistoryFlag(ids: List<Int>)

    // ---- Soft delete from favorites: clear isFavorite flag ----

    @Query("UPDATE translation_records SET isFavorite = 0 WHERE id IN (:ids)")
    suspend fun clearFavoriteFlag(ids: List<Int>)

    // ---- Physical delete: only when both flags are false ----

    @Query("DELETE FROM translation_records WHERE isHistory = 0 AND isFavorite = 0 AND id IN (:ids)")
    suspend fun deleteOrphans(ids: List<Int>)

    // ---- Toggle favorite ----

    @Query("UPDATE translation_records SET isFavorite = NOT isFavorite WHERE id = :id")
    suspend fun toggleFavorite(id: Int)

    @Query("UPDATE translation_records SET isFavorite = 1 WHERE id IN (:ids)")
    suspend fun setFavorite(ids: List<Int>)

    // ---- Cleanup by time (history only) ----

    @Query("UPDATE translation_records SET isHistory = 0 WHERE isHistory = 1 AND createTime < :beforeMs")
    suspend fun clearHistoryBefore(beforeMs: Long)

    @Query("DELETE FROM translation_records WHERE isHistory = 0 AND isFavorite = 0 AND createTime < :beforeMs")
    suspend fun deleteOrphansBefore(beforeMs: Long)

    // ---- Cleanup by time (favorites only) ----

    @Query("UPDATE translation_records SET isFavorite = 0 WHERE isFavorite = 1 AND createTime < :beforeMs")
    suspend fun clearFavoritesBefore(beforeMs: Long)
}
