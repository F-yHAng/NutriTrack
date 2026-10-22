package com.example.nutritrack.agent

import android.content.Context
import androidx.room.withTransaction
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.example.nutritrack.NutrackApplication
import com.example.nutritrack.data.NutrackDatabase
import com.example.nutritrack.data.NutritionPlan
import com.example.nutritrack.data.NutritionPlanDao
import com.example.nutritrack.data.NutritionPlanStatus
import com.example.nutritrack.data.PatientDao
import com.example.nutritrack.data.SessionManager
import com.example.nutritrack.data.SessionState
import java.time.Clock
import java.time.DateTimeException
import java.time.LocalDate
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

/**
 * Schedules only the local, deferrable transition that marks a confirmed plan
 * complete after its already-persisted end date. It never calls network or AI services.
 */
interface NutritionPlanLifecycleScheduler {
    fun ensureCompletionScheduled(plan: NutritionPlan)
    fun cancelCompletion(planId: String)
}

/** Thin boundary around WorkManager so lifecycle behavior can be tested without a device scheduler. */
interface NutritionPlanWorkQueue {
    fun enqueueUniqueCompletion(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest
    )
    fun cancelUniqueCompletion(name: String)
}

class WorkManagerNutritionPlanWorkQueue(
    private val workManager: WorkManager
) : NutritionPlanWorkQueue {
    override fun enqueueUniqueCompletion(
        name: String,
        policy: ExistingWorkPolicy,
        request: OneTimeWorkRequest
    ) {
        workManager.enqueueUniqueWork(name, policy, request)
    }

    override fun cancelUniqueCompletion(name: String) {
        workManager.cancelUniqueWork(name)
    }
}

class WorkManagerNutritionPlanLifecycleScheduler(
    private val workQueue: NutritionPlanWorkQueue,
    private val clock: Clock
) : NutritionPlanLifecycleScheduler {
    override fun ensureCompletionScheduled(plan: NutritionPlan) {
        if (plan.status !in SCHEDULABLE_STATUSES) return
        workQueue.enqueueUniqueCompletion(
            name = NutritionPlanCompletionWorker.uniqueWorkName(plan.id),
            policy = ExistingWorkPolicy.KEEP,
            request = createCompletionRequest(plan)
        )
    }

    override fun cancelCompletion(planId: String) {
        if (planId.isBlank()) return
        workQueue.cancelUniqueCompletion(NutritionPlanCompletionWorker.uniqueWorkName(planId))
    }

    internal fun createCompletionRequest(plan: NutritionPlan): OneTimeWorkRequest {
        val endDate = LocalDate.ofEpochDay(plan.validUntilEpochDay)
        // Completion is deferrable: do not ask WorkManager to run before the end date has passed.
        val dueAt = endDate.plusDays(1).atStartOfDay(clock.zone).toInstant().toEpochMilli()
        val initialDelayMillis = (dueAt - clock.millis()).coerceAtLeast(0L)
        return OneTimeWorkRequestBuilder<NutritionPlanCompletionWorker>()
            .setInputData(NutritionPlanCompletionWorker.inputData(plan.id, plan.userId))
            .setInitialDelay(initialDelayMillis, TimeUnit.MILLISECONDS)
            .setConstraints(Constraints.NONE)
            .addTag(NutritionPlanCompletionWorker.uniqueWorkName(plan.id))
            .build()
    }

    private companion object {
        val SCHEDULABLE_STATUSES = setOf(
            NutritionPlanStatus.CONFIRMED,
            NutritionPlanStatus.ACTIVE
        )
    }
}

sealed interface NutritionPlanCompletionResult {
    data object Completed : NutritionPlanCompletionResult
    data object NotDue : NutritionPlanCompletionResult
    data object NoOp : NutritionPlanCompletionResult
}

/** Resolves the only user session allowed to receive a local lifecycle transition. */
fun interface CurrentNutritionPlanUserProvider {
    suspend fun currentUserId(): Int?
}

