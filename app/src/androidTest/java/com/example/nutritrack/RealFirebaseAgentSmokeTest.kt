package com.example.nutritrack

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nutritrack.agent.AgentFailure
import com.example.nutritrack.agent.AgentFailureCode
import com.example.nutritrack.agent.AgentFailureCodec
import com.example.nutritrack.agent.AgentFailureStage
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
import com.example.nutritrack.agent.NutritionPlanningAgentConfig
import com.example.nutritrack.agent.RoomAgentReadDataSource
import com.example.nutritrack.agent.StructuredPlanRequest
import com.example.nutritrack.agent.StructuredPlanResult
import com.example.nutritrack.agent.ToolSelectionResult
import com.example.nutritrack.data.AgentRunStatus
import com.example.nutritrack.data.AgentToolCallStatus
import com.example.nutritrack.data.AiError
import com.example.nutritrack.data.AiGateway
import com.example.nutritrack.data.AiResult
import com.example.nutritrack.data.FoodIntake
import com.example.nutritrack.data.FruitNutrition
import com.example.nutritrack.data.FruitNutritionGateway
import com.example.nutritrack.data.FruitNutritionResult
import com.example.nutritrack.data.FruitNutritionUiState
import com.example.nutritrack.data.NutrackDatabase
import com.example.nutritrack.data.NutriCoachAgentUiState
import com.example.nutritrack.data.NutriCoachContentDataSource
import com.example.nutritrack.data.NutriCoachTip
import com.example.nutritrack.data.NutriCoachViewModel
import com.example.nutritrack.data.NutritionPlanStatus
import com.example.nutritrack.data.NutritionPlan
import com.example.nutritrack.data.Patient
import com.example.nutritrack.data.QuestionnaireProfile
import com.example.nutritrack.data.QuestionnaireProfileRules
import com.example.nutritrack.data.SessionSnapshot
import com.example.nutritrack.data.SessionState
import com.example.nutritrack.data.UserAccount
import com.example.nutritrack.ui.theme.NutritrackTheme
import java.time.Clock
import java.time.ZoneOffset
import java.security.MessageDigest
import java.util.Properties
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit, one-shot acceptance test. It is skipped unless realFirebaseAgent=true is supplied.
 * Recovery requires its own explicit flag and cannot delegate any model request.
 * The test reports only terminal state, counts and fingerprints; never model/user content.
 */
@RunWith(AndroidJUnit4::class)
class RealFirebaseAgentSmokeTest {
    @get:Rule
    val composeRule = createComposeRule()

    private lateinit var database: NutrackDatabase
    private lateinit var application: NutrackApplication
    private lateinit var viewModelStore: ViewModelStore
    private lateinit var viewModel: NutriCoachViewModel
    private lateinit var countingModelGateway: CountingAgentModelGateway
    private lateinit var repository: AgentRepository
    private lateinit var planningAgent: NutritionPlanningAgent
    private lateinit var sessionState: MutableStateFlow<SessionSnapshot>
    private lateinit var clock: Clock
    private lateinit var businessDataBefore: BusinessDataSnapshot
    private var renderContent by mutableStateOf(true)
    private var realSmokeEnabled = false
    private var recoveryOnly = false
    private var previousOutcome: Properties? = null
    private var modelName = NutritionPlanningAgentConfig.modelSettings.modelName
    private var uiDisplayed = false
    private var pageRestored = false
    private var processRestored = false

