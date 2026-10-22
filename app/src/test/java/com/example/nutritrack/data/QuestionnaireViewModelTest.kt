package com.example.nutritrack.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class QuestionnaireViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun existingValidProfile_isLoadedAndMarkedComplete() = runTest {
        val source = FakeQuestionnaireDataSource().apply { storedProfile = profile(USER_ID) }

        val viewModel = QuestionnaireViewModel(source, USER_ID)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.isComplete)
        assertEquals("Mindful Eater", viewModel.uiState.value.answers.selectedPersona)
        assertEquals(1, source.migrationCalls)
    }

    @Test
    fun firstQuestionnaire_startsEmptyAndAcceptsFormEvents() = runTest {
        val viewModel = QuestionnaireViewModel(FakeQuestionnaireDataSource(), USER_ID)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isComplete)
        viewModel.setFoodSelection("Fruits", true)
        viewModel.selectPersona("Balance Seeker")
        viewModel.setBiggestMealTime("12:00")
        viewModel.setSleepTime("22:00")
        viewModel.setWakeUpTime("07:00")

        val answers = viewModel.uiState.value.answers
        assertTrue(answers.canEatFruits)
        assertEquals("Balance Seeker", answers.selectedPersona)
        assertEquals(USER_ID, answers.userId)
    }

    @Test
    fun invalidForm_isRejectedWithoutWriting() = runTest {
        val source = FakeQuestionnaireDataSource()
        val viewModel = QuestionnaireViewModel(source, USER_ID)
        advanceUntilIdle()

        viewModel.save()

        assertEquals("Please select at least one food category", viewModel.uiState.value.errorMessage)
        assertEquals(0, source.saveCalls)
    }

    @Test
    fun saveSuccess_marksCompleteAndEmitsSingleEvent() = runTest {
        val source = FakeQuestionnaireDataSource()
        val viewModel = QuestionnaireViewModel(source, USER_ID)
        advanceUntilIdle()
        fillValidForm(viewModel)

        viewModel.save()
        advanceUntilIdle()

        assertEquals(USER_ID, source.savedAnswers?.userId)
        assertTrue(viewModel.uiState.value.isComplete)
        val eventId = requireNotNull(viewModel.uiState.value.savedEventId)
        viewModel.savedEventHandled(eventId)
        assertNull(viewModel.uiState.value.savedEventId)
    }

    @Test
    fun saveFailure_exposesErrorAndKeepsFormEditable() = runTest {
        val source = FakeQuestionnaireDataSource().apply {
            saveFailure = IllegalStateException("write failed")
        }
        val viewModel = QuestionnaireViewModel(source, USER_ID)
        advanceUntilIdle()
        fillValidForm(viewModel)

        viewModel.save()
        advanceUntilIdle()

        assertEquals("write failed", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isSaving)
        assertFalse(viewModel.uiState.value.isComplete)
    }

    @Test
    fun repeatedSaveWhileRequestRunning_isIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeQuestionnaireDataSource().apply { saveGate = gate }
        val viewModel = QuestionnaireViewModel(source, USER_ID)
        advanceUntilIdle()
        fillValidForm(viewModel)

        viewModel.save()
        runCurrent()
        viewModel.save()
        assertEquals(1, source.saveCalls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isComplete)
    }

    @Test
    fun profileForAnotherUser_isNeverExposedOrOverwritten() = runTest {
        val source = FakeQuestionnaireDataSource().apply { storedProfile = profile(OTHER_USER_ID) }
        val viewModel = QuestionnaireViewModel(source, USER_ID)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isComplete)
        assertEquals(USER_ID, viewModel.uiState.value.answers.userId)
        assertEquals("", viewModel.uiState.value.answers.selectedPersona)
    }

    private class FakeQuestionnaireDataSource : QuestionnaireDataSource {
        var storedProfile: QuestionnaireProfile? = null
        var savedAnswers: QuestionnaireAnswers? = null
        var saveFailure: Throwable? = null
        var saveGate: CompletableDeferred<Unit>? = null
        var migrationCalls = 0
        var saveCalls = 0

        override suspend fun migrateLegacyQuestionnaire(
            userId: Int
        ): LegacyQuestionnaireMigrationResult {
            migrationCalls++
            return LegacyQuestionnaireMigrationResult.ALREADY_MIGRATED
        }

        override suspend fun getQuestionnaireProfile(userId: Int): QuestionnaireProfile? =
            storedProfile

        override suspend fun saveQuestionnaireProfile(
            answers: QuestionnaireAnswers
        ): QuestionnaireProfile {
            saveCalls++
            saveFailure?.let { throw it }
            saveGate?.await()
            savedAnswers = answers
            return profile(answers.userId, answers)
        }
    }

    private fun fillValidForm(viewModel: QuestionnaireViewModel) {
        viewModel.setFoodSelection("Fruits", true)
        viewModel.selectPersona("Mindful Eater")
        viewModel.setBiggestMealTime("12:00")
        viewModel.setSleepTime("22:00")
        viewModel.setWakeUpTime("07:00")
    }

    private companion object {
        const val USER_ID = 501
        const val OTHER_USER_ID = 502

        fun profile(
            userId: Int,
            answers: QuestionnaireAnswers = QuestionnaireAnswers(
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
        ) = QuestionnaireProfile(
            userId = userId,
            canEatFruits = answers.canEatFruits,
            canEatVegetables = answers.canEatVegetables,
            canEatGrains = answers.canEatGrains,
            canEatRedMeat = answers.canEatRedMeat,
            canEatSeafood = answers.canEatSeafood,
            canEatPoultry = answers.canEatPoultry,
            canEatFish = answers.canEatFish,
            canEatEggs = answers.canEatEggs,
            canEatNutsAndSeeds = answers.canEatNutsAndSeeds,
            selectedPersona = answers.selectedPersona,
            biggestMealTime = answers.biggestMealTime,
            sleepTime = answers.sleepTime,
            wakeUpTime = answers.wakeUpTime,
            legacyPreferencesMigrationVersion = 1,
            updatedAtEpochMillis = 1L
        )
    }
}
