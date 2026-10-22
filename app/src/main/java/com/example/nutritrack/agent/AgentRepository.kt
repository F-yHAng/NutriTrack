package com.example.nutritrack.agent

import androidx.room.withTransaction
import com.example.nutritrack.data.AgentRun
import com.example.nutritrack.data.AgentRunDao
import com.example.nutritrack.data.AgentRunStatus
import com.example.nutritrack.data.AgentToolCall
import com.example.nutritrack.data.AgentToolCallDao
import com.example.nutritrack.data.AgentToolCallStatus
import com.example.nutritrack.data.NutrackDatabase
import com.example.nutritrack.data.NutritionPlan
import com.example.nutritrack.data.NutritionPlanDao
import com.example.nutritrack.data.NutritionPlanStatus
import com.example.nutritrack.data.PatientDao
import com.google.gson.Gson
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.time.Clock

data class AgentRunSnapshot(
    val run: AgentRun,
    val plan: NutritionPlan?,
    val toolCalls: List<AgentToolCall>
)

interface AgentRunHistoryDataSource {
    suspend fun recoverInterruptedAndLoadLatest(userId: Int): AgentRunSnapshot?
    suspend fun loadLatest(userId: Int): AgentRunSnapshot?
    suspend fun loadRun(userId: Int, runId: String): AgentRunSnapshot
}

enum class AgentRepositoryError {
    INVALID_USER,
    USER_NOT_FOUND,
    CONTEXT_DENIED,
    RUN_NOT_ACTIVE,
    INVALID_AUDIT_RECORD,
    INVALID_PLAN,
    RUN_ALREADY_ACTIVE,
    STATE_TRANSITION_FAILED
}

class AgentRepositoryException(
    val reason: AgentRepositoryError,
    val activeRunId: String? = null
) : IllegalStateException(reason.name)

