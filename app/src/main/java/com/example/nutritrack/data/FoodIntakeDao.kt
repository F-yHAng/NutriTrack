package com.example.nutritrack.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FoodIntakeDao {

    @Query(
        "SELECT * FROM food_intake WHERE patientUserId = :userId " +
            "ORDER BY submissionDate DESC, id DESC"
    )
    fun observeByUser(userId: Int): Flow<List<FoodIntake>>

    @Query(
        "SELECT * FROM food_intake WHERE patientUserId = :userId " +
            "AND submissionDate >= :startInclusive AND submissionDate < :endExclusive " +
            "ORDER BY submissionDate DESC, id DESC"
    )
    fun observeByUserAndDateRange(
        userId: Int,
        startInclusive: Long,
        endExclusive: Long
    ): Flow<List<FoodIntake>>

    @Query(
        "SELECT * FROM food_intake WHERE patientUserId = :userId " +
            "AND submissionDate >= :startInclusive AND submissionDate < :endExclusive " +
            "ORDER BY submissionDate ASC, id ASC"
    )
    suspend fun getByUserAndDateRange(
        userId: Int,
        startInclusive: Long,
        endExclusive: Long
    ): List<FoodIntake>

    @Query("SELECT * FROM food_intake WHERE id = :id AND patientUserId = :userId")
    suspend fun getByIdForUser(id: Long, userId: Int): FoodIntake?

    @Insert
    suspend fun insert(foodIntake: FoodIntake): Long

    @Update
    suspend fun updateEntity(foodIntake: FoodIntake): Int

    @Transaction
    suspend fun updateForUser(userId: Int, foodIntake: FoodIntake): Boolean {
        if (foodIntake.patientUserId != userId) return false
        if (getByIdForUser(foodIntake.id, userId) == null) return false
        return updateEntity(foodIntake) == 1
    }
}
