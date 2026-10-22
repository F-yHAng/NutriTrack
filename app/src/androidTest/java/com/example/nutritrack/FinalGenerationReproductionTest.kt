package com.example.nutritrack

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nutritrack.agent.AgentEvidenceBundleBuilder
import com.example.nutritrack.agent.AgentFunctionResult
import com.example.nutritrack.agent.AgentNutritionProfile
import com.example.nutritrack.agent.AgentNutritionTrend
import com.example.nutritrack.agent.AgentToolName
import com.example.nutritrack.agent.AgentToolResult
import com.example.nutritrack.agent.NutritionPlanOutputParser
import com.example.nutritrack.agent.NutritionPlanningAgentConfig
import com.example.nutritrack.agent.NutritionPlanningGoal
import com.example.nutritrack.agent.NutritionPlanningRequest
import com.example.nutritrack.agent.NutritionTrendDirection
import com.example.nutritrack.agent.StructuredPlanRequest
import com.example.nutritrack.agent.StructuredPlanResult
import com.example.nutritrack.data.AiModelConfig
import com.example.nutritrack.data.FirebaseAgentBackend
import com.example.nutritrack.data.FirebaseAgentRequestStyle
import com.example.nutritrack.data.FirebasePlanGenerationModelFactory
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.FirebaseAIException
import com.google.firebase.ai.type.GenerativeBackend
import com.google.firebase.ai.type.RequestOptions
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.content
import com.google.firebase.ai.type.generationConfig
import com.google.gson.JsonParser
import java.time.LocalDate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Default-off, single-request probes for isolating the final structured-output call from the full
 * Agent run. The offline tests below use [RecordingFinalGenerationTransport], never the Firebase
 * SDK. The online methods require both exact instrumentation arguments before obtaining an
 * Application context or constructing a Firebase model.
 */
@RunWith(AndroidJUnit4::class)
class FinalGenerationReproductionTest {
    @Test
    fun minimalStructuredOutput_onlineProbeRequiresItsOwnExplicitOptIn() = runBlocking {
        assumeTrue(isFinalGenerationReproductionEnabled(arguments(), FinalGenerationReproductionCase.MINIMAL))

        // Creates the target application first, so the existing variant-specific Firebase/App
        // Check initializer is the one used by the following Firebase AI SDK call.
        runOnlineProbeStage(
            case = FinalGenerationReproductionCase.MINIMAL,
            stage = FinalGenerationProbeStage.APPLICATION_INITIALIZATION,
            modelCallCount = 0
        ) {
            ApplicationProvider.getApplicationContext<NutrackApplication>()
        }
        val response = runOnlineProbeStage(
            case = FinalGenerationReproductionCase.MINIMAL,
            stage = FinalGenerationProbeStage.MODEL_GENERATION,
            modelCallCount = 1
        ) {
            MinimalStructuredOutputProbe.generateOnce()
        }

        assertMinimalOutput(response)
    }

    @Test
    fun productionStructuredPlan_onlineProbeRequiresItsOwnExplicitOptIn() = runBlocking {
        assumeTrue(isFinalGenerationReproductionEnabled(arguments(), FinalGenerationReproductionCase.PRODUCTION))

        // Construct the target application before using the same default FirebaseApp as production.
        ApplicationProvider.getApplicationContext<NutrackApplication>()
        val fixture = syntheticProductionFixture()
        val factory = FirebasePlanGenerationModelFactory()
        val configuration = factory.configuration(fixture.request)
        val rawJson = runOnlineProbeStage(
            case = FinalGenerationReproductionCase.PRODUCTION,
            stage = FinalGenerationProbeStage.MODEL_GENERATION,
            modelCallCount = 1
        ) {
            val response = factory.create(configuration)
                .generateContent(fixture.request.evidenceBundle.canonicalJson)
            requireNotNull(response.text) { "Expected a non-empty structured response" }
        }

        val validated = runOnlineProbeStage(
            case = FinalGenerationReproductionCase.PRODUCTION,
            stage = FinalGenerationProbeStage.PARSER_AND_DOMAIN_VALIDATION,
            modelCallCount = 0
        ) {
            NutritionPlanOutputParser().parse(
                rawJson = rawJson,
                request = fixture.planningRequest,
                successfulEvidenceReferences = fixture.evidenceAuditIdsByAlias
            )
        }
        assertEquals(
            fixture.evidenceAuditIdsByAlias.values.toSet(),
            validated.plan.evidenceToolCallIds.toSet()
        )
    }

