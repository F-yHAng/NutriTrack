package com.example.nutritrack.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TestWatcher
import org.junit.runner.Description
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DailyIntakeViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun load_exposesRecordsAndHistory() = runTest {
        val fake = FakeDailyIntakeDataSource().apply {
            records = listOf(record(id = 1, foodName = "Breakfast"))
            summaries = listOf(summary(recordCount = 1))
        }

        val viewModel = DailyIntakeViewModel(fake, USER_ID, TODAY)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertFalse(viewModel.uiState.value.isHistoryLoading)
        assertEquals("Breakfast", viewModel.uiState.value.records.single().foodName)
        assertEquals(1, viewModel.uiState.value.summaries.single().recordCount)
    }

    @Test
    fun emptyData_exposesEmptyState() = runTest {
        val viewModel = DailyIntakeViewModel(FakeDailyIntakeDataSource(), USER_ID, TODAY)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertTrue(viewModel.uiState.value.records.isEmpty())
        assertTrue(viewModel.uiState.value.summaries.isEmpty())
    }

    @Test
    fun saveSuccess_closesEditorAndShowsStatus() = runTest {
        val fake = FakeDailyIntakeDataSource()
        val viewModel = DailyIntakeViewModel(fake, USER_ID, TODAY)
        advanceUntilIdle()

        viewModel.showNewEntry()
        viewModel.save(validInput())
        advanceUntilIdle()

        assertEquals("Meal", fake.createdInput?.foodName)
        assertFalse(viewModel.uiState.value.isEditorOpen)
        assertEquals("Daily intake saved", viewModel.uiState.value.statusMessage)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    @Test
    fun repeatedSaveWhileRequestIsPending_createsOneRecord() = runTest {
        val fake = FakeDailyIntakeDataSource()
        val viewModel = DailyIntakeViewModel(fake, USER_ID, TODAY)
        advanceUntilIdle()

        viewModel.showNewEntry()
        viewModel.save(validInput())
        viewModel.save(validInput().copy(foodName = "Duplicate"))
        advanceUntilIdle()

        assertEquals(1, fake.createCalls)
        assertEquals("Meal", fake.createdInput?.foodName)
    }

    @Test
    fun edit_loadsOwnedRecordAndSavesUpdate() = runTest {
        val fake = FakeDailyIntakeDataSource().apply {
            records = listOf(record(id = 7, foodName = "Original"))
        }
        val viewModel = DailyIntakeViewModel(fake, USER_ID, TODAY)
        advanceUntilIdle()

        viewModel.editRecord(7)
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.isEditorOpen)
        assertNotNull(viewModel.uiState.value.editingRecord)

        viewModel.save(validInput().copy(recordId = 7, foodName = "Updated"))
        advanceUntilIdle()
        assertEquals("Updated", fake.updatedInput?.foodName)
        assertEquals(7L, fake.updatedInput?.recordId)
    }

    @Test
    fun dataSourceFailure_exposesErrorState() = runTest {
        val fake = FakeDailyIntakeDataSource().apply { observeError = true }
        val viewModel = DailyIntakeViewModel(fake, USER_ID, TODAY)
        advanceUntilIdle()

        assertFalse(viewModel.uiState.value.isLoading)
        assertEquals("simulated load failure", viewModel.uiState.value.errorMessage)

        fake.saveError = true
        viewModel.showNewEntry()
        viewModel.save(validInput())
        advanceUntilIdle()
        assertEquals("simulated save failure", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isSaving)
    }

    private class FakeDailyIntakeDataSource : DailyIntakeDataSource {
        var records: List<FoodIntake> = emptyList()
        var summaries: List<DailyIntakeSummary> = emptyList()
        var observeError = false
        var saveError = false
        var createdInput: DailyIntakeFormInput? = null
        var updatedInput: DailyIntakeFormInput? = null
        var createCalls = 0

        override fun observeDailyIntakes(
            userId: Int,
            localDate: LocalDate
        ): Flow<List<FoodIntake>> = if (observeError) {
            flow { error("simulated load failure") }
        } else {
            flowOf(records)
        }

        override fun observeDailyIntakeSummaries(
            userId: Int
        ): Flow<List<DailyIntakeSummary>> = flowOf(summaries)

        override suspend fun getDailyIntake(userId: Int, recordId: Long): FoodIntake? =
            records.firstOrNull { it.id == recordId && it.patientUserId == userId }

        override suspend fun createDailyIntake(userId: Int, input: DailyIntakeFormInput): Long {
            if (saveError) error("simulated save failure")
            createCalls += 1
            createdInput = input
            return 1L
        }

        override suspend fun updateDailyIntake(userId: Int, input: DailyIntakeFormInput) {
            if (saveError) error("simulated save failure")
            updatedInput = input
        }
    }

    private fun validInput() = DailyIntakeFormInput(
        foodName = "Meal",
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

    private fun record(id: Long, foodName: String) = FoodIntake(
        id = id,
        patientUserId = USER_ID,
        submissionDate = 1_788_537_600_000L,
        foodName = foodName
    )

    private fun summary(recordCount: Int) = DailyIntakeSummary(
        localDate = TODAY,
        recordCount = recordCount,
        vegetablesServings = 1.0,
        fruitServings = 1.0,
        grainsServings = 1.0,
        meatServings = 1.0,
        dairyServings = 0.0,
        discretionaryServings = 0.0,
        waterMl = 300.0,
        sugarG = 2.0
    )

    companion object {
        private const val USER_ID = 501
        private val TODAY = LocalDate.of(2026, 9, 4)
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class MainDispatcherRule(
    private val testDispatcher: TestDispatcher = StandardTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }

    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
