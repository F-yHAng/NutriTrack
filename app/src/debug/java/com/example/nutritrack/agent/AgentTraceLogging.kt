package com.example.nutritrack.agent

import android.util.Log
import com.example.nutritrack.data.TOOL_SELECTION_PROMPT_PREFIX
import com.example.nutritrack.data.TOOL_SELECTION_SYSTEM_INSTRUCTION
import com.google.gson.GsonBuilder
import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonPrimitive
import kotlinx.coroutines.CancellationException

object AgentTraceLogging {
    const val TAG = "NutriTrackAgentTrace"
    val logger: AgentTraceLogger = RedactingAgentTraceLogger { line -> Log.d(TAG, line) }
}

/** Debug only. No arbitrary strings, identifiers or health values may reach Logcat. */
internal class RedactingAgentTraceLogger(private val writeLine: (String) -> Unit) : AgentTraceLogger {
    private var event = 0L

    @Synchronized
    override fun record(stage: AgentTraceStage, payload: () -> String) {
        try {
            val preview = AgentTraceRedactor.preview(payload())
            val chunks = preview.chunked(2_000)
            val sequence = ++event
            chunks.forEachIndexed { index, chunk ->
                writeLine("${stage.name} event=$sequence part=${index + 1}/${chunks.size} redacted=true\n$chunk")
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Logging must not turn a valid request into a failed run, nor expose an exception.
        }
    }
}

internal object AgentTraceRedactor {
    private const val REDACTED = "[VALUE_REDACTED]"
    private val pretty = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    private val fieldPath = Regex("summary|disclaimer|recommendations\\[[0-7]\\]\\.(?:title|action)|cautions\\[[0-7]\\]")
    private val aliases = Regex("e[1-4]")
    private val safeValues = mapOf(
        "toolName" to AgentToolName.entries.map { it.wireName }.toSet(),
        "category" to NutritionRecommendationCategory.entries.map { it.name }.toSet(),
        "targetUnit" to NutritionTargetUnit.entries.map { it.name }.toSet(),
        "stage" to AgentFailureStage.entries.map { it.name }.toSet(),
        "errorCode" to AgentFailureCode.entries.map { it.name }.toSet(),
        "toolErrorCode" to AgentToolErrorCode.entries.map { it.name }.toSet(),
        "validationError" to NutritionPlanValidationError.entries.map { it.name }.toSet(),
        "ruleId" to PlanSafetyRule.entries.map { it.name }.toSet(),
        "failureCategory" to PlanSafetyCategory.entries.map { it.name }.toSet(),
        "status" to setOf("PASSED", "FAILED", "CANCELLED", "SUCCEEDED", "SAVED_DRAFT")
    )
    private val knownKeys = ("systemInstruction prompt tools toolName argumentsJson resultJson " +
        "startDate endDate query schemaVersion planningNeed goal validFrom validTo truncated items alias result " +
        "profile selectedFoodCategories persona mainMealTime sleepTime wakeTime overallHeifaScore " +
        "fruitHeifaScore vegetableHeifaScore days date recordCount vegetablesServings fruitServings grainsServings " +
        "meatServings dairyServings discretionaryServings sodiumMg alcoholStandardDrinks waterMl sugarG " +
        "saturatedFatG unsaturatedFatG observedDays averageFruitVegetableServings averageWaterMl averageSugarG " +
        "fruitVegetableDirection found fruit name family calories fat sugar carbohydrates protein " +
        "summary recommendations title action targetValue cautions disclaimer evidenceToolCallIds " +
        "fieldPath").split(' ').toSet() + safeValues.keys

    fun preview(raw: String): String {
        if (raw.length > 65_536) return "[CONTENT_OMITTED: SIZE_LIMIT]"
        return try {
            pretty.toJson(redact(JsonParser.parseString(raw), "", 0))
        } catch (_: StackOverflowError) {
            "[CONTENT_OMITTED: DEPTH_LIMIT]"
        } catch (_: Exception) {
            // Invalid JSON is still diagnosed by the real parser; never fall back to raw text.
            "[CONTENT_OMITTED: UNPARSEABLE_JSON]"
        }
    }

    private fun redact(value: JsonElement, key: String, depth: Int): JsonElement {
        if (depth > 12) return JsonPrimitive("[CONTENT_OMITTED: DEPTH_LIMIT]")
        if (value.isJsonObject) return JsonObject().apply {
            value.asJsonObject.entrySet().take(64).forEachIndexed { index, (childKey, child) ->
                if (childKey in knownKeys) add(childKey, redact(child, childKey, depth + 1))
                else addProperty("[UNKNOWN_FIELD_${index + 1}]", REDACTED)
            }
            if (value.asJsonObject.size() > 64) addProperty("[TRUNCATED]", true)
        }
        if (value.isJsonArray) return JsonArray().apply {
            value.asJsonArray.take(8).forEach { add(redact(it, key, depth + 1)) }
            if (value.asJsonArray.size() > 8) add("[TRUNCATED]")
        }
        if (value.isJsonNull) return value
        val primitive = value.asJsonPrimitive
        val text = primitive.asString
        if (primitive.isBoolean && key == "truncated") return value
        if (primitive.isNumber && key == "schemaVersion" && text == "1") return value
        if (!primitive.isString) return JsonPrimitive(REDACTED)
        if (text in safeValues[key].orEmpty()) return value
        if (key in setOf("alias", "evidenceToolCallIds") && aliases.matches(text)) return value
        if (key == "fieldPath" && fieldPath.matches(text)) return value
        if (key == "systemInstruction" && text == TOOL_SELECTION_SYSTEM_INSTRUCTION) return value
        if (key == "prompt" && text.startsWith(TOOL_SELECTION_PROMPT_PREFIX)) {
            val data = JsonParser.parseString(text.removePrefix(TOOL_SELECTION_PROMPT_PREFIX))
            return JsonPrimitive(TOOL_SELECTION_PROMPT_PREFIX + redact(data, "", depth + 1))
        }
        if (key in setOf("argumentsJson", "resultJson")) {
            return redact(JsonParser.parseString(text), "", depth + 1)
        }
        return JsonPrimitive(REDACTED)
    }
}
