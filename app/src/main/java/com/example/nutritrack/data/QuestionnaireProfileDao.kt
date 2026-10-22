package com.example.nutritrack.data

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert

@Dao
interface QuestionnaireProfileDao {
    @Query("SELECT * FROM questionnaire_profiles WHERE userId = :userId")
    suspend fun getByUserId(userId: Int): QuestionnaireProfile?

    @Upsert
    suspend fun upsert(profile: QuestionnaireProfile)
}
