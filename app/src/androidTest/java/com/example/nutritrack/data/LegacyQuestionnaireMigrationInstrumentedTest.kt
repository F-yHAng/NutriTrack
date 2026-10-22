package com.example.nutritrack.data

import android.content.ContentValues
import android.content.Context
import android.content.SharedPreferences
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LegacyQuestionnaireMigrationInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: NutrackDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NutrackDatabase::class.java).build()
        TEST_USER_IDS.forEach { preferences(it).edit().clear().commit() }
    }

    @After
    fun tearDown() {
        database.close()
        TEST_USER_IDS.forEach { preferences(it).edit().clear().commit() }
    }

    @Test
    fun completeLegacyQuestionnaire_migratesAndOnlyRemovesBusinessKeys() = runBlocking {
        insertPatient(USER_ONE)
        val preferences = preferences(USER_ONE)
        writeValidLegacyQuestionnaire(preferences, persona = "Mindful Eater")
        preferences.edit()
            .putBoolean("is_logged_in", true)
            .putInt("current_user_id", USER_ONE)
            .putString("last_page", "Insights")
            .commit()

        val repository = repository()
        val result = repository.migrateLegacyQuestionnaire(USER_ONE)

        assertEquals(LegacyQuestionnaireMigrationResult.MIGRATED, result)
        val profile = database.questionnaireProfileDao().getByUserId(USER_ONE)
        assertNotNull(profile)
        assertEquals("Mindful Eater", profile?.selectedPersona)
        assertTrue(profile?.canEatFruits == true)
        assertTrue(profile?.let(QuestionnaireProfileRules::isValid) == true)
        assertTrue(repository.hasCompletedQuestionnaire(USER_ONE))
        assertBusinessKeysRemoved(preferences)
        assertTrue(preferences.getBoolean("is_logged_in", false))
        assertEquals(USER_ONE, preferences.getInt("current_user_id", -1))
        assertEquals("Insights", preferences.getString("last_page", null))
    }

    @Test
    fun crashAfterRoomCommit_retryOnlyCleansLegacyKeysWithoutOverwritingRoom() = runBlocking {
        insertPatient(USER_ONE)
        val preferences = preferences(USER_ONE)
        writeValidLegacyQuestionnaire(preferences, persona = "Health Devotee")
        var shouldCrash = true
        val crashingMigrator = migrator {
            if (shouldCrash) {
                shouldCrash = false
                error("simulated crash after Room commit")
            }
        }

        try {
            crashingMigrator.migrateForUser(USER_ONE)
            throw AssertionError("Expected the simulated crash")
        } catch (expected: IllegalStateException) {
            assertEquals("simulated crash after Room commit", expected.message)
        }
        assertTrue(LegacyQuestionnaireMigration.BUSINESS_KEYS.all(preferences::contains))

        val committed = database.questionnaireProfileDao().getByUserId(USER_ONE)
        assertNotNull(committed)
        database.questionnaireProfileDao().upsert(
            committed!!.copy(selectedPersona = "Balance Seeker", updatedAtEpochMillis = 999L)
        )

        assertEquals(
            LegacyQuestionnaireMigrationResult.ALREADY_MIGRATED,
            crashingMigrator.migrateForUser(USER_ONE)
        )
        assertEquals(
            "Balance Seeker",
            database.questionnaireProfileDao().getByUserId(USER_ONE)?.selectedPersona
        )
        assertBusinessKeysRemoved(preferences)
    }

    @Test
    fun repeatedMigration_isIdempotent() = runBlocking {
        insertPatient(USER_ONE)
        val preferences = preferences(USER_ONE)
        writeValidLegacyQuestionnaire(preferences)
        val migrator = migrator()

        assertEquals(LegacyQuestionnaireMigrationResult.MIGRATED, migrator.migrateForUser(USER_ONE))
        val firstProfile = database.questionnaireProfileDao().getByUserId(USER_ONE)
        assertEquals(
            LegacyQuestionnaireMigrationResult.ALREADY_MIGRATED,
            migrator.migrateForUser(USER_ONE)
        )

        assertEquals(firstProfile, database.questionnaireProfileDao().getByUserId(USER_ONE))
        assertBusinessKeysRemoved(preferences)
    }

    @Test
    fun roomWriteFailure_keepsLegacyDataAndDoesNotMarkMigrationComplete() = runBlocking {
        insertPatient(USER_ONE)
        val preferences = preferences(USER_ONE)
        writeValidLegacyQuestionnaire(preferences)
        val realDao = database.questionnaireProfileDao()
        val failingDao = object : QuestionnaireProfileDao {
            override suspend fun getByUserId(userId: Int): QuestionnaireProfile? =
                realDao.getByUserId(userId)

            override suspend fun upsert(profile: QuestionnaireProfile) {
                error("simulated questionnaire write failure")
            }
        }
        val failingMigrator = LegacyQuestionnaireMigration(
            context = context,
            database = database,
            questionnaireProfileDao = failingDao,
            nowEpochMillis = { 1_788_600_000_000L }
        )

        try {
            failingMigrator.migrateForUser(USER_ONE)
            throw AssertionError("Expected the simulated write failure")
        } catch (expected: IllegalStateException) {
            assertEquals("simulated questionnaire write failure", expected.message)
        }

        assertNull(realDao.getByUserId(USER_ONE))
        assertTrue(LegacyQuestionnaireMigration.BUSINESS_KEYS.all(preferences::contains))
    }

    @Test
    fun incompleteOrInvalidLegacyData_doesNotCreateCompletedProfileOrDeleteData() = runBlocking {
        insertPatient(USER_ONE)
        insertPatient(USER_TWO)
        val incompletePreferences = preferences(USER_ONE)
        incompletePreferences.edit()
            .putString("selected_persona", "Mindful Eater")
            .putBoolean("food_Fruits", true)
            .commit()

        val invalidPreferences = preferences(USER_TWO)
        writeValidLegacyQuestionnaire(invalidPreferences)
        invalidPreferences.edit().putString("sleep_time", "25:00").commit()

        val migrator = migrator()
        assertEquals(
            LegacyQuestionnaireMigrationResult.INVALID_LEGACY_DATA,
            migrator.migrateForUser(USER_ONE)
        )
        assertEquals(
            LegacyQuestionnaireMigrationResult.INVALID_LEGACY_DATA,
            migrator.migrateForUser(USER_TWO)
        )
        assertNull(database.questionnaireProfileDao().getByUserId(USER_ONE))
        assertNull(database.questionnaireProfileDao().getByUserId(USER_TWO))
        assertFalse(repository().hasCompletedQuestionnaire(USER_ONE))
        assertFalse(repository().hasCompletedQuestionnaire(USER_TWO))
        assertTrue(incompletePreferences.contains("selected_persona"))
        assertEquals("25:00", invalidPreferences.getString("sleep_time", null))
    }

    @Test
    fun migration_isIsolatedByUser() = runBlocking {
        insertPatient(USER_ONE)
        insertPatient(USER_TWO)
        val firstPreferences = preferences(USER_ONE)
        val secondPreferences = preferences(USER_TWO)
        writeValidLegacyQuestionnaire(firstPreferences, persona = "Health Devotee")
        writeValidLegacyQuestionnaire(secondPreferences, persona = "Food Carefree")
        val migrator = migrator()

        assertEquals(LegacyQuestionnaireMigrationResult.MIGRATED, migrator.migrateForUser(USER_ONE))
        assertEquals("Health Devotee", database.questionnaireProfileDao().getByUserId(USER_ONE)?.selectedPersona)
        assertNull(database.questionnaireProfileDao().getByUserId(USER_TWO))
        assertBusinessKeysRemoved(firstPreferences)
        assertTrue(LegacyQuestionnaireMigration.BUSINESS_KEYS.all(secondPreferences::contains))

        assertEquals(LegacyQuestionnaireMigrationResult.MIGRATED, migrator.migrateForUser(USER_TWO))
        assertEquals("Food Carefree", database.questionnaireProfileDao().getByUserId(USER_TWO)?.selectedPersona)
        assertBusinessKeysRemoved(secondPreferences)
    }

    private fun migrator(afterCommit: () -> Unit = {}) = LegacyQuestionnaireMigration(
        context = context,
        database = database,
        questionnaireProfileDao = database.questionnaireProfileDao(),
        nowEpochMillis = { 1_788_600_000_000L },
        afterRoomCommitBeforeLegacyCleanup = afterCommit
    )

    private fun repository() = NutrackRepository(
        context = context,
        database = database,
        patientDao = database.patientDao(),
        foodIntakeDao = database.foodIntakeDao(),
        userAccountDao = database.userAccountDao(),
        nutriCoachTipDao = database.nutriCoachTipDao(),
        seedMetadataDao = database.seedMetadataDao(),
        questionnaireProfileDao = database.questionnaireProfileDao(),
        nowEpochMillis = { 1_788_600_000_000L }
    )

    private fun preferences(userId: Int): SharedPreferences =
        context.getSharedPreferences("user_${userId}_prefs", Context.MODE_PRIVATE)

    private fun writeValidLegacyQuestionnaire(
        preferences: SharedPreferences,
        persona: String = "Mindful Eater"
    ) {
        val editor = preferences.edit()
        QuestionnaireProfileRules.foodCategories.forEach { category ->
            editor.putBoolean("food_$category", category == "Fruits")
        }
        assertTrue(
            editor
                .putString("selected_persona", persona)
                .putString("biggest_meal_time", "12:00")
                .putString("sleep_time", "22:00")
                .putString("wake_up_time", "07:00")
                .commit()
        )
    }

    private fun assertBusinessKeysRemoved(preferences: SharedPreferences) {
        assertTrue(LegacyQuestionnaireMigration.BUSINESS_KEYS.none(preferences::contains))
    }

    private fun insertPatient(userId: Int) {
        val writableDatabase = database.openHelper.writableDatabase
        val values = ContentValues()
        writableDatabase.query("PRAGMA table_info(`patients`)").use { cursor ->
            val columnNameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                when (val columnName = cursor.getString(columnNameIndex)) {
                    "userId" -> values.put(columnName, userId)
                    "phoneNumber" -> values.put(columnName, "0400000$userId")
                    "name" -> values.put(columnName, "User $userId")
                    "sex" -> values.put(columnName, "Male")
                    else -> values.put(columnName, 1.0)
                }
            }
        }
        assertTrue(
            writableDatabase.insert("patients", SQLiteDatabase.CONFLICT_ABORT, values) != -1L
        )
    }

    companion object {
        private const val USER_ONE = 101
        private const val USER_TWO = 202
        private val TEST_USER_IDS = listOf(USER_ONE, USER_TWO)
    }
}
