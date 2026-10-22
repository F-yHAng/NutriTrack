package com.example.nutritrack.agent

import com.example.nutritrack.data.TOOL_SELECTION_PROMPT_PREFIX
import com.example.nutritrack.data.TOOL_SELECTION_SYSTEM_INSTRUCTION
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.CancellationException
import org.junit.Assert.*
import org.junit.Test

class AgentTraceLoggingTest {
    @Test fun releaseNoopDoesNotEvaluatePayload() {
        AgentTraceLogger.None.record(AgentTraceStage.RAW_PLAN_05) { error("Must not evaluate") }
    }

    @Test fun realPromptTemplateIsVisibleButUserValuesAreNot() {
        val input = JsonObject().apply {
            addProperty("systemInstruction", TOOL_SELECTION_SYSTEM_INSTRUCTION)
            addProperty("prompt", TOOL_SELECTION_PROMPT_PREFIX +
                """{"planningNeed":"Alice has diabetes","goal":"USER_DEFINED","validFrom":"2026-10-18"}""")
        }
        val preview = AgentTraceRedactor.preview(input.toString())
        assertTrue(preview.contains("Select only the declared read-only tools"))
        assertTrue(preview.contains("Never diagnose"))
        assertTrue(preview.contains("planningNeed"))
        listOf("Alice", "diabetes", "USER_DEFINED", "2026-10-18").forEach {
            assertFalse(preview.contains(it))
        }
    }

    @Test fun toolsAndEvidenceKeepStructureNotIdentityHealthOrCredentials() {
        val raw = """{
            "schemaVersion":1,"planningNeed":"Alice alice@example.test +60123456789",
            "items":[{"alias":"e1","toolName":"get_nutrition_profile","result":{
                "overallHeifaScore":87.123,"persona":"Health Devotee",
                "selectedFoodCategories":["Vegetables"],"userId":988401,
                "apiKey":"AIza_FAKE_KEY_SECRET","debugToken":"FAKE_DEBUG_SECRET",
                "Authorization":"Bearer FAKE_AUTH_SECRET"}}],
            "FAKE_SECRET_IN_FIELD_NAME":"private", "truncated":false
        }"""
        val preview = AgentTraceRedactor.preview(raw)
        val parsed = JsonParser.parseString(preview).asJsonObject
        assertEquals(1, parsed["schemaVersion"].asInt)
        assertFalse(parsed["truncated"].asBoolean)
        assertTrue(preview.contains("get_nutrition_profile"))
        assertTrue(preview.contains("e1"))
        assertTrue(preview.contains("overallHeifaScore"))
        listOf("Alice", "alice@", "60123456789", "87.123", "Health Devotee", "Vegetables",
            "988401", "AIza_", "FAKE_DEBUG", "FAKE_AUTH", "FAKE_SECRET_IN_FIELD_NAME").forEach {
            assertFalse("Leaked fixture: $it", preview.contains(it))
        }
    }

    @Test fun nestedToolArgumentsAndResultJsonAreRedactedToo() {
        val input = JsonObject().apply {
            addProperty("toolName", "get_intake_history")
            addProperty("argumentsJson", """{"startDate":"2026-10-18","endDate":"2026-10-19"}""")
            addProperty("resultJson", """{"days":[{"waterMl":1234,"date":"2026-10-18"}]}""")
        }
        val preview = AgentTraceRedactor.preview(input.toString())
        assertTrue(preview.contains("get_intake_history"))
        assertTrue(preview.contains("waterMl"))
        assertFalse(preview.contains("1234"))
        assertFalse(preview.contains("2026-10-18"))
        assertTrue(JsonParser.parseString(preview).asJsonObject["argumentsJson"].isJsonObject)
    }

    @Test fun preValidationPlanKeepsSchemaEnumsAndAliasesButNotBodyOrInternalIds() {
        val raw = """{"schemaVersion":1,"summary":"Alice private health detail",
            "recommendations":[{"category":"HYDRATION","title":"private title",
            "action":"private advice","targetValue":1456,"targetUnit":"MILLILITERS_PER_DAY"}],
            "cautions":["private caution"],"disclaimer":"private disclaimer",
            "evidenceToolCallIds":["e1","internal-audit-id"]}"""
        val preview = AgentTraceRedactor.preview(raw)
        assertTrue(preview.contains("HYDRATION"))
        assertTrue(preview.contains("MILLILITERS_PER_DAY"))
        assertTrue(preview.contains("e1"))
        listOf("Alice", "private", "1456", "internal-audit-id").forEach {
            assertFalse(preview.contains(it))
        }
    }

