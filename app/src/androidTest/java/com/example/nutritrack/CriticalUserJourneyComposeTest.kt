package com.example.nutritrack

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.nutritrack.data.AppRoute
import com.example.nutritrack.data.AuthViewModel
import com.example.nutritrack.data.DailyIntakeViewModel
import com.example.nutritrack.data.DashboardViewModel
import com.example.nutritrack.data.NutrackDatabase
import com.example.nutritrack.data.NutrackRepository
import com.example.nutritrack.data.QuestionnaireProfileRules
import com.example.nutritrack.data.QuestionnaireViewModel
import com.example.nutritrack.data.SessionManager
import com.example.nutritrack.data.SessionState
import com.example.nutritrack.ui.theme.NutritrackTheme
import java.io.File
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CriticalUserJourneyComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: NutrackDatabase
    private lateinit var repository: NutrackRepository
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var dataStoreFile: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var sessionManager: SessionManager
    private lateinit var viewModelStore: ViewModelStore
    private lateinit var authViewModel: AuthViewModel
    private lateinit var questionnaireViewModel: QuestionnaireViewModel
    private lateinit var dailyIntakeViewModel: DailyIntakeViewModel
    private lateinit var dashboardViewModel: DashboardViewModel
    private var renderContent by mutableStateOf(true)
    private var userId: Int = 0

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
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
        repository.initializeDatabase()
        val patient = repository.getAllPatients().first().first()
        userId = patient.userId
        context.getSharedPreferences("user_${userId}_prefs", Context.MODE_PRIVATE)
            .edit()
            .clear()
            .commit()
        assertTrue(
            repository.registerUser(
                userId = userId,
                phoneNumber = patient.phoneNumber,
                name = "Compose Smoke User",
                password = PASSWORD
            )
        )

        val dataStoreName = "critical-journey-${UUID.randomUUID()}"
        dataStoreFile = context.preferencesDataStoreFile(dataStoreName)
        dataStoreFile.delete()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { dataStoreFile }
        )
        sessionManager = SessionManager(dataStore)
        viewModelStore = ViewModelStore()
        authViewModel = ViewModelProvider(
            viewModelStore,
            AuthViewModel.Factory(repository, sessionManager)
        )["auth", AuthViewModel::class.java]
        questionnaireViewModel = ViewModelProvider(
            viewModelStore,
            QuestionnaireViewModel.Factory(repository, userId)
        )["questionnaire", QuestionnaireViewModel::class.java]
        dailyIntakeViewModel = ViewModelProvider(
            viewModelStore,
            DailyIntakeViewModel.Factory(repository, userId)
        )["daily-intake", DailyIntakeViewModel::class.java]
        dashboardViewModel = ViewModelProvider(
            viewModelStore,
            DashboardViewModel.Factory(
                dataSource = repository,
                sessionDataSource = sessionManager,
                userId = userId,
                initialRoute = AppRoute.HOME,
                dailyIntakeState = dailyIntakeViewModel.uiState
            )
        )["dashboard", DashboardViewModel::class.java]
    }

    @After
    fun tearDown() {
        if (::viewModelStore.isInitialized) {
            composeRule.runOnIdle { renderContent = false }
            composeRule.waitForIdle()
            viewModelStore.clear()
        }
        if (::dataStore.isInitialized) runBlocking { dataStore.edit { it.clear() } }
        if (::dataStoreScope.isInitialized) dataStoreScope.cancel()
        if (::dataStoreFile.isInitialized) dataStoreFile.delete()
        if (userId > 0) {
            context.getSharedPreferences("user_${userId}_prefs", Context.MODE_PRIVATE)
                .edit()
                .clear()
                .commit()
        }
        if (::database.isInitialized) database.close()
    }

    @Test
    fun loginQuestionnaireHomeAndIntake_persistsAndDisplaysRoomRecord() {
        composeRule.setContent {
            if (renderContent) {
                NutritrackTheme {
                    CriticalJourney(
                        authViewModel = authViewModel,
                        questionnaireViewModel = questionnaireViewModel,
                        dailyIntakeViewModel = dailyIntakeViewModel,
                        dashboardViewModel = dashboardViewModel
                    )
                }
            }
        }

        waitForTag("auth_user_id")
        composeRule.onNodeWithTag("auth_user_id").performClick()
        composeRule.onNodeWithTag("auth_user_option_$userId").performClick()
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            authViewModel.uiState.value.selectedUserIsRegistered
        }
        composeRule.onNodeWithTag("auth_password").performTextInput(PASSWORD)
        composeRule.onNodeWithTag("auth_login_submit").performClick()

        waitForTag("questionnaire_food_Fruits")
        composeRule.runOnIdle {
            questionnaireViewModel.setBiggestMealTime("12:00")
            questionnaireViewModel.setSleepTime("22:00")
            questionnaireViewModel.setWakeUpTime("07:00")
        }
        composeRule.onNodeWithTag("questionnaire_food_Fruits")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("questionnaire_persona")
            .performScrollTo()
            .performClick()
        composeRule.onNodeWithTag("questionnaire_persona_option_Mindful Eater")
            .performClick()
        composeRule.onNodeWithTag("questionnaire_save")
            .performScrollTo()
            .performClick()

        waitForTag("home_daily_intake")
        composeRule.onNodeWithTag("home_daily_intake")
            .performScrollTo()
            .performClick()
        waitForTag("daily_intake_add")
        composeRule.onNodeWithTag("daily_intake_add").performClick()
        waitForTag("daily_intake_save")

        val values = listOf(
            "Smoke Meal",
            "1", "1", "1", "1", "0", "0", "100",
            "0", "300", "300", "2", "1", "1"
        )
        val fields = composeRule.onAllNodes(hasSetTextAction(), useUnmergedTree = true)
        assertEquals("Unexpected editor field count", values.size, fields.fetchSemanticsNodes().size)
        values.forEachIndexed { index, value ->
            fields[index].performScrollTo().performTextInput(value)
        }
        composeRule.onNodeWithTag("daily_intake_save").performClick()

        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            dailyIntakeViewModel.uiState.value.records.any { it.foodName == "Smoke Meal" } &&
                dashboardViewModel.uiState.value.dailyIntakeSummaries.any { it.recordCount == 1 }
        }
        composeRule.onNodeWithText("Smoke Meal").assertExists()

        runBlocking {
            val profile = database.questionnaireProfileDao().getByUserId(userId)
            assertNotNull(profile)
            assertTrue(QuestionnaireProfileRules.isValid(checkNotNull(profile)))
            val selectedDate = dailyIntakeViewModel.uiState.value.selectedDate
            val records = repository.observeDailyIntakes(userId, selectedDate).first()
            assertEquals(listOf("Smoke Meal"), records.map { it.foodName })
            val session = sessionManager.state.first { it.sessionState !is SessionState.Loading }
            assertEquals(SessionState.LoggedIn(userId), session.sessionState)
        }
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    @Composable
    private fun CriticalJourney(
        authViewModel: AuthViewModel,
        questionnaireViewModel: QuestionnaireViewModel,
        dailyIntakeViewModel: DailyIntakeViewModel,
        dashboardViewModel: DashboardViewModel
    ) {
        var stage by remember { mutableStateOf(JourneyStage.AUTH) }
        val authState by authViewModel.uiState.collectAsStateWithLifecycle()
        val questionnaireState by questionnaireViewModel.uiState.collectAsStateWithLifecycle()
        val dailyIntakeState by dailyIntakeViewModel.uiState.collectAsStateWithLifecycle()
        val dashboardState by dashboardViewModel.uiState.collectAsStateWithLifecycle()

        authState.navigation?.let { navigation ->
            LaunchedEffect(navigation.eventId) {
                stage = if (navigation.hasCompletedQuestionnaire) {
                    JourneyStage.HOME
                } else {
                    JourneyStage.QUESTIONNAIRE
                }
                authViewModel.navigationHandled(navigation.eventId)
            }
        }
        questionnaireState.savedEventId?.let { eventId ->
            LaunchedEffect(eventId) {
                stage = JourneyStage.HOME
                dashboardViewModel.refresh()
                questionnaireViewModel.savedEventHandled(eventId)
            }
        }

        when (stage) {
            JourneyStage.AUTH -> LoginScreen(
                uiState = authState,
                onUserIdChanged = authViewModel::selectUser,
                onLogin = authViewModel::login,
                onShowRegistration = authViewModel::showRegistration,
                onShowLogin = authViewModel::showLogin,
                onValidateRegistrationCredentials = authViewModel::validateRegistrationCredentials,
                onRegister = authViewModel::register,
                onRetry = authViewModel::retryLoadingUsers,
                onClearError = authViewModel::clearError,
                onCancel = {}
            )
            JourneyStage.QUESTIONNAIRE -> QuestionnaireScreen(
                uiState = questionnaireState,
                onFoodSelectionChanged = questionnaireViewModel::setFoodSelection,
                onPersonaSelected = questionnaireViewModel::selectPersona,
                onBiggestMealTimeChanged = questionnaireViewModel::setBiggestMealTime,
                onSleepTimeChanged = questionnaireViewModel::setSleepTime,
                onWakeUpTimeChanged = questionnaireViewModel::setWakeUpTime,
                onSave = questionnaireViewModel::save,
                onRetry = questionnaireViewModel::retry,
                onBackPressed = {}
            )
            JourneyStage.HOME -> HomeScreen(
                dashboardUiState = dashboardState,
                dailyIntakeUiState = dailyIntakeState,
                onSelectRoute = dashboardViewModel::selectRoute,
                onSelectDate = dailyIntakeViewModel::selectDate,
                onAddDailyIntake = dailyIntakeViewModel::showNewEntry,
                onEditDailyIntake = dailyIntakeViewModel::editRecord,
                onSaveDailyIntake = dailyIntakeViewModel::save,
                onDismissDailyIntakeEditor = dailyIntakeViewModel::dismissEditor,
                onClearDailyIntakeFeedback = dailyIntakeViewModel::clearFeedback,
                onRetryDashboard = dashboardViewModel::refresh,
                onLogout = dashboardViewModel::logout,
                onEditQuestionnaire = {}
            )
        }
    }

    private enum class JourneyStage {
        AUTH,
        QUESTIONNAIRE,
        HOME
    }

    private companion object {
        const val PASSWORD = "SmokeTest1!"
        const val TIMEOUT_MILLIS = 15_000L
    }
}
