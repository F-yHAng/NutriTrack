package com.example.nutritrack.data

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.example.nutritrack.agent.AgentFailure
import com.example.nutritrack.agent.AgentFailureCode
import com.example.nutritrack.agent.AgentFailureCodec
import com.example.nutritrack.agent.AgentFailureStage
import com.example.nutritrack.agent.AgentRunHistoryDataSource
import com.example.nutritrack.agent.AgentRunSnapshot
import com.example.nutritrack.agent.AgentToolName
import com.example.nutritrack.agent.NutritionPlanOutputParser
import com.example.nutritrack.agent.NutritionPlanOutputV1
import com.example.nutritrack.agent.NutritionPlanActionDispatcher
import com.example.nutritrack.agent.NutritionPlanActionResult
import com.example.nutritrack.agent.NutritionPlanLifecycleScheduler
import com.example.nutritrack.agent.NutritionPlanningFailure
import com.example.nutritrack.agent.NutritionPlanningGoal
import com.example.nutritrack.agent.NutritionPlanningRequest
import com.example.nutritrack.agent.NutritionPlanningRequestPolicy
import com.example.nutritrack.agent.NutritionPlanningResult
import com.example.nutritrack.agent.NutritionPlanRunner
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeoutException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

interface NutriCoachContentDataSource {
    suspend fun getPatientByUserId(userId: Int): Patient?
    fun getNutriCoachTipsForUser(userId: Int): Flow<List<NutriCoachTip>>
    suspend fun insertNutriCoachTip(tip: NutriCoachTip): Long
}

data class AgentRecommendationUiModel(
    val title: String,
    val action: String,
    val target: String
)

data class AgentDraftPlanUiModel(
    val planId: String,
    val runId: String,
    val summary: String,
    val validFrom: String,
    val validTo: String,
    val recommendations: List<AgentRecommendationUiModel>,
    val cautions: List<String>,
    val disclaimer: String
)

data class AgentAuditUiModel(
    val sequence: Int,
    val toolName: String,
    val status: String,
    val time: String
)

sealed interface NutriCoachAgentUiState {
    data object Idle : NutriCoachAgentUiState
    data class Loading(val canCancel: Boolean) : NutriCoachAgentUiState

    data class DraftReady(
        val draft: AgentDraftPlanUiModel,
        val audit: List<AgentAuditUiModel>,
        val isActionInProgress: Boolean = false,
        val actionError: String? = null
    ) : NutriCoachAgentUiState

    data class Confirmed(
        val draft: AgentDraftPlanUiModel,
        val audit: List<AgentAuditUiModel>,
        val confirmedAt: String,
        val isActive: Boolean = false,
        val isActionInProgress: Boolean = false,
        val lifecycleError: String? = null
    ) : NutriCoachAgentUiState

    data class Completed(
        val draft: AgentDraftPlanUiModel,
        val audit: List<AgentAuditUiModel>,
        val completedAt: String
    ) : NutriCoachAgentUiState

    data class Abandoned(
        val draft: AgentDraftPlanUiModel,
        val audit: List<AgentAuditUiModel>,
        val lifecycleError: String? = null
    ) : NutriCoachAgentUiState

    data class Error(
        val code: String,
        val message: String,
        val stage: String? = null,
        val audit: List<AgentAuditUiModel> = emptyList(),
        val canRetry: Boolean = true,
        val retryRequiresPlanningNeed: Boolean = true
    ) : NutriCoachAgentUiState

    data class Cancelled(
        val audit: List<AgentAuditUiModel> = emptyList()
    ) : NutriCoachAgentUiState

    data object LoggedOut : NutriCoachAgentUiState
}

data class NutriCoachUiState(
    val userFruitScore: Double = 0.0,
    val isOptimalFruitScore: Boolean = false,
    val previousTips: List<NutriCoachTip> = emptyList(),
    val motivationalState: UiState = UiState.Initial,
    val planningNeed: String = "",
    val planningNeedError: String? = null,
    val agentState: NutriCoachAgentUiState = NutriCoachAgentUiState.Idle
)

