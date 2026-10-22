package com.example.nutritrack.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.LocalDate
import java.time.ZoneId

@RunWith(AndroidJUnit4::class)
class DailyIntakeRepositoryInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: NutrackDatabase
    private lateinit var repository: NutrackRepository

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NutrackDatabase::class.java).build()
        insertPatient(USER_ONE)
        insertPatient(USER_TWO)
        repository = NutrackRepository(
            context = context,
            database = database,
            patientDao = database.patientDao(),
            foodIntakeDao = database.foodIntakeDao(),
            userAccountDao = database.userAccountDao(),
            nutriCoachTipDao = database.nutriCoachTipDao(),
            seedMetadataDao = database.seedMetadataDao(),
            questionnaireProfileDao = database.questionnaireProfileDao(),
            localZoneId = ZoneId.of("Australia/Melbourne"),
            todayProvider = { LocalDate.of(2026, 9, 5) }
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun create_rejectsMissingInvalidAndOutOfRangeValuesWithoutWriting() = runBlocking {
        val invalidInput = validInput(LocalDate.of(2026, 9, 4)).copy(
            foodName = " ",
            fruitServings = "not-a-number",
            sodiumMg = "50001",
            waterMl = "",
            localDate = LocalDate.of(2026, 9, 6)
        )

        try {
            repository.createDailyIntake(USER_ONE, invalidInput)
            throw AssertionError("Expected validation to fail")
        } catch (expected: DailyIntakeValidationException) {
            assertTrue(expected.problems.any { it.contains("Food name") })
            assertTrue(expected.problems.any { it.contains("Fruit servings") })
            assertTrue(expected.problems.any { it.contains("Sodium") })
            assertTrue(expected.problems.any { it.contains("Water is required") })
            assertTrue(expected.problems.any { it.contains("Intake date") })
        }
        assertTrue(repository.observeDailyIntakeSummaries(USER_ONE).first().isEmpty())
    }

    @Test
    fun multipleEntries_areFilteredAndAggregatedByLocalDate() = runBlocking {
        val firstDate = LocalDate.of(2026, 9, 4)
        val secondDate = LocalDate.of(2026, 9, 5)
        repository.createDailyIntake(USER_ONE, validInput(firstDate).copy(foodName = "Breakfast"))
        repository.createDailyIntake(
            USER_ONE,
            validInput(firstDate).copy(
                foodName = "Dinner",
                vegetablesServings = "3",
                fruitServings = "2",
                waterMl = "600",
                totalBeverageMl = "800"
            )
        )
        repository.createDailyIntake(USER_ONE, validInput(secondDate).copy(foodName = "Next day"))
        repository.createDailyIntake(USER_TWO, validInput(firstDate).copy(foodName = "Other user"))

        val firstDateRecords = repository.observeDailyIntakes(USER_ONE, firstDate).first()
        assertEquals(2, firstDateRecords.size)
        assertTrue(firstDateRecords.all { it.patientUserId == USER_ONE })
        assertTrue(firstDateRecords.all { it.questionnaireResponses == "{}" })

        val summaries = repository.observeDailyIntakeSummaries(USER_ONE).first()
        assertEquals(listOf(secondDate, firstDate), summaries.map(DailyIntakeSummary::localDate))
        assertEquals(2, summaries[1].recordCount)
        assertEquals(5.0, summaries[1].vegetablesServings, 0.0)
        assertEquals(3.0, summaries[1].fruitServings, 0.0)
        assertEquals(1, repository.observeDailyIntakeSummaries(USER_TWO).first().single().recordCount)
    }

    @Test
    fun readAndUpdate_areIsolatedToCurrentUser() = runBlocking {
        val date = LocalDate.of(2026, 9, 4)
        val recordId = repository.createDailyIntake(
            USER_ONE,
            validInput(date).copy(foodName = "Owner meal")
        )
        val original = requireNotNull(repository.getDailyIntake(USER_ONE, recordId))
        database.foodIntakeDao().updateForUser(
            USER_ONE,
            original.copy(cruciferous = 2.5, questionnaireResponses = "legacy-questionnaire")
        )

        assertNull(repository.getDailyIntake(USER_TWO, recordId))
        try {
            repository.updateDailyIntake(
                USER_TWO,
                validInput(date).copy(recordId = recordId, foodName = "Unauthorized")
            )
            throw AssertionError("Expected user isolation to reject the update")
        } catch (_: DailyIntakeAccessException) {
            // Expected.
        }
        assertEquals("Owner meal", repository.getDailyIntake(USER_ONE, recordId)?.foodName)

        repository.updateDailyIntake(
            USER_ONE,
            validInput(date).copy(recordId = recordId, foodName = "Owner update")
        )
        val updated = repository.getDailyIntake(USER_ONE, recordId)
        assertEquals("Owner update", updated?.foodName)
        assertEquals(2.5, updated?.cruciferous ?: -1.0, 0.0)
        assertEquals("{}", updated?.questionnaireResponses)
    }

    private fun validInput(localDate: LocalDate) = DailyIntakeFormInput(
        foodName = "Meal",
        localDate = localDate,
        vegetablesServings = "2",
        fruitServings = "1",
        grainsServings = "1.5",
        meatServings = "1",
        dairyServings = "0",
        discretionaryServings = "0",
        sodiumMg = "500",
        alcoholStandardDrinks = "0",
        waterMl = "500",
        totalBeverageMl = "700",
        sugarG = "10",
        saturatedFatG = "5",
        unsaturatedFatG = "8"
    )

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
        private const val USER_ONE = 401
        private const val USER_TWO = 402
    }
}
