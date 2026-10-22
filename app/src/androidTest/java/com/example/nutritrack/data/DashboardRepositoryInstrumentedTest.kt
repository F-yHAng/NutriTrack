package com.example.nutritrack.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.time.LocalDate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DashboardRepositoryInstrumentedTest {
    private lateinit var database: NutrackDatabase
    private lateinit var repository: NutrackRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NutrackDatabase::class.java).build()
        repository = NutrackRepository(
            context = context,
            database = database,
            patientDao = database.patientDao(),
            foodIntakeDao = database.foodIntakeDao(),
            userAccountDao = database.userAccountDao(),
            nutriCoachTipDao = database.nutriCoachTipDao(),
            seedMetadataDao = database.seedMetadataDao(),
            questionnaireProfileDao = database.questionnaireProfileDao(),
            todayProvider = { TODAY }
        )
        runBlocking { repository.initializeDatabase() }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun dashboardSources_returnOwnedPatientProfileAndDailySummary() = runBlocking {
        val userIds = repository.observeAvailableUserIds().first().take(2)
        assertTrue(userIds.size >= 2)
        val firstUser = userIds[0]
        val secondUser = userIds[1]

        assertNotNull(repository.getPatientByUserId(firstUser))
        repository.saveQuestionnaireProfile(validAnswers(firstUser))
        repository.createDailyIntake(firstUser, validIntake())

        assertEquals(firstUser, repository.getQuestionnaireProfile(firstUser)?.userId)
        assertEquals(1, repository.observeDailyIntakeSummaries(firstUser).first().single().recordCount)
        assertTrue(repository.observeDailyIntakeSummaries(secondUser).first().isEmpty())
    }

    private fun validAnswers(userId: Int) = QuestionnaireAnswers(
        userId = userId,
        canEatFruits = true,
        canEatVegetables = false,
        canEatGrains = false,
        canEatRedMeat = false,
        canEatSeafood = false,
        canEatPoultry = false,
        canEatFish = false,
        canEatEggs = false,
        canEatNutsAndSeeds = false,
        selectedPersona = "Mindful Eater",
        biggestMealTime = "12:00",
        sleepTime = "22:00",
        wakeUpTime = "07:00"
    )

    private fun validIntake() = DailyIntakeFormInput(
        foodName = "Lunch",
        localDate = TODAY,
        vegetablesServings = "1",
        fruitServings = "1",
        grainsServings = "1",
        meatServings = "1",
        dairyServings = "0",
        discretionaryServings = "0",
        sodiumMg = "100",
        alcoholStandardDrinks = "0",
        waterMl = "300",
        totalBeverageMl = "300",
        sugarG = "2",
        saturatedFatG = "1",
        unsaturatedFatG = "1"
    )

    private companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 9, 5)
    }
}
