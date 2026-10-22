package com.example.nutritrack.agent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AgentModelRetryPolicyTest {
    @Test
    fun explicitTransientFailure_waitsOnceThenRunsOnlyTheCurrentStageAgain() = runTest {
        var calls = 0
        val result = async {
            callWithSingleExplicitTransientRetry(
                deadlineStillActive = { true },
                canWaitForRetry = { true },
                shouldRetry = { value: Int -> value == 1 },
                retryDelayMillis = AgentModelRetryPolicy.RETRY_DELAY_MILLIS,
                call = { ++calls }
            )
        }

        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(AgentModelRetryPolicy.RETRY_DELAY_MILLIS - 1)
        runCurrent()
        assertEquals(1, calls)
        advanceTimeBy(1)
        advanceUntilIdle()

        assertEquals(2, result.await())
        assertEquals(2, calls)
    }

    @Test
    fun nonRetryableFailure_doesNotDelayOrIssueAnotherRequest() = runTest {
        var calls = 0
        var delayCalled = false

        val result = callWithSingleExplicitTransientRetry(
            deadlineStillActive = { true },
            canWaitForRetry = { true },
            shouldRetry = { false },
            retryDelayMillis = AgentModelRetryPolicy.RETRY_DELAY_MILLIS,
            delayForRetry = { delayCalled = true },
            call = { ++calls }
        )

        assertEquals(1, result)
        assertEquals(1, calls)
        assertFalse(delayCalled)
    }

    @Test
    fun insufficientDeadline_doesNotWaitOrIssueSecondRequest() = runTest {
        var calls = 0
        var delayCalled = false

        val result = callWithSingleExplicitTransientRetry(
            deadlineStillActive = { true },
            canWaitForRetry = { false },
            shouldRetry = { true },
            retryDelayMillis = AgentModelRetryPolicy.RETRY_DELAY_MILLIS,
            delayForRetry = { delayCalled = true },
            call = { ++calls }
        )

        assertEquals(1, result)
        assertEquals(1, calls)
        assertFalse(delayCalled)
    }

    @Test
    fun deadlineExpiringDuringDelay_preventsTheSecondRequest() = runTest {
        var calls = 0
        var deadlineStillActive = true
        val result = async {
            callWithSingleExplicitTransientRetry(
                deadlineStillActive = { deadlineStillActive },
                canWaitForRetry = { true },
                shouldRetry = { true },
                retryDelayMillis = AgentModelRetryPolicy.RETRY_DELAY_MILLIS,
                delayForRetry = {
                    delay(it)
                    deadlineStillActive = false
                },
                call = { ++calls }
            )
        }

        runCurrent()
        advanceUntilIdle()

        assertEquals(1, result.await())
        assertEquals(1, calls)
    }

    @Test
    fun cancellationDuringRetryDelay_doesNotIssueAnotherRequest() = runTest {
        var calls = 0
        val enteredDelay = CompletableDeferred<Unit>()
        val job = launch {
            callWithSingleExplicitTransientRetry(
                deadlineStillActive = { true },
                canWaitForRetry = { true },
                shouldRetry = { true },
                retryDelayMillis = AgentModelRetryPolicy.RETRY_DELAY_MILLIS,
                delayForRetry = {
                    enteredDelay.complete(Unit)
                    delay(it)
                },
                call = { ++calls }
            )
        }

        runCurrent()
        enteredDelay.await()
        job.cancelAndJoin()

        assertTrue(job.isCancelled)
        assertEquals(1, calls)
    }
}
