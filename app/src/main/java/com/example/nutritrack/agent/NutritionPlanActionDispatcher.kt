package com.example.nutritrack.agent

import androidx.room.withTransaction
import com.example.nutritrack.data.NutrackDatabase
import com.example.nutritrack.data.NutritionPlan
import com.example.nutritrack.data.NutritionPlanDao
import com.example.nutritrack.data.NutritionPlanStatus
import java.time.Clock
import kotlinx.coroutines.CancellationException

/**
 * Applies the only user-initiated effects allowed for an Agent plan. It never
 * mutates profile, intake, account, run, or tool-call data.
 */
interface NutritionPlanActionDispatcher {
    suspend fun confirmDraft(userId: Int, planId: String): NutritionPlanActionResult
    suspend fun abandonDraft(userId: Int, planId: String): NutritionPlanActionResult
    suspend fun abandonConfirmedPlan(userId: Int, planId: String): NutritionPlanActionResult
}

sealed interface NutritionPlanActionResult {
    data class Applied(val plan: NutritionPlan) : NutritionPlanActionResult
    data class AlreadyApplied(val plan: NutritionPlan) : NutritionPlanActionResult
    data class Rejected(val plan: NutritionPlan) : NutritionPlanActionResult
    data object NotFound : NutritionPlanActionResult
    data object Failed : NutritionPlanActionResult
}

class RoomNutritionPlanActionDispatcher(
    private val database: NutrackDatabase,
    private val nutritionPlanDao: NutritionPlanDao,
    private val clock: Clock
) : NutritionPlanActionDispatcher {
    override suspend fun confirmDraft(userId: Int, planId: String): NutritionPlanActionResult =
        execute {
            database.withTransaction {
                val current = nutritionPlanDao.getByIdForUser(planId, userId)
                    ?: return@withTransaction NutritionPlanActionResult.NotFound
                val actionId = confirmationActionId(planId)
                when (current.status) {
                    NutritionPlanStatus.DRAFT -> {
                        val changed = nutritionPlanDao.confirmDraftIfStillDraft(
                            planId = planId,
                            userId = userId,
                            confirmationActionId = actionId,
                            confirmedAtEpochMillis = clock.millis()
                        )
                        val persisted = nutritionPlanDao.getByIdForUser(planId, userId)
                            ?: return@withTransaction NutritionPlanActionResult.NotFound
                        when {
                            changed == 1 -> NutritionPlanActionResult.Applied(persisted)
                            persisted.status == NutritionPlanStatus.CONFIRMED &&
                                persisted.confirmationActionId == actionId ->
                                NutritionPlanActionResult.AlreadyApplied(persisted)
                            else -> NutritionPlanActionResult.Rejected(persisted)
                        }
                    }

                    NutritionPlanStatus.CONFIRMED -> {
                        if (current.confirmationActionId == actionId) {
                            NutritionPlanActionResult.AlreadyApplied(current)
                        } else {
                            NutritionPlanActionResult.Rejected(current)
                        }
                    }

                    NutritionPlanStatus.ACTIVE,
                    NutritionPlanStatus.COMPLETED,
                    NutritionPlanStatus.ABANDONED -> NutritionPlanActionResult.Rejected(current)
                }
            }
        }

    override suspend fun abandonDraft(userId: Int, planId: String): NutritionPlanActionResult =
        execute {
            database.withTransaction {
                val current = nutritionPlanDao.getByIdForUser(planId, userId)
                    ?: return@withTransaction NutritionPlanActionResult.NotFound
                when (current.status) {
                    NutritionPlanStatus.DRAFT -> {
                        val changed = nutritionPlanDao.abandonDraftIfStillDraft(
                            planId = planId,
                            userId = userId,
                            updatedAtEpochMillis = clock.millis()
                        )
                        val persisted = nutritionPlanDao.getByIdForUser(planId, userId)
                            ?: return@withTransaction NutritionPlanActionResult.NotFound
                        if (changed == 1) {
                            NutritionPlanActionResult.Applied(persisted)
                        } else if (persisted.status == NutritionPlanStatus.ABANDONED) {
                            NutritionPlanActionResult.AlreadyApplied(persisted)
                        } else {
                            NutritionPlanActionResult.Rejected(persisted)
                        }
                    }

                    NutritionPlanStatus.ABANDONED -> NutritionPlanActionResult.AlreadyApplied(current)
                    NutritionPlanStatus.CONFIRMED,
                    NutritionPlanStatus.ACTIVE,
                    NutritionPlanStatus.COMPLETED -> NutritionPlanActionResult.Rejected(current)
                }
            }
        }

    override suspend fun abandonConfirmedPlan(
        userId: Int,
        planId: String
    ): NutritionPlanActionResult = execute {
        database.withTransaction {
            val current = nutritionPlanDao.getByIdForUser(planId, userId)
                ?: return@withTransaction NutritionPlanActionResult.NotFound
            when (current.status) {
                NutritionPlanStatus.CONFIRMED,
                NutritionPlanStatus.ACTIVE -> {
                    val changed = nutritionPlanDao.abandonConfirmedOrActiveIfStillEligible(
                        planId = planId,
                        userId = userId,
                        updatedAtEpochMillis = clock.millis()
                    )
                    val persisted = nutritionPlanDao.getByIdForUser(planId, userId)
                        ?: return@withTransaction NutritionPlanActionResult.NotFound
                    if (changed == 1) {
                        NutritionPlanActionResult.Applied(persisted)
                    } else if (persisted.status == NutritionPlanStatus.ABANDONED) {
                        NutritionPlanActionResult.AlreadyApplied(persisted)
                    } else {
                        NutritionPlanActionResult.Rejected(persisted)
                    }
                }

                NutritionPlanStatus.ABANDONED -> NutritionPlanActionResult.AlreadyApplied(current)
                NutritionPlanStatus.DRAFT,
                NutritionPlanStatus.COMPLETED -> NutritionPlanActionResult.Rejected(current)
            }
        }
    }

    private suspend fun execute(
        block: suspend () -> NutritionPlanActionResult
    ): NutritionPlanActionResult = try {
        block()
    } catch (cancelled: CancellationException) {
        throw cancelled
    } catch (_: Exception) {
        NutritionPlanActionResult.Failed
    }

    private fun confirmationActionId(planId: String): String = "confirm:$planId"
}
