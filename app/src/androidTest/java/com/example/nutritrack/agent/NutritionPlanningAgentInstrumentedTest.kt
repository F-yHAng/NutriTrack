package com.example.nutritrack.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.nutritrack.data.AgentRun
import com.example.nutritrack.data.AgentRunStatus
import com.example.nutritrack.data.AgentToolCallStatus
import com.example.nutritrack.data.FoodIntake
import com.example.nutritrack.data.FruitNutrition
import com.example.nutritrack.data.FruitNutritionGateway
import com.example.nutritrack.data.FruitNutritionResult
import com.example.nutritrack.data.NutrackDatabase
import com.example.nutritrack.data.NutritionPlan
import com.example.nutritrack.data.NutritionPlanStatus
import com.example.nutritrack.data.QuestionnaireProfile
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NutritionPlanningAgentInstrumentedTest {
    private lateinit var database: NutrackDatabase
    private lateinit var repository: AgentRepository

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NutrackDatabase::class.java).build()
        insertPatient(USER_ONE, PRIVATE_NAME, PRIVATE_PHONE)
        insertPatient(USER_TWO, "Other Private Person", "0400000902")
        database.questionnaireProfileDao().upsert(validProfile(USER_ONE))
        database.questionnaireProfileDao().upsert(validProfile(USER_TWO))
        database.foodIntakeDao().insert(intake(USER_ONE, "Private meal note", 2.0))
        database.foodIntakeDao().insert(intake(USER_TWO, "Other private meal", 99.0))
        repository = AgentRepository(
            database = database,
            patientDao = database.patientDao(),
            agentRunDao = database.agentRunDao(),
            agentToolCallDao = database.agentToolCallDao(),
            nutritionPlanDao = database.nutritionPlanDao(),
            clock = FIXED_CLOCK,
            idGenerator = SequenceIdGenerator()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun twoStageFlow_selectsOnceExecutesInOrderAndGeneratesOnce() = runBlocking {
        val gateway = FakeAgentModelGateway(
            selection = ToolSelectionResult.Selected(allToolCalls()),
            generate = { request ->
                assertEquals(listOf("e1", "e2", "e3", "e4"), request.evidenceAliases())
                StructuredPlanResult.Output(validPlanJson(request.evidenceAliases()))
            }
        )

        val result = agent(gateway).run(USER_ONE, REQUEST) as NutritionPlanningResult.Success

        assertEquals(1, gateway.selectionCount)
        assertEquals(1, gateway.generationCount)
        assertEquals(NutritionAgentToolDeclarations.all, gateway.selectionRequests.single().tools)
        assertFalse(gateway.selectionRequests.single().tools.joinToString().contains("userId"))
        assertEquals(AgentRunStatus.SUCCEEDED, database.agentRunDao().getById(result.runId)?.status)
        assertNotNull(database.nutritionPlanDao().getByRun(result.runId))
        assertEquals(
            listOf(
                "get_nutrition_profile",
                "get_intake_history",
                "calculate_nutrition_trend",
                "search_fruit_nutrition"
            ),
            repository.getTrace(AgentContext(USER_ONE, result.runId)).map { it.toolName }
        )
    }

    @Test
    fun oneLegalToolIsEnoughWhenItMatchesThePlanningNeed() = runBlocking {
        val gateway = successfulGateway(listOf(profileCall()))

        val result = agent(gateway).run(USER_ONE, REQUEST) as NutritionPlanningResult.Success

        assertEquals(1, gateway.selectionCount)
        assertEquals(1, gateway.generationCount)
        assertEquals(
            listOf("get_nutrition_profile"),
            repository.getTrace(AgentContext(USER_ONE, result.runId)).map { it.toolName }
        )
        assertEquals(listOf("e1"), gateway.generationRequests.single().evidenceAliases())
    }

    @Test
    fun emptySelectionFailsBeforeAnyToolExecutes() = runBlocking {
        val gateway = FakeAgentModelGateway(ToolSelectionResult.Selected(emptyList()))

        val result = agent(gateway).run(USER_ONE, REQUEST) as NutritionPlanningResult.Failed

        assertEquals(
            AgentFailure(
                AgentFailureStage.TOOL_SELECTION,
                AgentFailureCode.INSUFFICIENT_TOOL_SELECTION
            ),
            storedFailure(result.runId)
        )
        assertTrue(repository.getTrace(AgentContext(USER_ONE, result.runId)).isEmpty())
        assertEquals(1, gateway.selectionCount)
        assertEquals(0, gateway.generationCount)
    }

    @Test
    fun duplicateEquivalentCalls_areDeduplicatedDeterministically() = runBlocking {
        val calls = listOf(
            profileCall(),
            profileCall(),
            historyCall(),
            call(
                "get_intake_history",
                """{"endDate":"2026-09-02","startDate":"2026-09-01"}"""
            )
        )
        val gateway = successfulGateway(calls)

        val result = agent(gateway).run(USER_ONE, REQUEST) as NutritionPlanningResult.Success
        val trace = repository.getTrace(AgentContext(USER_ONE, result.runId))

        assertEquals(listOf(1, 2), trace.map { it.sequence })
        assertEquals(
            listOf("get_nutrition_profile", "get_intake_history"),
            trace.map { it.toolName }
        )
        assertEquals(listOf("e1", "e2"), gateway.generationRequests.single().evidenceAliases())
        assertEquals(1, gateway.selectionCount)
        assertEquals(1, gateway.generationCount)
    }

    @Test
    fun invalidToolArgumentsAndForgedUserId_areRejectedBeforeBusinessReads() = runBlocking {
        val invalidCalls = listOf(
            call("delete_everything", """{"phone":"$PRIVATE_PHONE"}"""),
            call("get_nutrition_profile", """{"userId":$USER_TWO}"""),
            call(
                "get_intake_history",
                """{"startDate":"2026-08-01","endDate":"2026-09-01"}"""
            ),
            call("search_fruit_nutrition", """{"query":""}""")
        )

        invalidCalls.forEach { invalidCall ->
            val gateway = FakeAgentModelGateway(
                ToolSelectionResult.Selected(listOf(profileCall(), historyCall(), invalidCall))
            )
            val result = agent(gateway).run(USER_ONE, REQUEST) as NutritionPlanningResult.Failed
            val trace = repository.getTrace(AgentContext(USER_ONE, result.runId)).single()

            assertEquals(NutritionPlanningFailure.TOOL_REJECTED, result.reason)
            assertEquals(AgentToolCallStatus.FAILED, trace.status)
            assertEquals("{}", trace.argumentsJson)
            assertFalse(trace.toString().contains(PRIVATE_PHONE))
            assertFalse(trace.toString().contains(USER_TWO.toString()))
            assertEquals(
                AgentFailure(
                    AgentFailureStage.TOOL_VALIDATION,
                    AgentFailureCode.TOOL_VALIDATION_FAILED
                ),
                storedFailure(result.runId)
            )
            assertEquals(0, gateway.generationCount)
        }
    }

    @Test
    fun trustedContextAndEvidenceBundle_excludeOtherUsersPiiAndInternalIds() = runBlocking {
        val gateway = successfulGateway(listOf(profileCall(), historyCall()))

        val result = agent(gateway).run(USER_ONE, REQUEST) as NutritionPlanningResult.Success
        val evidence = gateway.generationRequests.single().evidenceBundle.canonicalJson
        val trace = repository.getTrace(AgentContext(USER_ONE, result.runId))

        assertTrue(evidence.contains("\"fruitServings\":2.0"))
        assertFalse(evidence.contains("99.0"))
        trace.forEach { assertFalse(evidence.contains(it.id)) }
        listOf(
            PRIVATE_NAME,
            PRIVATE_PHONE,
            "Other Private Person",
            "0400000902",
            "Private meal note",
            "Other private meal",
            "userId",
            "firebase call id",
            "debug token",
            "api key"
        ).forEach { forbidden -> assertFalse(evidence.contains(forbidden, ignoreCase = true)) }
    }

    @Test
    fun evidenceAliases_areMappedToThisRunAuditIdsBeforePersistence() = runBlocking {
        val gateway = successfulGateway(listOf(profileCall(), historyCall()))

        val result = agent(gateway).run(USER_ONE, REQUEST) as NutritionPlanningResult.Success
        val traceIds = repository.getTrace(AgentContext(USER_ONE, result.runId)).map { it.id }
        val stored = checkNotNull(database.nutritionPlanDao().getByRun(result.runId))

        assertEquals(traceIds, result.output.evidenceToolCallIds)
        traceIds.forEach { assertTrue(stored.contentJson.contains(it)) }
        assertFalse(stored.contentJson.contains("\"e1\""))
        assertFalse(stored.contentJson.contains("\"e2\""))
    }

    @Test
    fun finalValidationFailures_keepSuccessfulTraceAndCreateNoDraft() = runBlocking {
        val outputs = listOf(
            "```json\n{}\n```" to AgentFailure(
                AgentFailureStage.FINAL_DECODING,
                AgentFailureCode.FINAL_JSON_INVALID
            ),
            validPlanJson(listOf("e1", "e2")).replace(
                "\"summary\": \"Use steady and practical nutrition habits\"",
                "\"summary\": \"Use steady and practical nutrition habits\", \"extra\": true"
            ) to AgentFailure(
                AgentFailureStage.FINAL_SCHEMA_VALIDATION,
                AgentFailureCode.FINAL_SCHEMA_INVALID
            ),
            validPlanJson(listOf("e9")) to AgentFailure(
                AgentFailureStage.FINAL_EVIDENCE_VALIDATION,
                AgentFailureCode.FINAL_EVIDENCE_INVALID
            ),
            validPlanJson(listOf("e1", "e2")).replace(
                "Use steady and practical nutrition habits",
                "Contact private@example.com for a plan"
            ) to AgentFailure(
                AgentFailureStage.FINAL_SAFETY_VALIDATION,
                AgentFailureCode.FINAL_SAFETY_INVALID,
                PlanSafetyDiagnostic(PlanSafetyRule.PII_EMAIL, "summary",
                    PlanSafetyCategory.IDENTITY_OR_CREDENTIAL)
            )
        )

        outputs.forEach { (output, expectedFailure) ->
            val gateway = FakeAgentModelGateway(
                selection = ToolSelectionResult.Selected(requiredCalls()),
                generate = { StructuredPlanResult.Output(output) }
            )
            val result = agent(gateway).run(USER_ONE, REQUEST) as NutritionPlanningResult.Failed

            assertEquals(expectedFailure, result.failure)
            assertEquals(expectedFailure, storedFailure(result.runId))
            assertTrue(
                repository.getTrace(AgentContext(USER_ONE, result.runId))
                    .all { it.status == AgentToolCallStatus.SUCCEEDED }
            )
            assertNull(database.nutritionPlanDao().getByRun(result.runId))
        }
    }

    @Test
    fun selectionAndGenerationFailures_haveSpecificSafeStagesAndNoDraft() = runBlocking {
        val selectionFailures = listOf(
            ToolSelectionResult.Refusal(AgentModelRefusalReason.SAFETY) to
                AgentFailureCode.MODEL_SAFETY_BLOCKED,
            ToolSelectionResult.Refusal(AgentModelRefusalReason.POLICY) to
                AgentFailureCode.MODEL_REFUSED,
            ToolSelectionResult.Failure(AgentModelFailure(AgentModelError.NETWORK)) to
                AgentFailureCode.MODEL_NETWORK,
            ToolSelectionResult.Failure(AgentModelFailure(AgentModelError.PERMISSION)) to
                AgentFailureCode.MODEL_PERMISSION,
            ToolSelectionResult.Failure(AgentModelFailure(AgentModelError.QUOTA)) to
                AgentFailureCode.MODEL_QUOTA,
            ToolSelectionResult.Failure(AgentModelFailure(AgentModelError.UNAVAILABLE)) to
                AgentFailureCode.MODEL_UNAVAILABLE,
            ToolSelectionResult.Failure(AgentModelFailure(AgentModelError.SERVER)) to
                AgentFailureCode.MODEL_SERVER,
            ToolSelectionResult.Failure(
                AgentModelFailure(AgentModelError.REQUEST_CONFIGURATION)
            ) to AgentFailureCode.MODEL_REQUEST_INVALID,
            ToolSelectionResult.Failure(AgentModelFailure(AgentModelError.SERIALIZATION)) to
                AgentFailureCode.MODEL_SERIALIZATION,
            ToolSelectionResult.Failure(AgentModelFailure(AgentModelError.INVALID_RESPONSE)) to
                AgentFailureCode.TOOL_SELECTION_INVALID,
            ToolSelectionResult.Failure(AgentModelFailure(AgentModelError.EMPTY_RESPONSE)) to
                AgentFailureCode.TOOL_SELECTION_INVALID
        )
        selectionFailures.forEach { (selection, expectedCode) ->
            val gateway = FakeAgentModelGateway(selection)
            val result = agent(gateway).run(USER_ONE, REQUEST) as NutritionPlanningResult.Failed

            assertEquals(
                AgentFailure(AgentFailureStage.TOOL_SELECTION, expectedCode),
                storedFailure(result.runId)
            )
            assertTrue(repository.getTrace(AgentContext(USER_ONE, result.runId)).isEmpty())
            assertNull(database.nutritionPlanDao().getByRun(result.runId))
        }

        val generationFailures = listOf(
            StructuredPlanResult.Refusal(AgentModelRefusalReason.SAFETY) to AgentFailure(
                AgentFailureStage.FINAL_SAFETY_VALIDATION,
                AgentFailureCode.MODEL_SAFETY_BLOCKED
            ),
            StructuredPlanResult.Failure(AgentModelFailure(AgentModelError.NETWORK)) to AgentFailure(
                AgentFailureStage.FINAL_GENERATION,
                AgentFailureCode.MODEL_NETWORK
            ),
            StructuredPlanResult.Failure(AgentModelFailure(AgentModelError.TIMEOUT)) to AgentFailure(
                AgentFailureStage.FINAL_GENERATION,
                AgentFailureCode.MODEL_TIMEOUT
            ),
            StructuredPlanResult.Failure(AgentModelFailure(AgentModelError.EMPTY_RESPONSE)) to AgentFailure(
                AgentFailureStage.FINAL_GENERATION,
                AgentFailureCode.FINAL_EMPTY
            ),
            StructuredPlanResult.Failure(AgentModelFailure(AgentModelError.INVALID_RESPONSE)) to
                AgentFailure(
                    AgentFailureStage.FINAL_DECODING,
                    AgentFailureCode.FINAL_JSON_INVALID
                )
        )
        generationFailures.forEach { (generation, expectedFailure) ->
            val gateway = FakeAgentModelGateway(
                selection = ToolSelectionResult.Selected(requiredCalls()),
                generate = { generation }
            )
            val result = agent(gateway).run(USER_ONE, REQUEST) as NutritionPlanningResult.Failed

            assertEquals(expectedFailure, storedFailure(result.runId))
            assertEquals(2, repository.getTrace(AgentContext(USER_ONE, result.runId)).size)
            assertNull(database.nutritionPlanDao().getByRun(result.runId))
        }
    }

    @Test
    fun retryableToolSelectionFailure_retriesOnceThenExecutesToolsAndPersistsOneDraft() = runBlocking {
        var selectionAttempt = 0
        val retryDelays = mutableListOf<Long>()
        val gateway = FakeAgentModelGateway(
            selectionBlock = {
                selectionAttempt += 1
                if (selectionAttempt == 1) {
                    ToolSelectionResult.Failure(retryableModelFailure())
                } else {
                    ToolSelectionResult.Selected(requiredCalls())
                }
            },
            generate = { request ->
                StructuredPlanResult.Output(validPlanJson(request.evidenceAliases()))
            }
        )

        val result = agent(
            gateway = gateway,
            totalTimeoutMillis = 20_000L,
            retryDelayMillis = AgentModelRetryPolicy.RETRY_DELAY_MILLIS,
            delayForRetry = { retryDelays += it }
        ).run(USER_ONE, REQUEST) as NutritionPlanningResult.Success

        assertEquals(2, gateway.selectionCount)
        assertEquals(1, gateway.generationCount)
        assertEquals(listOf(AgentModelRetryPolicy.RETRY_DELAY_MILLIS), retryDelays)
        assertEquals(1, database.agentRunDao().getByUser(USER_ONE).size)
        assertEquals(2, repository.getTrace(AgentContext(USER_ONE, result.runId)).size)
        assertEquals(1, database.nutritionPlanDao().getByUser(USER_ONE).size)
        assertEquals(AgentRunStatus.SUCCEEDED, database.agentRunDao().getById(result.runId)?.status)
    }

    @Test
    fun retryableFinalGenerationFailure_retriesWithoutRepeatingToolsAndSecondFailureCreatesNoDraft() = runBlocking {
        val retryDelays = mutableListOf<Long>()
        val gateway = FakeAgentModelGateway(
            selection = ToolSelectionResult.Selected(requiredCalls()),
            generate = { StructuredPlanResult.Failure(retryableModelFailure()) }
        )

        val result = agent(
            gateway = gateway,
            totalTimeoutMillis = 20_000L,
            retryDelayMillis = AgentModelRetryPolicy.RETRY_DELAY_MILLIS,
            delayForRetry = { retryDelays += it }
        ).run(USER_ONE, REQUEST) as NutritionPlanningResult.Failed

        assertEquals(1, gateway.selectionCount)
        assertEquals(2, gateway.generationCount)
        assertEquals(listOf(AgentModelRetryPolicy.RETRY_DELAY_MILLIS), retryDelays)
        assertEquals(1, database.agentRunDao().getByUser(USER_ONE).size)
        assertEquals(2, repository.getTrace(AgentContext(USER_ONE, result.runId)).size)
        assertTrue(
            repository.getTrace(AgentContext(USER_ONE, result.runId))
                .all { it.status == AgentToolCallStatus.SUCCEEDED }
        )
        assertEquals(
            AgentFailure(AgentFailureStage.FINAL_GENERATION, AgentFailureCode.MODEL_SERVER),
            storedFailure(result.runId)
        )
        assertNull(database.nutritionPlanDao().getByRun(result.runId))
    }

    @Test
    fun nonRetryableModelFailure_makesOneRequestAndDoesNotWait() = runBlocking {
        val retryDelays = mutableListOf<Long>()
        val gateway = FakeAgentModelGateway(
            selection = ToolSelectionResult.Failure(
                AgentModelFailure(
                    AgentModelError.PERMISSION,
                    AgentModelFailureDiagnostic(
                        exceptionType = "PermissionMissingException",
                        causeType = null,
                        messageSemantic = AgentModelMessageSemantic.PERMISSION,
                        publicHttpStatus = null,
                        retryDecision = AgentModelRetryDecision.DO_NOT_RETRY
                    )
                )
            )
        )

        val result = agent(
            gateway = gateway,
            delayForRetry = { retryDelays += it }
        ).run(USER_ONE, REQUEST) as NutritionPlanningResult.Failed

        assertEquals(1, gateway.selectionCount)
        assertEquals(0, gateway.generationCount)
        assertTrue(retryDelays.isEmpty())
        assertEquals(
            AgentFailure(AgentFailureStage.TOOL_SELECTION, AgentFailureCode.MODEL_PERMISSION),
            storedFailure(result.runId)
        )
        assertNull(database.nutritionPlanDao().getByRun(result.runId))
    }

    @Test
    fun cancellingDuringRetryDelay_cancelsRunWithoutASecondModelRequest() = runBlocking {
        val enteredRetryDelay = CompletableDeferred<Unit>()
        val gateway = FakeAgentModelGateway(
            selection = ToolSelectionResult.Failure(retryableModelFailure())
        )
        val job = launch {
            agent(
                gateway = gateway,
                totalTimeoutMillis = 20_000L,
                delayForRetry = {
                    enteredRetryDelay.complete(Unit)
                    awaitCancellation()
                }
            ).run(USER_ONE, REQUEST)
        }

        enteredRetryDelay.await()
        job.cancelAndJoin()

        val run = database.agentRunDao().getByUser(USER_ONE).single()
        assertEquals(1, gateway.selectionCount)
        assertEquals(0, gateway.generationCount)
        assertEquals(AgentRunStatus.CANCELLED, run.status)
        assertEquals(
            AgentFailure(AgentFailureStage.TOOL_SELECTION, AgentFailureCode.CANCELLED),
            AgentFailureCodec.decode(run.errorCode)
        )
        assertNull(database.nutritionPlanDao().getByRun(run.id))
    }

    @Test
    fun toolSelectionSdkFailure_isRecordedOnceWithoutPlanOrBusinessWrites() = runBlocking {
        val patientBefore = database.patientDao().getPatientByUserId(USER_ONE)
        val profileBefore = database.questionnaireProfileDao().getByUserId(USER_ONE)
        val intakeBefore = database.foodIntakeDao().getByUserAndDateRange(
            USER_ONE,
            Long.MIN_VALUE,
            Long.MAX_VALUE
        )
        val accountBefore = database.userAccountDao().getUserById(USER_ONE)
        val gateway = FakeAgentModelGateway(
            selection = ToolSelectionResult.Failure(AgentModelFailure(AgentModelError.SDK))
        )

        val result = agent(gateway).run(USER_ONE, REQUEST) as NutritionPlanningResult.Failed

        assertEquals(1, gateway.selectionCount)
        assertEquals(0, gateway.generationCount)
        assertEquals(
            AgentFailure(AgentFailureStage.TOOL_SELECTION, AgentFailureCode.MODEL_SDK),
            storedFailure(result.runId)
        )
        assertTrue(repository.getTrace(AgentContext(USER_ONE, result.runId)).isEmpty())
        assertNull(database.nutritionPlanDao().getByRun(result.runId))
        assertEquals(patientBefore, database.patientDao().getPatientByUserId(USER_ONE))
        assertEquals(profileBefore, database.questionnaireProfileDao().getByUserId(USER_ONE))
        assertEquals(
            intakeBefore,
            database.foodIntakeDao().getByUserAndDateRange(
                USER_ONE,
                Long.MIN_VALUE,
                Long.MAX_VALUE
            )
        )
        assertEquals(accountBefore, database.userAccountDao().getUserById(USER_ONE))
    }

    @Test
    fun unexpectedGatewayFailure_isSanitizedAndDoesNotLeakDetails() = runBlocking {
        val gateway = FakeAgentModelGateway(
            selectionBlock = { error("private failure detail must not be persisted") }
        )

        val result = agent(gateway).run(USER_ONE, REQUEST) as NutritionPlanningResult.Failed

        assertEquals(
            AgentFailure(AgentFailureStage.TOOL_SELECTION, AgentFailureCode.UNEXPECTED),
            storedFailure(result.runId)
        )
        assertFalse(
            database.agentRunDao().getById(result.runId)?.errorCode.orEmpty()
                .contains("detail")
        )
    }

    @Test
    fun timeoutAndCancellationReachDistinctTerminalStates() = runBlocking {
        val timeoutGateway = FakeAgentModelGateway(selectionBlock = { awaitCancellation() })
        val timeout = agent(timeoutGateway, totalTimeoutMillis = 50L)
            .run(USER_ONE, REQUEST) as NutritionPlanningResult.Failed

        assertEquals(NutritionPlanningFailure.MODEL_TIMEOUT, timeout.reason)
        assertEquals(
            AgentFailure(AgentFailureStage.TOOL_SELECTION, AgentFailureCode.MODEL_TIMEOUT),
            storedFailure(timeout.runId)
        )
        assertEquals(AgentRunStatus.FAILED, database.agentRunDao().getById(timeout.runId)?.status)
        assertNull(database.nutritionPlanDao().getByRun(timeout.runId))
        assertTrue(database.agentToolCallDao().getByRun(timeout.runId).isEmpty())

        val enteredGeneration = CompletableDeferred<Unit>()
        val cancelGateway = FakeAgentModelGateway(
            selection = ToolSelectionResult.Selected(requiredCalls()),
            generate = {
                enteredGeneration.complete(Unit)
                awaitCancellation()
            }
        )
        val job = launch { agent(cancelGateway).run(USER_ONE, REQUEST) }
        enteredGeneration.await()
        job.cancelAndJoin()

        val cancelled = database.agentRunDao().getByUser(USER_ONE).first()
        assertEquals(AgentRunStatus.CANCELLED, cancelled.status)
        assertEquals(
            AgentFailure(AgentFailureStage.FINAL_GENERATION, AgentFailureCode.CANCELLED),
            AgentFailureCodec.decode(cancelled.errorCode)
        )
        assertNull(database.nutritionPlanDao().getByRun(cancelled.id))
    }

    @Test
    fun maximumFourSelectedCalls_isEnforcedBeforeAuditWrites() = runBlocking {
        val tooManyCalls = listOf(
            profileCall(),
            historyCall(),
            trendCall(),
            fruitCall(),
            call("search_fruit_nutrition", """{"query":"pear"}""")
        )
        val gateway = FakeAgentModelGateway(ToolSelectionResult.Selected(tooManyCalls))

        val result = agent(gateway).run(USER_ONE, REQUEST) as NutritionPlanningResult.Failed

        assertEquals(NutritionPlanningFailure.TOOL_CALL_LIMIT, result.reason)
        assertEquals(
            AgentFailure(
                AgentFailureStage.TOOL_VALIDATION,
                AgentFailureCode.MAX_TOOL_CALLS_EXCEEDED
            ),
            storedFailure(result.runId)
        )
        assertTrue(repository.getTrace(AgentContext(USER_ONE, result.runId)).isEmpty())
        assertEquals(0, gateway.generationCount)
    }

    @Test
    fun toolExecutionFailure_keepsFailedTraceAndBusinessTablesUnchanged() = runBlocking {
        val invalidProfile = validProfile(USER_ONE).copy(selectedPersona = "Balanced Eater")
        database.questionnaireProfileDao().upsert(invalidProfile)
        val intakeBefore = database.foodIntakeDao()
            .getByUserAndDateRange(USER_ONE, 0L, Long.MAX_VALUE)
        val gateway = FakeAgentModelGateway(ToolSelectionResult.Selected(requiredCalls()))

        val result = agent(gateway).run(USER_ONE, REQUEST) as NutritionPlanningResult.Failed
        val trace = repository.getTrace(AgentContext(USER_ONE, result.runId)).single()

        assertEquals(NutritionPlanningFailure.TOOL_FAILED, result.reason)
        assertEquals(AgentToolCallStatus.FAILED, trace.status)
        assertEquals(AgentToolErrorCode.PROFILE_NOT_FOUND.name, trace.errorCode)
        assertEquals(
            AgentFailure(
                AgentFailureStage.TOOL_EXECUTION,
                AgentFailureCode.TOOL_EXECUTION_FAILED
            ),
            storedFailure(result.runId)
        )
        assertEquals(0, gateway.generationCount)
        assertNull(database.nutritionPlanDao().getByRun(result.runId))
        assertEquals(invalidProfile, database.questionnaireProfileDao().getByUserId(USER_ONE))
        assertEquals(
            intakeBefore,
            database.foodIntakeDao().getByUserAndDateRange(USER_ONE, 0L, Long.MAX_VALUE)
        )
    }

    @Test
    fun finalPersistenceFailure_rollsBackDraftAndPersistsSpecificFailure() = runBlocking {
        val existingRunId = "existing-run"
        database.agentRunDao().insert(
            AgentRun(existingRunId, USER_ONE, AgentRunStatus.SUCCEEDED, FIXED_CLOCK.millis())
        )
        database.nutritionPlanDao().insert(
            NutritionPlan(
                id = "duplicate-plan-id",
                userId = USER_ONE,
                runId = existingRunId,
                status = NutritionPlanStatus.DRAFT,
                schemaVersion = 1,
                validFromEpochDay = REQUEST.validFrom.toEpochDay(),
                validUntilEpochDay = REQUEST.validTo.toEpochDay(),
                contentJson = validPlanJson(listOf("legacy-evidence")),
                cautionsJson = "[]",
                disclaimer = "General information only.",
                createdAtEpochMillis = FIXED_CLOCK.millis()
            )
        )
        val collisionRepository = AgentRepository(
            database = database,
            patientDao = database.patientDao(),
            agentRunDao = database.agentRunDao(),
            agentToolCallDao = database.agentToolCallDao(),
            nutritionPlanDao = database.nutritionPlanDao(),
            clock = FIXED_CLOCK,
            idGenerator = ScriptedIdGenerator(
                listOf("collision-run", "collision-tool-1", "collision-tool-2", "duplicate-plan-id")
            )
        )
        val gateway = successfulGateway(requiredCalls())

        val result = agent(gateway, agentRepository = collisionRepository)
            .run(USER_ONE, REQUEST) as NutritionPlanningResult.Failed

        assertEquals(
            AgentFailure(
                AgentFailureStage.FINAL_PERSISTENCE,
                AgentFailureCode.FINAL_PERSISTENCE_FAILED
            ),
            AgentFailureCodec.decode(database.agentRunDao().getById(result.runId)?.errorCode)
        )
        assertNull(database.nutritionPlanDao().getByRun(result.runId))
        assertNotNull(database.nutritionPlanDao().getByRun(existingRunId))
    }

    @Test
    fun concurrentAndRepeatedStarts_returnExistingRunWithoutCallingModel() = runBlocking {
        val startGate = CompletableDeferred<Unit>()
        val attempts = List(2) {
            async(Dispatchers.Default) {
                startGate.await()
                runCatching { repository.startRun(USER_ONE) }
            }
        }
        startGate.complete(Unit)
        val outcomes = attempts.awaitAll()
        val existing = outcomes.single { it.isSuccess }.getOrThrow()
        val duplicate = outcomes.single { it.isFailure }.exceptionOrNull()
            as AgentRepositoryException
        assertEquals(AgentRepositoryError.RUN_ALREADY_ACTIVE, duplicate.reason)
        assertEquals(existing.id, duplicate.activeRunId)

        val gateway = successfulGateway(requiredCalls())
        val result = agent(gateway).run(USER_ONE, REQUEST)

        assertEquals(NutritionPlanningResult.AlreadyRunning(existing.id), result)
        assertEquals(0, gateway.selectionCount)
        assertEquals(0, gateway.generationCount)
        assertEquals(1, database.agentRunDao().getByUser(USER_ONE).size)
    }

    private fun agent(
        gateway: AgentModelGateway,
        totalTimeoutMillis: Long = 5_000L,
        agentRepository: AgentRepository = repository,
        retryDelayMillis: Long = AgentModelRetryPolicy.RETRY_DELAY_MILLIS,
        delayForRetry: suspend (Long) -> Unit = { }
    ) = NutritionPlanningAgent(
        repository = agentRepository,
        dispatcher = AgentToolDispatcher(
            registry = AgentToolRegistry(),
            repository = agentRepository,
            readDataSource = RoomAgentReadDataSource(
                database.patientDao(),
                database.questionnaireProfileDao(),
                database.foodIntakeDao(),
                ZoneOffset.UTC
            ),
            fruitNutritionGateway = FruitNutritionGateway {
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
            },
            clock = FIXED_CLOCK
        ),
        modelGateway = gateway,
        planParser = NutritionPlanOutputParser(),
        clock = FIXED_CLOCK,
        totalTimeoutMillis = totalTimeoutMillis,
        retryDelayMillis = retryDelayMillis,
        delayForRetry = delayForRetry
    )

    private fun successfulGateway(calls: List<AgentFunctionCall>) = FakeAgentModelGateway(
        selection = ToolSelectionResult.Selected(calls),
        generate = { request ->
            StructuredPlanResult.Output(validPlanJson(request.evidenceAliases()))
        }
    )

    private fun requiredCalls() = listOf(profileCall(), historyCall())

    private fun allToolCalls() = listOf(profileCall(), historyCall(), trendCall(), fruitCall())

    private fun profileCall() = call("get_nutrition_profile", "{}")

    private fun historyCall() = call(
        "get_intake_history",
        """{"startDate":"2026-09-01","endDate":"2026-09-02"}"""
    )

    private fun trendCall() = call(
        "calculate_nutrition_trend",
        """{"startDate":"2026-09-01","endDate":"2026-09-02"}"""
    )

    private fun fruitCall() = call("search_fruit_nutrition", """{"query":"apple"}""")

    private fun call(toolName: String, arguments: String) = AgentFunctionCall(
        toolName = toolName,
        argumentsJson = arguments
    )

    private fun StructuredPlanRequest.evidenceAliases() =
        evidenceBundle.items.map(AgentEvidenceItem::alias)

    private fun validPlanJson(evidenceIds: List<String>) = """
        {
          "schemaVersion": 1,
          "summary": "Use steady and practical nutrition habits",
          "validFrom": "2026-09-13",
          "validTo": "2026-09-19",
          "recommendations": [
            {
              "category": "HYDRATION",
              "title": "Hydrate consistently",
              "action": "Drink water at regular intervals",
              "targetValue": 1800,
              "targetUnit": "MILLILITERS_PER_DAY"
            }
          ],
          "cautions": ["Adjust the pace to personal comfort"],
          "disclaimer": "This is general information and does not replace advice from a qualified professional.",
          "evidenceToolCallIds": [${evidenceIds.joinToString { "\"$it\"" }}]
        }
    """.trimIndent()

    private fun retryableModelFailure() = AgentModelFailure(
        error = AgentModelError.SERVER,
        diagnostic = AgentModelFailureDiagnostic(
            exceptionType = "ServerException",
            causeType = null,
            messageSemantic = AgentModelMessageSemantic.EXPLICIT_RETRY_REQUESTED,
            publicHttpStatus = 503,
            retryDecision = AgentModelRetryDecision.RETRY_ONCE
        )
    )

    private suspend fun storedFailure(runId: String): AgentFailure? =
        AgentFailureCodec.decode(database.agentRunDao().getById(runId)?.errorCode)

    private fun intake(userId: Int, foodName: String, fruit: Double) = FoodIntake(
        patientUserId = userId,
        submissionDate = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(),
        foodName = foodName,
        fruitServings = fruit,
        vegetablesServings = 1.0,
        waterMl = 500.0,
        totalBeverageMl = 500.0,
        sugarG = 2.0
    )

    private fun validProfile(userId: Int) = QuestionnaireProfile(
        userId = userId,
        canEatFruits = true,
        canEatVegetables = true,
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
        updatedAtEpochMillis = FIXED_CLOCK.millis()
    )

    private fun insertPatient(userId: Int, name: String, phoneNumber: String) {
        val writableDatabase = database.openHelper.writableDatabase
        val values = ContentValues()
        writableDatabase.query("PRAGMA table_info(`patients`)").use { cursor ->
            val columnNameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                when (val columnName = cursor.getString(columnNameIndex)) {
                    "userId" -> values.put(columnName, userId)
                    "phoneNumber" -> values.put(columnName, phoneNumber)
                    "name" -> values.put(columnName, name)
                    "sex" -> values.put(columnName, "Male")
                    else -> values.put(columnName, 1.0)
                }
            }
        }
        assertTrue(writableDatabase.insert("patients", SQLiteDatabase.CONFLICT_ABORT, values) != -1L)
    }

    private class SequenceIdGenerator : IdGenerator {
        private var next = 0
        override fun newId(): String = "planning-id-${++next}"
    }

    private class ScriptedIdGenerator(ids: List<String>) : IdGenerator {
        private val remaining = ArrayDeque(ids)
        override fun newId(): String = remaining.removeFirst()
    }

    private class FakeAgentModelGateway(
        private val selection: ToolSelectionResult = ToolSelectionResult.Selected(emptyList()),
        private val generate: suspend (StructuredPlanRequest) -> StructuredPlanResult = {
            StructuredPlanResult.Failure(AgentModelFailure(AgentModelError.EMPTY_RESPONSE))
        },
        private val selectionBlock: (suspend (AgentToolSelectionRequest) -> ToolSelectionResult)? = null
    ) : AgentModelGateway {
        var selectionCount = 0
            private set
        var generationCount = 0
            private set
        val selectionRequests = mutableListOf<AgentToolSelectionRequest>()
        val generationRequests = mutableListOf<StructuredPlanRequest>()

        override suspend fun selectTools(request: AgentToolSelectionRequest): ToolSelectionResult {
            selectionCount += 1
            selectionRequests += request
            return selectionBlock?.invoke(request) ?: selection
        }

        override suspend fun generateStructuredPlan(
            request: StructuredPlanRequest
        ): StructuredPlanResult {
            generationCount += 1
            generationRequests += request
            return generate(request)
        }
    }

    private companion object {
        const val USER_ONE = 901
        const val USER_TWO = 902
        const val PRIVATE_NAME = "Private Person"
        const val PRIVATE_PHONE = "0400000901"
        val FIXED_CLOCK: Clock = Clock.fixed(
            Instant.parse("2026-09-12T10:00:00Z"),
            ZoneOffset.UTC
        )
        val REQUEST = NutritionPlanningRequest(
            planningNeed = "Create a balanced plan from my saved nutrition data.",
            goal = NutritionPlanningGoal.GENERAL_BALANCE,
            validFrom = LocalDate.parse("2026-09-13"),
            validTo = LocalDate.parse("2026-09-19")
        )
    }
}
