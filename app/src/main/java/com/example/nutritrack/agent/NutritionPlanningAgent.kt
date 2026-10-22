package com.example.nutritrack.agent

import com.example.nutritrack.data.NutritionPlan
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import java.time.Clock
import java.time.DateTimeException
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout

enum class NutritionPlanningFailure {
    INVALID_REQUEST,
    TOOL_REJECTED,
    TOOL_FAILED,
    MODEL_REFUSED,
    MODEL_NETWORK,
    MODEL_TIMEOUT,
    MODEL_ERROR,
    INVALID_PLAN,
    MODEL_ROUND_LIMIT,
    TOOL_CALL_LIMIT,
    INTERNAL_ERROR
}

sealed interface NutritionPlanningResult {
    data class Success(
        val runId: String,
        val plan: NutritionPlan,
        val output: NutritionPlanOutputV1
    ) : NutritionPlanningResult

    data class AlreadyRunning(val runId: String) : NutritionPlanningResult
    data class Rejected(val reason: NutritionPlanningFailure) : NutritionPlanningResult

    data class Failed(
        val runId: String,
        val reason: NutritionPlanningFailure,
        val failure: AgentFailure = reason.defaultAgentFailure()
    ) : NutritionPlanningResult

    data class Cancelled(
        val runId: String,
        val failure: AgentFailure = AgentFailure(
            AgentFailureStage.TOOL_SELECTION,
            AgentFailureCode.CANCELLED
        )
    ) : NutritionPlanningResult
}

private fun NutritionPlanningFailure.defaultAgentFailure(): AgentFailure = when (this) {
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
}

fun interface NutritionPlanRunner {
    suspend fun run(userId: Int, request: NutritionPlanningRequest): NutritionPlanningResult
}

