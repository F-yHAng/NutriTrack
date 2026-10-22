package com.example.nutritrack.data

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.LocalDate

@OptIn(ExperimentalCoroutinesApi::class)
class DashboardViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun load_exposesPatientQuestionnaireAndSexSpecificScores() = runTest {
        val patient = samplePatient().copy(userId = USER_ONE, sex = "Male")
        val source = FakeDashboardDataSource().apply {
            patients[USER_ONE] = patient
            profiles[USER_ONE] = profile(USER_ONE)
        }

        val viewModel = createViewModel(source, USER_ONE)
        advanceUntilIdle()

        val state = viewModel.uiState.value
        assertFalse(state.isLoading)
        assertEquals(patient.hEIFAtotalscoreMale.toFloat(), state.foodQualityScore)
        assertEquals(13, state.categoryScores.size)
        assertEquals(USER_ONE, state.questionnaireProfile?.userId)
    }

    @Test
    fun emptyData_exposesEmptyDashboardWithoutInventingScores() = runTest {
        val viewModel = createViewModel(FakeDashboardDataSource(), USER_ONE)
        advanceUntilIdle()

        assertNull(viewModel.uiState.value.patient)
        assertTrue(viewModel.uiState.value.categoryScores.isEmpty())
        assertEquals(0f, viewModel.uiState.value.foodQualityScore)
    }

    @Test
    fun realDailyIntakeState_isReusedForInsightsAggregation() = runTest {
        val intakeState = MutableStateFlow(emptyIntakeState())
        val viewModel = createViewModel(
            source = FakeDashboardDataSource(),
            userId = USER_ONE,
            intakeState = intakeState
        )
        advanceUntilIdle()

        intakeState.value = emptyIntakeState().copy(
            summaries = listOf(summary(recordCount = 3)),
            isHistoryLoading = false
        )
        advanceUntilIdle()

        assertEquals(3, viewModel.uiState.value.dailyIntakeSummaries.single().recordCount)
        assertFalse(viewModel.uiState.value.isHistoryLoading)
    }

    @Test
    fun repositoryFailure_exposesErrorState() = runTest {
        val source = FakeDashboardDataSource().apply {
            loadFailure = IllegalStateException("dashboard unavailable")
        }
        val viewModel = createViewModel(source, USER_ONE)
        advanceUntilIdle()

        assertEquals("dashboard unavailable", viewModel.uiState.value.errorMessage)
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun newViewModelForSwitchedUser_doesNotReusePreviousUserData() = runTest {
        val first = samplePatient().copy(userId = USER_ONE, sex = "Male")
        val second = samplePatient().copy(userId = USER_TWO, sex = "Female")
        val source = FakeDashboardDataSource().apply {
            patients[USER_ONE] = first
            patients[USER_TWO] = second
        }

        val firstViewModel = createViewModel(source, USER_ONE)
        val secondViewModel = createViewModel(source, USER_TWO)
        advanceUntilIdle()

        assertEquals(USER_ONE, firstViewModel.uiState.value.patient?.userId)
        assertEquals(USER_TWO, secondViewModel.uiState.value.patient?.userId)
        assertEquals(second.hEIFAtotalscoreFemale.toFloat(), secondViewModel.uiState.value.foodQualityScore)
    }

    @Test
    fun repeatedRefreshWhileLoading_isIgnored() = runTest {
        val gate = CompletableDeferred<Unit>()
        val source = FakeDashboardDataSource().apply { loadGate = gate }
        val viewModel = createViewModel(source, USER_ONE)
        runCurrent()

        viewModel.refresh()
        viewModel.refresh()
        assertEquals(1, source.patientLoads)

        gate.complete(Unit)
        advanceUntilIdle()
        assertFalse(viewModel.uiState.value.isRefreshing)
    }

    @Test
    fun routeAndLogoutEvents_arePersistedWithoutUiCoroutine() = runTest {
        val session = FakeDashboardSessionDataSource()
        val viewModel = createViewModel(FakeDashboardDataSource(), USER_ONE, session = session)
        advanceUntilIdle()

        viewModel.selectRoute(AppRoute.INSIGHTS)
        viewModel.logout()
        advanceUntilIdle()

        assertEquals(AppRoute.INSIGHTS, session.savedRoute)
        assertEquals(1, session.clearCalls)
    }

    private fun createViewModel(
        source: FakeDashboardDataSource,
        userId: Int,
        session: FakeDashboardSessionDataSource = FakeDashboardSessionDataSource(),
        intakeState: MutableStateFlow<DailyIntakeUiState> = MutableStateFlow(emptyIntakeState())
    ) = DashboardViewModel(
        dataSource = source,
        sessionDataSource = session,
        userId = userId,
        initialRoute = AppRoute.HOME,
        dailyIntakeState = intakeState
    )

    private class FakeDashboardDataSource : DashboardDataSource {
        val patients = mutableMapOf<Int, Patient>()
        val profiles = mutableMapOf<Int, QuestionnaireProfile>()
        var loadFailure: Throwable? = null
        var loadGate: CompletableDeferred<Unit>? = null
        var patientLoads = 0

        override suspend fun getPatientByUserId(userId: Int): Patient? {
            patientLoads++
            loadFailure?.let { throw it }
            loadGate?.await()
            return patients[userId]
        }

        override suspend fun migrateLegacyQuestionnaire(
            userId: Int
        ) = LegacyQuestionnaireMigrationResult.ALREADY_MIGRATED

        override suspend fun getQuestionnaireProfile(userId: Int): QuestionnaireProfile? =
            profiles[userId]
    }

    private class FakeDashboardSessionDataSource : DashboardSessionDataSource {
        var savedRoute: AppRoute? = null
        var clearCalls = 0

        override suspend fun saveLastPage(route: AppRoute) {
            savedRoute = route
        }

        override suspend fun clearSession() {
            clearCalls++
        }
    }

    private fun samplePatient(): Patient {
        val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("data.csv"))
        return stream.bufferedReader().use { CsvParser().parsePatientData(it).first() }
    }

    private fun profile(userId: Int) = QuestionnaireProfile(
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
        wakeUpTime = "07:00",
        legacyPreferencesMigrationVersion = 1,
        updatedAtEpochMillis = 1L
    )

    private fun emptyIntakeState() = DailyIntakeUiState(
        selectedDate = LocalDate.of(2026, 9, 5),
        isLoading = false,
        isHistoryLoading = false
    )

    private fun summary(recordCount: Int) = DailyIntakeSummary(
        localDate = LocalDate.of(2026, 9, 5),
        recordCount = recordCount,
        vegetablesServings = 1.0,
        fruitServings = 2.0,
        grainsServings = 1.0,
        meatServings = 1.0,
        dairyServings = 0.0,
        discretionaryServings = 0.0,
        waterMl = 500.0,
        sugarG = 3.0
    )

    private companion object {
        const val USER_ONE = 501
        const val USER_TWO = 502
    }
}
