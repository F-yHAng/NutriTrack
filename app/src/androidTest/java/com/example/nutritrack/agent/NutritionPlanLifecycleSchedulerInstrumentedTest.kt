package com.example.nutritrack.agent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import com.example.nutritrack.data.NutritionPlan
import com.example.nutritrack.data.NutritionPlanStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NutritionPlanLifecycleSchedulerInstrumentedTest {
    @Test
    fun confirmedPlanUsesOneKeepUniqueOfflineWorkAfterEndDateAcrossSchedulerRecreation() {
        val queue = RecordingWorkQueue()
        val scheduler = WorkManagerNutritionPlanLifecycleScheduler(queue, CLOCK)
        val plan = plan(status = NutritionPlanStatus.CONFIRMED)

        scheduler.ensureCompletionScheduled(plan)
        WorkManagerNutritionPlanLifecycleScheduler(queue, CLOCK).ensureCompletionScheduled(plan)

        val workName = NutritionPlanCompletionWorker.uniqueWorkName(PLAN_ID)
        assertEquals(2, queue.enqueueAttempts)
        assertEquals(listOf(ExistingWorkPolicy.KEEP, ExistingWorkPolicy.KEEP), queue.policies)
        assertEquals(1, queue.activeWork.size)
        val request = checkNotNull(queue.activeWork[workName])
        assertEquals(PLAN_ID, request.workSpec.input.getString(NutritionPlanCompletionWorker.KEY_PLAN_ID))
        assertEquals(USER_ID, request.workSpec.input.getInt(NutritionPlanCompletionWorker.KEY_USER_ID, -1))
        assertEquals(NetworkType.NOT_REQUIRED, request.workSpec.constraints.requiredNetworkType)
        assertEquals(
            LocalDate.of(2026, 10, 5).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli() -
                CLOCK.millis(),
            request.workSpec.initialDelay
        )
    }

    @Test
    fun nonSchedulablePlanDoesNotEnqueueAndAbandonCancelsTheSameUniqueName() {
        val queue = RecordingWorkQueue()
        val scheduler = WorkManagerNutritionPlanLifecycleScheduler(queue, CLOCK)

        scheduler.ensureCompletionScheduled(plan(status = NutritionPlanStatus.DRAFT))
        scheduler.ensureCompletionScheduled(plan(status = NutritionPlanStatus.ABANDONED))
        assertTrue(queue.activeWork.isEmpty())
        assertEquals(0, queue.enqueueAttempts)

        scheduler.cancelCompletion(PLAN_ID)
        assertEquals(listOf(NutritionPlanCompletionWorker.uniqueWorkName(PLAN_ID)), queue.cancelledNames)
        assertFalse(queue.activeWork.containsKey(NutritionPlanCompletionWorker.uniqueWorkName(PLAN_ID)))
    }

    private class RecordingWorkQueue : NutritionPlanWorkQueue {
        val activeWork = linkedMapOf<String, OneTimeWorkRequest>()
        val policies = mutableListOf<ExistingWorkPolicy>()
        val cancelledNames = mutableListOf<String>()
        var enqueueAttempts = 0

        override fun enqueueUniqueCompletion(
            name: String,
            policy: ExistingWorkPolicy,
            request: OneTimeWorkRequest
        ) {
            enqueueAttempts++
            policies += policy
            if (policy == ExistingWorkPolicy.KEEP) activeWork.putIfAbsent(name, request)
        }

        override fun cancelUniqueCompletion(name: String) {
            cancelledNames += name
            activeWork.remove(name)
        }
    }

    private fun plan(status: NutritionPlanStatus): NutritionPlan = NutritionPlan(
        id = PLAN_ID,
        userId = USER_ID,
        runId = RUN_ID,
        status = status,
        schemaVersion = 1,
        validFromEpochDay = LocalDate.of(2026, 9, 28).toEpochDay(),
        validUntilEpochDay = LocalDate.of(2026, 10, 4).toEpochDay(),
        contentJson = "{}",
        cautionsJson = "[]",
        disclaimer = "General guidance only.",
        createdAtEpochMillis = CLOCK.millis()
    )

    private companion object {
        const val USER_ID = 611
        const val RUN_ID = "run-lifecycle"
        const val PLAN_ID = "plan-lifecycle"
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-29T12:00:00Z"), ZoneOffset.UTC)
    }
}