    @Test
    fun realProbesAreDisabledUnlessBothExactArgumentsSelectOneCase() {
        val disabled = Bundle().apply {
            putString(FINAL_GENERATION_REPRODUCTION_CASE_ARGUMENT, "production")
        }
        assertFalse(isFinalGenerationReproductionEnabled(disabled, FinalGenerationReproductionCase.PRODUCTION))

        val wrongCase = Bundle().apply {
            putString(ENABLE_FINAL_GENERATION_REPRODUCTION_ARGUMENT, "true")
            putString(FINAL_GENERATION_REPRODUCTION_CASE_ARGUMENT, "other")
        }
        assertFalse(isFinalGenerationReproductionEnabled(wrongCase, FinalGenerationReproductionCase.MINIMAL))

        val minimal = Bundle().apply {
            putString(ENABLE_FINAL_GENERATION_REPRODUCTION_ARGUMENT, "true")
            putString(FINAL_GENERATION_REPRODUCTION_CASE_ARGUMENT, "minimal")
        }
        assertTrue(isFinalGenerationReproductionEnabled(minimal, FinalGenerationReproductionCase.MINIMAL))
        assertFalse(isFinalGenerationReproductionEnabled(minimal, FinalGenerationReproductionCase.PRODUCTION))
        assertFalse(isFinalGenerationReproductionEnabled(minimal, FinalGenerationReproductionCase.PLAIN_TEXT))

        val plainText = Bundle().apply {
            putString(ENABLE_FINAL_GENERATION_REPRODUCTION_ARGUMENT, "true")
            putString(FINAL_GENERATION_REPRODUCTION_CASE_ARGUMENT, "plainText")
        }
        assertTrue(isFinalGenerationReproductionEnabled(plainText, FinalGenerationReproductionCase.PLAIN_TEXT))
    }

    @Test
    fun plainText_onlineProbeRequiresItsOwnExplicitOptIn() = runBlocking {
        assumeTrue(isFinalGenerationReproductionEnabled(arguments(), FinalGenerationReproductionCase.PLAIN_TEXT))

        runOnlineProbeStage(
            case = FinalGenerationReproductionCase.PLAIN_TEXT,
            stage = FinalGenerationProbeStage.APPLICATION_INITIALIZATION,
            modelCallCount = 0
        ) {
            ApplicationProvider.getApplicationContext<NutrackApplication>()
        }
        val response = runOnlineProbeStage(
            case = FinalGenerationReproductionCase.PLAIN_TEXT,
            stage = FinalGenerationProbeStage.MODEL_GENERATION,
            modelCallCount = 1
        ) {
            MinimalStructuredOutputProbe.generatePlainTextOnce()
        }

        assertTrue("Expected a non-empty plain-text response", response.isNotBlank())
    }

    @Test
    fun minimalStructuredOutput_offlineProbeUsesFakeTransportExactlyOnce() = runBlocking {
        val fake = RecordingFinalGenerationTransport(MINIMAL_SYNTHETIC_RESPONSE)

        val response = fake.generate(minimalProbeContract())

        assertEquals(1, fake.requestCount)
        assertMinimalOutput(response)
    }