    @Before
    fun setUp() {
        val arguments = InstrumentationRegistry.getArguments()
        val enabled = arguments
            .getString(REAL_SMOKE_ARGUMENT)
            .let(::isRealFirebaseAgentSmokeEnabled)
        recoveryOnly = isRealFirebaseAgentSmokeEnabled(arguments.getString(RECOVERY_ARGUMENT))
        assumeTrue("Select exactly one explicit acceptance mode", enabled != recoveryOnly)
        val selectedMethod = if (recoveryOnly) "restoreRealDraftWithoutModelRequests"
            else "oneRealFunctionCallingRunPersistsAndDisplaysDraft"
        assumeTrue("A single explicit test method is required",
            arguments.getString("class") == "${javaClass.name}#$selectedMethod")
        realSmokeEnabled = true
        modelName = realAgentSmokeModelName(arguments.getString(MODEL_ARGUMENT))

        application = ApplicationProvider.getApplicationContext()
        if (recoveryOnly) {
            previousOutcome = Properties().apply {
                application.openFileInput(SAFE_OUTCOME_FILE).use(::load)
            }
            assertEquals("SUCCEEDED", previousOutcome!!.getProperty("status"))
            assertEquals("true", previousOutcome!!.getProperty("pageRestored"))
            assertTrue("Recovery must run in a new process",
                previousOutcome!!.getProperty("processId") != android.os.Process.myPid().toString())
            database = openSmokeDatabase()
            businessDataBefore = runBlocking { readBusinessDataSnapshot() }
        } else {
            application.deleteFile(SAFE_OUTCOME_FILE)
            application.deleteDatabase(REAL_SMOKE_DATABASE_NAME)
            writeSafeOutcome(
                SafeOutcome(
                    runStatus = "STARTING",
                    toolCallCount = 0,
                    succeededToolCallCount = 0,
                    failedToolCallCount = 0,
                    planCount = 0,
                    toolSelectionRequestCount = 0,
                    structuredPlanRequestCount = 0,
                    totalModelRequestCount = 0,
                    failureStage = "NONE",
                    errorCode = "NONE",
                    businessDataUnchanged = true
                )
            )
            database = openSmokeDatabase()
            insertDemoPatient()
            runBlocking {
                val profile = demoProfile()
                assertTrue(QuestionnaireProfileRules.isValid(profile))
                database.questionnaireProfileDao().upsert(profile)
                database.foodIntakeDao().insert(
                    FoodIntake(
                        patientUserId = DEMO_USER_ID,
                        submissionDate = System.currentTimeMillis(),
                        foodName = "Demo balanced meal",
                        vegetablesServings = 2.0,
                        fruitServings = 1.0,
                        grainsServings = 1.0,
                        waterMl = 600.0,
                        sugarG = 4.0
                    )
                )
                businessDataBefore = readBusinessDataSnapshot()
            }
        }

        clock = Clock.system(ZoneOffset.UTC)
        countingModelGateway = CountingAgentModelGateway(application.agentModelGateway, !recoveryOnly)
        initializeAgentRuntime()
        sessionState = MutableStateFlow(SessionSnapshot(SessionState.LoggedIn(DEMO_USER_ID)))
        viewModelStore = ViewModelStore()
        viewModel = createViewModel("real-agent-smoke")
    }

