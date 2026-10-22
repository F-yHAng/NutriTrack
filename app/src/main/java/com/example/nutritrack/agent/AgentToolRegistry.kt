package com.example.nutritrack.agent

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class AgentToolRegistry {
    val supportedToolNames: Set<String> = setOf(
        AgentToolName.GET_NUTRITION_PROFILE.wireName,
        AgentToolName.GET_INTAKE_HISTORY.wireName,
        AgentToolName.CALCULATE_NUTRITION_TREND.wireName,
        AgentToolName.SEARCH_FRUIT_NUTRITION.wireName
    )

    fun decode(invocation: AgentToolInvocation): DecodedAgentToolCall {
        val toolName = AgentToolName.entries.firstOrNull {
            it != AgentToolName.UNKNOWN_REJECTED && it.wireName == invocation.toolName
        } ?: throw AgentToolRejection(
            code = AgentToolErrorCode.UNKNOWN_TOOL,
            auditedToolName = AgentToolName.UNKNOWN_REJECTED
        )

        val arguments = parseObject(invocation.argumentsJson, toolName)
        if ("userId" in arguments.keySet()) {
            throw AgentToolRejection(
                code = AgentToolErrorCode.USER_ID_NOT_ALLOWED,
                auditedToolName = toolName
            )
        }

        return when (toolName) {
            AgentToolName.GET_NUTRITION_PROFILE -> {
                requireKeys(arguments, emptySet(), toolName)
                DecodedAgentToolCall(toolName, AgentToolInput.GetNutritionProfile, "{}")
            }

            AgentToolName.GET_INTAKE_HISTORY -> decodeDateRange(
                toolName = toolName,
                arguments = arguments,
                inputFactory = AgentToolInput::GetIntakeHistory
            )

            AgentToolName.CALCULATE_NUTRITION_TREND -> decodeDateRange(
                toolName = toolName,
                arguments = arguments,
                inputFactory = AgentToolInput::CalculateNutritionTrend
            )

            AgentToolName.SEARCH_FRUIT_NUTRITION -> decodeFruitQuery(arguments)
            AgentToolName.UNKNOWN_REJECTED -> error("Unknown tools are rejected above")
        }
    }

    private fun decodeDateRange(
        toolName: AgentToolName,
        arguments: JsonObject,
        inputFactory: (LocalDate, LocalDate) -> AgentToolInput
    ): DecodedAgentToolCall {
        requireKeys(arguments, setOf("startDate", "endDate"), toolName)
        val start = parseDate(arguments, "startDate", toolName)
        val end = parseDate(arguments, "endDate", toolName)
        val inclusiveDays = ChronoUnit.DAYS.between(start, end) + 1
        if (inclusiveDays !in 1..MAX_DATE_RANGE_DAYS) {
            throw AgentToolRejection(
                code = if (inclusiveDays > MAX_DATE_RANGE_DAYS) {
                    AgentToolErrorCode.DATE_RANGE_EXCEEDED
                } else {
                    AgentToolErrorCode.INVALID_ARGUMENTS
                },
                auditedToolName = toolName
            )
        }
        val canonical = JsonObject().apply {
            addProperty("startDate", start.toString())
            addProperty("endDate", end.toString())
        }.toString()
        return DecodedAgentToolCall(toolName, inputFactory(start, end), canonical)
    }

    private fun decodeFruitQuery(arguments: JsonObject): DecodedAgentToolCall {
        val toolName = AgentToolName.SEARCH_FRUIT_NUTRITION
        requireKeys(arguments, setOf("query"), toolName)
        val query = requiredString(arguments, "query", toolName).trim()
        if (query.length !in 1..MAX_FRUIT_QUERY_LENGTH || !FRUIT_QUERY_PATTERN.matches(query)) {
            throw AgentToolRejection(AgentToolErrorCode.INVALID_ARGUMENTS, toolName)
        }
        val canonical = JsonObject().apply { addProperty("query", query) }.toString()
        return DecodedAgentToolCall(
            toolName = toolName,
            input = AgentToolInput.SearchFruitNutrition(query),
            canonicalArgumentsJson = canonical
        )
    }

    private fun parseObject(rawJson: String, toolName: AgentToolName): JsonObject {
        if (rawJson.length > MAX_ARGUMENTS_JSON_LENGTH) {
            throw AgentToolRejection(AgentToolErrorCode.INVALID_ARGUMENTS, toolName)
        }
        return try {
            JsonParser.parseString(rawJson).takeIf { it.isJsonObject }?.asJsonObject
                ?: throw AgentToolRejection(AgentToolErrorCode.INVALID_ARGUMENTS, toolName)
        } catch (_: JsonSyntaxException) {
            throw AgentToolRejection(AgentToolErrorCode.INVALID_ARGUMENTS, toolName)
        } catch (_: IllegalStateException) {
            throw AgentToolRejection(AgentToolErrorCode.INVALID_ARGUMENTS, toolName)
        }
    }

    private fun requireKeys(
        arguments: JsonObject,
        expected: Set<String>,
        toolName: AgentToolName
    ) {
        if (arguments.keySet() != expected) {
            throw AgentToolRejection(AgentToolErrorCode.INVALID_ARGUMENTS, toolName)
        }
    }

    private fun parseDate(
        arguments: JsonObject,
        key: String,
        toolName: AgentToolName
    ): LocalDate = try {
        LocalDate.parse(requiredString(arguments, key, toolName))
    } catch (_: DateTimeException) {
        throw AgentToolRejection(AgentToolErrorCode.INVALID_ARGUMENTS, toolName)
    }

    private fun requiredString(
        arguments: JsonObject,
        key: String,
        toolName: AgentToolName
    ): String = try {
        arguments.get(key).takeIf { it.isJsonPrimitive && it.asJsonPrimitive.isString }?.asString
            ?: throw AgentToolRejection(AgentToolErrorCode.INVALID_ARGUMENTS, toolName)
    } catch (_: UnsupportedOperationException) {
        throw AgentToolRejection(AgentToolErrorCode.INVALID_ARGUMENTS, toolName)
    }

    companion object {
        const val MAX_DATE_RANGE_DAYS = 31L
        private const val MAX_ARGUMENTS_JSON_LENGTH = 2_048
        private const val MAX_FRUIT_QUERY_LENGTH = 50
        private val FRUIT_QUERY_PATTERN = Regex("[\\p{L}]+(?:[ '-][\\p{L}]+)*")
    }
}