    @Test
    fun plainTextControlContractHasNoJsonResponseContractOrTools() {
        val contract = plainTextProbeContract()

        assertEquals(FirebaseAgentBackend.GOOGLE_AI, contract.backend)
        assertEquals(AiModelConfig.DEFAULT_MODEL_NAME, contract.modelName)
        assertEquals(null, contract.responseMimeType)
        assertFalse(contract.hasResponseSchema)
        assertFalse(contract.hasTools)
        assertFalse(contract.hasToolConfig)
        assertEquals(FirebaseAgentRequestStyle.DIRECT_TEXT, contract.requestStyle)
    }

    @Test
    fun productionStructuredPlan_offlineProbeUsesProductionConfigurationAndRealParser() = runBlocking {
        val fixture = syntheticProductionFixture()
        val configuration = FirebasePlanGenerationModelFactory().configuration(fixture.request)
        val probe = FinalGenerationProbeContract(
            case = FinalGenerationReproductionCase.PRODUCTION,
            backend = configuration.backend,
            modelName = configuration.modelName,
            responseMimeType = configuration.responseMimeType,
            hasResponseSchema = configuration.responseSchemaEvidenceAliases.isNotEmpty(),
            hasTools = false,
            hasToolConfig = false,
            requestStyle = configuration.requestStyle,
            payload = fixture.request.evidenceBundle.canonicalJson
        )
        val fake = RecordingFinalGenerationTransport(PRODUCTION_SYNTHETIC_RESPONSE)

        val output = fake.generate(probe)
        val validated = NutritionPlanOutputParser().parse(
            rawJson = output,
            request = fixture.planningRequest,
            successfulEvidenceReferences = fixture.evidenceAuditIdsByAlias
        )

        assertEquals(1, fake.requestCount)
        assertEquals(FirebaseAgentBackend.GOOGLE_AI, probe.backend)
        assertEquals(AiModelConfig.DEFAULT_MODEL_NAME, probe.modelName)
        assertEquals("application/json", probe.responseMimeType)
        assertTrue(probe.hasResponseSchema)
        assertFalse(probe.hasTools)
        assertFalse(probe.hasToolConfig)
        assertEquals(FirebaseAgentRequestStyle.DIRECT_TEXT, probe.requestStyle)
        listOf("synthetic-audit-1", "synthetic-audit-2", "userId", "phone", "debug token")
            .forEach { forbidden ->
                assertFalse(probe.payload.contains(forbidden, ignoreCase = true))
            }
        assertEquals(
            fixture.evidenceAuditIdsByAlias.values.toSet(),
            validated.plan.evidenceToolCallIds.toSet()
        )
    }

    @Test
    fun probeDiagnosticPreservesReasonCodeAndRedactsSyntheticCredentialLikeValues() {
        val safeSummary = safeProbeFailureSummary(
            case = FinalGenerationReproductionCase.MINIMAL,
            stage = FinalGenerationProbeStage.MODEL_GENERATION,
            modelCallCount = 1,
            elapsedMillis = 17,
            throwable = IllegalStateException(
                "Permission denied; apiKey=DEMO_API_KEY; Authorization: Bearer DEMO_BEARER; " +
                    "debug token=DEMO_DEBUG_TOKEN; phone=0000000000"
            )
        )

        assertTrue(safeSummary.contains("messageSource=CLIENT_MESSAGE"))
        assertTrue(safeSummary.contains("messageReason=PERMISSION"))
        assertTrue(safeSummary.contains("messageHint=SDK_MESSAGE_PERMISSION"))
        assertTrue(safeSummary.contains("httpStatus=NOT_EXPOSED_BY_SDK"))
        listOf("DEMO_API_KEY", "DEMO_BEARER", "DEMO_DEBUG_TOKEN", "0000000000", "Permission denied")
            .forEach { sensitiveOrRaw ->
                assertFalse(safeSummary.contains(sensitiveOrRaw))
            }
    }

