package com.example.nutritrack.data

import com.example.nutritrack.agent.AgentRunHistoryDataSource
import com.example.nutritrack.agent.AgentRunSnapshot
import com.example.nutritrack.agent.AgentFailure
import com.example.nutritrack.agent.AgentFailureCode
import com.example.nutritrack.agent.AgentFailureCodec
import com.example.nutritrack.agent.AgentFailureStage
import com.example.nutritrack.agent.NutritionPlanOutputParser
import com.example.nutritrack.agent.NutritionPlanActionDispatcher
import com.example.nutritrack.agent.NutritionPlanActionResult
import com.example.nutritrack.agent.NutritionPlanLifecycleScheduler
import com.example.nutritrack.agent.NutritionPlanOutputV1
import com.example.nutritrack.agent.NutritionPlanningFailure
import com.example.nutritrack.agent.NutritionPlanningRequest
import com.example.nutritrack.agent.NutritionPlanningResult
import com.example.nutritrack.agent.NutritionPlanRunner
import com.example.nutritrack.agent.NutritionRecommendationCategory
import com.example.nutritrack.agent.NutritionRecommendationV1
import com.example.nutritrack.agent.NutritionTargetUnit
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class NutriCoachViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun generatePlan_movesIdleThroughLoadingToDraftReady() = runTest {
        val history = FakeHistory()
        val gate = CompletableDeferred<Unit>()
        val runner = FakeRunner { _, _ ->
            gate.await()
            history.runs[RUN_ID] = successfulSnapshot()
            successResult()
        }
        val viewModel = viewModel(runner = runner, history = history)
        advanceUntilIdle()
        assertEquals(NutriCoachAgentUiState.Idle, viewModel.uiState.value.agentState)

        viewModel.enterPlanningNeed()
        viewModel.generatePlan()
        runCurrent()
        assertEquals(
            NutriCoachAgentUiState.Loading(canCancel = true),
            viewModel.uiState.value.agentState
        )

        gate.complete(Unit)
        advanceUntilIdle()
        val ready = viewModel.uiState.value.agentState as NutriCoachAgentUiState.DraftReady
        assertEquals("Steady nutrition habits", ready.draft.summary)
        assertEquals(listOf(1), ready.audit.map(AgentAuditUiModel::sequence))
    }

    @Test
    fun modelFailuresExposeStableRetryableMessages() = runTest {
        val cases = listOf(
            NutritionPlanningFailure.MODEL_REFUSED to "MODEL_REFUSED",
            NutritionPlanningFailure.MODEL_TIMEOUT to "MODEL_TIMEOUT",
            NutritionPlanningFailure.MODEL_NETWORK to "MODEL_NETWORK",
            NutritionPlanningFailure.MODEL_ERROR to "MODEL_SDK"
        )
        cases.forEach { (reason, expectedCode) ->
            val history = FakeHistory()
            val runner = FakeRunner { _, _ ->
                history.runs[RUN_ID] = failedSnapshot(errorFor(reason))
                NutritionPlanningResult.Failed(RUN_ID, reason)
            }
            val viewModel = viewModel(runner = runner, history = history)
            advanceUntilIdle()
            viewModel.enterPlanningNeed()
            viewModel.generatePlan()
            advanceUntilIdle()

            val error = viewModel.uiState.value.agentState as NutriCoachAgentUiState.Error
            assertEquals(expectedCode, error.code)
            assertTrue(error.canRetry)
            assertEquals(1, error.audit.size)
        }
    }

    @Test
    fun restoredFieldLevelSafetyFailure_keepsTraceAndShowsOnlySafeUiMessage() = runTest {
        val diagnostic = com.example.nutritrack.agent.PlanSafetyDiagnostic(
            com.example.nutritrack.agent.PlanSafetyRule.MEDICAL_TREATMENT,
            "disclaimer",
            com.example.nutritrack.agent.PlanSafetyCategory.UNRECOGNIZED_NEGATION_SCOPE)
        val failure = AgentFailure(AgentFailureStage.FINAL_SAFETY_VALIDATION,
            AgentFailureCode.FINAL_SAFETY_INVALID, diagnostic)
        val history = FakeHistory().apply { latest = failedSnapshot(AgentFailureCodec.encode(failure)) }
        val runner = FakeRunner { _, _ -> error("Restoration must not call the model") }
        val viewModel = viewModel(history = history, runner = runner)
        advanceUntilIdle()
        val error = viewModel.uiState.value.agentState as NutriCoachAgentUiState.Error
        assertEquals("FINAL_SAFETY_INVALID", error.code)
        assertEquals(1, error.audit.size)
        assertTrue(error.canRetry)
        assertFalse(error.message.contains(diagnostic.safeSummary))
        assertEquals(0, runner.calls)
    }

    @Test
    fun cancelMarksUiCancelledAndKeepsAudit() = runTest {
        val history = FakeHistory()
        val started = CompletableDeferred<Unit>()
        val runner = FakeRunner { _, _ ->
            started.complete(Unit)
            try {
                awaitCancellation()
            } finally {
                history.latest = cancelledSnapshot()
            }
        }
        val viewModel = viewModel(runner = runner, history = history)
        advanceUntilIdle()

        viewModel.enterPlanningNeed()
        viewModel.generatePlan()
        started.await()
        viewModel.cancelGeneration()
        advanceUntilIdle()

        val cancelled = viewModel.uiState.value.agentState as NutriCoachAgentUiState.Cancelled
        assertEquals(1, cancelled.audit.size)
    }

    @Test
    fun retryCreatesANewRunAndPreservesPreviousSnapshot() = runTest {
        val history = FakeHistory()
        val results = ArrayDeque<NutritionPlanningResult>().apply {
            add(NutritionPlanningResult.Failed(RUN_ID, NutritionPlanningFailure.MODEL_TIMEOUT))
            add(successResult(SECOND_RUN_ID))
        }
        val runner = FakeRunner { _, _ ->
            results.removeFirst().also { result ->
                when (result) {
                    is NutritionPlanningResult.Failed -> history.runs[result.runId] =
                        failedSnapshot(AgentFailureCodec.encode(result.failure), result.runId)
                    is NutritionPlanningResult.Success -> history.runs[result.runId] =
                        successfulSnapshot(result.runId)
                    else -> Unit
                }
            }
        }
        val viewModel = viewModel(runner = runner, history = history)
        advanceUntilIdle()

        viewModel.enterPlanningNeed()
        viewModel.generatePlan()
        advanceUntilIdle()
        viewModel.retry()
        advanceUntilIdle()

        assertEquals(2, runner.calls)
        assertEquals(setOf(RUN_ID, SECOND_RUN_ID), history.runs.keys)
        assertTrue(viewModel.uiState.value.agentState is NutriCoachAgentUiState.DraftReady)
    }

    @Test
    fun loggedOutNeverStartsAnAgentRun() = runTest {
        val runner = FakeRunner { _, _ -> successResult() }
        val viewModel = viewModel(
            session = MutableStateFlow(SessionSnapshot(SessionState.LoggedOut)),
            runner = runner
        )
        advanceUntilIdle()

        viewModel.generatePlan()
        advanceUntilIdle()

        assertEquals(0, runner.calls)
        assertEquals(NutriCoachAgentUiState.LoggedOut, viewModel.uiState.value.agentState)
    }

    @Test
    fun repeatedClicksAndStateCollectorsDoNotDuplicateAgentRun() = runTest {
        val gate = CompletableDeferred<Unit>()
        val history = FakeHistory()
        val runner = FakeRunner { _, _ ->
            gate.await()
            history.runs[RUN_ID] = successfulSnapshot()
            successResult()
        }
        val viewModel = viewModel(runner = runner, history = history)
        advanceUntilIdle()

        viewModel.enterPlanningNeed()
        viewModel.generatePlan()
        viewModel.generatePlan()
        repeat(4) { viewModel.uiState.value }
        runCurrent()
        assertEquals(1, runner.calls)

        gate.complete(Unit)
        advanceUntilIdle()
        assertEquals(1, runner.calls)
    }

    @Test
    fun latestDraftIsRestoredWithoutCallingModel() = runTest {
        val stored = successfulSnapshot().let { snapshot ->
            snapshot.copy(
                toolCalls = snapshot.toolCalls.map {
                    it.copy(
                        argumentsJson = "{\"private\":\"SENSITIVE_ARGUMENT_SENTINEL\"}",
                        resultJson = "{\"private\":\"SENSITIVE_RESULT_SENTINEL\"}"
                    )
                }
            )
        }
        val history = FakeHistory().apply { latest = stored }
        val runner = FakeRunner { _, _ -> successResult() }
        val viewModel = viewModel(runner = runner, history = history)
        advanceUntilIdle()

        val ready = viewModel.uiState.value.agentState as NutriCoachAgentUiState.DraftReady
        assertEquals(RUN_ID, ready.draft.runId)
        assertEquals(0, runner.calls)
        assertEquals(1, history.recoveryCalls)
        assertFalse(ready.toString().contains("SENSITIVE_ARGUMENT_SENTINEL"))
        assertFalse(ready.toString().contains("SENSITIVE_RESULT_SENTINEL"))
    }

    @Test
    fun interruptedRunRestoresAsStableRetryableError() = runTest {
        val runner = FakeRunner { _, _ ->
            NutritionPlanningResult.Rejected(NutritionPlanningFailure.INVALID_REQUEST)
        }
        val history = FakeHistory().apply {
            latest = failedSnapshot(
                AgentFailureCodec.encode(
                    AgentFailure(
                        AgentFailureStage.PROCESS_RECOVERY,
                        AgentFailureCode.PROCESS_INTERRUPTED
                    )
                )
            )
        }
        val viewModel = viewModel(history = history, runner = runner)
        advanceUntilIdle()

        val error = viewModel.uiState.value.agentState as NutriCoachAgentUiState.Error
        assertEquals("PROCESS_INTERRUPTED", error.code)
        assertTrue(error.message.contains("interrupted"))
        assertEquals(1, error.audit.size)

        viewModel.enterPlanningNeed()
        viewModel.retry()
        advanceUntilIdle()
        assertEquals(1, runner.calls)
    }

    @Test
    fun persistedStableFailuresRestoreAsCodesStagesAndSanitizedMessages() = runTest {
        AgentFailureCode.entries
            .filterNot { it == AgentFailureCode.CANCELLED }
            .forEach { code ->
                val failure = AgentFailure(AgentFailureStage.FINAL_GENERATION, code)
                val history = FakeHistory().apply {
                    latest = failedSnapshot(AgentFailureCodec.encode(failure))
                }
                val viewModel = viewModel(history = history)
                advanceUntilIdle()

                val error = viewModel.uiState.value.agentState as NutriCoachAgentUiState.Error
                assertEquals(code.name, error.code)
                assertEquals(AgentFailureStage.FINAL_GENERATION.name, error.stage)
                assertFalse(error.message.contains("Throwable", ignoreCase = true))
                assertFalse(error.message.contains("SENSITIVE", ignoreCase = true))
            }
    }

    @Test
    fun loadFailureShowsRecoverableErrorAndRetryLoadsAgain() = runTest {
        val history = FakeHistory().apply { recoveryFailure = true }
        val viewModel = viewModel(history = history)
        advanceUntilIdle()
        val error = viewModel.uiState.value.agentState as NutriCoachAgentUiState.Error
        assertEquals("LOAD_FAILED", error.code)
        assertFalse(error.retryRequiresPlanningNeed)

        history.recoveryFailure = false
        history.latest = successfulSnapshot()
        viewModel.retry()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.agentState is NutriCoachAgentUiState.DraftReady)
    }

    @Test
    fun motivationalMessageIsSavedOnceOutsideComposition() = runTest {
        val content = FakeContentDataSource()
        val ai = AiGateway {
            AiResult.Success(AiResponse("Keep building balanced habits.", "fake-model"))
        }
        val viewModel = viewModel(content = content, ai = ai)
        advanceUntilIdle()

        viewModel.generateMotivationalMessage()
        viewModel.generateMotivationalMessage()
        advanceUntilIdle()

        assertEquals(1, content.savedTips.size)
        assertEquals(
            "Keep building balanced habits.",
            (viewModel.uiState.value.motivationalState as UiState.Success).outputText
        )
    }

    @Test
    fun planningNeedIsRequiredNormalizedAndForwarded() = runTest {
        val runner = FakeRunner { _, _ ->
            NutritionPlanningResult.Rejected(NutritionPlanningFailure.INVALID_REQUEST)
        }
        val viewModel = viewModel(runner = runner)
        advanceUntilIdle()

        viewModel.generatePlan()
        advanceUntilIdle()
        assertEquals(0, runner.calls)
        assertTrue(viewModel.uiState.value.planningNeedError != null)

        viewModel.planningNeedChanged("  Improve   hydration\nusing my recent intake.  ")
        viewModel.generatePlan()
        advanceUntilIdle()

        assertEquals(1, runner.calls)
        assertEquals(
            "Improve hydration using my recent intake.",
            runner.requests.single().planningNeed
        )
        assertEquals(
            "Improve hydration using my recent intake.",
            viewModel.uiState.value.planningNeed
        )
    }

    @Test
    fun planningNeedRejectsContactDataBeforeStartingRun() = runTest {
        val runner = FakeRunner { _, _ -> successResult() }
        val viewModel = viewModel(runner = runner)
        advanceUntilIdle()

        viewModel.planningNeedChanged("Send the plan to sample@example.com")
        viewModel.generatePlan()
        advanceUntilIdle()

        assertEquals(0, runner.calls)
        assertTrue(viewModel.uiState.value.planningNeedError != null)
        assertTrue(viewModel.uiState.value.agentState is NutriCoachAgentUiState.Idle)
    }

    @Test
    fun confirmDraft_isIdempotentAndRestoresThePersistedConfirmedState() = runTest {
        val draftSnapshot = successfulSnapshot()
        val confirmedPlan = checkNotNull(draftSnapshot.plan).copy(
            status = NutritionPlanStatus.CONFIRMED,
            confirmedAtEpochMillis = NOW + 10,
            confirmationActionId = "confirm:${draftSnapshot.plan.id}"
        )
        val history = FakeHistory().apply {
            latest = draftSnapshot
            runs[RUN_ID] = draftSnapshot.copy(plan = confirmedPlan)
        }
        val actions = FakePlanActionDispatcher().apply {
            confirmResult = NutritionPlanActionResult.Applied(confirmedPlan)
        }
        val runner = FakeRunner { _, _ -> successResult() }
        val viewModel = viewModel(history = history, actions = actions, runner = runner)
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.agentState is NutriCoachAgentUiState.DraftReady)
        viewModel.confirmDraft()
        viewModel.confirmDraft()
        advanceUntilIdle()

        val confirmed = viewModel.uiState.value.agentState as NutriCoachAgentUiState.Confirmed
        assertEquals("Steady nutrition habits", confirmed.draft.summary)
        assertEquals(1, actions.confirmCalls)
        assertEquals(0, runner.calls)
    }

    @Test
    fun confirmationSchedulesOneLocalCompletionWorkAndConfirmedPlanCanBeStopped() = runTest {
        val draftSnapshot = successfulSnapshot()
        val confirmedPlan = checkNotNull(draftSnapshot.plan).copy(
            status = NutritionPlanStatus.CONFIRMED,
            confirmedAtEpochMillis = NOW + 10,
            confirmationActionId = "confirm:${draftSnapshot.plan.id}",
            updatedAtEpochMillis = NOW + 10
        )
        val abandonedPlan = confirmedPlan.copy(
            status = NutritionPlanStatus.ABANDONED,
            updatedAtEpochMillis = NOW + 20
        )
        val history = FakeHistory().apply {
            latest = draftSnapshot
            runs[RUN_ID] = draftSnapshot.copy(plan = confirmedPlan)
        }
        val actions = FakePlanActionDispatcher().apply {
            confirmResult = NutritionPlanActionResult.Applied(confirmedPlan)
            abandonConfirmedResult = NutritionPlanActionResult.Applied(abandonedPlan)
        }
        val scheduler = FakePlanLifecycleScheduler()
        val viewModel = viewModel(history = history, actions = actions, lifecycleScheduler = scheduler)
        advanceUntilIdle()

        viewModel.confirmDraft()
        viewModel.confirmDraft()
        advanceUntilIdle()
        assertEquals(listOf(confirmedPlan.id), scheduler.scheduledPlanIds)

        history.runs[RUN_ID] = draftSnapshot.copy(plan = abandonedPlan)
        viewModel.abandonConfirmedPlan()
        advanceUntilIdle()

        assertTrue(viewModel.uiState.value.agentState is NutriCoachAgentUiState.Abandoned)
        assertEquals(1, actions.abandonConfirmedCalls)
        assertEquals(listOf(confirmedPlan.id), scheduler.cancelledPlanIds)
    }

    @Test
    fun recreatedViewModelRestoresConfirmedPlanAndReconcilesItsCompletionWork() = runTest {
        val confirmedPlan = checkNotNull(successfulSnapshot().plan).copy(
            status = NutritionPlanStatus.CONFIRMED,
            confirmedAtEpochMillis = NOW + 10,
            confirmationActionId = "confirm:plan-$RUN_ID",
            updatedAtEpochMillis = NOW + 10
        )
        val history = FakeHistory().apply {
            latest = successfulSnapshot().copy(plan = confirmedPlan)
            runs[RUN_ID] = latest!!
        }
        val scheduler = FakePlanLifecycleScheduler()
        val runner = FakeRunner { _, _ -> successResult() }

        val firstViewModel = viewModel(
            history = history,
            lifecycleScheduler = scheduler,
            runner = runner
        )
        advanceUntilIdle()
        assertTrue(firstViewModel.uiState.value.agentState is NutriCoachAgentUiState.Confirmed)

        val recreatedViewModel = viewModel(
            history = history,
            lifecycleScheduler = scheduler,
            runner = runner
        )
        advanceUntilIdle()

        assertTrue(recreatedViewModel.uiState.value.agentState is NutriCoachAgentUiState.Confirmed)
        assertEquals(listOf(confirmedPlan.id, confirmedPlan.id), scheduler.scheduledPlanIds)
        assertEquals(0, runner.calls)
    }

    @Test
    fun failedDraftConfirmationKeepsTheDraftRecoverable() = runTest {
        val draftSnapshot = successfulSnapshot()
        val history = FakeHistory().apply {
            latest = draftSnapshot
            runs[RUN_ID] = draftSnapshot
        }
        val actions = FakePlanActionDispatcher().apply {
            confirmResult = NutritionPlanActionResult.Failed
        }
        val viewModel = viewModel(history = history, actions = actions)
        advanceUntilIdle()

        viewModel.confirmDraft()
        advanceUntilIdle()

        val state = viewModel.uiState.value.agentState as NutriCoachAgentUiState.DraftReady
        assertFalse(state.isActionInProgress)
        assertTrue(state.actionError?.contains("Unable to update") == true)
        assertEquals(1, actions.confirmCalls)
    }

    @Test
    fun abandonedDraftRestoresAsTerminalAndCannotBeConfirmedAgain() = runTest {
        val draftSnapshot = successfulSnapshot()
        val abandonedPlan = checkNotNull(draftSnapshot.plan).copy(
            status = NutritionPlanStatus.ABANDONED
        )
        val history = FakeHistory().apply {
            latest = draftSnapshot
            runs[RUN_ID] = draftSnapshot.copy(plan = abandonedPlan)
        }
        val actions = FakePlanActionDispatcher().apply {
            abandonResult = NutritionPlanActionResult.Applied(abandonedPlan)
        }
        val viewModel = viewModel(history = history, actions = actions)
        advanceUntilIdle()

        viewModel.abandonDraft()
        advanceUntilIdle()
        assertTrue(viewModel.uiState.value.agentState is NutriCoachAgentUiState.Abandoned)

        viewModel.confirmDraft()
        advanceUntilIdle()
        assertEquals(0, actions.confirmCalls)
        assertEquals(1, actions.abandonCalls)
    }

    private fun viewModel(
        session: Flow<SessionSnapshot> = MutableStateFlow(
            SessionSnapshot(SessionState.LoggedIn(USER_ID))
        ),
        content: FakeContentDataSource = FakeContentDataSource(),
        ai: AiGateway = AiGateway { AiResult.Failure(AiError.Sdk) },
        runner: FakeRunner = FakeRunner { _, _ ->
            NutritionPlanningResult.Rejected(NutritionPlanningFailure.INVALID_REQUEST)
        },
        history: FakeHistory = FakeHistory(),
        actions: FakePlanActionDispatcher = FakePlanActionDispatcher(),
        lifecycleScheduler: FakePlanLifecycleScheduler = FakePlanLifecycleScheduler()
    ) = NutriCoachViewModel(
        sessionState = session,
        contentDataSource = content,
        aiGateway = ai,
        planRunner = runner,
        runHistory = history,
        planActionDispatcher = actions,
        planLifecycleScheduler = lifecycleScheduler,
        planParser = NutritionPlanOutputParser(),
        clock = FIXED_CLOCK,
        zoneId = ZoneOffset.UTC
    )

    private class FakeRunner(
        private val block: suspend (Int, NutritionPlanningRequest) -> NutritionPlanningResult
    ) : NutritionPlanRunner {
        var calls = 0
        val requests = mutableListOf<NutritionPlanningRequest>()

        override suspend fun run(
            userId: Int,
            request: NutritionPlanningRequest
        ): NutritionPlanningResult {
            calls++
            requests += request
            return block(userId, request)
        }
    }

    private class FakePlanActionDispatcher : NutritionPlanActionDispatcher {
        var confirmCalls = 0
        var abandonCalls = 0
        var abandonConfirmedCalls = 0
        var confirmResult: NutritionPlanActionResult = NutritionPlanActionResult.Failed
        var abandonResult: NutritionPlanActionResult = NutritionPlanActionResult.Failed
        var abandonConfirmedResult: NutritionPlanActionResult = NutritionPlanActionResult.Failed

        override suspend fun confirmDraft(
            userId: Int,
            planId: String
        ): NutritionPlanActionResult {
            confirmCalls++
            return confirmResult
        }

        override suspend fun abandonDraft(
            userId: Int,
            planId: String
        ): NutritionPlanActionResult {
            abandonCalls++
            return abandonResult
        }

        override suspend fun abandonConfirmedPlan(
            userId: Int,
            planId: String
        ): NutritionPlanActionResult {
            abandonConfirmedCalls++
            return abandonConfirmedResult
        }
    }

    private class FakePlanLifecycleScheduler : NutritionPlanLifecycleScheduler {
        val scheduledPlanIds = mutableListOf<String>()
        val cancelledPlanIds = mutableListOf<String>()

        override fun ensureCompletionScheduled(plan: NutritionPlan) {
            scheduledPlanIds += plan.id
        }

        override fun cancelCompletion(planId: String) {
            cancelledPlanIds += planId
        }
    }

    private fun NutriCoachViewModel.enterPlanningNeed() {
        planningNeedChanged(DEFAULT_PLANNING_NEED)
    }

    private class FakeHistory : AgentRunHistoryDataSource {
        var latest: AgentRunSnapshot? = null
        val runs = linkedMapOf<String, AgentRunSnapshot>()
        var recoveryFailure = false
        var recoveryCalls = 0

        override suspend fun recoverInterruptedAndLoadLatest(userId: Int): AgentRunSnapshot? {
            recoveryCalls++
            if (recoveryFailure) error("storage unavailable")
            return latest
        }

        override suspend fun loadLatest(userId: Int): AgentRunSnapshot? = latest

        override suspend fun loadRun(userId: Int, runId: String): AgentRunSnapshot =
            checkNotNull(runs[runId])
    }

    private class FakeContentDataSource : NutriCoachContentDataSource {
        val tips = MutableStateFlow<List<NutriCoachTip>>(emptyList())
        val savedTips = mutableListOf<NutriCoachTip>()

        override suspend fun getPatientByUserId(userId: Int): Patient? = samplePatient()

        override fun getNutriCoachTipsForUser(userId: Int): Flow<List<NutriCoachTip>> = tips

        override suspend fun insertNutriCoachTip(tip: NutriCoachTip): Long {
            savedTips += tip
            tips.value = savedTips.toList()
            return savedTips.size.toLong()
        }

        private fun samplePatient(): Patient {
            val stream = requireNotNull(javaClass.classLoader?.getResourceAsStream("data.csv"))
            return stream.bufferedReader().use { CsvParser().parsePatientData(it).first() }
                .copy(userId = USER_ID)
        }
    }

    private fun successResult(runId: String = RUN_ID) = NutritionPlanningResult.Success(
        runId = runId,
        plan = plan(runId),
        output = output()
    )

    private fun successfulSnapshot(runId: String = RUN_ID): AgentRunSnapshot = AgentRunSnapshot(
        run = AgentRun(runId, USER_ID, AgentRunStatus.SUCCEEDED, NOW, NOW + 2),
        plan = plan(runId),
        toolCalls = listOf(successfulCall(runId))
    )

    private fun failedSnapshot(
        error: String,
        runId: String = RUN_ID
    ): AgentRunSnapshot = AgentRunSnapshot(
        run = AgentRun(runId, USER_ID, AgentRunStatus.FAILED, NOW, NOW + 2, error),
        plan = null,
        toolCalls = listOf(successfulCall(runId))
    )

    private fun cancelledSnapshot() = AgentRunSnapshot(
        run = AgentRun(RUN_ID, USER_ID, AgentRunStatus.CANCELLED, NOW, NOW + 2),
        plan = null,
        toolCalls = listOf(successfulCall(RUN_ID))
    )

    private fun successfulCall(runId: String) = AgentToolCall(
        id = TOOL_CALL_ID,
        runId = runId,
        sequence = 1,
        toolName = "get_nutrition_profile",
        argumentsJson = "{}",
        resultJson = "{}",
        status = AgentToolCallStatus.SUCCEEDED,
        errorCode = null,
        startedAtEpochMillis = NOW,
        finishedAtEpochMillis = NOW + 1
    )

    private fun plan(runId: String) = NutritionPlan(
        id = "plan-$runId",
        userId = USER_ID,
        runId = runId,
        status = NutritionPlanStatus.DRAFT,
        schemaVersion = 1,
        validFromEpochDay = START_DATE.toEpochDay(),
        validUntilEpochDay = END_DATE.toEpochDay(),
        contentJson = planJson(),
        cautionsJson = "[\"General guidance only\"]",
        disclaimer = "This draft is general guidance, not medical advice.",
        createdAtEpochMillis = NOW
    )

    private fun output() = NutritionPlanOutputV1(
        schemaVersion = 1,
        summary = "Steady nutrition habits",
        validFrom = START_DATE,
        validTo = END_DATE,
        recommendations = listOf(
            NutritionRecommendationV1(
                category = NutritionRecommendationCategory.HYDRATION,
                title = "Drink regularly",
                action = "Spread water intake across the day.",
                targetValue = 2_000.0,
                targetUnit = NutritionTargetUnit.MILLILITERS_PER_DAY
            )
        ),
        cautions = listOf("General guidance only"),
        disclaimer = "This draft is general guidance, not medical advice.",
        evidenceToolCallIds = listOf(TOOL_CALL_ID)
    )

    private fun planJson() = """
        {
          "schemaVersion":1,
          "summary":"Steady nutrition habits",
          "validFrom":"$START_DATE",
          "validTo":"$END_DATE",
          "recommendations":[{
            "category":"HYDRATION",
            "title":"Drink regularly",
            "action":"Spread water intake across the day.",
            "targetValue":2000,
            "targetUnit":"MILLILITERS_PER_DAY"
          }],
          "cautions":["General guidance only"],
          "disclaimer":"This draft is general guidance, not medical advice.",
          "evidenceToolCallIds":["$TOOL_CALL_ID"]
        }
    """.trimIndent()

    private fun errorFor(failure: NutritionPlanningFailure): String = when (failure) {
        NutritionPlanningFailure.MODEL_REFUSED -> encodedFailure(
            AgentFailureCode.MODEL_REFUSED
        )
        NutritionPlanningFailure.MODEL_TIMEOUT -> encodedFailure(AgentFailureCode.MODEL_TIMEOUT)
        NutritionPlanningFailure.MODEL_NETWORK -> encodedFailure(AgentFailureCode.MODEL_NETWORK)
        NutritionPlanningFailure.MODEL_ERROR -> encodedFailure(AgentFailureCode.MODEL_SDK)
        else -> encodedFailure(AgentFailureCode.UNEXPECTED)
    }

    private fun encodedFailure(code: AgentFailureCode): String = AgentFailureCodec.encode(
        AgentFailure(AgentFailureStage.TOOL_SELECTION, code)
    )

    private companion object {
        const val DEFAULT_PLANNING_NEED =
            "Use my recent intake to create a balanced nutrition plan for next week."
        const val USER_ID = 701
        const val RUN_ID = "run-one"
        const val SECOND_RUN_ID = "run-two"
        const val TOOL_CALL_ID = "tool-one"
        val START_DATE: LocalDate = LocalDate.of(2026, 9, 12)
        val END_DATE: LocalDate = LocalDate.of(2026, 9, 18)
        val FIXED_CLOCK: Clock = Clock.fixed(
            Instant.parse("2026-09-12T08:00:00Z"),
            ZoneOffset.UTC
        )
        const val NOW = 1_757_664_000_000L
    }
}
