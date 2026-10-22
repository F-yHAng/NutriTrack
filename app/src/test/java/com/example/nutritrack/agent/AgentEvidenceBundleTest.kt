package com.example.nutritrack.agent

import com.google.gson.JsonParser
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentEvidenceBundleTest {
    private val builder = AgentEvidenceBundleBuilder()
    private val request = NutritionPlanningRequest(
        planningNeed = "Create a balanced plan from my saved nutrition data.",
        goal = NutritionPlanningGoal.GENERAL_BALANCE,
        validFrom = LocalDate.parse("2026-09-13"),
        validTo = LocalDate.parse("2026-09-19")
    )

    @Test
    fun aliasesAndSanitizedJson_areDeterministicAndNeverExposePiiOrAuditIds() {
        val first = builder.build(request, completeEvidenceResults(reversePayloadOrder = false))
        val second = builder.build(request, completeEvidenceResults(reversePayloadOrder = true))

        assertEquals(listOf("e1", "e2", "e3", "e4"), first.bundle.items.map { it.alias })
        assertEquals(
            linkedMapOf(
                "e1" to "audit-profile-42",
                "e2" to "audit-history-43",
                "e3" to "audit-trend-44",
                "e4" to "audit-fruit-45"
            ),
            first.auditToolCallIdsByAlias
        )
        assertEquals(first.bundle.canonicalJson, second.bundle.canonicalJson)
        assertEquals(request.planningNeed, first.bundle.planningNeed)
        assertEquals(
            request.planningNeed,
            JsonParser.parseString(first.bundle.canonicalJson)
                .asJsonObject["planningNeed"].asString
        )
        assertTrue(
            first.bundle.canonicalJson.toByteArray(Charsets.UTF_8).size <=
                AgentEvidenceBundleBuilder.MAX_TOTAL_JSON_BYTES
        )
        listOf(
            "audit-profile-42",
            "audit-history-43",
            "audit-trend-44",
            "audit-fruit-45",
            "Private Person",
            "private@example.com",
            "0400000901",
            "firebase-call-id-secret",
            "questionnaireResponses",
            "userId"
        ).forEach { forbidden ->
            assertFalse(
                "Model-visible evidence leaked $forbidden",
                first.bundle.canonicalJson.contains(forbidden, ignoreCase = true)
            )
        }
    }

    @Test
    fun planningNeedWithPiiNeverEntersModelEvidence() {
        val unsafe = request.copy(planningNeed = "Send this to person@example.com")

        assertThrows(AgentEvidenceBundleException::class.java) {
            builder.build(unsafe, listOf(completeEvidenceResults(false).first()))
        }
    }

    @Test
    fun everyNumericEvidenceShape_rejectsNonFiniteValues() {
        val invalidResults = listOf(
            functionResult(
                toolName = AgentToolName.GET_NUTRITION_PROFILE,
                auditId = "invalid-profile",
                argumentsJson = "{}",
                result = AgentToolResult.NutritionProfile(profile().copy(overallHeifaScore = Double.NaN))
            ),
            functionResult(
                toolName = AgentToolName.GET_INTAKE_HISTORY,
                auditId = "invalid-history",
                argumentsJson = rangeArguments("2026-09-01", "2026-09-01"),
                result = AgentToolResult.IntakeHistory(
                    listOf(dailyIntake(LocalDate.parse("2026-09-01")).copy(waterMl = Double.POSITIVE_INFINITY))
                )
            ),
            functionResult(
                toolName = AgentToolName.CALCULATE_NUTRITION_TREND,
                auditId = "invalid-trend",
                argumentsJson = rangeArguments("2026-09-01", "2026-09-01"),
                result = AgentToolResult.NutritionTrend(
                    trend().copy(averageSugarG = Double.NEGATIVE_INFINITY)
                )
            ),
            functionResult(
                toolName = AgentToolName.SEARCH_FRUIT_NUTRITION,
                auditId = "invalid-fruit",
                argumentsJson = "{\"query\":\"Apple\"}",
                result = fruit().copy(protein = Double.NaN)
            )
        )

        invalidResults.forEach { invalid ->
            assertThrows(AgentEvidenceBundleException::class.java) {
                builder.build(request, listOf(invalid))
            }
        }
    }

    @Test
    fun oversizedHistory_isAggregatedDeterministicallyAndRecordsTruncationWithinLimits() {
        val orderedDays = (1..31).map { day ->
            dailyIntake(LocalDate.of(2026, 8, day), seed = day.toDouble())
        }
        val ordered = builder.build(
            request,
            listOf(historyResult("history-large", orderedDays))
        )
        val reversed = builder.build(
            request,
            listOf(historyResult("history-large", orderedDays.reversed()))
        )

        assertEquals(ordered.bundle.canonicalJson, reversed.bundle.canonicalJson)
        assertTrue(ordered.bundle.truncated)
        assertTrue(ordered.bundle.items.single().truncated)
        assertTrue(
            ordered.bundle.items.single().resultJson.toByteArray(Charsets.UTF_8).size <=
                AgentEvidenceBundleBuilder.MAX_ITEM_RESULT_JSON_BYTES
        )
        assertTrue(
            ordered.bundle.canonicalJson.toByteArray(Charsets.UTF_8).size <=
                AgentEvidenceBundleBuilder.MAX_TOTAL_JSON_BYTES
        )

        val aggregate = JsonParser.parseString(ordered.bundle.items.single().resultJson).asJsonObject
        assertEquals("daily_average", aggregate["aggregation"].asString)
        assertEquals(31, aggregate["observedDays"].asInt)
        assertEquals("2026-08-01", aggregate["startDate"].asString)
        assertEquals("2026-08-31", aggregate["endDate"].asString)
        assertTrue(ordered.bundle.canonicalJson.contains("\"truncated\":true"))
    }

    private fun completeEvidenceResults(reversePayloadOrder: Boolean): List<AgentFunctionResult> {
        val profile = profile().let { value ->
            if (reversePayloadOrder) {
                value.copy(selectedFoodCategories = value.selectedFoodCategories.reversed())
            } else {
                value
            }
        }
        val days = listOf(
            dailyIntake(LocalDate.parse("2026-09-01"), 1.0),
            dailyIntake(LocalDate.parse("2026-09-02"), 2.0)
        ).let { if (reversePayloadOrder) it.reversed() else it }
        return listOf(
            functionResult(
                AgentToolName.GET_NUTRITION_PROFILE,
                "audit-profile-42",
                "{}",
                AgentToolResult.NutritionProfile(profile)
            ),
            functionResult(
                AgentToolName.GET_INTAKE_HISTORY,
                "audit-history-43",
                rangeArguments("2026-09-01", "2026-09-02"),
                AgentToolResult.IntakeHistory(days)
            ),
            functionResult(
                AgentToolName.CALCULATE_NUTRITION_TREND,
                "audit-trend-44",
                rangeArguments("2026-09-01", "2026-09-02"),
                AgentToolResult.NutritionTrend(trend())
            ),
            functionResult(
                AgentToolName.SEARCH_FRUIT_NUTRITION,
                "audit-fruit-45",
                "{\"query\":\"Apple\"}",
                fruit()
            )
        )
    }

    private fun profile() = AgentNutritionProfile(
        selectedFoodCategories = listOf("Vegetables", "Fruits"),
        persona = "Private Person private@example.com",
        mainMealTime = "0400000901",
        sleepTime = "firebase-call-id-secret",
        wakeTime = "questionnaireResponses",
        overallHeifaScore = 61.5,
        fruitHeifaScore = 7.0,
        vegetableHeifaScore = 8.0
    )

    private fun trend() = AgentNutritionTrend(
        observedDays = 2,
        averageFruitVegetableServings = 4.0,
        averageWaterMl = 1_800.0,
        averageSugarG = 32.0,
        fruitVegetableDirection = NutritionTrendDirection.STABLE
    )

    private fun fruit() = AgentToolResult.FruitNutrition(
        name = "Private Person",
        family = "private@example.com firebase-call-id-secret",
        calories = 52.0,
        fat = 0.2,
        sugar = 10.3,
        carbohydrates = 13.8,
        protein = 0.3
    )

    private fun historyResult(
        auditId: String,
        days: List<AgentDailyIntake>
    ) = functionResult(
        toolName = AgentToolName.GET_INTAKE_HISTORY,
        auditId = auditId,
        argumentsJson = rangeArguments("2026-08-01", "2026-08-31"),
        result = AgentToolResult.IntakeHistory(days)
    )

    private fun functionResult(
        toolName: AgentToolName,
        auditId: String,
        argumentsJson: String,
        result: AgentToolResult
    ) = AgentFunctionResult(
        toolName = toolName.wireName,
        auditToolCallId = auditId,
        canonicalArgumentsJson = argumentsJson,
        result = result
    )

    private fun rangeArguments(start: String, end: String) =
        "{\"startDate\":\"$start\",\"endDate\":\"$end\"}"

    private fun dailyIntake(
        date: LocalDate,
        seed: Double = 1.0
    ) = AgentDailyIntake(
        date = date,
        recordCount = 1,
        vegetablesServings = seed,
        fruitServings = seed + 0.1,
        grainsServings = seed + 0.2,
        meatServings = seed + 0.3,
        dairyServings = seed + 0.4,
        discretionaryServings = seed + 0.5,
        sodiumMg = seed + 100.0,
        alcoholStandardDrinks = seed + 0.6,
        waterMl = seed + 500.0,
        sugarG = seed + 2.0,
        saturatedFatG = seed + 0.7,
        unsaturatedFatG = seed + 0.8
    )
}