    @Test
    fun probeDiagnosticDistinguishesExplicitRetryAndExtractsOnlyPublicStatusCode() {
        val retrySummary = safeProbeFailureSummary(
            case = FinalGenerationReproductionCase.MINIMAL,
            stage = FinalGenerationProbeStage.MODEL_GENERATION,
            modelCallCount = 1,
            elapsedMillis = 17,
            throwable = IllegalStateException(
                "Service temporarily unavailable; please try again. HTTP status 503; " +
                    "apiKey=DEMO_API_KEY"
            )
        )
        assertTrue(retrySummary.contains("messageReason=TEMPORARY_RETRYABLE"))
        assertTrue(retrySummary.contains("messageHint=SDK_MESSAGE_RETRY_REQUESTED"))
        assertTrue(retrySummary.contains("httpStatus=503"))
        assertTrue(retrySummary.contains("httpStatusSource=PUBLIC_MESSAGE_TEXT"))
        assertFalse(retrySummary.contains("DEMO_API_KEY"))
        assertFalse(retrySummary.contains("temporarily unavailable"))

        val broadTemporarySummary = safeProbeFailureSummary(
            case = FinalGenerationReproductionCase.MINIMAL,
            stage = FinalGenerationProbeStage.MODEL_GENERATION,
            modelCallCount = 1,
            elapsedMillis = 17,
            throwable = IllegalStateException("Temporary diagnostic marker")
        )
        assertTrue(broadTemporarySummary.contains("messageReason=UNCLASSIFIED"))
        assertTrue(broadTemporarySummary.contains("messageHint=SDK_MESSAGE_TEMPORARY_TERM_ONLY"))
    }

    @Test
    fun onlineProbeStageReplacesRawThrowableWithSafeAssertion() = runBlocking {
        var assertion: AssertionError? = null
        try {
            runOnlineProbeStage(
                case = FinalGenerationReproductionCase.MINIMAL,
                stage = FinalGenerationProbeStage.MODEL_GENERATION,
                modelCallCount = 1
            ) {
                throw IllegalStateException(
                    "App Check failed; apiKey=DEMO_API_KEY; debug token=DEMO_DEBUG_TOKEN"
                )
            }
        } catch (failure: AssertionError) {
            assertion = failure
        }

        val safeFailure = checkNotNull(assertion)
        assertEquals(null, safeFailure.cause)
        assertTrue(safeFailure.message.orEmpty().contains("messageReason=APP_CHECK"))
        listOf("DEMO_API_KEY", "DEMO_DEBUG_TOKEN", "App Check failed").forEach { rawValue ->
            assertFalse(safeFailure.message.orEmpty().contains(rawValue))
        }
    }

    private fun arguments(): Bundle = InstrumentationRegistry.getArguments()
}

internal enum class FinalGenerationReproductionCase(val argumentValue: String) {
    MINIMAL("minimal"),
    PRODUCTION("production"),
    PLAIN_TEXT("plainText")
}

internal fun isFinalGenerationReproductionEnabled(
    arguments: Bundle,
    expectedCase: FinalGenerationReproductionCase
): Boolean =
    arguments.getString(ENABLE_FINAL_GENERATION_REPRODUCTION_ARGUMENT) == "true" &&
        arguments.getString(FINAL_GENERATION_REPRODUCTION_CASE_ARGUMENT) == expectedCase.argumentValue

/** Test-only contract used by the default-off offline probes; it cannot perform network I/O. */
internal data class FinalGenerationProbeContract(
    val case: FinalGenerationReproductionCase,
    val backend: FirebaseAgentBackend,
    val modelName: String,
    val responseMimeType: String?,
    val hasResponseSchema: Boolean,
    val hasTools: Boolean,
    val hasToolConfig: Boolean,
    val requestStyle: FirebaseAgentRequestStyle,
    val payload: String
)

private fun interface FinalGenerationTransport {
    suspend fun generate(contract: FinalGenerationProbeContract): String
}

