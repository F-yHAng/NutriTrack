package com.example.nutritrack.data

import android.content.Context
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

@RunWith(AndroidJUnit4::class)
class QuestionnaireRepositoryInstrumentedTest {
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
            questionnaireProfileDao = database.questionnaireProfileDao()
        )
        runBlocking { repository.initializeDatabase() }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun saveAndCompletion_areRoomBackedAndUserIsolated() = runBlocking {
        val userIds = repository.observeAvailableUserIds().first().take(2)
        assertTrue(userIds.size >= 2)
        val firstUser = userIds[0]
        val secondUser = userIds[1]

        repository.saveQuestionnaireProfile(validAnswers(firstUser))

        assertTrue(repository.hasCompletedQuestionnaire(firstUser))
        assertEquals(
            "Mindful Eater",
            repository.getQuestionnaireProfile(firstUser)?.selectedPersona
        )
        assertNull(repository.getQuestionnaireProfile(secondUser))
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
}
