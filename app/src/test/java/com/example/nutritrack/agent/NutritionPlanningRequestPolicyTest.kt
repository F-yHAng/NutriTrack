package com.example.nutritrack.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NutritionPlanningRequestPolicyTest {
    @Test
    fun normalizesWhitespaceWithoutChangingTheUserMeaning() {
        assertEquals(
            "Improve hydration using my recent intake.",
            NutritionPlanningRequestPolicy.normalizeOrNull(
                "  Improve   hydration\nusing my recent intake.  "
            )
        )
    }

    @Test
    fun rejectsIdentityCredentialAndOversizedInputs() {
        listOf(
            "Send the plan to person@example.com",
            "Use phone 0400 000 123",
            "Include my debug token",
            "Use patient id 12345",
            "Use account-ID ABC-42",
            "x".repeat(NutritionPlanningRequestPolicy.MAX_LENGTH + 1),
            "Improve hydration\u200Bnext week"
        ).forEach { value ->
            assertNull(value, NutritionPlanningRequestPolicy.normalizeOrNull(value))
        }
    }
}