class NutriCoachViewModel(
    sessionState: Flow<SessionSnapshot>,
    private val contentDataSource: NutriCoachContentDataSource,
    private val aiGateway: AiGateway,
    private val planRunner: NutritionPlanRunner,
    private val runHistory: AgentRunHistoryDataSource,
    private val planActionDispatcher: NutritionPlanActionDispatcher,
    private val planLifecycleScheduler: NutritionPlanLifecycleScheduler,
    private val planParser: NutritionPlanOutputParser,
    private val clock: Clock,
    private val zoneId: ZoneId = clock.zone
) : ViewModel() {
    private val _uiState = MutableStateFlow(NutriCoachUiState())
    val uiState: StateFlow<NutriCoachUiState> = _uiState.asStateFlow()

    private var trustedUserId: Int? = null
    private var pageDataJob: Job? = null
    private var generationJob: Job? = null
    private var planActionJob: Job? = null
    private var motivationalJob: Job? = null
    private var loadJob: Job? = null
    private var retryAction = RetryAction.GENERATE

    init {
        viewModelScope.launch {
            sessionState
                .map { it.sessionState }
                .distinctUntilChanged()
                .collectLatest(::handleSessionState)
        }
    }

    fun loadLatest() {
        loadLatest(recoverInterrupted = false)
    }

    fun generatePlan() {
        startPlanGeneration()
    }

    fun planningNeedChanged(value: String) {
        if (generationJob?.isActive == true) return
        _uiState.update {
            it.copy(
                planningNeed = value,
                planningNeedError = null
            )
        }
    }

    fun cancelGeneration() {
        generationJob?.takeIf(Job::isActive)?.cancel()
    }

    fun confirmDraft() {
        applyDraftAction(DraftAction.CONFIRM)
    }

    fun abandonDraft() {
        applyDraftAction(DraftAction.ABANDON)
    }

    fun abandonConfirmedPlan() {
        applyConfirmedPlanAbandonment()
    }

    fun retry() {
        when (retryAction) {
            RetryAction.LOAD -> loadLatest()
            RetryAction.GENERATE -> startPlanGeneration()
        }
    }

    fun generateMotivationalMessage() {
        val userId = trustedUserId ?: run {
            _uiState.update { it.copy(agentState = NutriCoachAgentUiState.LoggedOut) }
            return
        }
        if (motivationalJob?.isActive == true) return
        _uiState.update { it.copy(motivationalState = UiState.Loading) }
        motivationalJob = viewModelScope.launch {
            val resultState = try {
                val result = withTimeout(MOTIVATIONAL_TIMEOUT_MILLIS) {
                    aiGateway.generate(AiRequest(MOTIVATIONAL_PROMPT))
                }
                result.toUiState()
            } catch (_: TimeoutCancellationException) {
                AiError.Timeout.toUiState()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: TimeoutException) {
                AiError.Timeout.toUiState()
            } catch (_: Exception) {
                AiError.Sdk.toUiState()
            }
            if (trustedUserId != userId) return@launch
            _uiState.update { it.copy(motivationalState = resultState) }
            if (resultState is UiState.Success) {
                runCatching {
                    contentDataSource.insertNutriCoachTip(
                        NutriCoachTip(
                            patientUserId = userId,
                            tipContent = resultState.outputText,
                            tipType = "ai_generated",
                            userFruitScore = _uiState.value.userFruitScore,
                            aiModel = resultState.modelName
                        )
                    )
                }
            }
        }
    }

    private suspend fun handleSessionState(state: SessionState) {
        when (state) {
            SessionState.Loading -> Unit
            SessionState.LoggedOut -> clearForUnavailableSession(NutriCoachAgentUiState.LoggedOut)
            is SessionState.ReadError -> clearForUnavailableSession(
                NutriCoachAgentUiState.Error(
                    code = "SESSION_READ_FAILED",
                    message = "Unable to read the current session.",
                    canRetry = false
                )
            )

            is SessionState.LoggedIn -> if (trustedUserId != state.userId) {
                switchToUser(state.userId)
            }
        }
    }

    private fun switchToUser(userId: Int) {
        cancelUserWork()
        trustedUserId = userId
        _uiState.value = NutriCoachUiState(
            agentState = NutriCoachAgentUiState.Loading(canCancel = false)
        )
        pageDataJob = viewModelScope.launch {
            launch {
                runCatching { contentDataSource.getPatientByUserId(userId) }
                    .getOrNull()
                    ?.takeIf { trustedUserId == userId }
                    ?.let { patient ->
                        val score = if (patient.sex.equals("Male", ignoreCase = true)) {
                            patient.fruitHEIFAscoreMale
                        } else {
                            patient.fruitHEIFAscoreFemale
                        }
                        _uiState.update {
                            it.copy(
                                userFruitScore = score,
                                isOptimalFruitScore = score >= OPTIMAL_FRUIT_SCORE
                            )
                        }
                    }
            }
            launch {
                contentDataSource.getNutriCoachTipsForUser(userId)
                    .catch { emit(emptyList()) }
                    .collect { tips ->
                        if (trustedUserId == userId) {
                            _uiState.update { it.copy(previousTips = tips) }
                        }
                    }
            }
        }
        loadLatest(recoverInterrupted = true)
    }

    private fun clearForUnavailableSession(agentState: NutriCoachAgentUiState) {
        cancelUserWork()
        trustedUserId = null
        _uiState.value = NutriCoachUiState(agentState = agentState)
    }

    private fun cancelUserWork() {
        pageDataJob?.cancel()
        loadJob?.cancel()
        generationJob?.cancel()
        planActionJob?.cancel()
        motivationalJob?.cancel()
    }

    private fun loadLatest(recoverInterrupted: Boolean) {
        val userId = trustedUserId ?: run {
            _uiState.update { it.copy(agentState = NutriCoachAgentUiState.LoggedOut) }
            return
        }
        if (
            generationJob?.isActive == true ||
            loadJob?.isActive == true ||
            planActionJob?.isActive == true
        ) return
        retryAction = RetryAction.LOAD
        _uiState.update {
            it.copy(agentState = NutriCoachAgentUiState.Loading(canCancel = false))
        }
        loadJob = viewModelScope.launch {
            try {
                val snapshot = if (recoverInterrupted) {
                    runHistory.recoverInterruptedAndLoadLatest(userId)
                } else {
                    runHistory.loadLatest(userId)
                }
                if (trustedUserId == userId) {
                    val lifecycleError = reconcilePlanLifecycle(snapshot?.plan)
                    val restoredState = snapshot.toUiState(lifecycleError)
                    retryAction = RetryAction.GENERATE
                    _uiState.update { it.copy(agentState = restoredState) }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                if (trustedUserId == userId) {
                    retryAction = RetryAction.LOAD
                    _uiState.update {
                        it.copy(
                            agentState = NutriCoachAgentUiState.Error(
                                code = "LOAD_FAILED",
                                message = "Unable to load the latest draft. Please retry.",
                                retryRequiresPlanningNeed = false
                            )
                        )
                    }
                }
            }
        }
    }

    private fun startPlanGeneration() {
        val userId = trustedUserId ?: run {
            _uiState.update { it.copy(agentState = NutriCoachAgentUiState.LoggedOut) }
            return
        }
        if (
            generationJob?.isActive == true ||
            loadJob?.isActive == true ||
            planActionJob?.isActive == true
        ) return
        val planningNeed = NutritionPlanningRequestPolicy.normalizeOrNull(
            _uiState.value.planningNeed
        ) ?: run {
            _uiState.update {
                it.copy(
                    planningNeedError = if (it.planningNeed.isBlank()) {
                        "Enter the nutrition goal you want the draft to address."
                    } else {
                        "Use up to ${NutritionPlanningRequestPolicy.MAX_LENGTH} characters and remove contact, account, or credential details."
                    }
                )
            }
            return
        }
        retryAction = RetryAction.GENERATE
        _uiState.update {
            it.copy(
                planningNeed = planningNeed,
                planningNeedError = null,
                agentState = NutriCoachAgentUiState.Loading(canCancel = true)
            )
        }
        val today = LocalDate.now(clock)
        val request = NutritionPlanningRequest(
            planningNeed = planningNeed,
            goal = NutritionPlanningGoal.USER_DEFINED,
            validFrom = today,
            validTo = today.plusDays(DEFAULT_PLAN_DAYS - 1L)
        )
        generationJob = viewModelScope.launch {
            try {
                val result = planRunner.run(userId, request)
                if (trustedUserId != userId) return@launch
                val state = when (result) {
                    is NutritionPlanningResult.Success -> loadRunState(userId, result.runId)
                    is NutritionPlanningResult.AlreadyRunning -> NutriCoachAgentUiState.Error(
                        code = "RUN_ALREADY_ACTIVE",
                        message = "A nutrition plan is already being generated."
                    )

                    is NutritionPlanningResult.Rejected -> failureState(result.reason)
                    is NutritionPlanningResult.Failed -> loadFailureState(
                        userId,
                        result.runId,
                        result.failure
                    )

                    is NutritionPlanningResult.Cancelled -> loadCancelledState(userId, result.runId)
                }
                _uiState.update { it.copy(agentState = state) }
            } catch (cancelled: CancellationException) {
                withContext(NonCancellable) {
                    if (trustedUserId == userId) {
                        val latest = runCatching { runHistory.loadLatest(userId) }.getOrNull()
                        _uiState.update {
                            it.copy(
                                agentState = NutriCoachAgentUiState.Cancelled(
                                    latest?.toolCalls?.toAuditUi().orEmpty()
                                )
                            )
                        }
                    }
                }
            } catch (_: Exception) {
                if (trustedUserId == userId) {
                    _uiState.update {
                        it.copy(
                            agentState = NutriCoachAgentUiState.Error(
                                code = AgentFailureCode.UNEXPECTED.name,
                                message = "Unable to generate a nutrition plan. Please retry."
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun loadRunState(
        userId: Int,
        runId: String
    ): NutriCoachAgentUiState = runHistory.loadRun(userId, runId).toUiState()

    private fun applyDraftAction(action: DraftAction) {
        val userId = trustedUserId ?: run {
            _uiState.update { it.copy(agentState = NutriCoachAgentUiState.LoggedOut) }
            return
        }
        if (planActionJob?.isActive == true) return
        val draftState = _uiState.value.agentState as? NutriCoachAgentUiState.DraftReady ?: return
        if (draftState.isActionInProgress) return

        _uiState.update { current ->
            val currentDraft = current.agentState as? NutriCoachAgentUiState.DraftReady
                ?: return@update current
            current.copy(
                agentState = currentDraft.copy(
                    isActionInProgress = true,
                    actionError = null
                )
            )
        }

        planActionJob = viewModelScope.launch {
            val result = try {
                when (action) {
                    DraftAction.CONFIRM -> planActionDispatcher.confirmDraft(
                        userId,
                        draftState.draft.planId
                    )
                    DraftAction.ABANDON -> planActionDispatcher.abandonDraft(
                        userId,
                        draftState.draft.planId
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NutritionPlanActionResult.Failed
            }
            if (trustedUserId != userId) return@launch

            val schedulingError = when (result) {
                is NutritionPlanActionResult.Applied -> result.plan
                is NutritionPlanActionResult.AlreadyApplied -> result.plan
                else -> null
            }?.takeIf { action == DraftAction.CONFIRM }?.let(::scheduleCompletionSafely)

            when (result) {
                is NutritionPlanActionResult.Applied,
                is NutritionPlanActionResult.AlreadyApplied,
                is NutritionPlanActionResult.Rejected -> {
                    val restored = runCatching {
                        loadRunState(userId, draftState.draft.runId)
                    }.getOrElse {
                        actionFailureState()
                    }
                    _uiState.update {
                        it.copy(agentState = restored.withLifecycleSchedulingError(schedulingError))
                    }
                }

                NutritionPlanActionResult.NotFound -> _uiState.update {
                    it.withDraftActionError("This draft is no longer available. Reload the page and try again.")
                }

                NutritionPlanActionResult.Failed -> _uiState.update {
                    it.withDraftActionError("Unable to update this draft. Please try again.")
                }
            }
        }
    }

    private fun NutriCoachUiState.withDraftActionError(message: String): NutriCoachUiState {
        val draft = agentState as? NutriCoachAgentUiState.DraftReady ?: return this
        return copy(agentState = draft.copy(isActionInProgress = false, actionError = message))
    }

    private fun applyConfirmedPlanAbandonment() {
        val userId = trustedUserId ?: run {
            _uiState.update { it.copy(agentState = NutriCoachAgentUiState.LoggedOut) }
            return
        }
        if (planActionJob?.isActive == true) return
        val confirmedState = _uiState.value.agentState as? NutriCoachAgentUiState.Confirmed ?: return
        if (confirmedState.isActionInProgress) return

        _uiState.update { current ->
            val confirmed = current.agentState as? NutriCoachAgentUiState.Confirmed
                ?: return@update current
            current.copy(agentState = confirmed.copy(isActionInProgress = true, lifecycleError = null))
        }
        planActionJob = viewModelScope.launch {
            val result = try {
                planActionDispatcher.abandonConfirmedPlan(userId, confirmedState.draft.planId)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                NutritionPlanActionResult.Failed
            }
            if (trustedUserId != userId) return@launch

            when (result) {
                is NutritionPlanActionResult.Applied,
                is NutritionPlanActionResult.AlreadyApplied,
                is NutritionPlanActionResult.Rejected -> {
                    val changedPlan = when (result) {
                        is NutritionPlanActionResult.Applied -> result.plan
                        is NutritionPlanActionResult.AlreadyApplied -> result.plan
                        is NutritionPlanActionResult.Rejected -> result.plan
                        else -> error("unreachable")
                    }
                    val cancellationError = changedPlan
                        .takeIf { it.status == NutritionPlanStatus.ABANDONED }
                        ?.let(::cancelCompletionSafely)
                    val restored = runCatching {
                        loadRunState(userId, confirmedState.draft.runId)
                    }.getOrElse {
                        actionFailureState()
                    }
                    _uiState.update {
                        it.copy(agentState = restored.withLifecycleSchedulingError(cancellationError))
                    }
                }

                NutritionPlanActionResult.NotFound -> _uiState.update {
                    it.withConfirmedActionError("This confirmed plan is no longer available. Reload the page and try again.")
                }

                NutritionPlanActionResult.Failed -> _uiState.update {
                    it.withConfirmedActionError("Unable to stop this plan. Please try again.")
                }
            }
        }
    }

    private fun NutriCoachUiState.withConfirmedActionError(message: String): NutriCoachUiState {
        val confirmed = agentState as? NutriCoachAgentUiState.Confirmed ?: return this
        return copy(agentState = confirmed.copy(isActionInProgress = false, lifecycleError = message))
    }

    private fun actionFailureState(): NutriCoachAgentUiState.Error = NutriCoachAgentUiState.Error(
        code = "PLAN_ACTION_LOAD_FAILED",
        message = "The draft was updated, but its current status could not be loaded. Reload the page.",
        retryRequiresPlanningNeed = false
    )

    private suspend fun loadFailureState(
        userId: Int,
        runId: String,
        fallback: AgentFailure
    ): NutriCoachAgentUiState {
        val snapshot = runCatching { runHistory.loadRun(userId, runId) }.getOrNull()
        val audit = snapshot?.toolCalls?.toAuditUi().orEmpty()
        val failure = AgentFailureCodec.decode(snapshot?.run?.errorCode) ?: fallback
        return failureState(failure, audit)
    }

    private suspend fun loadCancelledState(
        userId: Int,
        runId: String
    ): NutriCoachAgentUiState {
        val audit = runCatching { runHistory.loadRun(userId, runId).toolCalls.toAuditUi() }
            .getOrDefault(emptyList())
        return NutriCoachAgentUiState.Cancelled(audit)
    }

    private fun reconcilePlanLifecycle(plan: NutritionPlan?): String? {
        val current = plan ?: return null
        return when (current.status) {
            NutritionPlanStatus.CONFIRMED,
            NutritionPlanStatus.ACTIVE -> scheduleCompletionSafely(current)
            NutritionPlanStatus.ABANDONED -> cancelCompletionSafely(current)
            else -> null
        }
    }

    private fun scheduleCompletionSafely(plan: NutritionPlan): String? = runCatching {
        planLifecycleScheduler.ensureCompletionScheduled(plan)
    }.exceptionOrNull()?.let {
        "The plan is confirmed, but its local completion task could not be scheduled. Reopen this page to retry."
    }

    private fun cancelCompletionSafely(plan: NutritionPlan): String? = runCatching {
        planLifecycleScheduler.cancelCompletion(plan.id)
    }.exceptionOrNull()?.let {
        "The plan was stopped, but local task cancellation could not be completed yet. Reopen this page to retry."
    }

    private fun NutriCoachAgentUiState.withLifecycleSchedulingError(
        message: String?
    ): NutriCoachAgentUiState = when (this) {
        is NutriCoachAgentUiState.Confirmed -> copy(lifecycleError = message)
        is NutriCoachAgentUiState.Abandoned -> copy(lifecycleError = message)
        else -> this
    }

    private fun AgentRunSnapshot?.toUiState(
        lifecycleError: String? = null
    ): NutriCoachAgentUiState {
        this ?: return NutriCoachAgentUiState.Idle
        val audit = toolCalls.toAuditUi()
        return when (run.status) {
            AgentRunStatus.RUNNING -> NutriCoachAgentUiState.Error(
                code = "RUN_ALREADY_ACTIVE",
                message = "A nutrition plan is already being generated.",
                audit = audit
            )

            AgentRunStatus.CANCELLED -> NutriCoachAgentUiState.Cancelled(audit)
            AgentRunStatus.FAILED -> failedRunState(run.errorCode, audit)
            AgentRunStatus.SUCCEEDED -> {
                val storedPlan = plan ?: return NutriCoachAgentUiState.Error(
                    code = "INVALID_STORED_PLAN",
                    message = "The saved draft could not be loaded. Generate a new draft.",
                    audit = audit
                )
                val request = NutritionPlanningRequest(
                    planningNeed = RESTORED_PLAN_NEED,
                    goal = NutritionPlanningGoal.USER_DEFINED,
                    validFrom = LocalDate.ofEpochDay(storedPlan.validFromEpochDay),
                    validTo = LocalDate.ofEpochDay(storedPlan.validUntilEpochDay)
                )
                val parsed = runCatching {
                    planParser.parse(
                        rawJson = storedPlan.contentJson,
                        request = request,
                        successfulToolCallIds = toolCalls
                            .filter { it.status == AgentToolCallStatus.SUCCEEDED }
                            .mapTo(linkedSetOf(), AgentToolCall::id)
                    ).plan
                }.getOrNull() ?: return NutriCoachAgentUiState.Error(
                    code = "INVALID_STORED_PLAN",
                    message = "The saved draft could not be loaded. Generate a new draft.",
                    audit = audit
                )
                val evidenceAudit = toolCalls
                    .filter { it.id in parsed.evidenceToolCallIds }
                    .toAuditUi()
                val draft = parsed.toUiModel(storedPlan.id, run.id)
                when (storedPlan.status) {
                    NutritionPlanStatus.DRAFT -> NutriCoachAgentUiState.DraftReady(
                        draft = draft,
                        audit = evidenceAudit
                    )
                    NutritionPlanStatus.CONFIRMED,
                    NutritionPlanStatus.ACTIVE -> NutriCoachAgentUiState.Confirmed(
                        draft = draft,
                        audit = evidenceAudit,
                        confirmedAt = storedPlan.confirmedAtEpochMillis
                            ?.let(::formatConfirmationTime)
                            ?: "Recorded confirmation",
                        isActive = storedPlan.status == NutritionPlanStatus.ACTIVE,
                        lifecycleError = lifecycleError
                    )
                    NutritionPlanStatus.COMPLETED -> NutriCoachAgentUiState.Completed(
                        draft = draft,
                        audit = evidenceAudit,
                        completedAt = storedPlan.completedAtEpochMillis
                            ?.let(::formatCompletionTime)
                            ?: "Recorded completion"
                    )
                    NutritionPlanStatus.ABANDONED -> NutriCoachAgentUiState.Abandoned(
                        draft = draft,
                        audit = evidenceAudit,
                        lifecycleError = lifecycleError
                    )
                }
            }
        }
    }

    private fun failedRunState(
        errorCode: String?,
        audit: List<AgentAuditUiModel>
    ): NutriCoachAgentUiState.Error = failureState(
        AgentFailureCodec.decode(errorCode) ?: AgentFailure(
            AgentFailureStage.PROCESS_RECOVERY,
            AgentFailureCode.UNEXPECTED
        ),
        audit
    )

    private fun failureState(
        failure: AgentFailure,
        audit: List<AgentAuditUiModel> = emptyList()
    ): NutriCoachAgentUiState.Error = NutriCoachAgentUiState.Error(
        code = failure.code.name,
        stage = failure.stage.name,
        message = when (failure.code) {
            AgentFailureCode.MODEL_CONFIGURATION ->
                "The planning service is not fully configured yet. Please try again later."
            AgentFailureCode.MODEL_PERMISSION ->
                "The planning service could not verify app or project access. Check the service setup and retry."
            AgentFailureCode.MODEL_QUOTA ->
                "The planning service has reached its current quota. Please try again later."
            AgentFailureCode.MODEL_UNAVAILABLE,
            AgentFailureCode.MODEL_SERVER ->
                "The planning service is temporarily unavailable. Please try again later."
            AgentFailureCode.MODEL_REQUEST_INVALID,
            AgentFailureCode.MODEL_SERIALIZATION ->
                "The planning request could not be processed safely. Please try again."
            AgentFailureCode.MODEL_NETWORK ->
                "Unable to reach the planning service. Check your connection and retry."
            AgentFailureCode.MODEL_TIMEOUT ->
                "Plan generation timed out. Please retry."
            AgentFailureCode.MODEL_REFUSED,
            AgentFailureCode.MODEL_SAFETY_BLOCKED,
            AgentFailureCode.FINAL_SAFETY_INVALID ->
                "The AI could not provide a plan within the safety rules. You can retry."
            AgentFailureCode.FINAL_EMPTY,
            AgentFailureCode.FINAL_JSON_INVALID,
            AgentFailureCode.FINAL_SCHEMA_INVALID,
            AgentFailureCode.FINAL_EVIDENCE_INVALID,
            AgentFailureCode.TOOL_SELECTION_INVALID,
            AgentFailureCode.FUNCTION_RESPONSE_INVALID ->
                "The generated plan could not be verified. Please retry."
            AgentFailureCode.INSUFFICIENT_TOOL_SELECTION ->
                "The planning service did not select usable evidence. Please retry."
            AgentFailureCode.FINAL_PERSISTENCE_FAILED ->
                "The draft could not be saved. Please retry."
            AgentFailureCode.PROCESS_INTERRUPTED ->
                "The previous generation was interrupted. You can retry safely."
            AgentFailureCode.CANCELLED -> "Plan generation was cancelled."
            else -> "Unable to generate a nutrition plan. Please retry."
        },
        audit = audit
    )

    private fun failureState(
        failure: NutritionPlanningFailure,
        audit: List<AgentAuditUiModel> = emptyList()
    ): NutriCoachAgentUiState.Error = failureState(
        when (failure) {
            NutritionPlanningFailure.INVALID_REQUEST -> AgentFailure(
                AgentFailureStage.TOOL_VALIDATION,
                AgentFailureCode.INVALID_REQUEST
            )
            NutritionPlanningFailure.TOOL_REJECTED -> AgentFailure(
                AgentFailureStage.TOOL_VALIDATION,
                AgentFailureCode.TOOL_VALIDATION_FAILED
            )
            NutritionPlanningFailure.TOOL_FAILED -> AgentFailure(
                AgentFailureStage.TOOL_EXECUTION,
                AgentFailureCode.TOOL_EXECUTION_FAILED
            )
            NutritionPlanningFailure.MODEL_REFUSED -> AgentFailure(
                AgentFailureStage.TOOL_SELECTION,
                AgentFailureCode.MODEL_REFUSED
            )
            NutritionPlanningFailure.MODEL_NETWORK -> AgentFailure(
                AgentFailureStage.TOOL_SELECTION,
                AgentFailureCode.MODEL_NETWORK
            )
            NutritionPlanningFailure.MODEL_TIMEOUT -> AgentFailure(
                AgentFailureStage.TOOL_SELECTION,
                AgentFailureCode.MODEL_TIMEOUT
            )
            NutritionPlanningFailure.MODEL_ERROR -> AgentFailure(
                AgentFailureStage.TOOL_SELECTION,
                AgentFailureCode.MODEL_SDK
            )
            NutritionPlanningFailure.INVALID_PLAN -> AgentFailure(
                AgentFailureStage.FINAL_SCHEMA_VALIDATION,
                AgentFailureCode.FINAL_SCHEMA_INVALID
            )
            NutritionPlanningFailure.MODEL_ROUND_LIMIT -> AgentFailure(
                AgentFailureStage.TOOL_SELECTION,
                AgentFailureCode.MAX_ROUNDS_EXCEEDED
            )
            NutritionPlanningFailure.TOOL_CALL_LIMIT -> AgentFailure(
                AgentFailureStage.TOOL_VALIDATION,
                AgentFailureCode.MAX_TOOL_CALLS_EXCEEDED
            )
            NutritionPlanningFailure.INTERNAL_ERROR -> AgentFailure(
                AgentFailureStage.TOOL_SELECTION,
                AgentFailureCode.UNEXPECTED
            )
        },
        audit
    )

    private fun NutritionPlanOutputV1.toUiModel(
        planId: String,
        runId: String
    ) = AgentDraftPlanUiModel(
        planId = planId,
        runId = runId,
        summary = summary,
        validFrom = validFrom.toString(),
        validTo = validTo.toString(),
        recommendations = recommendations.map { recommendation ->
            AgentRecommendationUiModel(
                title = recommendation.title,
                action = recommendation.action,
                target = "${recommendation.targetValue.toCompactNumber()} ${recommendation.targetUnit.name.toDisplayLabel()}"
            )
        },
        cautions = cautions,
        disclaimer = disclaimer
    )

    private fun formatConfirmationTime(epochMillis: Long): String =
        AUDIT_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))

    private fun formatCompletionTime(epochMillis: Long): String =
        AUDIT_TIME_FORMATTER.format(Instant.ofEpochMilli(epochMillis).atZone(zoneId))

    private fun List<AgentToolCall>.toAuditUi(): List<AgentAuditUiModel> =
        sortedBy(AgentToolCall::sequence).map { call ->
            AgentAuditUiModel(
                sequence = call.sequence,
                toolName = call.toolName.toToolDisplayName(),
                status = call.status.name.lowercase().replaceFirstChar(Char::uppercase),
                time = AUDIT_TIME_FORMATTER.format(
                    Instant.ofEpochMilli(call.startedAtEpochMillis).atZone(zoneId)
                )
            )
        }

    private fun String.toToolDisplayName(): String = when (this) {
        AgentToolName.GET_NUTRITION_PROFILE.wireName -> "Nutrition profile"
        AgentToolName.GET_INTAKE_HISTORY.wireName -> "Intake history"
        AgentToolName.CALCULATE_NUTRITION_TREND.wireName -> "Nutrition trend"
        AgentToolName.SEARCH_FRUIT_NUTRITION.wireName -> "Fruit nutrition"
        else -> "Unsupported tool"
    }

    private fun Double.toCompactNumber(): String =
        if (this % 1.0 == 0.0) toLong().toString() else toString()

    private fun String.toDisplayLabel(): String = lowercase()
        .split('_')
        .joinToString(" ")

    private fun AiResult.toUiState(): UiState = when (this) {
        is AiResult.Success -> response.text.trim().takeIf(String::isNotEmpty)?.let { text ->
            UiState.Success(text, response.modelName)
        } ?: AiError.EmptyResponse.toUiState()

        is AiResult.Failure -> error.toUiState()
    }

    private fun AiError.toUiState(): UiState.Error = UiState.Error(
        when (this) {
            AiError.ServiceConfigurationPending ->
                "服务配置待完成，请完成 App Check 注册后重试。"
            AiError.EmptyResponse -> "No response received from AI"
            AiError.Timeout -> "AI request timed out. Please try again."
            AiError.Network -> "Unable to reach AI service. Check your connection and try again."
            AiError.Sdk -> "AI service error. Please try again."
            AiError.Cancelled -> "AI request cancelled."
        }
    )

    class Factory(
        private val sessionState: Flow<SessionSnapshot>,
        private val contentDataSource: NutriCoachContentDataSource,
        private val aiGateway: AiGateway,
        private val planRunner: NutritionPlanRunner,
        private val runHistory: AgentRunHistoryDataSource,
        private val planActionDispatcher: NutritionPlanActionDispatcher,
        private val planLifecycleScheduler: NutritionPlanLifecycleScheduler,
        private val planParser: NutritionPlanOutputParser,
        private val clock: Clock
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(NutriCoachViewModel::class.java))
            return NutriCoachViewModel(
                sessionState = sessionState,
                contentDataSource = contentDataSource,
                aiGateway = aiGateway,
                planRunner = planRunner,
                runHistory = runHistory,
                planActionDispatcher = planActionDispatcher,
                planLifecycleScheduler = planLifecycleScheduler,
                planParser = planParser,
                clock = clock
            ) as T
        }
    }

    private enum class RetryAction { LOAD, GENERATE }
    private enum class DraftAction { CONFIRM, ABANDON }

    private companion object {
        const val DEFAULT_PLAN_DAYS = 7L
        const val OPTIMAL_FRUIT_SCORE = 8.0
        const val MOTIVATIONAL_TIMEOUT_MILLIS = 30_000L
        const val RESTORED_PLAN_NEED = "Restore the saved nutrition plan draft."
        const val MOTIVATIONAL_PROMPT =
            "Generate a short encouraging message to help someone improve their fruit intake."
        val AUDIT_TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter.ofPattern(
            "yyyy-MM-dd HH:mm",
            Locale.US
        )
    }
}