class AgentRepository(
    private val database: NutrackDatabase,
    private val patientDao: PatientDao,
    private val agentRunDao: AgentRunDao,
    private val agentToolCallDao: AgentToolCallDao,
    private val nutritionPlanDao: NutritionPlanDao,
    private val clock: Clock,
    private val idGenerator: IdGenerator,
    private val gson: Gson = Gson()
) : AgentRunHistoryDataSource {
    suspend fun startRun(userId: Int): AgentRun = database.withTransaction {
        if (userId <= 0) throw AgentRepositoryException(AgentRepositoryError.INVALID_USER)
        if (patientDao.getPatientByUserId(userId) == null) {
            throw AgentRepositoryException(AgentRepositoryError.USER_NOT_FOUND)
        }
        agentRunDao.getRunningByUser(userId)?.let { activeRun ->
            throw AgentRepositoryException(
                reason = AgentRepositoryError.RUN_ALREADY_ACTIVE,
                activeRunId = activeRun.id
            )
        }
        val run = AgentRun(
            id = idGenerator.validId(),
            userId = userId,
            status = AgentRunStatus.RUNNING,
            createdAtEpochMillis = clock.millis()
        )
        agentRunDao.insert(run)
        run
    }

    suspend fun requireRunningRun(context: AgentContext): AgentRun = database.withTransaction {
        requireOwnedRun(context).also { run ->
            if (run.status != AgentRunStatus.RUNNING) {
                throw AgentRepositoryException(AgentRepositoryError.RUN_NOT_ACTIVE)
            }
        }
    }

    internal suspend fun recordToolCall(
        context: AgentContext,
        toolName: AgentToolName,
        argumentsJson: String,
        startedAtEpochMillis: Long
    ): AgentToolCall = database.withTransaction {
        val run = requireOwnedRun(context)
        if (run.status != AgentRunStatus.RUNNING) {
            throw AgentRepositoryException(AgentRepositoryError.RUN_NOT_ACTIVE)
        }
        validateAuditJson(argumentsJson, MAX_ARGUMENTS_JSON_LENGTH)
        val call = AgentToolCall(
            id = idGenerator.validId(),
            runId = context.runId,
            sequence = agentToolCallDao.nextSequence(context.runId),
            toolName = toolName.wireName,
            argumentsJson = argumentsJson,
            resultJson = null,
            status = AgentToolCallStatus.RUNNING,
            errorCode = null,
            startedAtEpochMillis = startedAtEpochMillis,
            finishedAtEpochMillis = null
        )
        agentToolCallDao.insert(call)
        call
    }

    internal suspend fun finishToolCall(
        context: AgentContext,
        callId: String,
        status: AgentToolCallStatus,
        resultJson: String?,
        errorCode: AgentToolErrorCode?,
        finishedAtEpochMillis: Long
    ) {
        if (status == AgentToolCallStatus.RUNNING) {
            throw AgentRepositoryException(AgentRepositoryError.INVALID_AUDIT_RECORD)
        }
        if (status == AgentToolCallStatus.SUCCEEDED) {
            val successJson = resultJson
                ?: throw AgentRepositoryException(AgentRepositoryError.INVALID_AUDIT_RECORD)
            validateAuditJson(successJson, MAX_RESULT_JSON_LENGTH)
            if (errorCode != null) {
                throw AgentRepositoryException(AgentRepositoryError.INVALID_AUDIT_RECORD)
            }
        } else if (resultJson != null || errorCode == null) {
            throw AgentRepositoryException(AgentRepositoryError.INVALID_AUDIT_RECORD)
        }
        database.withTransaction {
            requireOwnedRun(context)
            val updated = agentToolCallDao.transitionStatus(
                callId = callId,
                runId = context.runId,
                expectedStatus = AgentToolCallStatus.RUNNING,
                newStatus = status,
                resultJson = resultJson,
                errorCode = errorCode?.name,
                finishedAtEpochMillis = finishedAtEpochMillis
            )
            if (updated != 1) {
                throw AgentRepositoryException(AgentRepositoryError.STATE_TRANSITION_FAILED)
            }
        }
    }

    suspend fun completeRunWithDraftPlan(
        context: AgentContext,
        input: DraftNutritionPlanInput
    ): NutritionPlan {
        validatePlan(input)
        return database.withTransaction {
            val run = requireOwnedRun(context)
            if (run.status != AgentRunStatus.RUNNING) {
                throw AgentRepositoryException(AgentRepositoryError.RUN_NOT_ACTIVE)
            }
            val plan = NutritionPlan(
                id = idGenerator.validId(),
                userId = context.userId,
                runId = context.runId,
                status = NutritionPlanStatus.DRAFT,
                schemaVersion = input.schemaVersion,
                validFromEpochDay = input.validFrom.toEpochDay(),
                validUntilEpochDay = input.validUntil.toEpochDay(),
                contentJson = input.contentJson,
                cautionsJson = gson.toJson(input.cautions),
                disclaimer = input.disclaimer,
                createdAtEpochMillis = clock.millis()
            )
            nutritionPlanDao.insert(plan)
            val updated = agentRunDao.transitionStatus(
                runId = context.runId,
                userId = context.userId,
                expectedStatus = AgentRunStatus.RUNNING,
                newStatus = AgentRunStatus.SUCCEEDED,
                finishedAtEpochMillis = clock.millis(),
                errorCode = null
            )
            if (updated != 1) {
                throw AgentRepositoryException(AgentRepositoryError.STATE_TRANSITION_FAILED)
            }
            plan
        }
    }

    suspend fun failRun(context: AgentContext, failure: AgentFailure) {
        finishRun(context, AgentRunStatus.FAILED, AgentFailureCodec.encode(failure))
    }

    suspend fun cancelRun(
        context: AgentContext,
        failure: AgentFailure = AgentFailure(
            AgentFailureStage.TOOL_SELECTION,
            AgentFailureCode.CANCELLED
        )
    ) {
        check(failure.code == AgentFailureCode.CANCELLED)
        finishRun(context, AgentRunStatus.CANCELLED, AgentFailureCodec.encode(failure))
    }

    suspend fun getTrace(context: AgentContext): List<AgentToolCall> = database.withTransaction {
        requireOwnedRun(context)
        agentToolCallDao.getByRun(context.runId)
    }

    override suspend fun recoverInterruptedAndLoadLatest(userId: Int): AgentRunSnapshot? =
        database.withTransaction {
            requireKnownUser(userId)
            agentRunDao.getRunningByUser(userId)?.let { interrupted ->
                val finishedAt = clock.millis()
                agentToolCallDao.failRunningCalls(
                    runId = interrupted.id,
                    errorCode = AgentToolErrorCode.PROCESS_INTERRUPTED.name,
                    finishedAtEpochMillis = finishedAt
                )
                val updated = agentRunDao.transitionStatus(
                    runId = interrupted.id,
                    userId = userId,
                    expectedStatus = AgentRunStatus.RUNNING,
                    newStatus = AgentRunStatus.FAILED,
                    finishedAtEpochMillis = finishedAt,
                    errorCode = AgentFailureCodec.encode(
                        AgentFailure(
                            AgentFailureStage.PROCESS_RECOVERY,
                            AgentFailureCode.PROCESS_INTERRUPTED
                        )
                    )
                )
                if (updated != 1) {
                    throw AgentRepositoryException(AgentRepositoryError.STATE_TRANSITION_FAILED)
                }
            }
            latestSnapshot(userId)
        }

    override suspend fun loadLatest(userId: Int): AgentRunSnapshot? = database.withTransaction {
        requireKnownUser(userId)
        latestSnapshot(userId)
    }

    override suspend fun loadRun(userId: Int, runId: String): AgentRunSnapshot =
        database.withTransaction {
            requireKnownUser(userId)
            val run = requireOwnedRun(AgentContext(userId, runId))
            snapshot(run)
        }

    private suspend fun finishRun(
        context: AgentContext,
        status: AgentRunStatus,
        errorCode: String?
    ) = database.withTransaction {
        requireOwnedRun(context)
        val updated = agentRunDao.transitionStatus(
            runId = context.runId,
            userId = context.userId,
            expectedStatus = AgentRunStatus.RUNNING,
            newStatus = status,
            finishedAtEpochMillis = clock.millis(),
            errorCode = errorCode
        )
        if (updated != 1) {
            throw AgentRepositoryException(AgentRepositoryError.RUN_NOT_ACTIVE)
        }
    }

    private suspend fun requireOwnedRun(context: AgentContext): AgentRun {
        if (context.userId <= 0 || context.runId.isBlank()) {
            throw AgentRepositoryException(AgentRepositoryError.CONTEXT_DENIED)
        }
        val run = agentRunDao.getById(context.runId)
            ?: throw AgentRepositoryException(AgentRepositoryError.CONTEXT_DENIED)
        if (run.userId != context.userId) {
            throw AgentRepositoryException(AgentRepositoryError.CONTEXT_DENIED)
        }
        return run
    }

    private suspend fun requireKnownUser(userId: Int) {
        if (userId <= 0) throw AgentRepositoryException(AgentRepositoryError.INVALID_USER)
        if (patientDao.getPatientByUserId(userId) == null) {
            throw AgentRepositoryException(AgentRepositoryError.USER_NOT_FOUND)
        }
    }

    private suspend fun latestSnapshot(userId: Int): AgentRunSnapshot? =
        agentRunDao.getByUser(userId).firstOrNull()?.let { snapshot(it) }

    private suspend fun snapshot(run: AgentRun) = AgentRunSnapshot(
        run = run,
        plan = nutritionPlanDao.getByRun(run.id),
        toolCalls = agentToolCallDao.getByRun(run.id)
    )

    private fun validatePlan(input: DraftNutritionPlanInput) {
        val valid = input.schemaVersion > 0 &&
            input.validUntil >= input.validFrom &&
            input.disclaimer.isNotBlank() &&
            input.disclaimer.length <= MAX_DISCLAIMER_LENGTH &&
            input.cautions.size <= MAX_CAUTION_COUNT &&
            input.cautions.all { it.isNotBlank() && it.length <= MAX_CAUTION_LENGTH } &&
            input.contentJson.length <= MAX_PLAN_JSON_LENGTH &&
            isJsonObject(input.contentJson)
        if (!valid) throw AgentRepositoryException(AgentRepositoryError.INVALID_PLAN)
    }

    private fun validateAuditJson(value: String, maximumLength: Int) {
        if (value.length > maximumLength || !isJsonObject(value)) {
            throw AgentRepositoryException(AgentRepositoryError.INVALID_AUDIT_RECORD)
        }
    }

    private fun isJsonObject(value: String): Boolean = try {
        JsonParser.parseString(value).isJsonObject
    } catch (_: JsonSyntaxException) {
        false
    }

    private fun IdGenerator.validId(): String = newId().takeIf {
        it.isNotBlank() && it.length <= MAX_ID_LENGTH && ID_PATTERN.matches(it)
    } ?: throw AgentRepositoryException(AgentRepositoryError.INVALID_AUDIT_RECORD)

    private companion object {
        const val MAX_ID_LENGTH = 64
        const val MAX_ARGUMENTS_JSON_LENGTH = 2_048
        const val MAX_RESULT_JSON_LENGTH = 65_536
        const val MAX_PLAN_JSON_LENGTH = 65_536
        const val MAX_DISCLAIMER_LENGTH = 1_000
        const val MAX_CAUTION_COUNT = 20
        const val MAX_CAUTION_LENGTH = 500
        val ID_PATTERN = Regex("[A-Za-z0-9_-]+")
    }
}
