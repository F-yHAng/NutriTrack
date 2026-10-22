package com.example.nutritrack.agent

import java.time.LocalDate
import java.util.UUID

fun interface IdGenerator {
    fun newId(): String
}

object UuidIdGenerator : IdGenerator {
    override fun newId(): String = UUID.randomUUID().toString()
}

data class AgentContext(
    val userId: Int,
    val runId: String
)

data class AgentToolInvocation(
    val toolName: String,
    val argumentsJson: String
)

enum class AgentToolName(val wireName: String) {
    GET_NUTRITION_PROFILE("get_nutrition_profile"),
    GET_INTAKE_HISTORY("get_intake_history"),
    CALCULATE_NUTRITION_TREND("calculate_nutrition_trend"),
    SEARCH_FRUIT_NUTRITION("search_fruit_nutrition"),
    UNKNOWN_REJECTED("unknown_rejected")
}

sealed interface AgentToolInput {
    data object GetNutritionProfile : AgentToolInput

    data class GetIntakeHistory(
        val startDate: LocalDate,
        val endDate: LocalDate
    ) : AgentToolInput

    data class CalculateNutritionTrend(
        val startDate: LocalDate,
        val endDate: LocalDate
    ) : AgentToolInput

    data class SearchFruitNutrition(val query: String) : AgentToolInput
}

data class DecodedAgentToolCall(
    val toolName: AgentToolName,
    val input: AgentToolInput,
    val canonicalArgumentsJson: String
)

data class AgentNutritionProfile(
    val selectedFoodCategories: List<String>,
    val persona: String,
    val mainMealTime: String,
    val sleepTime: String,
    val wakeTime: String,
    val overallHeifaScore: Double,
    val fruitHeifaScore: Double,
    val vegetableHeifaScore: Double
)

data class AgentDailyIntake(
    val date: LocalDate,
    val recordCount: Int,
    val vegetablesServings: Double,
    val fruitServings: Double,
    val grainsServings: Double,
    val meatServings: Double,
    val dairyServings: Double,
    val discretionaryServings: Double,
    val sodiumMg: Double,
    val alcoholStandardDrinks: Double,
    val waterMl: Double,
    val sugarG: Double,
    val saturatedFatG: Double,
    val unsaturatedFatG: Double
)

enum class NutritionTrendDirection {
    INCREASING,
    STABLE,
    DECREASING,
    NO_DATA
}

data class AgentNutritionTrend(
    val observedDays: Int,
    val averageFruitVegetableServings: Double,
    val averageWaterMl: Double,
    val averageSugarG: Double,
    val fruitVegetableDirection: NutritionTrendDirection
)

sealed interface AgentToolResult {
    data class NutritionProfile(val profile: AgentNutritionProfile) : AgentToolResult
    data class IntakeHistory(val days: List<AgentDailyIntake>) : AgentToolResult
    data class NutritionTrend(val trend: AgentNutritionTrend) : AgentToolResult

    data class FruitNutrition(
        val name: String,
        val family: String,
        val calories: Double,
        val fat: Double,
        val sugar: Double,
        val carbohydrates: Double,
        val protein: Double
    ) : AgentToolResult

    data object FruitNotFound : AgentToolResult
}

enum class AgentToolErrorCode {
    UNKNOWN_TOOL,
    INVALID_ARGUMENTS,
    USER_ID_NOT_ALLOWED,
    DATE_RANGE_EXCEEDED,
    CONTEXT_DENIED,
    RUN_NOT_ACTIVE,
    PROFILE_NOT_FOUND,
    INVALID_STORED_DATA,
    FRUIT_NOT_FOUND,
    FRUIT_TIMEOUT,
    FRUIT_NO_NETWORK,
    FRUIT_SERVICE_UNAVAILABLE,
    FRUIT_INVALID_RESPONSE,
    FRUIT_REQUEST_REJECTED,
    TOOL_CANCELLED,
    PROCESS_INTERRUPTED,
    UNKNOWN_FAILURE
}

sealed interface AgentDispatchResult {
    data class Success(
        val result: AgentToolResult,
        val toolCallId: String,
        val canonicalArgumentsJson: String,
        val canonicalResultJson: String
    ) : AgentDispatchResult

