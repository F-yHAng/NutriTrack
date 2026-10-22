package com.example.nutritrack.agent

import com.example.nutritrack.data.FoodIntake
import com.example.nutritrack.data.FoodIntakeDao
import com.example.nutritrack.data.Patient
import com.example.nutritrack.data.PatientDao
import com.example.nutritrack.data.QuestionnaireProfileDao
import com.example.nutritrack.data.QuestionnaireProfileRules
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.abs

interface AgentReadDataSource {
    suspend fun getNutritionProfile(userId: Int): AgentNutritionProfile?

    suspend fun getIntakeHistory(
        userId: Int,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<AgentDailyIntake>
}

class RoomAgentReadDataSource(
    private val patientDao: PatientDao,
    private val questionnaireProfileDao: QuestionnaireProfileDao,
    private val foodIntakeDao: FoodIntakeDao,
    private val localZoneId: ZoneId = ZoneId.systemDefault()
) : AgentReadDataSource {
    override suspend fun getNutritionProfile(userId: Int): AgentNutritionProfile? {
        require(userId > 0) { "A valid trusted user is required" }
        val patient = patientDao.getPatientByUserId(userId) ?: return null
        val questionnaire = questionnaireProfileDao.getByUserId(userId)
            ?.takeIf(QuestionnaireProfileRules::isValid)
            ?: return null
        val male = when (patient.sex.trim().lowercase()) {
            "male" -> true
            "female" -> false
            else -> throw AgentToolExecutionFailure(AgentToolErrorCode.INVALID_STORED_DATA)
        }
        return AgentNutritionProfile(
            selectedFoodCategories = buildList {
                if (questionnaire.canEatFruits) add("Fruits")
                if (questionnaire.canEatVegetables) add("Vegetables")
                if (questionnaire.canEatGrains) add("Grains")
                if (questionnaire.canEatRedMeat) add("Red Meat")
                if (questionnaire.canEatSeafood) add("Seafood")
                if (questionnaire.canEatPoultry) add("Poultry")
                if (questionnaire.canEatFish) add("Fish")
                if (questionnaire.canEatEggs) add("Eggs")
                if (questionnaire.canEatNutsAndSeeds) add("Nuts/Seeds")
            },
            persona = questionnaire.selectedPersona,
            mainMealTime = questionnaire.biggestMealTime,
            sleepTime = questionnaire.sleepTime,
            wakeTime = questionnaire.wakeUpTime,
            overallHeifaScore = patient.scoreForSex(
                male,
                patient.hEIFAtotalscoreMale,
                patient.hEIFAtotalscoreFemale
            ),
            fruitHeifaScore = patient.scoreForSex(
                male,
                patient.fruitHEIFAscoreMale,
                patient.fruitHEIFAscoreFemale
            ),
            vegetableHeifaScore = patient.scoreForSex(
                male,
                patient.vegetablesHEIFAscoreMale,
                patient.vegetablesHEIFAscoreFemale
            )
        )
    }

    override suspend fun getIntakeHistory(
        userId: Int,
        startDate: LocalDate,
        endDate: LocalDate
    ): List<AgentDailyIntake> {
        require(userId > 0) { "A valid trusted user is required" }
        val startInclusive = startDate.atStartOfDay(localZoneId).toInstant().toEpochMilli()
        val endExclusive = endDate.plusDays(1).atStartOfDay(localZoneId).toInstant().toEpochMilli()
        return foodIntakeDao.getByUserAndDateRange(userId, startInclusive, endExclusive)
            .groupBy { intake ->
                Instant.ofEpochMilli(intake.submissionDate).atZone(localZoneId).toLocalDate()
            }
            .toSortedMap()
            .map { (date, records) -> records.toDailyIntake(date) }
    }

    private fun Patient.scoreForSex(male: Boolean, maleScore: Double, femaleScore: Double): Double =
        (if (male) maleScore else femaleScore).validatedStoredNumber()

    private fun List<FoodIntake>.toDailyIntake(date: LocalDate): AgentDailyIntake = AgentDailyIntake(
        date = date,
        recordCount = size,
        vegetablesServings = sumOf { it.vegetablesServings.validatedStoredNumber() },
        fruitServings = sumOf { it.fruitServings.validatedStoredNumber() },
        grainsServings = sumOf { it.grainsServings.validatedStoredNumber() },
        meatServings = sumOf { it.meatServings.validatedStoredNumber() },
        dairyServings = sumOf { it.dairyServings.validatedStoredNumber() },
        discretionaryServings = sumOf { it.discretionaryServings.validatedStoredNumber() },
        sodiumMg = sumOf { it.sodiumMg.validatedStoredNumber() },
        alcoholStandardDrinks = sumOf { it.alcoholStandardDrinks.validatedStoredNumber() },
        waterMl = sumOf { it.waterMl.validatedStoredNumber() },
        sugarG = sumOf { it.sugarG.validatedStoredNumber() },
        saturatedFatG = sumOf { it.saturatedFatG.validatedStoredNumber() },
        unsaturatedFatG = sumOf { it.unsaturatedFatG.validatedStoredNumber() }
    )

    private fun Double.validatedStoredNumber(): Double = takeIf { isFinite() && this >= 0.0 }
        ?: throw AgentToolExecutionFailure(AgentToolErrorCode.INVALID_STORED_DATA)
}

object NutritionTrendCalculator {
    fun calculate(history: List<AgentDailyIntake>): AgentNutritionTrend {
        if (history.isEmpty()) {
            return AgentNutritionTrend(
                observedDays = 0,
                averageFruitVegetableServings = 0.0,
                averageWaterMl = 0.0,
                averageSugarG = 0.0,
                fruitVegetableDirection = NutritionTrendDirection.NO_DATA
            )
        }
        val ordered = history.sortedBy(AgentDailyIntake::date)
        val fruitVegetableValues = ordered.map { it.fruitServings + it.vegetablesServings }
        val slope = linearSlope(fruitVegetableValues)
        val direction = when {
            ordered.size < 2 || abs(slope) < STABLE_SLOPE_THRESHOLD -> NutritionTrendDirection.STABLE
            slope > 0.0 -> NutritionTrendDirection.INCREASING
            else -> NutritionTrendDirection.DECREASING
        }
        return AgentNutritionTrend(
            observedDays = ordered.size,
            averageFruitVegetableServings = fruitVegetableValues.average(),
            averageWaterMl = ordered.map(AgentDailyIntake::waterMl).average(),
            averageSugarG = ordered.map(AgentDailyIntake::sugarG).average(),
            fruitVegetableDirection = direction
        )
    }

    private fun linearSlope(values: List<Double>): Double {
        if (values.size < 2) return 0.0
        val xMean = (values.size - 1) / 2.0
        val yMean = values.average()
        var numerator = 0.0
        var denominator = 0.0
        values.forEachIndexed { index, value ->
            val centeredX = index - xMean
            numerator += centeredX * (value - yMean)
            denominator += centeredX * centeredX
        }
        return if (denominator == 0.0) 0.0 else numerator / denominator
    }

    private const val STABLE_SLOPE_THRESHOLD = 0.01
}