    private fun initializeAgentRuntime() {
        repository = AgentRepository(
            database = database,
            patientDao = database.patientDao(),
            agentRunDao = database.agentRunDao(),
            agentToolCallDao = database.agentToolCallDao(),
            nutritionPlanDao = database.nutritionPlanDao(),
            clock = clock,
            idGenerator = SmokeIdGenerator()
        )
        planningAgent = NutritionPlanningAgent(
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
                fruitNutritionGateway = demoFruitGateway(),
                clock = clock
            ),
            modelGateway = countingModelGateway,
            planParser = NutritionPlanOutputParser(),
            clock = clock,
            settings = NutritionPlanningAgentConfig.modelSettings.copy(modelName = modelName)
        )
    }

    @After
    fun tearDown() {
        if (realSmokeEnabled && ::database.isInitialized) {
            runCatching { writeSafeOutcome(readSafeOutcome()) }
        }
        if (::viewModelStore.isInitialized) {
            composeRule.runOnIdle { renderContent = false }
            composeRule.waitForIdle()
            viewModelStore.clear()
        }
        if (::database.isInitialized) database.close()
        // Keep only this isolated fixture for the explicit, no-generation process recovery test.
    }

    @Test
    fun oneRealFunctionCallingRunPersistsAndDisplaysDraft() {
        assumeTrue("Generation mode was not selected", !recoveryOnly)
        renderScreen()

        waitForTag("agent_request_input", SHORT_WAIT_MILLIS)
        composeRule.onNodeWithTag("agent_request_input").performTextInput(PLANNING_NEED)
        waitForTag("agent_generate_plan", SHORT_WAIT_MILLIS)
        composeRule.onNodeWithTag("agent_generate_plan").performClick()
        try {
            composeRule.waitUntil(timeoutMillis = REAL_RUN_WAIT_MILLIS) {
                viewModel.uiState.value.agentState.let { state ->
                    state !is NutriCoachAgentUiState.Loading &&
                        state !is NutriCoachAgentUiState.Idle
                }
            }
        } finally {
            writeSafeOutcome(readSafeOutcome())
        }

        val state = viewModel.uiState.value.agentState
        val outcome = readSafeOutcome()

        assertTrue("Real Agent did not reach DraftReady", state is NutriCoachAgentUiState.DraftReady)
        assertEquals(AgentRunStatus.SUCCEEDED.name, outcome.runStatus)
        assertTrue(outcome.toolSelectionRequestCount in 1..2)
        assertTrue(outcome.structuredPlanRequestCount in 1..2)
        assertEquals(outcome.toolSelectionRequestCount + outcome.structuredPlanRequestCount,
            outcome.totalModelRequestCount)
        assertTrue(outcome.totalModelRequestCount in 2..4)
        assertTrue("Expected at least one audited function call", outcome.toolCallCount >= 1)
        assertEquals(outcome.toolCallCount, outcome.succeededToolCallCount)
        assertEquals(0, outcome.failedToolCallCount)
        assertEquals(1, outcome.planCount)
        assertTrue(outcome.businessDataUnchanged)
        runBlocking {
            val run = database.agentRunDao().getByUser(DEMO_USER_ID).single()
            val calls = database.agentToolCallDao().getByRun(run.id)
            assertEquals((1..calls.size).toList(), calls.map { it.sequence })
            assertTrue(calls.all { it.status == AgentToolCallStatus.SUCCEEDED })
            assertEquals(NutritionPlanStatus.DRAFT, database.nutritionPlanDao().getByRun(run.id)?.status)
        }
        assertDraftDisplayed()
        uiDisplayed = true
        val draftBeforeRestart = (state as NutriCoachAgentUiState.DraftReady).draft
        val selectionRequestsBeforeRestart = countingModelGateway.toolSelectionRequestCount
        val planRequestsBeforeRestart = countingModelGateway.structuredPlanRequestCount
        composeRule.runOnIdle { renderContent = false }
        composeRule.waitForIdle()
        viewModelStore.clear()
        database.close()
        database = openSmokeDatabase()
        initializeAgentRuntime()
        viewModelStore = ViewModelStore()
        viewModel = createViewModel("real-agent-smoke-restored")
        composeRule.runOnIdle { renderContent = true }
        waitForTag("agent_draft", SHORT_WAIT_MILLIS)
        assertTrue("Recreated page must display the identical draft", draftBeforeRestart ==
            (viewModel.uiState.value.agentState as NutriCoachAgentUiState.DraftReady).draft)
        assertEquals(selectionRequestsBeforeRestart, countingModelGateway.toolSelectionRequestCount)
        assertEquals(planRequestsBeforeRestart, countingModelGateway.structuredPlanRequestCount)
        pageRestored = true
    }

    @Test
    fun restoreRealDraftWithoutModelRequests() {
        assumeTrue("Recovery mode was not selected", recoveryOnly)
        renderScreen()
        waitForTag("agent_draft", SHORT_WAIT_MILLIS)
        assertDraftDisplayed()
        val outcome = readSafeOutcome()
        assertEquals("SUCCEEDED", outcome.runStatus)
        assertEquals(1, outcome.planCount)
        assertEquals(previousOutcome!!.getProperty("draftFingerprint"), draftFingerprint())
        assertEquals(previousOutcome!!.getProperty("businessFingerprint"), businessFingerprint())
        val plan = runBlocking { database.nutritionPlanDao().getByUser(DEMO_USER_ID).single() }
        val draft = (viewModel.uiState.value.agentState as NutriCoachAgentUiState.DraftReady).draft
        assertEquals(plan.id, draft.planId)
        assertEquals(plan.runId, draft.runId)
        assertEquals(0, countingModelGateway.toolSelectionRequestCount)
        assertEquals(0, countingModelGateway.structuredPlanRequestCount)
        uiDisplayed = true
        pageRestored = true
        processRestored = true
    }

    private fun renderScreen() {
        composeRule.setContent {
            if (renderContent) {
                val state by viewModel.uiState.collectAsStateWithLifecycle()
                NutritrackTheme {
                    NutriCoachScreen(
                        coachUiState = state,
                        fruitNutritionUiState = FruitNutritionUiState(),
                        onFruitQueryChanged = {},
                        onSearchFruit = {},
                        onRetryFruit = {},
                        onPlanningNeedChanged = viewModel::planningNeedChanged,
                        onGeneratePlan = viewModel::generatePlan,
                        onCancelPlan = viewModel::cancelGeneration,
                        onRetryPlan = viewModel::retry,
                        onGenerateMotivationalMessage = {}
                    )
                }
            }
        }
    }

    private fun assertDraftDisplayed() {
        listOf(
            "agent_draft",
            "agent_summary",
            "agent_cautions",
            "agent_disclaimer",
            "agent_audit"
        ).forEach { tag ->
            assertTrue(
                "Expected sanitized Agent UI element",
                composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
            )
        }
    }

    private fun createViewModel(key: String): NutriCoachViewModel = ViewModelProvider(
        viewModelStore,
        NutriCoachViewModel.Factory(
            sessionState = sessionState,
            contentDataSource = EmptyCoachContentDataSource,
            aiGateway = AiGateway { AiResult.Failure(AiError.Sdk) },
            planRunner = planningAgent,
            runHistory = repository,
            planActionDispatcher = RoomNutritionPlanActionDispatcher(
                database = database,
                nutritionPlanDao = database.nutritionPlanDao(),
                clock = clock
            ),
            planLifecycleScheduler = NoOpPlanLifecycleScheduler,
            planParser = NutritionPlanOutputParser(),
            clock = clock
        )
    )[key, NutriCoachViewModel::class.java]

    private fun openSmokeDatabase(): NutrackDatabase = Room.databaseBuilder(
        application,
        NutrackDatabase::class.java,
        REAL_SMOKE_DATABASE_NAME
    ).build()

    private object NoOpPlanLifecycleScheduler : NutritionPlanLifecycleScheduler {
        override fun ensureCompletionScheduled(plan: NutritionPlan) = Unit
        override fun cancelCompletion(planId: String) = Unit
    }

    private fun waitForTag(tag: String, timeoutMillis: Long) {
        composeRule.waitUntil(timeoutMillis = timeoutMillis) {
            composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
        }
    }

    private fun writeSafeOutcome(outcome: SafeOutcome) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.openFileOutput(SAFE_OUTCOME_FILE, android.content.Context.MODE_PRIVATE).use { output ->
            output.writer().use { writer ->
                writer.appendLine("status=${outcome.runStatus}")
                writer.appendLine("toolCalls=${outcome.toolCallCount}")
                writer.appendLine("succeededToolCalls=${outcome.succeededToolCallCount}")
                writer.appendLine("failedToolCalls=${outcome.failedToolCallCount}")
                writer.appendLine("plans=${outcome.planCount}")
                writer.appendLine("toolSelectionRequests=${outcome.toolSelectionRequestCount}")
                writer.appendLine("structuredPlanRequests=${outcome.structuredPlanRequestCount}")
                writer.appendLine("totalModelRequests=${outcome.totalModelRequestCount}")
                writer.appendLine("failureStage=${outcome.failureStage}")
                writer.appendLine("errorCode=${outcome.errorCode}")
                writer.appendLine("businessDataUnchanged=${outcome.businessDataUnchanged}")
                writer.appendLine("model=$modelName")
                writer.appendLine("processId=${android.os.Process.myPid()}")
                writer.appendLine("uiDisplayed=$uiDisplayed")
                writer.appendLine("pageRestored=$pageRestored")
                writer.appendLine("processRestored=$processRestored")
                writer.appendLine("recoveryModelRequests=${if (recoveryOnly) countingModelGateway.toolSelectionRequestCount + countingModelGateway.structuredPlanRequestCount else 0}")
                writer.appendLine("draftFingerprint=${if (::database.isInitialized) draftFingerprint() else "NONE"}")
                writer.appendLine("businessFingerprint=${if (::database.isInitialized) businessFingerprint() else "NONE"}")
            }
        }
    }

    private fun readSafeOutcome(): SafeOutcome = runBlocking {
        val runs = database.agentRunDao().getByUser(DEMO_USER_ID)
        val run = runs.singleOrNull()
        val calls = run?.let { database.agentToolCallDao().getByRun(it.id) }.orEmpty()
        val plans = database.nutritionPlanDao().getByUser(DEMO_USER_ID)
        val decodedFailure = AgentFailureCodec.decode(run?.errorCode)
        val safeFailure = when {
            decodedFailure != null -> decodedFailure
            run?.status == AgentRunStatus.FAILED -> AgentFailure(
                AgentFailureStage.PROCESS_RECOVERY,
                AgentFailureCode.UNEXPECTED
            )
            else -> null
        }
        val businessDataUnchanged = ::businessDataBefore.isInitialized &&
            businessDataBefore.hasSameContentAs(readBusinessDataSnapshot())
        val toolSelectionRequestCount = if (recoveryOnly) {
            previousOutcome!!.getProperty("toolSelectionRequests").toInt()
        } else if (::countingModelGateway.isInitialized) {
            countingModelGateway.toolSelectionRequestCount
        } else {
            0
        }
        val structuredPlanRequestCount = if (recoveryOnly) {
            previousOutcome!!.getProperty("structuredPlanRequests").toInt()
        } else if (::countingModelGateway.isInitialized) {
            countingModelGateway.structuredPlanRequestCount
        } else {
            0
        }
        SafeOutcome(
            runStatus = run?.status?.name ?: "NO_RUN",
            toolCallCount = calls.size,
            succeededToolCallCount = calls.count {
                it.status == AgentToolCallStatus.SUCCEEDED
            },
            failedToolCallCount = calls.count { it.status == AgentToolCallStatus.FAILED },
            planCount = plans.size,
            toolSelectionRequestCount = toolSelectionRequestCount,
            structuredPlanRequestCount = structuredPlanRequestCount,
            totalModelRequestCount = toolSelectionRequestCount + structuredPlanRequestCount,
            failureStage = safeFailure?.stage?.name ?: "NONE",
            errorCode = safeFailure?.code?.name ?: "NONE",
            businessDataUnchanged = businessDataUnchanged
        )
    }

    private suspend fun readBusinessDataSnapshot() = BusinessDataSnapshot(
        patient = database.patientDao().getPatientByUserId(DEMO_USER_ID),
        questionnaireProfile = database.questionnaireProfileDao().getByUserId(DEMO_USER_ID),
        foodIntakes = database.foodIntakeDao()
            .getByUserAndDateRange(DEMO_USER_ID, 0L, Long.MAX_VALUE),
        userAccount = database.userAccountDao().getUserById(DEMO_USER_ID)
    )

    private fun draftFingerprint(): String = runBlocking {
        fingerprint(database.nutritionPlanDao().getByUser(DEMO_USER_ID).toString())
    }

    private fun businessFingerprint(): String = runBlocking {
        fingerprint(readBusinessDataSnapshot().toString())
    }

    private fun fingerprint(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun insertDemoPatient() {
        val writableDatabase = database.openHelper.writableDatabase
        val values = ContentValues()
        writableDatabase.query("PRAGMA table_info(`patients`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                when (val column = cursor.getString(nameIndex)) {
                    "userId" -> values.put(column, DEMO_USER_ID)
                    "phoneNumber" -> values.put(column, "DEMO_CONTACT_NOT_REAL")
                    "name" -> values.put(column, "Demo User")
                    "sex" -> values.put(column, "Male")
                    else -> values.put(column, 1.0)
                }
            }
        }
        assertTrue(writableDatabase.insert("patients", SQLiteDatabase.CONFLICT_ABORT, values) != -1L)
    }

    private fun demoProfile() = QuestionnaireProfile(
        userId = DEMO_USER_ID,
        canEatFruits = true,
        canEatVegetables = true,
        canEatGrains = true,
        canEatRedMeat = false,
        canEatSeafood = false,
        canEatPoultry = true,
        canEatFish = false,
        canEatEggs = true,
        canEatNutsAndSeeds = true,
        selectedPersona = "Balance Seeker",
        biggestMealTime = "12:00",
        sleepTime = "22:00",
        wakeUpTime = "07:00",
        legacyPreferencesMigrationVersion = 1,
        updatedAtEpochMillis = DEMO_UPDATED_AT_EPOCH_MILLIS
    )

    private fun demoFruitGateway() = FruitNutritionGateway {
        FruitNutritionResult.Found(
            FruitNutrition(
                name = "Apple",
                family = "Rosaceae",
                calories = 52.0,
                fat = 0.2,
                sugar = 10.3,
                carbohydrates = 13.8,
                protein = 0.3
            )
        )
    }

    private object EmptyCoachContentDataSource : NutriCoachContentDataSource {
        override suspend fun getPatientByUserId(userId: Int): Patient? = null

        override fun getNutriCoachTipsForUser(userId: Int): Flow<List<NutriCoachTip>> =
            MutableStateFlow(emptyList())

        override suspend fun insertNutriCoachTip(tip: NutriCoachTip): Long = 0L
    }

    private class SmokeIdGenerator : IdGenerator {
        private var next = 0
        override fun newId(): String = "real-smoke-${++next}"
    }

    private class CountingAgentModelGateway(
        private val delegate: AgentModelGateway,
        private val allowGeneration: Boolean
    ) : AgentModelGateway {
        private val toolSelectionRequests = AtomicInteger()
        private val structuredPlanRequests = AtomicInteger()

        val toolSelectionRequestCount: Int
            get() = toolSelectionRequests.get()

        val structuredPlanRequestCount: Int
            get() = structuredPlanRequests.get()

        override suspend fun selectTools(
            request: AgentToolSelectionRequest
        ): ToolSelectionResult {
            toolSelectionRequests.incrementAndGet()
            check(allowGeneration) { "Model calls are forbidden during recovery" }
            return delegate.selectTools(request)
        }

        override suspend fun generateStructuredPlan(
            request: StructuredPlanRequest
        ): StructuredPlanResult {
            structuredPlanRequests.incrementAndGet()
            check(allowGeneration) { "Model calls are forbidden during recovery" }
            return delegate.generateStructuredPlan(request)
        }
    }

    private data class BusinessDataSnapshot(
        val patient: Patient?,
        val questionnaireProfile: QuestionnaireProfile?,
        val foodIntakes: List<FoodIntake>,
        val userAccount: UserAccount?
    ) {
        fun hasSameContentAs(other: BusinessDataSnapshot): Boolean =
            patient == other.patient &&
                questionnaireProfile == other.questionnaireProfile &&
                foodIntakes == other.foodIntakes &&
                userAccount.hasSameContentAs(other.userAccount)

        private fun UserAccount?.hasSameContentAs(other: UserAccount?): Boolean = when {
            this == null || other == null -> this == null && other == null
            else -> {
                val redactedBytes = byteArrayOf()
                copy(passwordHash = redactedBytes, passwordSalt = redactedBytes) ==
                    other.copy(passwordHash = redactedBytes, passwordSalt = redactedBytes) &&
                    passwordHash.contentEquals(other.passwordHash) &&
                    passwordSalt.contentEquals(other.passwordSalt)
            }
        }
    }

    private data class SafeOutcome(
        val runStatus: String,
        val toolCallCount: Int,
        val succeededToolCallCount: Int,
        val failedToolCallCount: Int,
        val planCount: Int,
        val toolSelectionRequestCount: Int,
        val structuredPlanRequestCount: Int,
        val totalModelRequestCount: Int,
        val failureStage: String,
        val errorCode: String,
        val businessDataUnchanged: Boolean
    )

    private companion object {
        const val PLANNING_NEED =
            "Use my recent intake and nutrition profile to improve hydration next week."
        const val REAL_SMOKE_ARGUMENT = "realFirebaseAgent"
        const val RECOVERY_ARGUMENT = "realFirebaseAgentRecovery"
        const val MODEL_ARGUMENT = "realFirebaseAgentModelName"
        const val REAL_SMOKE_DATABASE_NAME = "real-agent-smoke.db"
        const val SAFE_OUTCOME_FILE = "real_agent_smoke_status.txt"
        const val DEMO_USER_ID = 988_001
        const val DEMO_UPDATED_AT_EPOCH_MILLIS = 1_757_664_000_000L
        const val SHORT_WAIT_MILLIS = 10_000L
        const val REAL_RUN_UI_GRACE_MILLIS = 15_000L
        val REAL_RUN_WAIT_MILLIS =
            NutritionPlanningAgentConfig.TOTAL_TIMEOUT_MILLIS + REAL_RUN_UI_GRACE_MILLIS
    }
}

