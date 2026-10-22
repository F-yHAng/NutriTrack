package com.example.nutritrack.agent

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import java.time.DateTimeException
import java.time.LocalDate
import java.time.temporal.ChronoUnit

data class AgentEvidenceItem(
    val alias: String,
    val toolName: String,
    val resultJson: String,
    val truncated: Boolean
)

data class AgentEvidenceBundle(
    val schemaVersion: Int,
    val planningNeed: String,
    val goal: NutritionPlanningGoal,
    val validFrom: LocalDate,
    val validTo: LocalDate,
    val items: List<AgentEvidenceItem>,
    val truncated: Boolean,
    val canonicalJson: String
)

internal data class BuiltAgentEvidenceBundle(
    val bundle: AgentEvidenceBundle,
    val auditToolCallIdsByAlias: Map<String, String>
)

internal class AgentEvidenceBundleException : IllegalArgumentException("Invalid Agent evidence")

class AgentEvidenceBundleBuilder {
    internal fun build(
        request: NutritionPlanningRequest,
        results: List<AgentFunctionResult>
    ): BuiltAgentEvidenceBundle = try {
        buildValidated(request, results)
    } catch (failure: AgentEvidenceBundleException) {
        throw failure
    } catch (_: Exception) {
        fail()
    }

    private fun buildValidated(
        request: NutritionPlanningRequest,
        results: List<AgentFunctionResult>
    ): BuiltAgentEvidenceBundle {
        if (results.isEmpty() || results.size > NutritionPlanningAgentConfig.MAX_TOOL_CALLS) fail()
        if (
            results.any { it.auditToolCallId.isBlank() } ||
            results.map { it.auditToolCallId }.distinct().size != results.size
        ) {
            fail()
        }

        val items = results.mapIndexed { index, result ->
            val alias = "e${index + 1}"
            val sanitized = sanitizeResult(result)
            AgentEvidenceItem(alias, result.toolName, sanitized.json, sanitized.truncated)
        }
        val canonicalJson = JsonObject().apply {
            addProperty("schemaVersion", SCHEMA_VERSION)
            addProperty("planningNeed", request.planningNeed)
            addProperty("goal", request.goal.name)
            addProperty("validFrom", request.validFrom.toString())
            addProperty("validTo", request.validTo.toString())
            addProperty("truncated", items.any(AgentEvidenceItem::truncated))
            add("items", JsonArray().apply {
                items.forEach { item ->
                    add(JsonObject().apply {
                        addProperty("alias", item.alias)
                        addProperty("toolName", item.toolName)
                        addProperty("truncated", item.truncated)
                        add("result", parseObject(item.resultJson))
                    })
                }
            })
        }.toString()
        if (
            canonicalJson.utf8Size() > MAX_TOTAL_JSON_BYTES ||
            containsForbiddenJsonData(canonicalJson) ||
            results.any { canonicalJson.contains(it.auditToolCallId) }
        ) {
            fail()
        }
        return BuiltAgentEvidenceBundle(
            bundle = AgentEvidenceBundle(
                schemaVersion = SCHEMA_VERSION,
                planningNeed = request.planningNeed,
                goal = request.goal,
                validFrom = request.validFrom,
                validTo = request.validTo,
                items = items,
                truncated = items.any(AgentEvidenceItem::truncated),
                canonicalJson = canonicalJson
            ),
            auditToolCallIdsByAlias = items.mapIndexed { index, item ->
                item.alias to results[index].auditToolCallId
            }.toMap()
        )
    }

    private fun sanitizeResult(result: AgentFunctionResult): SanitizedResult {
        if (result.canonicalArgumentsJson.length > MAX_ARGUMENTS_JSON_LENGTH) fail()
        val sanitized = when (result.toolName) {
            AgentToolName.GET_NUTRITION_PROFILE.wireName -> sanitizeProfile(result)
            AgentToolName.GET_INTAKE_HISTORY.wireName -> sanitizeHistory(result)
            AgentToolName.CALCULATE_NUTRITION_TREND.wireName -> sanitizeTrend(result)
            AgentToolName.SEARCH_FRUIT_NUTRITION.wireName -> sanitizeFruit(result)
            else -> fail()
        }
        if (sanitized.json.utf8Size() > MAX_ITEM_RESULT_JSON_BYTES) fail()
        return sanitized
    }