    data class Rejected(
        val errorCode: AgentToolErrorCode,
        val toolCallId: String? = null
    ) : AgentDispatchResult

    data class Failure(
        val errorCode: AgentToolErrorCode,
        val toolCallId: String
    ) : AgentDispatchResult
}

internal class AgentToolRejection(
    val code: AgentToolErrorCode,
    val auditedToolName: AgentToolName,
    val canonicalArgumentsJson: String = "{}"
) : IllegalArgumentException(code.name)

internal class AgentToolExecutionFailure(
    val code: AgentToolErrorCode
) : IllegalStateException(code.name)

data class DraftNutritionPlanInput(
    val schemaVersion: Int,
    val validFrom: LocalDate,
    val validUntil: LocalDate,
    val contentJson: String,
    val cautions: List<String>,
    val disclaimer: String
)

enum class AgentFailureStage {
    TOOL_SELECTION,
    TOOL_VALIDATION,
    TOOL_EXECUTION,
    FUNCTION_RESPONSE_MAPPING,
    FINAL_GENERATION,
    FINAL_DECODING,
    FINAL_SCHEMA_VALIDATION,
    FINAL_EVIDENCE_VALIDATION,
    FINAL_SAFETY_VALIDATION,
    FINAL_PERSISTENCE,
    PROCESS_RECOVERY
}

enum class AgentFailureCode {
    INVALID_REQUEST,
    MODEL_CONFIGURATION,
    MODEL_PERMISSION,
    MODEL_QUOTA,
    MODEL_UNAVAILABLE,
    MODEL_SERVER,
    MODEL_REQUEST_INVALID,
    MODEL_SERIALIZATION,
    MODEL_NETWORK,
    MODEL_TIMEOUT,
    MODEL_REFUSED,
    MODEL_SAFETY_BLOCKED,
    MODEL_SDK,
    INSUFFICIENT_TOOL_SELECTION,
    TOOL_SELECTION_INVALID,
    TOOL_VALIDATION_FAILED,
    TOOL_EXECUTION_FAILED,
    FUNCTION_RESPONSE_INVALID,
    FINAL_EMPTY,
    FINAL_JSON_INVALID,
    FINAL_SCHEMA_INVALID,
    FINAL_EVIDENCE_INVALID,
    FINAL_SAFETY_INVALID,
    FINAL_PERSISTENCE_FAILED,
    MAX_ROUNDS_EXCEEDED,
    MAX_TOOL_CALLS_EXCEEDED,
    CANCELLED,
    PROCESS_INTERRUPTED,
    UNEXPECTED
}

enum class PlanSafetyRule {
    PII_EMAIL, PII_CREDENTIAL, PII_PHONE,
    MEDICAL_DIAGNOSIS, MEDICAL_TREATMENT, MEDICAL_PRESCRIPTION, MEDICAL_CURE,
    MEDICAL_GUARANTEE, MEDICAL_DISEASE_PREVENTION, MEDICAL_MEDICATION_CHANGE
}

enum class PlanSafetyCategory {
    IDENTITY_OR_CREDENTIAL, UNNEGATED_MEDICAL_CLAIM, UNRECOGNIZED_NEGATION_SCOPE
}

/** Only fixed rule/category values and a bounded schema path; never the matching text. */
data class PlanSafetyDiagnostic(
    val ruleId: PlanSafetyRule,
    val fieldPath: String,
    val category: PlanSafetyCategory
) {
    init {
        require(FIELD_PATH.matches(fieldPath))
    }

    val safeSummary: String
        get() = "ruleId=${ruleId.name} fieldPath=$fieldPath category=${category.name}"

    companion object {
        private val FIELD_PATH = Regex(
            "summary|disclaimer|recommendations\\[[0-7]\\]\\.(?:title|action)|cautions\\[[0-7]\\]"
        )
    }
}

data class AgentFailure(
    val stage: AgentFailureStage,
    val code: AgentFailureCode,
    val safetyDiagnostic: PlanSafetyDiagnostic? = null
)

