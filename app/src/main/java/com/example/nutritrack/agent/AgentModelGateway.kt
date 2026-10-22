package com.example.nutritrack.agent

import com.example.nutritrack.data.AiModelConfig
import java.time.LocalDate
import kotlinx.coroutines.delay

enum class NutritionPlanningGoal {
    GENERAL_BALANCE,
    MORE_FRUIT_AND_VEGETABLES,
    HYDRATION,
    LOWER_SUGAR,
    LOWER_SODIUM,
    USER_DEFINED
}

data class NutritionPlanningRequest(
    val planningNeed: String,
    val goal: NutritionPlanningGoal,
    val validFrom: LocalDate,
    val validTo: LocalDate
)

object NutritionPlanningRequestPolicy {
    const val MAX_LENGTH = 300
    private const val MAX_UTF8_BYTES = 1_024

    fun normalizeOrNull(raw: String): String? {
        if (raw.any(::isForbiddenControlCharacter)) return null
        val normalized = raw.trim().replace(WHITESPACE, " ")
        if (
            normalized.isEmpty() ||
            normalized.length > MAX_LENGTH ||
            normalized.toByteArray(Charsets.UTF_8).size > MAX_UTF8_BYTES ||
            EMAIL_PATTERN.containsMatchIn(normalized) ||
            SECRET_PATTERN.containsMatchIn(normalized) ||
            PHONE_PATTERN.findAll(normalized).any { match ->
                !ISO_DATE_PATTERN.matches(match.value.trim())
            }
        ) {
            return null
        }
        return normalized
    }

    private fun isForbiddenControlCharacter(character: Char): Boolean =
        (!character.isWhitespace() && character.isISOControl()) ||
            Character.getType(character) == Character.FORMAT.toInt()

    private val WHITESPACE = Regex("\\s+")
    private val EMAIL_PATTERN = Regex(
        "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
        RegexOption.IGNORE_CASE
    )
    private val PHONE_PATTERN = Regex("(?<!\\d)(?:\\+?\\d[ -]?){8,15}(?!\\d)")
    private val ISO_DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
    private val SECRET_PATTERN = Regex(
        "\\b(?:password|debug[ _-]?token|api[ _-]?key|AIza[0-9A-Za-z_-]+|" +
            "firebase[ _-]?call[ _-]?id|user[ _-]?id|account[ _-]?id|" +
            "patient[ _-]?id|member[ _-]?id)\\b",
        RegexOption.IGNORE_CASE
    )
}

enum class AgentToolParameterType {
    STRING
}

data class AgentToolParameterDeclaration(
    val type: AgentToolParameterType,
    val description: String,
    val format: String? = null
)

data class AgentModelToolDeclaration(
    val name: String,
    val description: String,
    val parameters: Map<String, AgentToolParameterDeclaration>
)

object NutritionAgentToolDeclarations {
    val all: List<AgentModelToolDeclaration> = listOf(
        AgentModelToolDeclaration(
            name = AgentToolName.GET_NUTRITION_PROFILE.wireName,
            description = "Read the current user's minimal nutrition profile without identity data.",
            parameters = emptyMap()
        ),
        dateRangeDeclaration(
            name = AgentToolName.GET_INTAKE_HISTORY.wireName,
            description = "Read aggregated daily intake history for an inclusive range of at most 31 days."
        ),
        dateRangeDeclaration(
            name = AgentToolName.CALCULATE_NUTRITION_TREND.wireName,
            description = "Calculate a deterministic nutrition trend for an inclusive range of at most 31 days."
        ),
        AgentModelToolDeclaration(
            name = AgentToolName.SEARCH_FRUIT_NUTRITION.wireName,
            description = "Look up public nutrition data for one fruit name.",
            parameters = mapOf(
                "query" to AgentToolParameterDeclaration(
                    type = AgentToolParameterType.STRING,
                    description = "Fruit name only; no person or account identifiers."
                )
            )
        )
    )

    private fun dateRangeDeclaration(name: String, description: String) =
        AgentModelToolDeclaration(
            name = name,
            description = description,
            parameters = mapOf(
                "startDate" to AgentToolParameterDeclaration(
                    type = AgentToolParameterType.STRING,
                    description = "Inclusive local date in YYYY-MM-DD format.",
                    format = "date"
                ),
                "endDate" to AgentToolParameterDeclaration(
                    type = AgentToolParameterType.STRING,
                    description = "Inclusive local date in YYYY-MM-DD format, no more than 31 days after startDate.",
                    format = "date"
                )
            )
        )
}

data class AgentModelSettings(
    val modelName: String,
    val temperature: Float,
    val maxOutputTokens: Int,
    val requestTimeoutMillis: Long
)