private class RecordingFinalGenerationTransport(
    private val response: String
) : FinalGenerationTransport {
    var requestCount: Int = 0
        private set

    override suspend fun generate(contract: FinalGenerationProbeContract): String {
        check(requestCount == 0) { "Each reproduction probe permits one request only" }
        check(contract.responseMimeType == "application/json")
        check(contract.hasResponseSchema)
        check(!contract.hasTools)
        check(!contract.hasToolConfig)
        requestCount++
        return response
    }
}

/**
 * Test-only minimal schema. It shares the target application's Firebase initialization,
 * GOOGLE_AI backend, model, timeout and App Check provider, but intentionally does not reuse the
 * production plan schema so a future explicit probe can isolate schema complexity.
 */
private object MinimalStructuredOutputProbe {
    suspend fun generateOnce(): String {
        val settings = NutritionPlanningAgentConfig.modelSettings
        val response = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = settings.modelName,
            generationConfig = generationConfig {
                temperature = settings.temperature
                maxOutputTokens = settings.maxOutputTokens
                responseMimeType = "application/json"
                responseSchema = Schema.obj(mapOf("summary" to Schema.string()))
            },
            safetySettings = null,
            tools = null,
            toolConfig = null,
            systemInstruction = content {
                text("Return only a JSON object with a short synthetic summary.")
            },
            requestOptions = RequestOptions(
                timeoutInMillis = settings.requestTimeoutMillis,
                autoFunctionCallingTurnLimit = 1
            )
        ).generateContent(MINIMAL_SYNTHETIC_PROMPT)
        return requireNotNull(response.text) { "Expected a non-empty structured response" }
    }

    /**
     * Default-off online control. It differs from [generateOnce] only where a JSON-only response
     * contract is incompatible with a plain-text control: no MIME/schema and no JSON instruction.
     */
    suspend fun generatePlainTextOnce(): String {
        val settings = NutritionPlanningAgentConfig.modelSettings
        val response = Firebase.ai(backend = GenerativeBackend.googleAI()).generativeModel(
            modelName = settings.modelName,
            generationConfig = generationConfig {
                temperature = settings.temperature
                maxOutputTokens = settings.maxOutputTokens
            },
            safetySettings = null,
            tools = null,
            toolConfig = null,
            systemInstruction = null,
            requestOptions = RequestOptions(
                timeoutInMillis = settings.requestTimeoutMillis,
                autoFunctionCallingTurnLimit = 1
            )
        ).generateContent(MINIMAL_SYNTHETIC_PROMPT)
        return requireNotNull(response.text) { "Expected a non-empty plain-text response" }
    }
}

private data class ProductionFinalGenerationFixture(
    val planningRequest: NutritionPlanningRequest,
    val request: StructuredPlanRequest,
    val evidenceAuditIdsByAlias: Map<String, String>
)

private fun syntheticProductionFixture(): ProductionFinalGenerationFixture {
    val planningRequest = NutritionPlanningRequest(
        planningNeed = "Provide general hydration guidance from synthetic weekly records.",
        goal = NutritionPlanningGoal.HYDRATION,
        validFrom = LocalDate.parse("2026-10-01"),
        validTo = LocalDate.parse("2026-10-07")
    )
    val evidence = AgentEvidenceBundleBuilder().build(
        planningRequest,
        listOf(
            AgentFunctionResult(
                toolName = AgentToolName.GET_NUTRITION_PROFILE.wireName,
                auditToolCallId = "synthetic-audit-1",
                canonicalArgumentsJson = "{}",
                result = AgentToolResult.NutritionProfile(
                    AgentNutritionProfile(
                        selectedFoodCategories = listOf("Fruits", "Vegetables"),
                        persona = "Balance Seeker",
                        mainMealTime = "12:00",
                        sleepTime = "22:00",
                        wakeTime = "07:00",
                        overallHeifaScore = 60.0,
                        fruitHeifaScore = 7.0,
                        vegetableHeifaScore = 8.0
                    )
                )
            ),
            AgentFunctionResult(
                toolName = AgentToolName.CALCULATE_NUTRITION_TREND.wireName,
                auditToolCallId = "synthetic-audit-2",
                canonicalArgumentsJson =
                    "{\"startDate\":\"2026-10-01\",\"endDate\":\"2026-10-07\"}",
                result = AgentToolResult.NutritionTrend(
                    AgentNutritionTrend(
                        observedDays = 2,
                        averageFruitVegetableServings = 3.0,
                        averageWaterMl = 1_400.0,
                        averageSugarG = 30.0,
                        fruitVegetableDirection = NutritionTrendDirection.STABLE
                    )
                )
            )
        )
    )
    return ProductionFinalGenerationFixture(
        planningRequest = planningRequest,
        request = StructuredPlanRequest(
            settings = NutritionPlanningAgentConfig.modelSettings,
            evidenceBundle = evidence.bundle
        ),
        evidenceAuditIdsByAlias = evidence.auditToolCallIdsByAlias
    )
}

