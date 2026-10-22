package com.example.nutritrack.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface NutritionPlanDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(plan: NutritionPlan)

    @Query("SELECT * FROM nutrition_plans WHERE runId = :runId")
    suspend fun getByRun(runId: String): NutritionPlan?

    @Query("SELECT * FROM nutrition_plans WHERE id = :planId AND userId = :userId")
    suspend fun getByIdForUser(planId: String, userId: Int): NutritionPlan?

    @Query(
        "SELECT * FROM nutrition_plans WHERE userId = :userId " +
            "ORDER BY createdAtEpochMillis DESC, id DESC"
    )
    suspend fun getByUser(userId: Int): List<NutritionPlan>

    @Query(
        "UPDATE nutrition_plans SET status = 'CONFIRMED', " +
            "confirmedAtEpochMillis = :confirmedAtEpochMillis, " +
            "confirmationActionId = :confirmationActionId, " +
            "updatedAtEpochMillis = :confirmedAtEpochMillis " +
            "WHERE id = :planId AND userId = :userId AND status = 'DRAFT'"
    )
    suspend fun confirmDraftIfStillDraft(
        planId: String,
        userId: Int,
        confirmationActionId: String,
        confirmedAtEpochMillis: Long
    ): Int

    @Query(
        "UPDATE nutrition_plans SET status = 'ABANDONED', " +
            "updatedAtEpochMillis = :updatedAtEpochMillis " +
        "WHERE id = :planId AND userId = :userId AND status = 'DRAFT'"
    )
    suspend fun abandonDraftIfStillDraft(
        planId: String,
        userId: Int,
        updatedAtEpochMillis: Long
    ): Int

    @Query(
        "UPDATE nutrition_plans SET status = 'ABANDONED', " +
            "updatedAtEpochMillis = :updatedAtEpochMillis " +
            "WHERE id = :planId AND userId = :userId AND status IN ('CONFIRMED', 'ACTIVE')"
    )
    suspend fun abandonConfirmedOrActiveIfStillEligible(
        planId: String,
        userId: Int,
        updatedAtEpochMillis: Long
    ): Int

    @Query(
        "UPDATE nutrition_plans SET status = 'COMPLETED', " +
            "updatedAtEpochMillis = :completedAtEpochMillis, " +
            "completedAtEpochMillis = :completedAtEpochMillis " +
            "WHERE id = :planId AND userId = :userId AND status IN ('CONFIRMED', 'ACTIVE')"
    )
    suspend fun completeConfirmedOrActiveIfStillEligible(
        planId: String,
        userId: Int,
        completedAtEpochMillis: Long
    ): Int
}