    private fun sanitizeProfile(result: AgentFunctionResult): SanitizedResult {
        if (parseObject(result.canonicalArgumentsJson).keySet().isNotEmpty()) fail()
        val profile = (result.result as? AgentToolResult.NutritionProfile)?.profile ?: fail()
        var truncated = false
        if (profile.selectedFoodCategories.any { it !in ALLOWED_FOOD_CATEGORIES }) fail()
        val categories = profile.selectedFoodCategories.distinct().sorted()
            .take(MAX_PROFILE_CATEGORIES).map { raw ->
            rejectPii(raw)
            raw.boundedString().also { if (it.second) truncated = true }.first
        }
        if (categories.size != profile.selectedFoodCategories.size) truncated = true
        val json = JsonObject().apply {
            add("selectedFoodCategories", JsonArray().apply { categories.forEach(::add) })
            addProperty("overallHeifaScore", profile.overallHeifaScore.requireFinite())
            addProperty("fruitHeifaScore", profile.fruitHeifaScore.requireFinite())
            addProperty("vegetableHeifaScore", profile.vegetableHeifaScore.requireFinite())
        }
        return SanitizedResult(json.toString(), truncated)
    }

    private fun sanitizeHistory(result: AgentFunctionResult): SanitizedResult {
        val history = (result.result as? AgentToolResult.IntakeHistory)?.days ?: fail()
        val (startDate, endDate) = parseRangeArguments(result.canonicalArgumentsJson)
        val allDays = history.sortedBy(AgentDailyIntake::date)
        if (allDays.any { it.date !in startDate..endDate }) fail()
        val keptDays = allDays.take(MAX_HISTORY_DAYS)
        var truncated = keptDays.size != allDays.size
        var json = JsonObject().apply {
            add("days", JsonArray().apply { keptDays.forEach { add(it.toJson()) } })
        }
        if (json.toString().utf8Size() > MAX_ITEM_RESULT_JSON_BYTES) {
            json = aggregateHistory(keptDays)
            truncated = true
        }
        return SanitizedResult(json.toString(), truncated)
    }

    private fun sanitizeTrend(result: AgentFunctionResult): SanitizedResult {
        val trend = (result.result as? AgentToolResult.NutritionTrend)?.trend ?: fail()
        parseRangeArguments(result.canonicalArgumentsJson)
        if (trend.observedDays !in 0..MAX_HISTORY_DAYS) fail()
        val json = JsonObject().apply {
            addProperty("observedDays", trend.observedDays)
            addProperty(
                "averageFruitVegetableServings",
                trend.averageFruitVegetableServings.requireFinite()
            )
            addProperty("averageWaterMl", trend.averageWaterMl.requireFinite())
            addProperty("averageSugarG", trend.averageSugarG.requireFinite())
            addProperty("fruitVegetableDirection", trend.fruitVegetableDirection.name)
        }
        return SanitizedResult(json.toString(), false)
    }

    private fun sanitizeFruit(result: AgentFunctionResult): SanitizedResult {
        val arguments = parseObject(result.canonicalArgumentsJson)
        arguments.requireExactKeys(setOf("query"))
        val query = arguments.strictString("query")
        rejectPii(query)
        if (query.length !in 1..MAX_FRUIT_QUERY_LENGTH || !FRUIT_QUERY_PATTERN.matches(query)) fail()
        val boundedQuery = query.boundedString()
        val json = JsonObject().apply {
            addProperty("query", boundedQuery.first)
            when (val fruit = result.result) {
                is AgentToolResult.FruitNutrition -> {
                    addProperty("found", true)
                    addProperty("calories", fruit.calories.requireFinite())
                    addProperty("fat", fruit.fat.requireFinite())
                    addProperty("sugar", fruit.sugar.requireFinite())
                    addProperty("carbohydrates", fruit.carbohydrates.requireFinite())
                    addProperty("protein", fruit.protein.requireFinite())
                }
                AgentToolResult.FruitNotFound -> addProperty("found", false)
                else -> fail()
            }
        }
        return SanitizedResult(json.toString(), boundedQuery.second)
    }