class SessionManagerCurrentNutritionPlanUserProvider(
    private val sessionManager: SessionManager
) : CurrentNutritionPlanUserProvider {
    override suspend fun currentUserId(): Int? {
        val snapshot = sessionManager.state.first { it.sessionState !is SessionState.Loading }
        return (snapshot.sessionState as? SessionState.LoggedIn)?.userId
    }
}

/**
 * Room-only completion logic. Keeping this separate from the Worker makes its
 * authorization and idempotent transaction testable without WorkManager internals.
 */
class RoomNutritionPlanCompletionProcessor(
    private val database: NutrackDatabase,
    private val nutritionPlanDao: NutritionPlanDao,
    private val patientDao: PatientDao,
    private val currentUserProvider: CurrentNutritionPlanUserProvider,
    private val clock: Clock
) {
    suspend fun completeIfDue(userId: Int, planId: String): NutritionPlanCompletionResult {
        if (userId <= 0 || !PLAN_ID_PATTERN.matches(planId)) {
            return NutritionPlanCompletionResult.NoOp
        }
        if (currentUserProvider.currentUserId() != userId) {
            return NutritionPlanCompletionResult.NoOp
        }
        return database.withTransaction {
            // The worker trusts neither an arbitrary plan ID nor a stale account.
            if (patientDao.getPatientByUserId(userId) == null) {
                return@withTransaction NutritionPlanCompletionResult.NoOp
            }
            val plan = nutritionPlanDao.getByIdForUser(planId, userId)
                ?: return@withTransaction NutritionPlanCompletionResult.NoOp
            if (plan.status !in ELIGIBLE_STATUSES) {
                return@withTransaction NutritionPlanCompletionResult.NoOp
            }
            val endDate = try {
                LocalDate.ofEpochDay(plan.validUntilEpochDay)
            } catch (_: DateTimeException) {
                return@withTransaction NutritionPlanCompletionResult.NoOp
            }
            if (LocalDate.now(clock).isBefore(endDate)) {
                return@withTransaction NutritionPlanCompletionResult.NotDue
            }
            val completedAt = clock.millis()
            when (
                nutritionPlanDao.completeConfirmedOrActiveIfStillEligible(
                    planId = planId,
                    userId = userId,
                    completedAtEpochMillis = completedAt
                )
            ) {
                1 -> NutritionPlanCompletionResult.Completed
                else -> NutritionPlanCompletionResult.NoOp
            }
        }
    }

    private companion object {
        val ELIGIBLE_STATUSES = setOf(
            NutritionPlanStatus.CONFIRMED,
            NutritionPlanStatus.ACTIVE
        )
        val PLAN_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")
    }
}

class NutritionPlanCompletionWorker @JvmOverloads constructor(
    appContext: Context,
    params: WorkerParameters,
    private val injectedProcessor: RoomNutritionPlanCompletionProcessor? = null
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        val planId = inputData.getString(KEY_PLAN_ID).orEmpty()
        val userId = inputData.getInt(KEY_USER_ID, INVALID_USER_ID)
        if (userId <= 0 || !PLAN_ID_PATTERN.matches(planId)) return Result.failure()

        val processor = injectedProcessor ?: (applicationContext.applicationContext
            as? NutrackApplication)?.nutritionPlanCompletionProcessor
            ?: return Result.failure()

        return try {
            when (processor.completeIfDue(userId, planId)) {
                NutritionPlanCompletionResult.Completed,
                NutritionPlanCompletionResult.NoOp -> Result.success()
                NutritionPlanCompletionResult.NotDue -> Result.retry()
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A transient local database failure remains retryable; no plan data is fabricated.
            Result.retry()
        }
    }

    companion object {
        const val KEY_PLAN_ID = "nutrition_plan_id"
        const val KEY_USER_ID = "nutrition_plan_user_id"
        private const val INVALID_USER_ID = -1
        private val PLAN_ID_PATTERN = Regex("[A-Za-z0-9_-]{1,64}")

        fun uniqueWorkName(planId: String): String = "nutrition-plan-complete:$planId"

        fun inputData(planId: String, userId: Int): Data = workDataOf(
            KEY_PLAN_ID to planId,
            KEY_USER_ID to userId
        )
    }
}