private fun minimalProbeContract() = FinalGenerationProbeContract(
    case = FinalGenerationReproductionCase.MINIMAL,
    backend = FirebaseAgentBackend.GOOGLE_AI,
    modelName = AiModelConfig.DEFAULT_MODEL_NAME,
    responseMimeType = "application/json",
    hasResponseSchema = true,
    hasTools = false,
    hasToolConfig = false,
    requestStyle = FirebaseAgentRequestStyle.DIRECT_TEXT,
    payload = MINIMAL_SYNTHETIC_PROMPT
)

private fun plainTextProbeContract() = FinalGenerationProbeContract(
    case = FinalGenerationReproductionCase.PLAIN_TEXT,
    backend = FirebaseAgentBackend.GOOGLE_AI,
    modelName = AiModelConfig.DEFAULT_MODEL_NAME,
    responseMimeType = null,
    hasResponseSchema = false,
    hasTools = false,
    hasToolConfig = false,
    requestStyle = FirebaseAgentRequestStyle.DIRECT_TEXT,
    payload = MINIMAL_SYNTHETIC_PROMPT
)

private fun assertMinimalOutput(raw: String) {
    val root = JsonParser.parseString(raw).takeIf { it.isJsonObject }?.asJsonObject
    checkNotNull(root) { "Expected a JSON object" }
    assertEquals(setOf("summary"), root.keySet())
    assertTrue(root.get("summary").asString.isNotBlank())
}

