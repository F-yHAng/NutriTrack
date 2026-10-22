package com.example.nutritrack.data

import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

data class DailyIntakeFormInput(
    val recordId: Long? = null,
    val foodName: String,
    val localDate: LocalDate,
    val vegetablesServings: String,
    val fruitServings: String,
    val grainsServings: String,
    val meatServings: String,
    val dairyServings: String,
    val discretionaryServings: String,
    val sodiumMg: String,
    val alcoholStandardDrinks: String,
    val waterMl: String,
    val totalBeverageMl: String,
    val sugarG: String,
    val saturatedFatG: String,
    val unsaturatedFatG: String
)

data class DailyIntakeSummary(
    val localDate: LocalDate,
    val recordCount: Int,
    val vegetablesServings: Double,
    val fruitServings: Double,
    val grainsServings: Double,
    val meatServings: Double,
    val dairyServings: Double,
    val discretionaryServings: Double,
    val waterMl: Double,
    val sugarG: Double
)

interface DailyIntakeDataSource {
    fun observeDailyIntakes(userId: Int, localDate: LocalDate): Flow<List<FoodIntake>>
    fun observeDailyIntakeSummaries(userId: Int): Flow<List<DailyIntakeSummary>>
    suspend fun getDailyIntake(userId: Int, recordId: Long): FoodIntake?
    suspend fun createDailyIntake(userId: Int, input: DailyIntakeFormInput): Long
    suspend fun updateDailyIntake(userId: Int, input: DailyIntakeFormInput)
}

class DailyIntakeValidationException(
    val problems: List<String>
) : IllegalArgumentException(problems.joinToString(separator = "\n"))

class DailyIntakeAccessException : IllegalStateException(
    "Daily intake record was not found for the current user"
)