object NutritionPlanningAgentConfig {
    const val MAX_MODEL_REQUESTS = 2
    const val MAX_TOOL_CALLS = 4
    const val MODEL_REQUEST_TIMEOUT_MILLIS = 60_000L
    const val MIN_NON_MODEL_BUDGET_MILLIS = 8_000L
    const val TOTAL_TIMEOUT_MILLIS =
        MODEL_REQUEST_TIMEOUT_MILLIS * MAX_MODEL_REQUESTS + MIN_NON_MODEL_BUDGET_MILLIS

    val modelSettings = AgentModelSettings(
        modelName = AiModelConfig.AGENT_MODEL_NAME,
        temperature = 0.1f,
        maxOutputTokens = 2_048,
        requestTimeoutMillis = MODEL_REQUEST_TIMEOUT_MILLIS
    )
}

data class AgentFunctionCall(
    val toolName: String,
    val argumentsJson: String
)

data class AgentFunctionResult(
    val toolName: String,
    val auditToolCallId: String,
    val canonicalArgumentsJson: String,
    val result: AgentToolResult
)

enum class AgentModelRefusalReason {
    SAFETY,
    POLICY,
    UNSUPPORTED_REQUEST
}

enum class AgentModelError {
    CONFIGURATION,
    PERMISSION,
    QUOTA,
    UNAVAILABLE,
    SERVER,
    REQUEST_CONFIGURATION,
    SERIALIZATION,
    NETWORK,
    TIMEOUT,
    EMPTY_RESPONSE,
    INVALID_RESPONSE,
    SDK,
    CANCELLED
}

/**
 * Fixed, non-content diagnostics derived from an SDK exception. They are safe to pass through the
 * Agent boundary for retry decisions and diagnostics; the original message/body never leaves the
 * Firebase implementation.
 */
enum class AgentModelMessageSemantic {
    NONE,
    APP_CHECK,
    PERMISSION,
    QUOTA,
    BILLING,
    SERVICE_DISABLED,
    REQUEST_CONFIGURATION,
    MODEL_ACCESS,
    EXPLICIT_RETRY_REQUESTED,
    TEMPORARILY_UNAVAILABLE,
    TEMPORARY_OVERLOAD,
    INTERNAL,
    UNAVAILABLE,
    UNCLASSIFIED
}

enum class AgentModelRetryDecision {
    DO_NOT_RETRY,
    RETRY_ONCE
}

data class AgentModelFailureDiagnostic(
    val exceptionType: String,
    val causeType: String?,
    val messageSemantic: AgentModelMessageSemantic,
    val publicHttpStatus: Int?,
    val retryDecision: AgentModelRetryDecision
)

data class AgentModelFailure(
    val error: AgentModelError,
    val diagnostic: AgentModelFailureDiagnostic? = null
)

internal object AgentModelRetryPolicy {
    const val RETRY_DELAY_MILLIS = 7_000L
}

/**
 * Executes the current stage at most twice. It is intentionally stage-local: callers retain the
 * same AgentRun/evidence and decide whether the first result carries an explicit transient signal.
 */
internal suspend fun <T> callWithSingleExplicitTransientRetry(
    deadlineStillActive: () -> Boolean,
    canWaitForRetry: (Long) -> Boolean = { deadlineStillActive() },
    shouldRetry: (T) -> Boolean,
    retryDelayMillis: Long = AgentModelRetryPolicy.RETRY_DELAY_MILLIS,
    delayForRetry: suspend (Long) -> Unit = { delay(it) },
    call: suspend () -> T
): T {
    val first = call()
    if (!shouldRetry(first) || !canWaitForRetry(retryDelayMillis)) return first
    delayForRetry(retryDelayMillis)
    if (!deadlineStillActive()) return first
    return call()
}

data class AgentToolSelectionRequest(
    val planningRequest: NutritionPlanningRequest,
    val settings: AgentModelSettings,
    val tools: List<AgentModelToolDeclaration>
)

sealed interface ToolSelectionResult {
    data class Selected(val calls: List<AgentFunctionCall>) : ToolSelectionResult
    data class Refusal(val reason: AgentModelRefusalReason) : ToolSelectionResult
    data class Failure(val failure: AgentModelFailure) : ToolSelectionResult
}

data class StructuredPlanRequest(
    val settings: AgentModelSettings,
    val evidenceBundle: AgentEvidenceBundle
)

sealed interface StructuredPlanResult {
    data class Output(val json: String) : StructuredPlanResult
    data class Refusal(val reason: AgentModelRefusalReason) : StructuredPlanResult
    data class Failure(val failure: AgentModelFailure) : StructuredPlanResult
}

interface AgentModelGateway {
    suspend fun selectTools(request: AgentToolSelectionRequest): ToolSelectionResult

    suspend fun generateStructuredPlan(request: StructuredPlanRequest): StructuredPlanResult
}
