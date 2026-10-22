package com.example.nutritrack.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface NutriCoachTipDao {
    
    @Query("SELECT * FROM nutricoach_tips WHERE patientUserId = :userId ORDER BY timestamp DESC")
    fun getTipsForUser(userId: Int): Flow<List<NutriCoachTip>>
    
    @Query("SELECT * FROM nutricoach_tips WHERE patientUserId = :userId AND tipType = :tipType ORDER BY timestamp DESC")
    fun getTipsForUserByType(userId: Int, tipType: String): Flow<List<NutriCoachTip>>
    
    @Insert
    suspend fun insertTip(tip: NutriCoachTip): Long
    
    @Update
    suspend fun updateTip(tip: NutriCoachTip)
    
    @Delete
    suspend fun deleteTip(tip: NutriCoachTip)
    
    @Query("DELETE FROM nutricoach_tips WHERE patientUserId = :userId")
    suspend fun deleteAllTipsForUser(userId: Int)
    
    @Query("SELECT COUNT(*) FROM nutricoach_tips WHERE patientUserId = :userId")
    suspend fun getTipCountForUser(userId: Int): Int
    
    @Query("SELECT * FROM nutricoach_tips WHERE id = :tipId")
    suspend fun getTipById(tipId: Long): NutriCoachTip?
} 