internal fun isRealFirebaseAgentSmokeEnabled(value: String?): Boolean = value == "true"

internal fun realAgentSmokeModelName(value: String?): String {
    require(value == null || value == "gemini-3.5-flash-lite") { "Unapproved model override" }
    return value ?: NutritionPlanningAgentConfig.modelSettings.modelName
}

@RunWith(AndroidJUnit4::class)
class RealFirebaseAgentSmokeGateTest {
    @Test
    fun modelOverrideIsExplicitAndLimitedToTheApprovedCandidate() {
        assertEquals(NutritionPlanningAgentConfig.modelSettings.modelName, realAgentSmokeModelName(null))
        assertEquals("gemini-3.5-flash-lite", realAgentSmokeModelName("gemini-3.5-flash-lite"))
        listOf("", "other-model", " gemini-3.5-flash-lite ").forEach {
            assertTrue(runCatching { realAgentSmokeModelName(it) }.isFailure)
        }
    }
    @Test
    fun onlyExactTrueEnablesRealFirebaseAgentSmoke() {
        assertTrue(isRealFirebaseAgentSmokeEnabled("true"))
        listOf(null, "", "false", " true ", "TRUE", "True").forEach { value ->
            assertFalse(isRealFirebaseAgentSmokeEnabled(value))
        }
    }
}