class NutritionPlanningAgent(
    private val repository: AgentRepository,
    private val dispatcher: AgentToolDispatcher,
    private val modelGateway: AgentModelGateway,
    private val planParser: NutritionPlanOutputParser,
    private val clock: Clock,
    private val settings: AgentModelSettings = NutritionPlanningAgentConfig.modelSettings,
    private val maximumToolCalls: Int = NutritionPlanningAgentConfig.MAX_TOOL_CALLS,
    private val evidenceBundleBuilder: AgentEvidenceBundleBuilder = AgentEvidenceBundleBuilder(),
    private val totalTimeoutMillis: Long = NutritionPlanningAgentConfig.TOTAL_TIMEOUT_MILLIS,
    private val retryDelayMillis: Long = AgentModelRetryPolicy.RETRY_DELAY_MILLIS,
    private val traceLogger: AgentTraceLogger = AgentTraceLogger.None,
    private val delayForRetry: suspend (Long) -> Unit = { kotlinx.coroutines.delay(it) }
) : NutritionPlanRunner {
    override suspend fun run(userId: Int, request: NutritionPlanningRequest): NutritionPlanningResult {
        if (!request.isValid() || userId <= 0) {
            return NutritionPlanningResult.Rejected(NutritionPlanningFailure.INVALID_REQUEST)
        }
        val run = try {
            repository.startRun(userId)
        } catch (duplicate: AgentRepositoryException) {
            if (duplicate.reason == AgentRepositoryError.RUN_ALREADY_ACTIVE) {
                return NutritionPlanningResult.AlreadyRunning(
                    duplicate.activeRunId.orEmpty()
                )
            }
            throw duplicate
        }
        val context = AgentContext(userId = userId, runId = run.id)
        val deadline = safeDeadline(clock.millis(), totalTimeoutMillis)
        val stageTracker = FailureStageTracker()

        return try {
            withTimeout(totalTimeoutMillis) {
                execute(context, request, deadline, stageTracker)
            }
        } catch (_: TimeoutCancellationException) {
            val failure = AgentFailure(stageTracker.current, AgentFailureCode.MODEL_TIMEOUT)
            failSafely(context, failure)
            NutritionPlanningResult.Failed(
                run.id,
                NutritionPlanningFailure.MODEL_TIMEOUT,
                failure
            )
        } catch (cancelled: CancellationException) {
            val failure = AgentFailure(stageTracker.current, AgentFailureCode.CANCELLED)
            withContext(NonCancellable) { cancelSafely(context, failure) }
            throw cancelled
        } catch (_: Exception) {
            val failure = AgentFailure(stageTracker.current, AgentFailureCode.UNEXPECTED)
            failSafely(context, failure)
            NutritionPlanningResult.Failed(
                run.id,
                NutritionPlanningFailure.INTERNAL_ERROR,
                failure
            )
        }
    }

    private suspend fun execute(
        context: AgentContext,
        request: NutritionPlanningRequest,
        deadlineEpochMillis: Long,
        stageTracker: FailureStageTracker
    ): NutritionPlanningResult {
        stageTracker.current = AgentFailureStage.TOOL_SELECTION
        if (clock.millis() >= deadlineEpochMillis) return timeout(context, stageTracker.current)
        val selectedCalls = when (
            val selection = callWithSingleExplicitTransientRetry(
                deadlineStillActive = { clock.millis() < deadlineEpochMillis },
                canWaitForRetry = { delayMillis ->
                    deadlineEpochMillis - clock.millis() > delayMillis
                },
                shouldRetry = { result ->
                    (result as? ToolSelectionResult.Failure)
                        ?.failure
                        ?.diagnostic
                        ?.retryDecision == AgentModelRetryDecision.RETRY_ONCE
                },
                retryDelayMillis = retryDelayMillis,
                delayForRetry = delayForRetry
            ) {
                modelGateway.selectTools(
                    AgentToolSelectionRequest(
                        planningRequest = request,
                        settings = settings,
                        tools = NutritionAgentToolDeclarations.all
                    )
                )
            }
        ) {
            is ToolSelectionResult.Selected -> selection.calls
            is ToolSelectionResult.Refusal -> return failRefusal(
                context,
                selection.reason,
                AgentFailureStage.TOOL_SELECTION
            )
            is ToolSelectionResult.Failure -> return finishModelFailure(
                context,
                selection.failure,
                AgentFailureStage.TOOL_SELECTION
            )
        }

        traceLogger.record(AgentTraceStage.FUNCTION_CALLS_02) {
            JsonArray().apply {
                selectedCalls.forEach { call ->
                    add(JsonObject().apply {
                        addProperty("toolName", call.toolName)
                        addProperty("argumentsJson", call.argumentsJson)
                    })
                }
            }.toString()
        }
        if (selectedCalls.size > maximumToolCalls) {
            return fail(
                context,
                AgentFailure(
                    AgentFailureStage.TOOL_VALIDATION,
                    AgentFailureCode.MAX_TOOL_CALLS_EXCEEDED
                ),
                NutritionPlanningFailure.TOOL_CALL_LIMIT
            )
        }

        stageTracker.current = AgentFailureStage.TOOL_VALIDATION
        val preparedCalls = prepareDistinctCalls(context, selectedCalls) ?: return fail(
            context,
            AgentFailure(
                AgentFailureStage.TOOL_VALIDATION,
                AgentFailureCode.TOOL_VALIDATION_FAILED
            ),
            NutritionPlanningFailure.TOOL_REJECTED
        )
        if (preparedCalls.isEmpty()) {
            return fail(
                context,
                AgentFailure(
                    AgentFailureStage.TOOL_SELECTION,
                    AgentFailureCode.INSUFFICIENT_TOOL_SELECTION
                ),
                NutritionPlanningFailure.MODEL_ERROR
            )
        }

        val successfulResults = mutableListOf<AgentFunctionResult>()
        preparedCalls.forEach { decoded ->
            if (clock.millis() >= deadlineEpochMillis) {
                return timeout(context, stageTracker.current)
            }
            stageTracker.current = AgentFailureStage.TOOL_EXECUTION
            when (val dispatched = dispatcher.dispatch(context, decoded)) {
                is AgentDispatchResult.Success -> {
                    traceLogger.record(AgentTraceStage.TOOL_RESULT_03) {
                        JsonObject().apply {
                            addProperty("toolName", decoded.toolName.wireName)
                            addProperty("status", "SUCCEEDED")
                            addProperty("resultJson", dispatched.canonicalResultJson)
                        }.toString()
                    }
                    successfulResults += AgentFunctionResult(
                        toolName = decoded.toolName.wireName,
                        auditToolCallId = dispatched.toolCallId,
                        canonicalArgumentsJson = dispatched.canonicalArgumentsJson,
                        result = dispatched.result
                    )
                }
                is AgentDispatchResult.Rejected -> {
                    traceToolFailure(decoded.toolName, dispatched.errorCode)
                    return fail(
                        context,
                        AgentFailure(AgentFailureStage.TOOL_VALIDATION, AgentFailureCode.TOOL_VALIDATION_FAILED),
                        NutritionPlanningFailure.TOOL_REJECTED
                    )
                }
                is AgentDispatchResult.Failure -> {
                    traceToolFailure(decoded.toolName, dispatched.errorCode)
                    return fail(
                        context,
                        AgentFailure(AgentFailureStage.TOOL_EXECUTION, AgentFailureCode.TOOL_EXECUTION_FAILED),
                        NutritionPlanningFailure.TOOL_FAILED
                    )
                }
            }
        }

        stageTracker.current = AgentFailureStage.FINAL_EVIDENCE_VALIDATION
        val builtEvidence = try {
            requireEvidenceMatchesTrace(context, successfulResults)
            evidenceBundleBuilder.build(request, successfulResults)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            return fail(
                context,
                AgentFailure(
                    AgentFailureStage.FINAL_EVIDENCE_VALIDATION,
                    AgentFailureCode.FINAL_EVIDENCE_INVALID
                ),
                NutritionPlanningFailure.INVALID_PLAN
            )
        }
        traceLogger.record(AgentTraceStage.EVIDENCE_04) { builtEvidence.bundle.canonicalJson }
        if (clock.millis() >= deadlineEpochMillis) {
            return timeout(context, AgentFailureStage.FINAL_GENERATION)
        }
        return finishWithPlan(context, request, builtEvidence, deadlineEpochMillis, stageTracker)
    }

    private suspend fun prepareDistinctCalls(
        context: AgentContext,
        calls: List<AgentFunctionCall>
    ): List<DecodedAgentToolCall>? {
        val distinct = linkedMapOf<Pair<AgentToolName, String>, DecodedAgentToolCall>()
        calls.forEach { call ->
            val invocation = AgentToolInvocation(call.toolName, call.argumentsJson)
            val decoded = try {
                dispatcher.decode(invocation)
            } catch (_: AgentToolRejection) {
                dispatcher.dispatch(context, invocation)
                return null
            }
            distinct.putIfAbsent(
                decoded.toolName to decoded.canonicalArgumentsJson,
                decoded
            )
        }
        return distinct.values.toList()
    }

    private suspend fun requireEvidenceMatchesTrace(
        context: AgentContext,
        results: List<AgentFunctionResult>
    ) {
        val trace = repository.getTrace(context)
        if (trace.size != results.size) throw AgentEvidenceBundleException()
        trace.zip(results).forEachIndexed { index, (call, result) ->
            if (
                call.sequence != index + 1 ||
                call.id != result.auditToolCallId ||
                call.status != com.example.nutritrack.data.AgentToolCallStatus.SUCCEEDED ||
                call.toolName != result.toolName ||
                call.argumentsJson != result.canonicalArgumentsJson
            ) {
                throw AgentEvidenceBundleException()
            }
        }
    }

    private suspend fun finishWithPlan(
        context: AgentContext,
        request: NutritionPlanningRequest,
        evidence: BuiltAgentEvidenceBundle,
        deadlineEpochMillis: Long,
        stageTracker: FailureStageTracker
    ): NutritionPlanningResult {
        stageTracker.current = AgentFailureStage.FINAL_GENERATION
        return when (
            val finalResult = callWithSingleExplicitTransientRetry(
                deadlineStillActive = { clock.millis() < deadlineEpochMillis },
                canWaitForRetry = { delayMillis ->
                    deadlineEpochMillis - clock.millis() > delayMillis
                },
                shouldRetry = { result ->
                    (result as? StructuredPlanResult.Failure)
                        ?.failure
                        ?.diagnostic
                        ?.retryDecision == AgentModelRetryDecision.RETRY_ONCE
                },
                retryDelayMillis = retryDelayMillis,
                delayForRetry = delayForRetry
            ) {
                modelGateway.generateStructuredPlan(
                    StructuredPlanRequest(
                        settings = settings,
                        evidenceBundle = evidence.bundle
                    )
                )
            }
        ) {
            is StructuredPlanResult.Output -> {
                traceLogger.record(AgentTraceStage.RAW_PLAN_05) { finalResult.json }
                if (finalResult.json.isBlank()) {
                    return fail(
                        context,
                        AgentFailure(
                            AgentFailureStage.FINAL_GENERATION,
                            AgentFailureCode.FINAL_EMPTY
                        ),
                        NutritionPlanningFailure.INVALID_PLAN
                    )
                }
                stageTracker.current = AgentFailureStage.FINAL_DECODING
                val validated = try {
                    planParser.parse(
                        rawJson = finalResult.json,
                        request = request,
                        successfulEvidenceReferences = evidence.auditToolCallIdsByAlias
                    )
                } catch (validation: NutritionPlanValidationException) {
                    val failure = validation.reason.toAgentFailure().copy(
                        safetyDiagnostic = validation.safetyDiagnostic
                    )
                    stageTracker.current = failure.stage
                    return fail(
                        context,
                        failure,
                        NutritionPlanningFailure.INVALID_PLAN,
                        validation.reason
                    )
                }
                traceLogger.record(AgentTraceStage.VALIDATION_06) { "{\"status\":\"PASSED\"}" }
                stageTracker.current = AgentFailureStage.FINAL_PERSISTENCE
                val plan = try {
                    repository.completeRunWithDraftPlan(context, validated.draftInput)
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Exception) {
                    return fail(
                        context,
                        AgentFailure(
                            AgentFailureStage.FINAL_PERSISTENCE,
                            AgentFailureCode.FINAL_PERSISTENCE_FAILED
                        ),
                        NutritionPlanningFailure.INTERNAL_ERROR
                    )
                }
                traceLogger.record(AgentTraceStage.VALIDATION_06) { "{\"status\":\"SAVED_DRAFT\"}" }
                NutritionPlanningResult.Success(context.runId, plan, validated.plan)
            }

            is StructuredPlanResult.Refusal -> failRefusal(
                context = context,
                reason = finalResult.reason,
                stage = AgentFailureStage.FINAL_SAFETY_VALIDATION
            )

            is StructuredPlanResult.Failure -> finishModelFailure(
                context,
                finalResult.failure,
                AgentFailureStage.FINAL_GENERATION
            )
        }
    }

    private suspend fun finishModelFailure(
        context: AgentContext,
        failure: AgentModelFailure,
        stage: AgentFailureStage
    ): NutritionPlanningResult = when (failure.error) {
        AgentModelError.NETWORK -> fail(
            context,
            AgentFailure(stage, AgentFailureCode.MODEL_NETWORK),
            NutritionPlanningFailure.MODEL_NETWORK
        )

        AgentModelError.TIMEOUT -> fail(
            context,
            AgentFailure(stage, AgentFailureCode.MODEL_TIMEOUT),
            NutritionPlanningFailure.MODEL_TIMEOUT
        )

        AgentModelError.CANCELLED -> {
            val cancellation = AgentFailure(stage, AgentFailureCode.CANCELLED)
            repository.cancelRun(context, cancellation)
            NutritionPlanningResult.Cancelled(context.runId, cancellation)
        }

        AgentModelError.CONFIGURATION -> fail(
            context,
            AgentFailure(stage, AgentFailureCode.MODEL_CONFIGURATION),
            NutritionPlanningFailure.MODEL_ERROR
        )

        AgentModelError.PERMISSION -> fail(
            context,
            AgentFailure(stage, AgentFailureCode.MODEL_PERMISSION),
            NutritionPlanningFailure.MODEL_ERROR
        )

        AgentModelError.QUOTA -> fail(
            context,
            AgentFailure(stage, AgentFailureCode.MODEL_QUOTA),
            NutritionPlanningFailure.MODEL_ERROR
        )

        AgentModelError.UNAVAILABLE -> fail(
            context,
            AgentFailure(stage, AgentFailureCode.MODEL_UNAVAILABLE),
            NutritionPlanningFailure.MODEL_ERROR
        )

        AgentModelError.SERVER -> fail(
            context,
            AgentFailure(stage, AgentFailureCode.MODEL_SERVER),
            NutritionPlanningFailure.MODEL_ERROR
        )

        AgentModelError.REQUEST_CONFIGURATION -> fail(
            context,
            AgentFailure(stage, AgentFailureCode.MODEL_REQUEST_INVALID),
            NutritionPlanningFailure.MODEL_ERROR
        )

        AgentModelError.SERIALIZATION -> fail(
            context,
            AgentFailure(stage, AgentFailureCode.MODEL_SERIALIZATION),
            NutritionPlanningFailure.MODEL_ERROR
        )

        AgentModelError.EMPTY_RESPONSE -> {
            val emptyFailure = if (stage == AgentFailureStage.FINAL_GENERATION) {
                AgentFailure(AgentFailureStage.FINAL_GENERATION, AgentFailureCode.FINAL_EMPTY)
            } else {
                AgentFailure(stage, AgentFailureCode.TOOL_SELECTION_INVALID)
            }
            fail(context, emptyFailure, NutritionPlanningFailure.MODEL_ERROR)
        }

        AgentModelError.INVALID_RESPONSE -> {
            val invalidFailure = if (stage == AgentFailureStage.FINAL_GENERATION) {
                AgentFailure(AgentFailureStage.FINAL_DECODING, AgentFailureCode.FINAL_JSON_INVALID)
            } else {
                AgentFailure(stage, AgentFailureCode.TOOL_SELECTION_INVALID)
            }
            fail(context, invalidFailure, NutritionPlanningFailure.MODEL_ERROR)
        }

        AgentModelError.SDK -> fail(
            context,
            AgentFailure(stage, AgentFailureCode.MODEL_SDK),
            NutritionPlanningFailure.MODEL_ERROR
        )
    }

    private suspend fun timeout(
        context: AgentContext,
        stage: AgentFailureStage
    ): NutritionPlanningResult = fail(
        context,
        AgentFailure(stage, AgentFailureCode.MODEL_TIMEOUT),
        NutritionPlanningFailure.MODEL_TIMEOUT
    )

    private suspend fun failRefusal(
        context: AgentContext,
        reason: AgentModelRefusalReason,
        stage: AgentFailureStage
    ): NutritionPlanningResult {
        val failure = if (reason == AgentModelRefusalReason.SAFETY) {
            AgentFailure(
                if (stage == AgentFailureStage.FINAL_GENERATION) {
                    AgentFailureStage.FINAL_SAFETY_VALIDATION
                } else {
                    stage
                },
                AgentFailureCode.MODEL_SAFETY_BLOCKED
            )
        } else {
            AgentFailure(stage, AgentFailureCode.MODEL_REFUSED)
        }
        return fail(context, failure, NutritionPlanningFailure.MODEL_REFUSED)
    }

    private fun NutritionPlanValidationError.toAgentFailure(): AgentFailure = when (this) {
        NutritionPlanValidationError.MALFORMED_JSON -> AgentFailure(
            AgentFailureStage.FINAL_DECODING,
            AgentFailureCode.FINAL_JSON_INVALID
        )

        NutritionPlanValidationError.UNKNOWN_FIELD,
        NutritionPlanValidationError.INVALID_SCHEMA_VERSION,
        NutritionPlanValidationError.INVALID_STRING,
        NutritionPlanValidationError.INVALID_DATE,
        NutritionPlanValidationError.INVALID_ENUM,
        NutritionPlanValidationError.INVALID_NUMBER -> AgentFailure(
            AgentFailureStage.FINAL_SCHEMA_VALIDATION,
            AgentFailureCode.FINAL_SCHEMA_INVALID
        )

        NutritionPlanValidationError.INVALID_EVIDENCE -> AgentFailure(
            AgentFailureStage.FINAL_EVIDENCE_VALIDATION,
            AgentFailureCode.FINAL_EVIDENCE_INVALID
        )

        NutritionPlanValidationError.UNSAFE_CONTENT -> AgentFailure(
            AgentFailureStage.FINAL_SAFETY_VALIDATION,
            AgentFailureCode.FINAL_SAFETY_INVALID
        )
    }

    private suspend fun fail(
        context: AgentContext,
        metadata: AgentFailure,
        reason: NutritionPlanningFailure,
        validationError: NutritionPlanValidationError? = null
    ): NutritionPlanningResult {
        traceFailure(metadata, validationError)
        repository.failRun(context, metadata)
        return NutritionPlanningResult.Failed(context.runId, reason, metadata)
    }

    private suspend fun failSafely(context: AgentContext, failure: AgentFailure) {
        traceFailure(failure)
        runCatching { repository.failRun(context, failure) }
    }

    private suspend fun cancelSafely(context: AgentContext, failure: AgentFailure) {
        traceFailure(failure)
        runCatching { repository.cancelRun(context, failure) }
    }

    private fun traceFailure(failure: AgentFailure, validationError: NutritionPlanValidationError? = null) {
        traceLogger.record(AgentTraceStage.VALIDATION_06) {
            JsonObject().apply {
                addProperty("status", if (failure.code == AgentFailureCode.CANCELLED) "CANCELLED" else "FAILED")
                addProperty("stage", failure.stage.name)
                addProperty("errorCode", failure.code.name)
                validationError?.let { addProperty("validationError", it.name) }
                failure.safetyDiagnostic?.let {
                    addProperty("ruleId", it.ruleId.name)
                    addProperty("fieldPath", it.fieldPath)
                    addProperty("failureCategory", it.category.name)
                }
            }.toString()
        }
    }

    private fun traceToolFailure(tool: AgentToolName, code: AgentToolErrorCode) {
        traceLogger.record(AgentTraceStage.TOOL_RESULT_03) {
            JsonObject().apply {
                addProperty("toolName", tool.wireName)
                addProperty("status", "FAILED")
                addProperty("toolErrorCode", code.name)
            }.toString()
        }
    }

    private class FailureStageTracker(
        var current: AgentFailureStage = AgentFailureStage.TOOL_SELECTION
    )

    private fun NutritionPlanningRequest.isValid(): Boolean = try {
        NutritionPlanningRequestPolicy.normalizeOrNull(planningNeed) == planningNeed &&
            ChronoUnit.DAYS.between(validFrom, validTo) + 1 in 1..31
    } catch (_: DateTimeException) {
        false
    }

    private fun safeDeadline(startEpochMillis: Long, durationMillis: Long): Long =
        if (durationMillis > Long.MAX_VALUE - startEpochMillis) Long.MAX_VALUE
        else startEpochMillis + durationMillis
}
