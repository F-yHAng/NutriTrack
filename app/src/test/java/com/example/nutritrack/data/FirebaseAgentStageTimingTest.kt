package com.example.nutritrack.data

import com.example.nutritrack.agent.NutritionPlanningAgentConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Test

class FirebaseAgentStageTimingTest {
    @Test
    fun recordsStageAndMonotonicDuration_withoutContentOrExtraInvocations() = runTest {
        val times = ArrayDeque(listOf(1_000_000L, 61_001_000_000L))
        val events = mutableListOf<String>()
        var calls = 0
        val result = measureFirebaseAgentStage(
            FirebaseAgentRequestPhase.TOOL_SELECTION,
            NutritionPlanningAgentConfig.MODEL_REQUEST_TIMEOUT_MILLIS,
            nanoTime = { times.removeFirst() }, report = events::add) {
            calls++
            "PRIVATE_RESPONSE"
        }
        assertEquals("PRIVATE_RESPONSE", result)
        assertEquals(1, calls)
        assertEquals(listOf(
            "phase=TOOL_SELECTION event=START timeoutMillis=60000",
            "phase=TOOL_SELECTION event=END elapsedMillis=61000 timeoutMillis=60000"
        ), events)
        assertFalse(events.joinToString().contains("PRIVATE"))
    }

    @Test
    fun cancellationAndFailureAreRethrownWithEndTiming_noRetryOrExceptionText() = runTest {
        listOf(CancellationException("PRIVATE_CANCEL"), IllegalStateException("PRIVATE_FAILURE"))
            .forEach { original ->
                val events = mutableListOf<String>()
                var calls = 0
                val actual = try {
                    measureFirebaseAgentStage(
                        FirebaseAgentRequestPhase.PLAN_GENERATION,
                        NutritionPlanningAgentConfig.MODEL_REQUEST_TIMEOUT_MILLIS,
                        nanoTime = { 0L }, report = events::add) {
                        calls++
                        throw original
                    }
                } catch (error: Exception) { error }
                assertSame(original, actual)
                assertEquals(1, calls)
                assertEquals(2, events.size)
                assertFalse(events.joinToString().contains("PRIVATE"))
            }
    }
}
