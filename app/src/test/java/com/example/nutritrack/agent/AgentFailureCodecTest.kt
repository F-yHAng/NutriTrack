package com.example.nutritrack.agent

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class AgentFailureCodecTest {
    @Test
    fun everyStableStageAndCode_roundTripsWithoutDetails() {
        AgentFailureStage.entries.forEach { stage ->
            AgentFailureCode.entries.forEach { code ->
                val failure = AgentFailure(stage, code)
                val encoded = AgentFailureCodec.encode(failure)

                assertEquals(failure, AgentFailureCodec.decode(encoded))
                assertEquals(3, encoded.split('|').size)
            }
        }
    }

    @Test
    fun malformedMetadata_isRejectedAndLegacyCodesRemainReadable() {
        listOf(null, "", "v1|UNKNOWN|MODEL_NETWORK", "v2|TOOL_SELECTION|MODEL_NETWORK")
            .forEach { assertNull(AgentFailureCodec.decode(it)) }

        assertEquals(
            AgentFailure(
                AgentFailureStage.PROCESS_RECOVERY,
                AgentFailureCode.PROCESS_INTERRUPTED
            ),
            AgentFailureCodec.decode("PROCESS_INTERRUPTED")
        )
    }

    @Test
    fun boundedSafetyDiagnostic_roundTripsAndRejectsUntrustedPaths() {
        val failure = AgentFailure(AgentFailureStage.FINAL_SAFETY_VALIDATION,
            AgentFailureCode.FINAL_SAFETY_INVALID,
            PlanSafetyDiagnostic(PlanSafetyRule.MEDICAL_TREATMENT, "disclaimer",
                PlanSafetyCategory.UNRECOGNIZED_NEGATION_SCOPE))
        val encoded = AgentFailureCodec.encode(failure)
        assertEquals(failure, AgentFailureCodec.decode(encoded))
        listOf("disclaimer.private@example.com", "recommendations[8].action", "rawResponse")
            .forEach { assertNull(AgentFailureCodec.decode(encoded.replace("disclaimer", it))) }
        assertNull(AgentFailureCodec.decode(encoded.replace("MEDICAL_TREATMENT", "RAW_TEXT")))
        assertNull(AgentFailureCodec.decode(encoded.replace("FINAL_SAFETY_INVALID", "MODEL_SDK")))
    }
}
