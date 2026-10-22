package com.example.nutritrack.agent

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentToolRegistryTest {
    private val registry = AgentToolRegistry()

    @Test
    fun registry_exposesOnlyFourReadOnlyTools() {
        assertEquals(
            setOf(
                "get_nutrition_profile",
                "get_intake_history",
                "calculate_nutrition_trend",
                "search_fruit_nutrition"
            ),
            registry.supportedToolNames
        )
    }

    @Test
    fun decoder_rejectsUnknownToolAndUserIdParameter() {
        assertRejection(AgentToolErrorCode.UNKNOWN_TOOL) {
            registry.decode(AgentToolInvocation("delete_food_intake", "{}"))
        }
        assertRejection(AgentToolErrorCode.USER_ID_NOT_ALLOWED) {
            registry.decode(
                AgentToolInvocation(
                    "get_intake_history",
                    """{"startDate":"2026-09-01","endDate":"2026-09-02","userId":2}"""
                )
            )
        }
    }

    @Test
    fun dateRange_accepts31DaysAndRejectsLongerOrReversedRanges() {
        val decoded = registry.decode(
            AgentToolInvocation(
                "get_intake_history",
                """{"startDate":"2026-08-01","endDate":"2026-08-31"}"""
            )
        )
        assertTrue(decoded.input is AgentToolInput.GetIntakeHistory)

        assertRejection(AgentToolErrorCode.DATE_RANGE_EXCEEDED) {
            registry.decode(
                AgentToolInvocation(
                    "calculate_nutrition_trend",
                    """{"startDate":"2026-08-01","endDate":"2026-09-01"}"""
                )
            )
        }
        assertRejection(AgentToolErrorCode.INVALID_ARGUMENTS) {
            registry.decode(
                AgentToolInvocation(
                    "get_intake_history",
                    """{"startDate":"2026-09-02","endDate":"2026-09-01"}"""
                )
            )
        }
    }

    @Test
    fun decoder_rejectsMalformedExtraAndInvalidFruitArguments() {
        assertRejection(AgentToolErrorCode.INVALID_ARGUMENTS) {
            registry.decode(AgentToolInvocation("get_nutrition_profile", "not-json"))
        }
        assertRejection(AgentToolErrorCode.INVALID_ARGUMENTS) {
            registry.decode(
                AgentToolInvocation("get_nutrition_profile", """{"unexpected":"value"}""")
            )
        }
        listOf("", "apple123", "../apple", "a".repeat(51)).forEach { query ->
            assertRejection(AgentToolErrorCode.INVALID_ARGUMENTS) {
                registry.decode(
                    AgentToolInvocation("search_fruit_nutrition", """{"query":"$query"}""")
                )
            }
        }
    }

    @Test
    fun canonicalArguments_containOnlyWhitelistedToolFields() {
        val decoded = registry.decode(
            AgentToolInvocation("search_fruit_nutrition", """{"query":"  Dragon Fruit  "}""")
        )

        assertEquals("""{"query":"Dragon Fruit"}""", decoded.canonicalArgumentsJson)
        assertFalse(decoded.canonicalArgumentsJson.contains("userId"))
    }

    @Test
    fun trendCalculator_isDeterministicAndHandlesNoData() {
        val days = listOf(
            intake(LocalDate.of(2026, 9, 1), fruit = 1.0, vegetables = 1.0),
            intake(LocalDate.of(2026, 9, 2), fruit = 2.0, vegetables = 2.0),
            intake(LocalDate.of(2026, 9, 3), fruit = 3.0, vegetables = 3.0)
        )

        val first = NutritionTrendCalculator.calculate(days)
        val second = NutritionTrendCalculator.calculate(days.reversed())

        assertEquals(first, second)
        assertEquals(NutritionTrendDirection.INCREASING, first.fruitVegetableDirection)
        assertEquals(4.0, first.averageFruitVegetableServings, 0.0)
        assertEquals(NutritionTrendDirection.NO_DATA, NutritionTrendCalculator.calculate(emptyList()).fruitVegetableDirection)
    }

    @Test
    fun auditJson_omitsIdentityAndFreeTextIntakeFields() {
        val privateMealText = "Private Person 0400000000 person@example.com"
        val json = AgentAuditJson.encode(
            AgentToolResult.IntakeHistory(
                listOf(intake(LocalDate.of(2026, 9, 1), fruit = 1.0, vegetables = 2.0))
            )
        )

        assertFalse(json.contains("userId"))
        assertFalse(json.contains("phoneNumber"))
        assertFalse(json.contains("email"))
        assertFalse(json.contains("password"))
        assertFalse(json.contains(privateMealText))
    }

    private fun intake(date: LocalDate, fruit: Double, vegetables: Double) = AgentDailyIntake(
        date = date,
        recordCount = 1,
        vegetablesServings = vegetables,
        fruitServings = fruit,
        grainsServings = 1.0,
        meatServings = 0.0,
        dairyServings = 0.0,
        discretionaryServings = 0.0,
        sodiumMg = 100.0,
        alcoholStandardDrinks = 0.0,
        waterMl = 500.0,
        sugarG = 2.0,
        saturatedFatG = 1.0,
        unsaturatedFatG = 1.0
    )

    private fun assertRejection(
        expected: AgentToolErrorCode,
        block: () -> Unit
    ) {
        val failure = runCatching(block).exceptionOrNull()
        assertTrue(failure is AgentToolRejection)
        assertEquals(expected, (failure as AgentToolRejection).code)
    }
}