/** Stores bounded codes/schema paths in the v10 errorCode column, never matching text. */
object AgentFailureCodec {
    private const val VERSION = "v1"
    private const val SEPARATOR = "|"

    fun encode(failure: AgentFailure): String {
        val diagnostic = failure.safetyDiagnostic ?: return listOf(
            VERSION, failure.stage.name, failure.code.name
        ).joinToString(SEPARATOR)
        require(failure.stage == AgentFailureStage.FINAL_SAFETY_VALIDATION)
        require(failure.code == AgentFailureCode.FINAL_SAFETY_INVALID)
        return listOf(
            "v2", failure.stage.name, failure.code.name,
            diagnostic.ruleId.name, diagnostic.fieldPath, diagnostic.category.name
        ).joinToString(SEPARATOR)
    }

    fun decode(value: String?): AgentFailure? {
        if (value.isNullOrBlank()) return null
        val parts = value.split(SEPARATOR)
        if (parts.size == 6 && parts[0] == "v2") {
            if (parts[1] != AgentFailureStage.FINAL_SAFETY_VALIDATION.name ||
                parts[2] != AgentFailureCode.FINAL_SAFETY_INVALID.name) return null
            val rule = PlanSafetyRule.entries.firstOrNull { it.name == parts[3] } ?: return null
            val category = PlanSafetyCategory.entries.firstOrNull { it.name == parts[5] } ?: return null
            val diagnostic = try {
                PlanSafetyDiagnostic(rule, parts[4], category)
            } catch (_: IllegalArgumentException) { return null }
            return AgentFailure(
                AgentFailureStage.FINAL_SAFETY_VALIDATION,
                AgentFailureCode.FINAL_SAFETY_INVALID,
                diagnostic
            )
        }
        if (parts.size == 3 && parts[0] == VERSION) {
            val stage = enumValues<AgentFailureStage>().firstOrNull { it.name == parts[1] }
            val code = enumValues<AgentFailureCode>().firstOrNull { it.name == parts[2] }
            if (stage != null && code != null) return AgentFailure(stage, code)
            return null
        }
        return legacyFailure(value)
    }

    private fun legacyFailure(value: String): AgentFailure? = when (value) {
        "TOOL_FAILURE" -> AgentFailure(
            AgentFailureStage.TOOL_EXECUTION,
            AgentFailureCode.TOOL_EXECUTION_FAILED
        )

        "INVALID_PLAN" -> AgentFailure(
            AgentFailureStage.FINAL_SCHEMA_VALIDATION,
            AgentFailureCode.FINAL_SCHEMA_INVALID
        )

        "MODEL_REFUSAL" -> AgentFailure(
            AgentFailureStage.TOOL_SELECTION,
            AgentFailureCode.MODEL_REFUSED
        )

        "MODEL_NETWORK" -> AgentFailure(
            AgentFailureStage.TOOL_SELECTION,
            AgentFailureCode.MODEL_NETWORK
        )

        "MODEL_TIMEOUT" -> AgentFailure(
            AgentFailureStage.TOOL_SELECTION,
            AgentFailureCode.MODEL_TIMEOUT
        )

        "MODEL_ERROR" -> AgentFailure(
            AgentFailureStage.TOOL_SELECTION,
            AgentFailureCode.MODEL_SDK
        )

        "LOOP_LIMIT" -> AgentFailure(
            AgentFailureStage.TOOL_SELECTION,
            AgentFailureCode.MAX_ROUNDS_EXCEEDED
        )

        "TOOL_LIMIT" -> AgentFailure(
            AgentFailureStage.TOOL_VALIDATION,
            AgentFailureCode.MAX_TOOL_CALLS_EXCEEDED
        )

        "PROCESS_INTERRUPTED" -> AgentFailure(
            AgentFailureStage.PROCESS_RECOVERY,
            AgentFailureCode.PROCESS_INTERRUPTED
        )

        "INTERNAL_ERROR" -> AgentFailure(
            AgentFailureStage.TOOL_SELECTION,
            AgentFailureCode.UNEXPECTED
        )

        else -> null
    }
}
