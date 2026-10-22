package com.example.nutritrack.data

import android.content.Context
import android.content.SharedPreferences
import androidx.room.withTransaction
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class LegacyQuestionnaireMigrationResult {
    NO_LEGACY_DATA,
    INVALID_LEGACY_DATA,
    MIGRATED,
    ALREADY_MIGRATED,
    CLEANUP_PENDING
}

class LegacyQuestionnaireMigration(
    private val context: Context,
    private val database: NutrackDatabase,
    private val questionnaireProfileDao: QuestionnaireProfileDao,
    private val nowEpochMillis: () -> Long,
    private val afterRoomCommitBeforeLegacyCleanup: () -> Unit = {}
) {
    suspend fun migrateForUser(userId: Int): LegacyQuestionnaireMigrationResult =
        withContext(Dispatchers.IO) {
            require(userId > 0) { "A positive userId is required" }
            val preferences = preferencesFor(userId)
            val existingProfile = questionnaireProfileDao.getByUserId(userId)

            if (
                existingProfile != null &&
                existingProfile.legacyPreferencesMigrationVersion >= MIGRATION_VERSION
            ) {
                if (!QuestionnaireProfileRules.isValid(existingProfile)) {
                    return@withContext LegacyQuestionnaireMigrationResult.INVALID_LEGACY_DATA
                }
                return@withContext cleanupResult(
                    preferences = preferences,
                    successResult = LegacyQuestionnaireMigrationResult.ALREADY_MIGRATED
                )
            }

            val answers = when (val legacyData = readLegacyAnswers(userId, preferences)) {
                LegacyData.None -> return@withContext LegacyQuestionnaireMigrationResult.NO_LEGACY_DATA
                LegacyData.Invalid -> return@withContext LegacyQuestionnaireMigrationResult.INVALID_LEGACY_DATA
                is LegacyData.Valid -> legacyData.answers
            }

            val wroteProfile = database.withTransaction {
                val profileInsideTransaction = questionnaireProfileDao.getByUserId(userId)
                if (
                    profileInsideTransaction != null &&
                    profileInsideTransaction.legacyPreferencesMigrationVersion >= MIGRATION_VERSION
                ) {
                    check(QuestionnaireProfileRules.isValid(profileInsideTransaction)) {
                        "Existing migrated questionnaire profile is invalid"
                    }
                    false
                } else {
                    questionnaireProfileDao.upsert(answers.toProfile(nowEpochMillis()))
                    true
                }
            }

            if (wroteProfile) afterRoomCommitBeforeLegacyCleanup()
            cleanupResult(
                preferences = preferences,
                successResult = if (wroteProfile) {
                    LegacyQuestionnaireMigrationResult.MIGRATED
                } else {
                    LegacyQuestionnaireMigrationResult.ALREADY_MIGRATED
                }
            )
        }

    fun clearLegacyBusinessKeys(userId: Int): Boolean = clearBusinessKeys(preferencesFor(userId))

    private fun readLegacyAnswers(userId: Int, preferences: SharedPreferences): LegacyData {
        val values = preferences.all
        if (BUSINESS_KEYS.none(values::containsKey)) return LegacyData.None
        if (BUSINESS_KEYS.any { it !in values }) return LegacyData.Invalid

        fun booleanValue(key: String): Boolean? = values[key] as? Boolean
        fun stringValue(key: String): String? = values[key] as? String

        val answers = QuestionnaireAnswers(
            userId = userId,
            canEatFruits = booleanValue(foodKey("Fruits")) ?: return LegacyData.Invalid,
            canEatVegetables = booleanValue(foodKey("Vegetables")) ?: return LegacyData.Invalid,
            canEatGrains = booleanValue(foodKey("Grains")) ?: return LegacyData.Invalid,
            canEatRedMeat = booleanValue(foodKey("Red Meat")) ?: return LegacyData.Invalid,
            canEatSeafood = booleanValue(foodKey("Seafood")) ?: return LegacyData.Invalid,
            canEatPoultry = booleanValue(foodKey("Poultry")) ?: return LegacyData.Invalid,
            canEatFish = booleanValue(foodKey("Fish")) ?: return LegacyData.Invalid,
            canEatEggs = booleanValue(foodKey("Eggs")) ?: return LegacyData.Invalid,
            canEatNutsAndSeeds = booleanValue(foodKey("Nuts/Seeds")) ?: return LegacyData.Invalid,
            selectedPersona = stringValue(KEY_PERSONA) ?: return LegacyData.Invalid,
            biggestMealTime = stringValue(KEY_BIGGEST_MEAL_TIME) ?: return LegacyData.Invalid,
            sleepTime = stringValue(KEY_SLEEP_TIME) ?: return LegacyData.Invalid,
            wakeUpTime = stringValue(KEY_WAKE_UP_TIME) ?: return LegacyData.Invalid
        )
        return if (QuestionnaireProfileRules.validate(answers) == null) {
            LegacyData.Valid(answers)
        } else {
            LegacyData.Invalid
        }
    }

    private fun QuestionnaireAnswers.toProfile(updatedAtEpochMillis: Long) = QuestionnaireProfile(
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
        wakeUpTime = wakeUpTime,
        legacyPreferencesMigrationVersion = MIGRATION_VERSION,
        updatedAtEpochMillis = updatedAtEpochMillis
    )

    private fun cleanupResult(
        preferences: SharedPreferences,
        successResult: LegacyQuestionnaireMigrationResult
    ): LegacyQuestionnaireMigrationResult = if (clearBusinessKeys(preferences)) {
        successResult
    } else {
        LegacyQuestionnaireMigrationResult.CLEANUP_PENDING
    }

    private fun clearBusinessKeys(preferences: SharedPreferences): Boolean {
        val editor = preferences.edit()
        BUSINESS_KEYS.forEach(editor::remove)
        return editor.commit()
    }

    private fun preferencesFor(userId: Int): SharedPreferences =
        context.getSharedPreferences("user_${userId}_prefs", Context.MODE_PRIVATE)

    private sealed interface LegacyData {
        data object None : LegacyData
        data object Invalid : LegacyData
        data class Valid(val answers: QuestionnaireAnswers) : LegacyData
    }

    companion object {
        private const val MIGRATION_VERSION =
            QuestionnaireProfileRules.LEGACY_PREFERENCES_MIGRATION_VERSION
        private const val KEY_PERSONA = "selected_persona"
        private const val KEY_BIGGEST_MEAL_TIME = "biggest_meal_time"
        private const val KEY_SLEEP_TIME = "sleep_time"
        private const val KEY_WAKE_UP_TIME = "wake_up_time"

        private fun foodKey(category: String) = "food_$category"

        val BUSINESS_KEYS: Set<String> = buildSet {
            QuestionnaireProfileRules.foodCategories.mapTo(this, ::foodKey)
            add(KEY_PERSONA)
            add(KEY_BIGGEST_MEAL_TIME)
            add(KEY_SLEEP_TIME)
            add(KEY_WAKE_UP_TIME)
        }
    }
}