    private fun AgentDailyIntake.toJson() = JsonObject().apply {
        if (recordCount < 0) fail()
        addProperty("date", date.toString())
        addProperty("recordCount", recordCount)
        addProperty("vegetablesServings", vegetablesServings.requireFinite())
        addProperty("fruitServings", fruitServings.requireFinite())
        addProperty("grainsServings", grainsServings.requireFinite())
        addProperty("meatServings", meatServings.requireFinite())
        addProperty("dairyServings", dairyServings.requireFinite())
        addProperty("discretionaryServings", discretionaryServings.requireFinite())
        addProperty("sodiumMg", sodiumMg.requireFinite())
        addProperty("alcoholStandardDrinks", alcoholStandardDrinks.requireFinite())
        addProperty("waterMl", waterMl.requireFinite())
        addProperty("sugarG", sugarG.requireFinite())
        addProperty("saturatedFatG", saturatedFatG.requireFinite())
        addProperty("unsaturatedFatG", unsaturatedFatG.requireFinite())
    }

    private fun aggregateHistory(days: List<AgentDailyIntake>): JsonObject = JsonObject().apply {
        addProperty("aggregation", "daily_average")
        addProperty("observedDays", days.size)
        if (days.isNotEmpty()) {
            addProperty("startDate", days.first().date.toString())
            addProperty("endDate", days.last().date.toString())
        }
        addProperty("totalRecordCount", days.sumOf(AgentDailyIntake::recordCount))
        addProperty("averageVegetablesServings", days.averageOf(AgentDailyIntake::vegetablesServings))
        addProperty("averageFruitServings", days.averageOf(AgentDailyIntake::fruitServings))
        addProperty("averageGrainsServings", days.averageOf(AgentDailyIntake::grainsServings))
        addProperty("averageMeatServings", days.averageOf(AgentDailyIntake::meatServings))
        addProperty("averageDairyServings", days.averageOf(AgentDailyIntake::dairyServings))
        addProperty(
            "averageDiscretionaryServings",
            days.averageOf(AgentDailyIntake::discretionaryServings)
        )
        addProperty("averageSodiumMg", days.averageOf(AgentDailyIntake::sodiumMg))
        addProperty(
            "averageAlcoholStandardDrinks",
            days.averageOf(AgentDailyIntake::alcoholStandardDrinks)
        )
        addProperty("averageWaterMl", days.averageOf(AgentDailyIntake::waterMl))
        addProperty("averageSugarG", days.averageOf(AgentDailyIntake::sugarG))
        addProperty("averageSaturatedFatG", days.averageOf(AgentDailyIntake::saturatedFatG))
        addProperty("averageUnsaturatedFatG", days.averageOf(AgentDailyIntake::unsaturatedFatG))
    }

    private fun List<AgentDailyIntake>.averageOf(selector: (AgentDailyIntake) -> Double): Double =
        if (isEmpty()) 0.0 else sumOf { selector(it).requireFinite() } / size

    private fun parseRangeArguments(rawJson: String): Pair<LocalDate, LocalDate> {
        val arguments = parseObject(rawJson)
        arguments.requireExactKeys(setOf("startDate", "endDate"))
        val start = try {
            LocalDate.parse(arguments.strictString("startDate"))
        } catch (_: DateTimeException) {
            fail()
        }
        val end = try {
            LocalDate.parse(arguments.strictString("endDate"))
        } catch (_: DateTimeException) {
            fail()
        }
        if (ChronoUnit.DAYS.between(start, end) + 1 !in 1..MAX_HISTORY_DAYS.toLong()) fail()
        return start to end
    }