    @Test fun untrustedNamesEnumsAndLookalikePromptAreNotPrinted() {
        val raw = """{"toolName":"Alice","category":"alice@example.test",
            "fieldPath":"summary.SECRET","schemaVersion":988401,"alias":"e1secret",
            "systemInstruction":"FAKE_KEY","prompt":"FAKE_TOKEN"}"""
        val preview = AgentTraceRedactor.preview(raw)
        listOf("Alice", "alice@", "SECRET", "988401", "e1secret", "FAKE_").forEach {
            assertFalse(preview.contains(it))
        }
    }

    @Test fun invalidOrOversizedRawOutputIsOmittedNeverEchoed() {
        val invalid = AgentTraceRedactor.preview("not JSON: FAKE_KEY alice@example.test")
        assertEquals("[CONTENT_OMITTED: UNPARSEABLE_JSON]", invalid)
        val large = AgentTraceRedactor.preview("FAKE_SECRET".repeat(7000))
        assertEquals("[CONTENT_OMITTED: SIZE_LIMIT]", large)
        val nested = AgentTraceRedactor.preview("[".repeat(100) + "\"FAKE_SECRET\"" + "]".repeat(100))
        assertTrue(nested.contains("DEPTH_LIMIT"))
        assertFalse(nested.contains("FAKE_SECRET"))
    }

    @Test fun validationFailurePreservesOnlyFixedRuleAndSchemaPath() {
        val preview = AgentTraceRedactor.preview("""{
            "status":"FAILED","stage":"FINAL_SAFETY_VALIDATION",
            "errorCode":"FINAL_SAFETY_INVALID","validationError":"UNSAFE_CONTENT",
            "ruleId":"PII_EMAIL","fieldPath":"recommendations[0].action",
            "failureCategory":"IDENTITY_OR_CREDENTIAL","message":"alice@example.test"}""")
        listOf("FAILED", "FINAL_SAFETY_INVALID", "UNSAFE_CONTENT", "PII_EMAIL",
            "recommendations[0].action", "IDENTITY_OR_CREDENTIAL").forEach {
            assertTrue(preview.contains(it))
        }
        assertFalse(preview.contains("alice@"))
    }

    @Test fun stageSequenceAndChunkHeadersAreStableAndChunksAreBounded() {
        val lines = mutableListOf<String>()
        val logger = RedactingAgentTraceLogger(lines::add)
        AgentTraceStage.entries.forEach { stage ->
            logger.record(stage) { """{"status":"PASSED"}""" }
        }
        assertEquals(AgentTraceStage.entries.map { it.name }, lines.map { it.substringBefore(' ') })
        assertTrue(lines.all { "redacted=true" in it })
        lines.clear()
        logger.record(AgentTraceStage.EVIDENCE_04) {
            "[" + List(8) { "{\"days\":[" + List(8) { "{\"waterMl\":1234}" }.joinToString() + "]}" }.joinToString() + "]"
        }
        assertTrue(lines.size > 1)
        assertTrue(lines.all { it.length < 2200 && !it.contains("1234") })
    }

    @Test fun loggerFailuresDoNotChangeBusinessOutcomeButCancellationStillPropagates() {
        val lines = mutableListOf<String>()
        val logger = RedactingAgentTraceLogger(lines::add)
        logger.record(AgentTraceStage.RAW_PLAN_05) { error("FAKE_SECRET") }
        assertTrue(lines.isEmpty())
        RedactingAgentTraceLogger { error("sink unavailable") }
            .record(AgentTraceStage.VALIDATION_06) { "{}" }
        try {
            logger.record(AgentTraceStage.RAW_PLAN_05) { throw CancellationException("cancel") }
            fail("Cancellation must propagate")
        } catch (_: CancellationException) {
            assertTrue(lines.isEmpty())
        }
    }
}
