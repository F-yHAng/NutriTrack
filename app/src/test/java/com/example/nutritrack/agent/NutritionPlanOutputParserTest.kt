package com.example.nutritrack.agent

import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class NutritionPlanOutputParserTest {
    private val parser = NutritionPlanOutputParser()
    private val request = NutritionPlanningRequest(
        planningNeed = "Create a balanced plan from my saved nutrition data.",
        goal = NutritionPlanningGoal.GENERAL_BALANCE,
        validFrom = LocalDate.parse("2026-09-13"),
        validTo = LocalDate.parse("2026-09-19")
    )

    @Test
    fun validOutput_isNormalizedAndProducesDraftInput() {
        val parsed = parser.parse(validJson(), request, setOf("call-1"))

        assertEquals(1, parsed.plan.schemaVersion)
        assertEquals(NutritionRecommendationCategory.HYDRATION, parsed.plan.recommendations.single().category)
        assertEquals(listOf("call-1"), parsed.plan.evidenceToolCallIds)
        assertEquals(parsed.normalizedJson, parsed.draftInput.contentJson)
        assertFalse(parsed.normalizedJson.contains("```"))
    }

    @Test
    fun malformedOrMarkdownWrappedJson_isRejected() {
        listOf("not-json", "```json\n${validJson()}\n```").forEach { value ->
            assertReason(NutritionPlanValidationError.MALFORMED_JSON) {
                parser.parse(value, request, setOf("call-1"))
            }
        }
    }

    @Test
    fun unknownFieldsAndEnums_areRejected() {
        assertReason(NutritionPlanValidationError.UNKNOWN_FIELD) {
            parser.parse(
                validJson().replace(
                    "\"summary\": \"Steady changes\"",
                    "\"summary\": \"Steady changes\", \"extra\": true"
                ),
                request,
                setOf("call-1")
            )
        }
        assertReason(NutritionPlanValidationError.INVALID_ENUM) {
            parser.parse(
                validJson().replace("\"HYDRATION\"", "\"MEDICATION\""),
                request,
                setOf("call-1")
            )
        }
    }

    @Test
    fun invalidNumericRangeAndDate_areRejected() {
        assertReason(NutritionPlanValidationError.INVALID_NUMBER) {
            parser.parse(
                validJson().replace("1800", "12000"),
                request,
                setOf("call-1")
            )
        }
        assertReason(NutritionPlanValidationError.INVALID_DATE) {
            parser.parse(
                validJson().replace("2026-09-19", "2026-09-20"),
                request,
                setOf("call-1")
            )
        }
    }

    @Test
    fun missingOrForeignEvidence_isRejected() {
        listOf(emptySet(), setOf("another-run-call")).forEach { ids ->
            assertReason(NutritionPlanValidationError.INVALID_EVIDENCE) {
                parser.parse(validJson(), request, ids)
            }
        }
    }

    @Test
    fun temporaryEvidenceReference_mapsToInternalAuditIdAfterStrictValidation() {
        val aliasedJson = validJson().replace(
            "\"evidenceToolCallIds\": [\"call-1\"]",
            "\"evidenceToolCallIds\": [\"e1\"]"
        )

        val parsed = parser.parse(
            aliasedJson,
            request,
            mapOf("e1" to "internal-call-42")
        )

        assertEquals(listOf("internal-call-42"), parsed.plan.evidenceToolCallIds)
        assertFalse(parsed.normalizedJson.contains("\"e1\""))
        assertFalse(parsed.normalizedJson.contains("call-1"))
    }

    @Test
    fun unknownDuplicateOrInternalEvidenceReference_isRejected() {
        val mapping = mapOf("e1" to "internal-call-42")
        listOf(
            "[\"e9\"]",
            "[\"e1\", \"e1\"]",
            "[\"internal-call-42\"]"
        ).forEach { evidence ->
            assertReason(NutritionPlanValidationError.INVALID_EVIDENCE) {
                parser.parse(
                    validJson().replace("[\"call-1\"]", evidence),
                    request,
                    mapping
                )
            }
        }
    }

    @Test
    fun temporaryEvidenceAliasMap_mustUseLowercaseContiguousE1ThroughE4() {
        val validFourAliases = linkedMapOf(
            "e1" to "internal-call-1",
            "e2" to "internal-call-2",
            "e3" to "internal-call-3",
            "e4" to "internal-call-4"
        )
        val fourthAliasJson = validJson().replace("[\"call-1\"]", "[\"e4\"]")

        val parsed = parser.parse(fourthAliasJson, request, validFourAliases)

        assertEquals(listOf("internal-call-4"), parsed.plan.evidenceToolCallIds)

        listOf(
            mapOf("E1" to "internal-call-1"),
            mapOf("e2" to "internal-call-2"),
            linkedMapOf("e1" to "internal-call-1", "e3" to "internal-call-3"),
            mapOf("call-1" to "internal-call-1"),
            linkedMapOf(
                "e1" to "internal-call-1",
                "e2" to "internal-call-2",
                "e3" to "internal-call-3",
                "e4" to "internal-call-4",
                "e5" to "internal-call-5"
            ),
            linkedMapOf("e1" to "internal-call-1", "e2" to "internal-call-1")
        ).forEach { invalidMapping ->
            assertReason(NutritionPlanValidationError.INVALID_EVIDENCE) {
                parser.parse(
                    validJson().replace("[\"call-1\"]", "[\"e1\"]"),
                    request,
                    invalidMapping
                )
            }
        }
    }

    @Test
    fun isoDatesAndNegatedSafetyLanguage_areAllowedButPiiAndMedicalActionsRemainBlocked() {
        val safe = validJson()
            .replace(
                "Steady changes",
                "Follow this plan from 2026-09-13 to 2026-09-19"
            )
            .replace(
                "Drink water regularly",
                "This guidance does not diagnose or treat disease and does not change medication"
            )
        parser.parse(safe, request, setOf("call-1"))
        parser.parse(
            validJson().replace(
                "General information only; seek qualified care for personal medical needs.",
                "This is not a substitute for medical diagnosis or treatment."
            ),
            request,
            setOf("call-1")
        )
        parser.parse(
            validJson().replace(
                "General information only; seek qualified care for personal medical needs.",
                "This is general guidance, not diagnosis, treatment, medication advice, or a guarantee."
            ),
            request,
            setOf("call-1")
        )

        listOf(
            "Call +61 400 000 901 for help",
            "This plan guarantees a cure",
            "Stop medication immediately",
            "Do not provide medication advice and stop medication",
            "Do not skip breakfast and stop medication",
            "不要漏餐然后停药"
        ).forEach { unsafe ->
            assertReason(NutritionPlanValidationError.UNSAFE_CONTENT) {
                parser.parse(
                    validJson().replace("Steady changes", unsafe),
                    request,
                    setOf("call-1")
                )
            }
        }
        assertReason(NutritionPlanValidationError.UNSAFE_CONTENT) {
            parser.parse(
                validJson().replace(
                    "General information only; seek qualified care for personal medical needs.",
                    "This disclaimer guarantees a cure."
                ),
                request,
                setOf("call-1")
            )
        }
    }

    @Test
    fun piiAndMedicalClaims_areRejected() {
        assertReason(NutritionPlanValidationError.UNSAFE_CONTENT) {
            parser.parse(
                validJson().replace("Steady changes", "Contact private@example.com"),
                request,
                setOf("call-1")
            )
        }
        assertReason(NutritionPlanValidationError.UNSAFE_CONTENT) {
            parser.parse(
                validJson().replace("Drink water regularly", "Stop medication immediately"),
                request,
                setOf("call-1")
            )
        }
    }

    @Test
    fun intendedAsSubstituteDisclaimer_isNotAMedicalClaim_butPositiveAndSeparateActionsAre() {
        parser.parse(validJson().replace(
            "General information only; seek qualified care for personal medical needs.",
            "This guidance is not intended as a substitute for medical diagnosis or treatment."
        ), request, setOf("call-1"))
        listOf(
            "This guidance is intended as a substitute for medical diagnosis or treatment.",
            "This guidance is not intended as a substitute for medical diagnosis and stop medication.",
            "This guidance is not intended as a substitute for medical diagnosis. Stop medication."
        ).forEach { value ->
            assertReason(NutritionPlanValidationError.UNSAFE_CONTENT) {
                parser.parse(validJson().replace("Steady changes", value), request, setOf("call-1"))
            }
        }
    }

    @Test
    fun safetyDiagnostics_coverEveryTextFieldWithoutIncludingMatchedContent() {
        val fields = listOf(
            "Steady changes" to "summary",
            "Regular hydration" to "recommendations[0].title",
            "Drink water regularly" to "recommendations[0].action",
            "Adjust gradually" to "cautions[0]",
            "General information only; seek qualified care for personal medical needs." to "disclaimer"
        )
        val rules = listOf(
            "private@example.com" to PlanSafetyRule.PII_EMAIL,
            "debug token" to PlanSafetyRule.PII_CREDENTIAL,
            "+61 400 000 901" to PlanSafetyRule.PII_PHONE,
            "diagnose disease" to PlanSafetyRule.MEDICAL_DIAGNOSIS,
            "treat disease" to PlanSafetyRule.MEDICAL_TREATMENT,
            "prescribe drugs" to PlanSafetyRule.MEDICAL_PRESCRIPTION,
            "cure disease" to PlanSafetyRule.MEDICAL_CURE,
            "guarantee results" to PlanSafetyRule.MEDICAL_GUARANTEE,
            "prevent disease" to PlanSafetyRule.MEDICAL_DISEASE_PREVENTION,
            "stop medication" to PlanSafetyRule.MEDICAL_MEDICATION_CHANGE
        )
        fields.forEach { (original, path) ->
            rules.forEach { (content, rule) ->
                val error = assertThrows(NutritionPlanValidationException::class.java) {
                    parser.parse(validJson().replace(original, content), request, setOf("call-1"))
                }
                assertEquals(NutritionPlanValidationError.UNSAFE_CONTENT, error.reason)
                assertEquals(rule, error.safetyDiagnostic?.ruleId)
                assertEquals(path, error.safetyDiagnostic?.fieldPath)
                assertEquals(if (rule.name.startsWith("PII_"))
                    PlanSafetyCategory.IDENTITY_OR_CREDENTIAL else PlanSafetyCategory.UNNEGATED_MEDICAL_CLAIM,
                    error.safetyDiagnostic?.category)
                assertFalse(error.message.orEmpty().contains(content))
            }
        }
        val ambiguous = assertThrows(NutritionPlanValidationException::class.java) {
            parser.parse(validJson().replace("Steady changes", "Do not skip breakfast and stop medication"),
                request, setOf("call-1"))
        }
        assertEquals(PlanSafetyCategory.UNRECOGNIZED_NEGATION_SCOPE, ambiguous.safetyDiagnostic?.category)
    }

    private fun validJson() = """
        {
          "schemaVersion": 1,
          "summary": "Steady changes",
          "validFrom": "2026-09-13",
          "validTo": "2026-09-19",
          "recommendations": [
            {
              "category": "HYDRATION",
              "title": "Regular hydration",
              "action": "Drink water regularly",
              "targetValue": 1800,
              "targetUnit": "MILLILITERS_PER_DAY"
            }
          ],
          "cautions": ["Adjust gradually"],
          "disclaimer": "General information only; seek qualified care for personal medical needs.",
          "evidenceToolCallIds": ["call-1"]
        }
    """.trimIndent()

    private fun assertReason(
        reason: NutritionPlanValidationError,
        block: () -> Unit
    ) {
        val error = assertThrows(NutritionPlanValidationException::class.java, block)
        assertEquals(reason, error.reason)
    }
}