    private fun parseObject(rawJson: String): JsonObject = try {
        JsonParser.parseString(rawJson).takeIf(JsonElement::isJsonObject)?.asJsonObject ?: fail()
    } catch (_: JsonSyntaxException) {
        fail()
    } catch (_: IllegalStateException) {
        fail()
    }

    private fun JsonObject.requireExactKeys(expected: Set<String>) {
        if (keySet() != expected) fail()
    }

    private fun JsonObject.strictString(key: String): String = get(key)?.takeIf {
        it.isJsonPrimitive && it.asJsonPrimitive.isString
    }?.asString?.trim()?.takeIf(String::isNotEmpty) ?: fail()

    private fun Double.requireFinite(): Double = takeIf { isFinite() && this >= 0.0 }
        ?.let { if (it == 0.0) 0.0 else it }
        ?: fail()

    private fun String.boundedString(): Pair<String, Boolean> =
        if (length <= MAX_STRING_LENGTH) this to false else take(MAX_STRING_LENGTH) to true

    private fun rejectPii(value: String) {
        if (containsForbiddenData(value)) fail()
    }

    private fun containsForbiddenJsonData(value: String): Boolean =
        parseObject(value).containsForbiddenJsonData()

    private fun JsonElement.containsForbiddenJsonData(): Boolean = when {
        isJsonObject -> asJsonObject.entrySet().any { (key, child) ->
            containsForbiddenData(key) || child.containsForbiddenJsonData()
        }
        isJsonArray -> asJsonArray.any { child -> child.containsForbiddenJsonData() }
        isJsonPrimitive && asJsonPrimitive.isString -> containsForbiddenData(asString)
        else -> false
    }

    private fun containsForbiddenData(value: String): Boolean =
        EMAIL_PATTERN.containsMatchIn(value) ||
            SECRET_PATTERN.containsMatchIn(value) ||
            PHONE_PATTERN.findAll(value).any { match ->
                val candidate = match.value.trim()
                !ISO_DATE_PATTERN.matches(candidate)
            }

    private fun String.utf8Size(): Int = toByteArray(Charsets.UTF_8).size

    private fun fail(): Nothing = throw AgentEvidenceBundleException()

    private data class SanitizedResult(val json: String, val truncated: Boolean)

    companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_STRING_LENGTH = 120
        const val MAX_HISTORY_DAYS = 31
        const val MAX_ITEM_RESULT_JSON_BYTES = 6_000
        const val MAX_TOTAL_JSON_BYTES = 28_000
        private const val MAX_ARGUMENTS_JSON_LENGTH = 2_048
        private const val MAX_PROFILE_CATEGORIES = 9
        private const val MAX_FRUIT_QUERY_LENGTH = 50
        private val ALLOWED_FOOD_CATEGORIES = setOf(
            "Fruits",
            "Vegetables",
            "Grains",
            "Red Meat",
            "Seafood",
            "Poultry",
            "Fish",
            "Eggs",
            "Nuts/Seeds"
        )
        private val FRUIT_QUERY_PATTERN = Regex("[\\p{L}]+(?:[ '-][\\p{L}]+)*")
        private val EMAIL_PATTERN = Regex(
            "[A-Z0-9._%+-]+@[A-Z0-9.-]+\\.[A-Z]{2,}",
            RegexOption.IGNORE_CASE
        )
        private val PHONE_PATTERN = Regex("(?<!\\d)(?:\\+?\\d[ -]?){8,15}(?!\\d)")
        private val ISO_DATE_PATTERN = Regex("\\d{4}-\\d{2}-\\d{2}")
        private val SECRET_PATTERN = Regex(
            "\\b(?:password|debug[ _-]?token|api[ _-]?key|AIza[0-9A-Za-z_-]+|" +
                "firebase[ _-]?call[ _-]?id|user[ _-]?id)\\b",
            RegexOption.IGNORE_CASE
        )
    }
}
