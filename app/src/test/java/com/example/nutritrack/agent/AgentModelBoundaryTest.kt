package com.example.nutritrack.agent

import com.example.nutritrack.data.FirebaseFinishDisposition
import com.example.nutritrack.data.classifyFirebaseFinishReason
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AgentModelBoundaryTest {
    @Test
    fun modelReceivesExactlyFourReadOnlyToolsWithoutUserIdentityParameter() {
        assertEquals(
            listOf(
                "get_nutrition_profile",
                "get_intake_history",
                "calculate_nutrition_trend",
                "search_fruit_nutrition"
            ),
            NutritionAgentToolDeclarations.all.map { it.name }
        )
        assertTrue(
            NutritionAgentToolDeclarations.all
                .flatMap { it.parameters.keys }
                .none { it.equals("userId", ignoreCase = true) }
        )
    }

    @Test
    fun orchestrationLimitsAreBoundedAndCentralized() {
        assertEquals(2, NutritionPlanningAgentConfig.MAX_MODEL_REQUESTS)
        assertEquals(4, NutritionPlanningAgentConfig.MAX_TOOL_CALLS)
        assertEquals(60_000L, NutritionPlanningAgentConfig.MODEL_REQUEST_TIMEOUT_MILLIS)
        assertEquals(
            NutritionPlanningAgentConfig.MODEL_REQUEST_TIMEOUT_MILLIS,
            NutritionPlanningAgentConfig.modelSettings.requestTimeoutMillis
        )
        assertEquals(8_000L, NutritionPlanningAgentConfig.MIN_NON_MODEL_BUDGET_MILLIS)
        assertEquals(
            128_000L,
            NutritionPlanningAgentConfig.TOTAL_TIMEOUT_MILLIS
        )
        assertEquals(
            NutritionPlanningAgentConfig.modelSettings.requestTimeoutMillis *
                NutritionPlanningAgentConfig.MAX_MODEL_REQUESTS +
                NutritionPlanningAgentConfig.MIN_NON_MODEL_BUDGET_MILLIS,
            NutritionPlanningAgentConfig.TOTAL_TIMEOUT_MILLIS
        )
    }

    @Test
    fun firebaseSdkTypesDoNotLeakIntoAgentDomainPackage() {
        val agentDirectory = File(moduleDirectory(), AGENT_SOURCE_PATH)
        assertTrue(agentDirectory.isDirectory)
        agentDirectory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { sourceFile ->
                assertFalse(
                    "Firebase SDK import leaked into ${sourceFile.name}",
                    sourceFile.readText().contains("import com.google.firebase")
                )
            }
    }

    @Test
    fun firebaseBoundaryUsesOnlySanitizedEvidenceBundleForStructuredGeneration() {
        val source = File(moduleDirectory(), FIREBASE_GATEWAY_SOURCE_PATH).readText()

        assertFalse(source.contains("auditToolCallId"))
        assertFalse(source.contains("AgentFunctionResult"))
        assertTrue(source.contains("request.evidenceBundle.canonicalJson"))
        assertTrue(source.contains("responseMimeType = JSON_RESPONSE_MIME_TYPE"))
        assertTrue(source.contains("JSON_RESPONSE_MIME_TYPE = \"application/json\""))
        assertTrue(source.contains("Schema.enumeration(evidenceReferences)"))
    }

    @Test
    fun firebaseProviderCorrelationAndFunctionResponsesAreAbsentFromProductionPath() {
        val module = moduleDirectory()
        val domainDirectory = File(module, AGENT_SOURCE_PATH)
        val productionDirectory = File(module, "src/main/java")

        domainDirectory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { sourceFile ->
                val source = sourceFile.readText()
                assertFalse("Provider id leaked into ${sourceFile.name}", source.contains("modelCallId"))
                assertFalse("Provider id leaked into ${sourceFile.name}", source.contains("providerId"))
            }
        productionDirectory.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { sourceFile ->
                val source = sourceFile.readText()
                assertFalse(
                    "Provider id leaked into ${sourceFile.name}",
                    source.contains("providerId")
                )
                assertFalse(source.contains("FunctionResponsePart"))
            }
    }

    @Test
    fun firebaseAgentUsesHistoricalOneShotChatThenIndependentStructuredRequest() {
        val source = File(moduleDirectory(), FIREBASE_GATEWAY_SOURCE_PATH).readText()

        assertTrue(source.contains("override suspend fun selectTools"))
        assertTrue(source.contains("override suspend fun generateStructuredPlan"))
        assertTrue(source.contains("FirebaseToolSelectionModelFactory"))
        assertTrue(source.contains("FirebasePlanGenerationModelFactory"))
        assertEquals(1, Regex("\\.startChat\\(\\)").findAll(source).count())
        assertEquals(
            1,
            Regex("val prompt = buildToolSelectionPrompt\\(request\\)")
                .findAll(source).count()
        )
        assertEquals(
            1,
            Regex("\\.sendMessage\\(prompt\\)")
                .findAll(source).count()
        )
        assertEquals(
            1,
            Regex("\\.generateContent\\(request\\.evidenceBundle\\.canonicalJson\\)")
                .findAll(source).count()
        )
        assertFalse(source.contains("FunctionResponsePart"))
        assertFalse(source.contains("AutoFunctionDeclaration"))
        assertTrue(source.contains("toolConfig = null"))
        assertFalse(source.contains("FunctionCallingConfig.any"))
    }

    @Test
    fun everyFirebaseFinishReasonIsClassifiedFailClosed() {
        assertEquals(
            FirebaseFinishDisposition.SUCCESS,
            classifyFirebaseFinishReason("STOP")
        )
        listOf(
            "SAFETY",
            "BLOCKLIST",
            "PROHIBITED_CONTENT",
            "SPII",
            "IMAGE_SAFETY",
            "IMAGE_PROHIBITED_CONTENT"
        ).forEach { reason ->
            assertEquals(
                reason,
                FirebaseFinishDisposition.SAFETY_BLOCKED,
                classifyFirebaseFinishReason(reason)
            )
        }
        listOf("RECITATION", "IMAGE_RECITATION", "LANGUAGE").forEach { reason ->
            assertEquals(
                reason,
                FirebaseFinishDisposition.POLICY_REFUSED,
                classifyFirebaseFinishReason(reason)
            )
        }
        listOf(
            null,
            "UNKNOWN",
            "MAX_TOKENS",
            "OTHER",
            "MALFORMED_FUNCTION_CALL",
            "IMAGE_OTHER",
            "NO_IMAGE",
            "UNEXPECTED_TOOL_CALL",
            "TOO_MANY_TOOL_CALLS",
            "MISSING_THOUGHT_SIGNATURE",
            "MALFORMED_RESPONSE",
            "FUTURE_REASON"
        ).forEach { reason ->
            assertEquals(
                reason,
                FirebaseFinishDisposition.INVALID_RESPONSE,
                classifyFirebaseFinishReason(reason)
            )
        }
    }

    @Test
    fun firebaseBoundaryMapsSdkBlockAndStoppedExceptionsBeforeGenericSdkError() {
        val source = File(moduleDirectory(), FIREBASE_GATEWAY_SOURCE_PATH).readText()

        assertTrue(source.contains("catch (_: PromptBlockedException)"))
        assertTrue(source.contains("catch (_: ContentBlockedException)"))
        assertTrue(source.contains("catch (stopped: ResponseStoppedException)"))
        assertTrue(source.contains("catch (error: FirebaseAiSerializationException)"))
        assertTrue(source.contains("error.toLoggedFailure(phase, modelName"))
    }

    private fun moduleDirectory(): File {
        val workingDirectory = File(requireNotNull(System.getProperty("user.dir")))
        return if (File(workingDirectory, "src/main/java").isDirectory) {
            workingDirectory
        } else {
            File(workingDirectory, "app")
        }
    }

    private companion object {
        const val AGENT_SOURCE_PATH =
            "src/main/java/com/example/nutritrack/agent"
        const val FIREBASE_GATEWAY_SOURCE_PATH =
            "src/main/java/com/example/nutritrack/data/FirebaseAgentModelGateway.kt"
    }
}
