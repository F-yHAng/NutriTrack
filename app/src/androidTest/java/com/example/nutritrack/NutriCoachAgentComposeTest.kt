package com.example.nutritrack

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
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
import com.example.nutritrack.agent.AgentFunctionCall
import com.example.nutritrack.agent.AgentModelGateway
import com.example.nutritrack.agent.AgentRepository
import com.example.nutritrack.agent.AgentToolSelectionRequest
import com.example.nutritrack.agent.AgentToolDispatcher
import com.example.nutritrack.agent.AgentToolRegistry
import com.example.nutritrack.agent.IdGenerator
import com.example.nutritrack.agent.NutritionPlanOutputParser
import com.example.nutritrack.agent.NutritionPlanningAgent
import com.example.nutritrack.agent.NutritionPlanLifecycleScheduler
import com.example.nutritrack.agent.RoomNutritionPlanActionDispatcher
import com.example.nutritrack.agent.RoomAgentReadDataSource
import com.example.nutritrack.agent.StructuredPlanRequest
import com.example.nutritrack.agent.StructuredPlanResult
import com.example.nutritrack.agent.ToolSelectionResult
import com.example.nutritrack.data.AgentRunStatus
import com.example.nutritrack.data.AgentAuditUiModel
import com.example.nutritrack.data.AiError
import com.example.nutritrack.data.AiGateway
import com.example.nutritrack.data.AiResult
import com.example.nutritrack.data.FoodIntake
import com.example.nutritrack.data.FruitNutrition
import com.example.nutritrack.data.FruitNutritionGateway
import com.example.nutritrack.data.FruitNutritionResult
import com.example.nutritrack.data.NutrackDatabase
import com.example.nutritrack.data.NutriCoachContentDataSource
import com.example.nutritrack.data.NutriCoachTip
import com.example.nutritrack.data.NutriCoachViewModel
import com.example.nutritrack.data.NutritionPlanStatus
import com.example.nutritrack.data.NutritionPlan
import com.example.nutritrack.data.Patient
import com.example.nutritrack.data.QuestionnaireProfile
import com.example.nutritrack.data.SessionManager
import com.example.nutritrack.ui.theme.NutritrackTheme
import java.io.File
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NutriCoachAgentComposeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var context: Context
    private lateinit var database: NutrackDatabase
    private lateinit var databaseName: String
    private lateinit var sessionManager: SessionManager
    private lateinit var dataStore: DataStore<Preferences>
    private lateinit var dataStoreFile: File
    private lateinit var dataStoreScope: CoroutineScope
    private lateinit var viewModelStore: ViewModelStore
    private lateinit var viewModel: NutriCoachViewModel
    private lateinit var modelGateway: FakeJourneyModelGateway
    private var renderContent by mutableStateOf(true)

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        databaseName = "agent-compose-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(context, NutrackDatabase::class.java, databaseName).build()
        insertPatient()
        database.questionnaireProfileDao().upsert(profile())
        database.foodIntakeDao().insert(
            FoodIntake(
                patientUserId = USER_ID,
                submissionDate = FIXED_CLOCK.millis(),
                foodName = "Demo meal",
                vegetablesServings = 1.0,
                fruitServings = 2.0,
                waterMl = 500.0,
                sugarG = 3.0
            )
        )

        val storeName = "agent-compose-${UUID.randomUUID()}"
        dataStoreFile = context.preferencesDataStoreFile(storeName)
        dataStoreFile.delete()
        dataStoreScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        dataStore = PreferenceDataStoreFactory.create(
            scope = dataStoreScope,
            produceFile = { dataStoreFile }
        )
        sessionManager = SessionManager(dataStore)
        sessionManager.saveLoginSession(USER_ID)
        modelGateway = FakeJourneyModelGateway()
        createViewModel()
    }

    private fun createViewModel() {
        val repository = AgentRepository(
            database = database,
            patientDao = database.patientDao(),
            agentRunDao = database.agentRunDao(),
            agentToolCallDao = database.agentToolCallDao(),
            nutritionPlanDao = database.nutritionPlanDao(),
            clock = FIXED_CLOCK,
            idGenerator = SequenceIdGenerator()
        )
        val agent = NutritionPlanningAgent(
            repository = repository,
            dispatcher = AgentToolDispatcher(
                registry = AgentToolRegistry(),
                repository = repository,
                readDataSource = RoomAgentReadDataSource(
                    database.patientDao(),
                    database.questionnaireProfileDao(),
                    database.foodIntakeDao(),
                    ZoneOffset.UTC
                ),
                fruitNutritionGateway = fakeFruitGateway(),
                clock = FIXED_CLOCK
            ),
            modelGateway = modelGateway,
            planParser = NutritionPlanOutputParser(),
            clock = FIXED_CLOCK
        )
        viewModelStore = ViewModelStore()
        viewModel = ViewModelProvider(
            viewModelStore,
            NutriCoachViewModel.Factory(
                sessionState = sessionManager.state,
                contentDataSource = FakeContentDataSource(),
                aiGateway = AiGateway { AiResult.Failure(AiError.Sdk) },
                planRunner = agent,
                runHistory = repository,
                planActionDispatcher = RoomNutritionPlanActionDispatcher(
                    database = database,
                    nutritionPlanDao = database.nutritionPlanDao(),
                    clock = FIXED_CLOCK
                ),
                planLifecycleScheduler = NoOpPlanLifecycleScheduler,
                planParser = NutritionPlanOutputParser(),
                clock = FIXED_CLOCK
            )
        )["agent-compose", NutriCoachViewModel::class.java]
    }

    private object NoOpPlanLifecycleScheduler : NutritionPlanLifecycleScheduler {
        override fun ensureCompletionScheduled(plan: NutritionPlan) = Unit
        override fun cancelCompletion(planId: String) = Unit
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
        if (::database.isInitialized) database.close()
        if (::databaseName.isInitialized) context.deleteDatabase(databaseName)
    }

    @Test
    fun fakeAgentFlowConfirmsDraftAndRestoresItWithoutAnotherModelRequest() {
        composeRule.setContent {
            if (renderContent) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                NutritrackTheme {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        AgentPlanCard(
                            state = state.agentState,
                            planningNeed = state.planningNeed,
                            planningNeedError = state.planningNeedError,
                            onPlanningNeedChanged = viewModel::planningNeedChanged,
                            onGenerate = viewModel::generatePlan,
                            onCancel = viewModel::cancelGeneration,
                            onRetry = viewModel::retry,
                            onConfirm = viewModel::confirmDraft,
                            onAbandon = viewModel::abandonDraft
                        )
                    }
                }
            }
        }

        waitForTag("agent_request_input")
        composeRule.onNodeWithTag("agent_request_input").performTextInput(PLANNING_NEED)
        waitForTag("agent_generate_plan")
        composeRule.onNodeWithTag("agent_generate_plan").assertIsEnabled().performClick()
        waitForTag("agent_draft")

        composeRule.onNodeWithTag("agent_summary").assertExists()
        composeRule.onNodeWithTag("agent_recommendation_0").assertExists()
        composeRule.onNodeWithTag("agent_cautions").assertExists()
        composeRule.onNodeWithTag("agent_disclaimer").assertExists()
        composeRule.onNodeWithTag("agent_audit_1").assertExists()
        composeRule.onNodeWithTag("agent_audit_2").assertDoesNotExist()
        composeRule.onNodeWithText("SENSITIVE_NAME_SENTINEL", substring = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText("SENSITIVE_PHONE_SENTINEL", substring = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText("RAW_ARGUMENTS_SENTINEL", substring = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText("RAW_RESULT_SENTINEL", substring = true)
            .assertDoesNotExist()

        composeRule.onNodeWithTag("agent_confirm_plan").assertIsEnabled().performClick()
        waitForTag("agent_plan_confirmed")
        composeRule.onNodeWithTag("agent_confirm_plan").assertDoesNotExist()
        composeRule.onNodeWithTag("agent_abandon_plan").assertDoesNotExist()

        composeRule.runOnIdle { renderContent = false }
        composeRule.waitForIdle()
        val savedPlan = runBlocking { database.nutritionPlanDao().getByUser(USER_ID).single() }
        composeRule.runOnIdle { viewModelStore.clear() }
        database.close()
        database = Room.databaseBuilder(context, NutrackDatabase::class.java, databaseName).build()
        composeRule.runOnIdle { createViewModel() }
        composeRule.runOnIdle { renderContent = true }
        waitForTag("agent_plan_confirmed")
        composeRule.onNodeWithTag("agent_recommendation_0").assertExists()
        composeRule.onNodeWithTag("agent_disclaimer").assertExists()
        composeRule.onNodeWithTag("agent_audit_1").assertExists()
        assertEquals(1, modelGateway.toolSelectionCalls)
        assertEquals(1, modelGateway.structuredPlanCalls)
        assertEquals(PLANNING_NEED, modelGateway.lastPlanningNeed)

        runBlocking {
            assertEquals(savedPlan, database.nutritionPlanDao().getByUser(USER_ID).single())
            val runs = database.agentRunDao().getByUser(USER_ID)
            assertEquals(1, runs.size)
            assertEquals(AgentRunStatus.SUCCEEDED, runs.single().status)
            assertEquals(2, database.agentToolCallDao().getByRun(runs.single().id).size)
            assertEquals(
                NutritionPlanStatus.CONFIRMED,
                database.nutritionPlanDao().getByRun(runs.single().id)?.status
            )
            assertEquals(
                "confirm:${database.nutritionPlanDao().getByRun(runs.single().id)?.id}",
                database.nutritionPlanDao().getByRun(runs.single().id)?.confirmationActionId
            )
            assertEquals(
                FIXED_CLOCK.millis(),
                database.nutritionPlanDao().getByRun(runs.single().id)?.confirmedAtEpochMillis
            )
            assertEquals(
                "SENSITIVE_NAME_SENTINEL",
                database.patientDao().getPatientByUserId(USER_ID)?.name
            )
            assertEquals(profile(), database.questionnaireProfileDao().getByUserId(USER_ID))
            assertEquals(
                1,
                database.foodIntakeDao().getByUserAndDateRange(USER_ID, 0L, Long.MAX_VALUE).size
            )
            assertEquals(null, database.userAccountDao().getUserById(USER_ID))
        }
    }

    @Test
    fun discardedDraftRestoresAsTerminalAndCannotBeConfirmedFromTheUi() {
        composeRule.setContent {
            if (renderContent) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                NutritrackTheme {
                    Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                        AgentPlanCard(
                            state = state.agentState,
                            planningNeed = state.planningNeed,
                            planningNeedError = state.planningNeedError,
                            onPlanningNeedChanged = viewModel::planningNeedChanged,
                            onGenerate = viewModel::generatePlan,
                            onCancel = viewModel::cancelGeneration,
                            onRetry = viewModel::retry,
                            onConfirm = viewModel::confirmDraft,
                            onAbandon = viewModel::abandonDraft
                        )
                    }
                }
            }
        }

        waitForTag("agent_request_input")
        composeRule.onNodeWithTag("agent_request_input").performTextInput(PLANNING_NEED)
        composeRule.onNodeWithTag("agent_generate_plan").performClick()
        waitForTag("agent_draft")
        composeRule.onNodeWithTag("agent_abandon_plan").assertIsEnabled().performClick()
        waitForTag("agent_plan_abandoned")
        composeRule.onNodeWithTag("agent_confirm_plan").assertDoesNotExist()
        assertEquals(
            NutritionPlanStatus.ABANDONED,
            runBlocking { database.nutritionPlanDao().getByUser(USER_ID).single().status }
        )

        composeRule.runOnIdle { renderContent = false }
        composeRule.waitForIdle()
        composeRule.runOnIdle { viewModelStore.clear() }
        database.close()
        database = Room.databaseBuilder(context, NutrackDatabase::class.java, databaseName).build()
        composeRule.runOnIdle { createViewModel() }
        composeRule.runOnIdle { renderContent = true }
        waitForTag("agent_plan_abandoned")
        composeRule.onNodeWithTag("agent_confirm_plan").assertDoesNotExist()
        assertEquals(1, modelGateway.toolSelectionCalls)
        assertEquals(1, modelGateway.structuredPlanCalls)
    }

    @Test
    fun errorCardRendersOnlySanitizedMessageAndAuditSummary() {
        composeRule.setContent {
            NutritrackTheme {
                AgentPlanCard(
                    state = com.example.nutritrack.data.NutriCoachAgentUiState.Error(
                        code = "RAW_CODE_SENTINEL",
                        stage = "RAW_STAGE_SENTINEL",
                        message = "The generated plan could not be verified. Please retry.",
                        audit = listOf(
                            AgentAuditUiModel(
                                sequence = 1,
                                toolName = "Nutrition profile",
                                status = "Succeeded",
                                time = "09:30"
                            )
                        )
                    ),
                    planningNeed = PLANNING_NEED,
                    planningNeedError = null,
                    onPlanningNeedChanged = {},
                    onGenerate = {},
                    onCancel = {},
                    onRetry = {}
                )
            }
        }

        composeRule.onNodeWithTag("agent_error").assertExists()
        composeRule.onNodeWithTag("agent_retry").assertExists()
        composeRule.onNodeWithText("The generated plan could not be verified. Please retry.")
            .assertExists()
        composeRule.onNodeWithText("RAW_CODE_SENTINEL", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("RAW_STAGE_SENTINEL", substring = true).assertDoesNotExist()
        composeRule.onNodeWithText("RAW_ARGUMENTS_SENTINEL", substring = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText("RAW_RESULT_SENTINEL", substring = true)
            .assertDoesNotExist()
        composeRule.onNodeWithText("SENSITIVE_PHONE_SENTINEL", substring = true)
            .assertDoesNotExist()
    }

    @Test
    fun restoredErrorRequiresAPlanningNeedBeforeRetry() {
        composeRule.setContent {
            NutritrackTheme {
                AgentPlanCard(
                    state = com.example.nutritrack.data.NutriCoachAgentUiState.Error(
                        code = "FINAL_SAFETY_INVALID",
                        message = "The generated plan could not be verified. Please retry."
                    ),
                    planningNeed = "",
                    planningNeedError = null,
                    onPlanningNeedChanged = {},
                    onGenerate = {},
                    onCancel = {},
                    onRetry = {}
                )
            }
        }

        composeRule.onNodeWithTag("agent_retry").assertIsNotEnabled()
    }

    @Test
    fun loadErrorCanRetryWithoutAPlanningNeed() {
        composeRule.setContent {
            NutritrackTheme {
                AgentPlanCard(
                    state = com.example.nutritrack.data.NutriCoachAgentUiState.Error(
                        code = "LOAD_FAILED",
                        message = "Unable to load the latest draft. Please retry.",
                        retryRequiresPlanningNeed = false
                    ),
                    planningNeed = "",
                    planningNeedError = null,
                    onPlanningNeedChanged = {},
                    onGenerate = {},
                    onCancel = {},
                    onRetry = {}
                )
            }
        }

        composeRule.onNodeWithTag("agent_retry").assertIsEnabled()
    }

    private fun waitForTag(tag: String) {
        composeRule.waitUntil(timeoutMillis = TIMEOUT_MILLIS) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun insertPatient() {
        val writableDatabase = database.openHelper.writableDatabase
        val values = ContentValues()
        writableDatabase.query("PRAGMA table_info(`patients`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                when (val column = cursor.getString(nameIndex)) {
                    "userId" -> values.put(column, USER_ID)
                    "phoneNumber" -> values.put(column, "SENSITIVE_PHONE_SENTINEL")
                    "name" -> values.put(column, "SENSITIVE_NAME_SENTINEL")
                    "sex" -> values.put(column, "Male")
                    else -> values.put(column, 1.0)
                }
            }
        }
        assertTrue(writableDatabase.insert("patients", SQLiteDatabase.CONFLICT_ABORT, values) != -1L)
    }

    private fun profile() = QuestionnaireProfile(
        userId = USER_ID,
        canEatFruits = true,
        canEatVegetables = true,
        canEatGrains = true,
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
        updatedAtEpochMillis = FIXED_CLOCK.millis()
    )

    private fun fakeFruitGateway() = FruitNutritionGateway {
        FruitNutritionResult.Found(
            FruitNutrition(
                name = "RAW_RESULT_SENTINEL",
                family = "Rosaceae",
                calories = 52.0,
                fat = 0.2,
                sugar = 10.3,
                carbohydrates = 13.8,
                protein = 0.3
            )
        )
    }

    private class FakeContentDataSource : NutriCoachContentDataSource {
        override suspend fun getPatientByUserId(userId: Int): Patient? = null

        override fun getNutriCoachTipsForUser(userId: Int): Flow<List<NutriCoachTip>> =
            MutableStateFlow(emptyList())

        override suspend fun insertNutriCoachTip(tip: NutriCoachTip): Long = 1L
    }

    private class SequenceIdGenerator : IdGenerator {
        private var value = 0
        override fun newId(): String = "compose-${++value}"
    }

    private class FakeJourneyModelGateway : AgentModelGateway {
        var toolSelectionCalls = 0
            private set
        var structuredPlanCalls = 0
            private set
        var lastPlanningNeed: String? = null
            private set

        override suspend fun selectTools(
            request: AgentToolSelectionRequest
        ): ToolSelectionResult {
            toolSelectionCalls++
            lastPlanningNeed = request.planningRequest.planningNeed
            val startDate = request.planningRequest.validFrom
            val endDate = request.planningRequest.validTo
            return ToolSelectionResult.Selected(
                listOf(
                    AgentFunctionCall(
                        toolName = "get_nutrition_profile",
                        argumentsJson = "{}"
                    ),
                    AgentFunctionCall(
                        toolName = "get_intake_history",
                        argumentsJson =
                            "{\"startDate\":\"$startDate\",\"endDate\":\"$endDate\"}"
                    )
                )
            )
        }

        override suspend fun generateStructuredPlan(
            request: StructuredPlanRequest
        ): StructuredPlanResult {
            structuredPlanCalls++
            check(request.evidenceBundle.planningNeed == PLANNING_NEED)
            val evidenceAlias = request.evidenceBundle.items.first().alias
            return StructuredPlanResult.Output(
                planJson(
                    evidenceId = evidenceAlias,
                    validFrom = request.evidenceBundle.validFrom,
                    validTo = request.evidenceBundle.validTo
                )
            )
        }
    }

    companion object {
        private const val PLANNING_NEED =
            "Use my recent intake to improve hydration and fruit intake next week."
        private const val USER_ID = 799
        private const val TIMEOUT_MILLIS = 10_000L
        private val FIXED_CLOCK: Clock = Clock.fixed(
            Instant.parse("2026-09-12T08:00:00Z"),
            ZoneOffset.UTC
        )

        private fun planJson(
            evidenceId: String,
            validFrom: LocalDate,
            validTo: LocalDate
        ) = """
            {
              "schemaVersion":1,
              "summary":"Build steady, balanced habits this week",
              "validFrom":"$validFrom",
              "validTo":"$validTo",
              "recommendations":[{
                "category":"HYDRATION",
                "title":"Drink water regularly",
                "action":"Spread water intake across the day.",
                "targetValue":2000,
                "targetUnit":"MILLILITERS_PER_DAY"
              }],
              "cautions":["Adjust gradually based on comfort."],
              "disclaimer":"This guidance is not intended as a substitute for medical diagnosis or treatment.",
              "evidenceToolCallIds":["$evidenceId"]
            }
        """.trimIndent()
    }
}
