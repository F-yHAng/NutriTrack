package com.example.nutritrack.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.PrimaryKey
import kotlin.math.abs

@Entity(
    tableName = "questionnaire_profiles",
    foreignKeys = [
        ForeignKey(
            entity = Patient::class,
            parentColumns = ["userId"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ]
)
data class QuestionnaireProfile(
    @PrimaryKey
    val userId: Int,
    val canEatFruits: Boolean,
    val canEatVegetables: Boolean,
    val canEatGrains: Boolean,
    val canEatRedMeat: Boolean,
    val canEatSeafood: Boolean,
    val canEatPoultry: Boolean,
    val canEatFish: Boolean,
    val canEatEggs: Boolean,
    val canEatNutsAndSeeds: Boolean,
    val selectedPersona: String,
    val biggestMealTime: String,
    val sleepTime: String,
    val wakeUpTime: String,
    val legacyPreferencesMigrationVersion: Int,
    val updatedAtEpochMillis: Long
) {
    fun toAnswers() = QuestionnaireAnswers(
        userId = userId,
        canEatFruits = canEatFruits,
        canEatVegetables = canEatVegetables,
        canEatGrains = canEatGrains,
        canEatRedMeat = canEatRedMeat,
        canEatSeafood = canEatSeafood,
        canEatPoultry = canEatPoultry,
        canEatFish = canEatFish,
        canEatEggs = canEatEggs,
        canEatNutsAndSeeds = canEatNutsAndSeeds,
        selectedPersona = selectedPersona,
        biggestMealTime = biggestMealTime,
        sleepTime = sleepTime,
        wakeUpTime = wakeUpTime
    )
}

data class QuestionnaireAnswers(
    val userId: Int,
    val canEatFruits: Boolean,
    val canEatVegetables: Boolean,
    val canEatGrains: Boolean,
    val canEatRedMeat: Boolean,
    val canEatSeafood: Boolean,
    val canEatPoultry: Boolean,
    val canEatFish: Boolean,
    val canEatEggs: Boolean,
    val canEatNutsAndSeeds: Boolean,
    val selectedPersona: String,
    val biggestMealTime: String,
    val sleepTime: String,
    val wakeUpTime: String
)

enum class QuestionnaireValidationError {
    INVALID_USER,
    NO_FOOD_SELECTED,
    INVALID_PERSONA,
    INVALID_TIME,
    TIME_CONFLICT
}

object QuestionnaireProfileRules {
    const val LEGACY_PREFERENCES_MIGRATION_VERSION = 1

    val foodCategories = listOf(
        "Fruits",
        "Vegetables",
        "Grains",
        "Red Meat",
        "Seafood",
        "Poultry",
        "Fish",
        "Eggs",
        "Nuts/Seeds"
    )

    val personas = listOf(
        "Health Devotee",
        "Mindful Eater",
        "Wellness Striver",
        "Balance Seeker",
        "Health Procrastinator",
        "Food Carefree"
    )

    fun validate(answers: QuestionnaireAnswers): QuestionnaireValidationError? {
        if (answers.userId <= 0) return QuestionnaireValidationError.INVALID_USER
        if (!answers.hasSelectedFood()) return QuestionnaireValidationError.NO_FOOD_SELECTED
        if (answers.selectedPersona !in personas) return QuestionnaireValidationError.INVALID_PERSONA

        val times = listOf(
            answers.biggestMealTime,
            answers.sleepTime,
            answers.wakeUpTime
        ).map { parseTimeToMinutes(it) ?: return QuestionnaireValidationError.INVALID_TIME }

        if (
            abs(times[0] - times[1]) < 30 ||
            abs(times[0] - times[2]) < 30 ||
            abs(times[1] - times[2]) < 30
        ) {
            return QuestionnaireValidationError.TIME_CONFLICT
        }
        return null
    }

    fun isValid(profile: QuestionnaireProfile): Boolean = validate(profile.toAnswers()) == null

    private fun QuestionnaireAnswers.hasSelectedFood(): Boolean =
        canEatFruits || canEatVegetables || canEatGrains || canEatRedMeat ||
            canEatSeafood || canEatPoultry || canEatFish || canEatEggs || canEatNutsAndSeeds

    private fun parseTimeToMinutes(value: String): Int? {
        if (!TIME_PATTERN.matches(value)) return null
        val (hour, minute) = value.split(':').map(String::toInt)
        return hour * 60 + minute
    }

    private val TIME_PATTERN = Regex("(?:[01]\\d|2[0-3]):[0-5]\\d")
}