private suspend fun <T> runOnlineProbeStage(
    case: FinalGenerationReproductionCase,
    stage: FinalGenerationProbeStage,
    modelCallCount: Int,
    block: suspend () -> T
): T {
    val startedAtEpochMillis = System.currentTimeMillis()
    val startedAtElapsedRealtime = SystemClock.elapsedRealtime()
    Log.i(
        FINAL_GENERATION_REPRODUCTION_DIAGNOSTIC_TAG,
        "case=${case.name} stage=${stage.name} event=START modelCalls=$modelCallCount " +
            "startedAtEpochMillis=$startedAtEpochMillis"
    )
    return try {
        block().also {
            val elapsedMillis = SystemClock.elapsedRealtime() - startedAtElapsedRealtime
            Log.i(
                FINAL_GENERATION_REPRODUCTION_DIAGNOSTIC_TAG,
                "case=${case.name} stage=${stage.name} event=SUCCESS modelCalls=$modelCallCount " +
                    "elapsedMillis=$elapsedMillis"
            )
        }
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (failure: Throwable) {
        val elapsedMillis = SystemClock.elapsedRealtime() - startedAtElapsedRealtime
        val safeSummary = safeProbeFailureSummary(
            case = case,
            stage = stage,
            modelCallCount = modelCallCount,
            elapsedMillis = elapsedMillis,
            throwable = failure
        )
        Log.w(FINAL_GENERATION_REPRODUCTION_DIAGNOSTIC_TAG, safeSummary)
        // Do not attach failure: JUnit reports causes and messages verbatim.
        throw AssertionError(safeSummary)
    }
}

private enum class FinalGenerationProbeStage {
    APPLICATION_INITIALIZATION,
    MODEL_GENERATION,
    PARSER_AND_DOMAIN_VALIDATION
}

private fun safeProbeFailureSummary(
    case: FinalGenerationReproductionCase,
    stage: FinalGenerationProbeStage,
    modelCallCount: Int,
    elapsedMillis: Long,
    throwable: Throwable
): String {
    val message = throwable.message
    val diagnostic = message.safeMessageDiagnostic()
    return buildString {
        append("case=").append(case.name)
        append(" stage=").append(stage.name)
        append(" event=FAIL")
        append(" modelCalls=").append(modelCallCount)
        append(" elapsedMillis=").append(elapsedMillis)
        append(" exception=").append(throwable::class.java.simpleName.ifBlank { "UNKNOWN" })
        append(" causes=").append(throwable.causeTypes().joinToString(prefix = "[", postfix = "]"))
        append(" messagePresent=").append(!message.isNullOrBlank())
        append(" messageSource=").append(
            when {
                message.isNullOrBlank() -> "ABSENT"
                throwable is FirebaseAIException -> "SDK_PUBLIC_MESSAGE"
                else -> "CLIENT_MESSAGE"
            }
        )
        append(" messageReason=").append(diagnostic.reason)
        append(" messageHint=").append(diagnostic.hint)
        append(" httpStatus=").append(diagnostic.httpStatus)
        append(" httpStatusSource=").append(diagnostic.httpStatusSource)
        append(" sdkErrorCode=NOT_EXPOSED_BY_SDK")
    }
}

private fun Throwable.causeTypes(): List<String> = generateSequence(cause) { it.cause }
    .take(2)
    .map { it::class.java.simpleName.ifBlank { "UNKNOWN" } }
    .toList()

private data class SafeProbeMessageDiagnostic(
    val reason: String,
    val hint: String,
    val httpStatus: String,
    val httpStatusSource: String
)

/**
 * Uses only the SDK's public message in a synthetic test process. It never returns message text;
 * the finite labels below are safe to include in test output and distinguish an explicit retry
 * instruction from a broad, otherwise unhelpful occurrence of "temporary".
 */
private fun String?.safeMessageDiagnostic(): SafeProbeMessageDiagnostic {
    val message = this.orEmpty()
    val httpStatus = message.publicHttpStatus()
    val base = when {
        message.isBlank() -> SafeProbeMessageDiagnostic("NONE", "SDK_MESSAGE_ABSENT", "NOT_EXPOSED_BY_SDK", "NOT_EXPOSED_BY_SDK")
        message.contains("app check", ignoreCase = true) ||
            message.contains("attestation", ignoreCase = true) -> safeDiagnostic("APP_CHECK", "SDK_MESSAGE_APP_CHECK", httpStatus)
        message.contains("permission", ignoreCase = true) ||
            message.contains("unauthenticated", ignoreCase = true) ||
            message.contains("forbidden", ignoreCase = true) -> safeDiagnostic("PERMISSION", "SDK_MESSAGE_PERMISSION", httpStatus)
        message.contains("quota", ignoreCase = true) ||
            message.contains("rate limit", ignoreCase = true) ||
            message.contains("resource exhausted", ignoreCase = true) -> safeDiagnostic("QUOTA", "SDK_MESSAGE_QUOTA", httpStatus)
        message.contains("billing", ignoreCase = true) -> safeDiagnostic("BILLING", "SDK_MESSAGE_BILLING", httpStatus)
        message.contains("service disabled", ignoreCase = true) ||
            message.contains("api is not enabled", ignoreCase = true) -> safeDiagnostic("SERVICE_DISABLED", "SDK_MESSAGE_SERVICE_DISABLED", httpStatus)
        message.contains("responseSchema", ignoreCase = true) ||
            message.contains("responseMimeType", ignoreCase = true) ||
            message.contains("invalid argument", ignoreCase = true) -> safeDiagnostic("REQUEST_CONFIGURATION", "SDK_MESSAGE_REQUEST_CONFIGURATION", httpStatus)
        message.contains("model", ignoreCase = true) &&
            (message.contains("not found", ignoreCase = true) ||
                message.contains("unsupported", ignoreCase = true) ||
                message.contains("unavailable", ignoreCase = true)) -> safeDiagnostic("MODEL_ACCESS", "SDK_MESSAGE_MODEL_ACCESS", httpStatus)
        message.contains("try again", ignoreCase = true) -> safeDiagnostic("TEMPORARY_RETRYABLE", "SDK_MESSAGE_RETRY_REQUESTED", httpStatus)
        message.contains("temporarily unavailable", ignoreCase = true) ||
            message.contains("temporary unavailable", ignoreCase = true) -> safeDiagnostic("TEMPORARY_RETRYABLE", "SDK_MESSAGE_TEMPORARY_UNAVAILABLE", httpStatus)
        message.contains("internal", ignoreCase = true) ||
            message.contains("server error", ignoreCase = true) -> safeDiagnostic("INTERNAL", "SDK_MESSAGE_INTERNAL", httpStatus)
        message.contains("unavailable", ignoreCase = true) -> safeDiagnostic("UNAVAILABLE", "SDK_MESSAGE_UNAVAILABLE", httpStatus)
        message.contains("temporary", ignoreCase = true) -> safeDiagnostic("UNCLASSIFIED", "SDK_MESSAGE_TEMPORARY_TERM_ONLY", httpStatus)
        else -> safeDiagnostic("UNCLASSIFIED", "SDK_MESSAGE_UNCLASSIFIED", httpStatus)
    }
    return base
}

private fun safeDiagnostic(
    reason: String,
    hint: String,
    httpStatus: String?
) = SafeProbeMessageDiagnostic(
    reason = reason,
    hint = hint,
    httpStatus = httpStatus ?: "NOT_EXPOSED_BY_SDK",
    httpStatusSource = if (httpStatus == null) "NOT_EXPOSED_BY_SDK" else "PUBLIC_MESSAGE_TEXT"
)

/** Only an explicit public `HTTP status 4xx/5xx` token is retained, never the surrounding text. */
private fun String.publicHttpStatus(): String? =
    HTTP_STATUS_PATTERN.find(this)?.groupValues?.getOrNull(1)

private const val ENABLE_FINAL_GENERATION_REPRODUCTION_ARGUMENT =
    "enableFinalGenerationReproduction"
private const val FINAL_GENERATION_REPRODUCTION_CASE_ARGUMENT =
    "finalGenerationReproductionCase"
private const val FINAL_GENERATION_REPRODUCTION_DIAGNOSTIC_TAG = "NutriTrackFinalGenProbe"
private const val MINIMAL_SYNTHETIC_PROMPT =
    "Return one short synthetic hydration summary."
private const val MINIMAL_SYNTHETIC_RESPONSE = "{\"summary\":\"Synthetic hydration summary.\"}"
private const val PRODUCTION_SYNTHETIC_RESPONSE = """
    {
      "schemaVersion": 1,
      "summary": "General hydration guidance based on supplied synthetic records.",
      "validFrom": "2026-10-01",
      "validTo": "2026-10-07",
      "recommendations": [
        {
          "category": "HYDRATION",
          "title": "Regular hydration",
          "action": "Drink water regularly through the day.",
          "targetValue": 1800,
          "targetUnit": "MILLILITERS_PER_DAY"
        }
      ],
      "cautions": ["Adjust gradually and seek qualified care for personal concerns."],
      "disclaimer": "General information only; seek qualified care for personal medical needs.",
      "evidenceToolCallIds": ["e1", "e2"]
    }
"""

private val HTTP_STATUS_PATTERN =
    Regex("""(?i)\b(?:http(?:\s+status)?|status)\s*[:=]?\s*([45]\d{2})\b""")
