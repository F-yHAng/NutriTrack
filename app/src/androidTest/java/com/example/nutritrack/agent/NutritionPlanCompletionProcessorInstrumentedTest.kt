package com.example.nutritrack.agent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.TestListenableWorkerBuilder
import com.example.nutritrack.data.AgentRun
import com.example.nutritrack.data.AgentRunStatus
import com.example.nutritrack.data.AgentToolCall
import com.example.nutritrack.data.AgentToolCallStatus
import com.example.nutritrack.data.FoodIntake
import com.example.nutritrack.data.NutrackDatabase
import com.example.nutritrack.data.NutritionPlan
import com.example.nutritrack.data.NutritionPlanStatus
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the Room-only portion of the delayed plan-completion Worker.
 * These tests intentionally use an in-memory database and never initialize
 * WorkManager, Firebase, Gemini, or any network client.
 */
@RunWith(AndroidJUnit4::class)
class NutritionPlanCompletionProcessorInstrumentedTest {
    private lateinit var database: NutrackDatabase
    private lateinit var processor: RoomNutritionPlanCompletionProcessor
    private var effectiveUserId: Int? = USER_ID

    @Before
    fun setUp() {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NutrackDatabase::class.java
        ).build()
        processor = RoomNutritionPlanCompletionProcessor(
            database = database,
            nutritionPlanDao = database.nutritionPlanDao(),
            patientDao = database.patientDao(),
            currentUserProvider = CurrentNutritionPlanUserProvider { effectiveUserId },
            clock = CLOCK
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun notDue_keepsConfirmedPlanAndBusinessAndAuditDataUntouched() = runBlocking {
        insertPatient(USER_ID)
        val fixture = insertTracePlanAndIntake(
            userId = USER_ID,
            planId = "plan-not-due",
            runId = "run-not-due",
            status = NutritionPlanStatus.CONFIRMED,
            validUntil = TODAY.plusDays(1)
        )
        val beforePlan = requireNotNull(database.nutritionPlanDao().getByRun(fixture.runId))
        val beforePatient = database.patientDao().getPatientByUserId(USER_ID)
        val beforeRun = database.agentRunDao().getById(fixture.runId)
        val beforeCalls = database.agentToolCallDao().getByRun(fixture.runId)
        val beforeIntake = database.foodIntakeDao().getByIdForUser(fixture.intakeId, USER_ID)

        val result = processor.completeIfDue(USER_ID, fixture.planId)

        assertEquals(NutritionPlanCompletionResult.NotDue, result)
        assertEquals(beforePlan, database.nutritionPlanDao().getByRun(fixture.runId))
        assertEquals(beforePatient, database.patientDao().getPatientByUserId(USER_ID))
        assertEquals(beforeRun, database.agentRunDao().getById(fixture.runId))
        assertEquals(beforeCalls, database.agentToolCallDao().getByRun(fixture.runId))
        assertEquals(beforeIntake, database.foodIntakeDao().getByIdForUser(fixture.intakeId, USER_ID))
    }

    @Test
    fun dueActivePlan_completesOnceAndRepeatedExecutionIsNoOp() = runBlocking {
        insertPatient(USER_ID)
        val fixture = insertTracePlanAndIntake(
            userId = USER_ID,
            planId = "plan-due",
            runId = "run-due",
            status = NutritionPlanStatus.ACTIVE,
            validUntil = TODAY.minusDays(1)
        )
        val beforePatient = database.patientDao().getPatientByUserId(USER_ID)
        val beforeRun = database.agentRunDao().getById(fixture.runId)
        val beforeCalls = database.agentToolCallDao().getByRun(fixture.runId)
        val beforeIntake = database.foodIntakeDao().getByIdForUser(fixture.intakeId, USER_ID)

        assertEquals(NutritionPlanCompletionResult.Completed, processor.completeIfDue(USER_ID, fixture.planId))
        val completed = requireNotNull(database.nutritionPlanDao().getByRun(fixture.runId))
        assertEquals(NutritionPlanStatus.COMPLETED, completed.status)
        assertEquals(CLOCK.millis(), completed.completedAtEpochMillis)
        assertEquals(CLOCK.millis(), completed.updatedAtEpochMillis)

        assertEquals(NutritionPlanCompletionResult.NoOp, processor.completeIfDue(USER_ID, fixture.planId))
        assertEquals(completed, database.nutritionPlanDao().getByRun(fixture.runId))
        assertEquals(beforePatient, database.patientDao().getPatientByUserId(USER_ID))
        assertEquals(beforeRun, database.agentRunDao().getById(fixture.runId))
        assertEquals(beforeCalls, database.agentToolCallDao().getByRun(fixture.runId))
        assertEquals(beforeIntake, database.foodIntakeDao().getByIdForUser(fixture.intakeId, USER_ID))
    }

    @Test
    fun abandonedPlanAndCrossUserRequestDoNotCompleteAnyPlan() = runBlocking {
        insertPatient(USER_ID)
        insertPatient(OTHER_USER_ID)
        val abandoned = insertTracePlanAndIntake(
            userId = USER_ID,
            planId = "plan-abandoned",
            runId = "run-abandoned",
            status = NutritionPlanStatus.ABANDONED,
            validUntil = TODAY.minusDays(1)
        )
        val ownedByFirstUser = insertTracePlanAndIntake(
            userId = USER_ID,
            planId = "plan-owned",
            runId = "run-owned",
            status = NutritionPlanStatus.CONFIRMED,
            validUntil = TODAY.minusDays(1)
        )
        val abandonedBefore = database.nutritionPlanDao().getByRun(abandoned.runId)
        val ownedBefore = database.nutritionPlanDao().getByRun(ownedByFirstUser.runId)

        assertEquals(NutritionPlanCompletionResult.NoOp, processor.completeIfDue(USER_ID, abandoned.planId))
        effectiveUserId = OTHER_USER_ID
        assertEquals(
            NutritionPlanCompletionResult.NoOp,
            processor.completeIfDue(OTHER_USER_ID, ownedByFirstUser.planId)
        )

        assertEquals(abandonedBefore, database.nutritionPlanDao().getByRun(abandoned.runId))
        assertEquals(ownedBefore, database.nutritionPlanDao().getByRun(ownedByFirstUser.runId))
    }

    @Test
    fun duePlanForNonCurrentSessionUserDoesNotComplete() = runBlocking {
        insertPatient(USER_ID)
        insertPatient(OTHER_USER_ID)
        val fixture = insertTracePlanAndIntake(
            userId = USER_ID,
            planId = "plan-other-session",
            runId = "run-other-session",
            status = NutritionPlanStatus.CONFIRMED,
            validUntil = TODAY.minusDays(1)
        )
        effectiveUserId = OTHER_USER_ID

        assertEquals(
            NutritionPlanCompletionResult.NoOp,
            processor.completeIfDue(USER_ID, fixture.planId)
        )
        assertEquals(
            NutritionPlanStatus.CONFIRMED,
            database.nutritionPlanDao().getByRun(fixture.runId)?.status
        )
    }

    @Test
    fun missingPatientOrPlan_returnsNoOpWithoutCreatingOrMutatingData() = runBlocking {
        assertEquals(
            NutritionPlanCompletionResult.NoOp,
            processor.completeIfDue(MISSING_USER_ID, "plan-missing-owner")
        )

        assertNull(database.patientDao().getPatientByUserId(MISSING_USER_ID))
        assertTrue(database.nutritionPlanDao().getByUser(MISSING_USER_ID).isEmpty())
        assertTrue(database.agentRunDao().getByUser(MISSING_USER_ID).isEmpty())
    }

    @Test
    fun workerUsesInjectedOfflineProcessorAndCompletesOnlyTheDuePlan() = runBlocking {
        insertPatient(USER_ID)
        val fixture = insertTracePlanAndIntake(
            userId = USER_ID,
            planId = "plan-worker-due",
            runId = "run-worker-due",
            status = NutritionPlanStatus.CONFIRMED,
            validUntil = TODAY.minusDays(1)
        )
        val worker = completionWorker(fixture.planId, USER_ID)

        assertTrue(worker.doWork() is ListenableWorker.Result.Success)
        assertEquals(
            NutritionPlanStatus.COMPLETED,
            database.nutritionPlanDao().getByRun(fixture.runId)?.status
        )
    }

    @Test
    fun workerRetriesWithoutCompletingAPlanBeforeItsEndDate() = runBlocking {
        insertPatient(USER_ID)
        val fixture = insertTracePlanAndIntake(
            userId = USER_ID,
            planId = "plan-worker-not-due",
            runId = "run-worker-not-due",
            status = NutritionPlanStatus.CONFIRMED,
            validUntil = TODAY.plusDays(1)
        )

        assertTrue(completionWorker(fixture.planId, USER_ID).doWork() is ListenableWorker.Result.Retry)
        assertEquals(
            NutritionPlanStatus.CONFIRMED,
            database.nutritionPlanDao().getByRun(fixture.runId)?.status
        )
    }

    @Test
    fun workerExposesWorkManagerRequiredTwoArgumentConstructor() {
        assertNotNull(
            NutritionPlanCompletionWorker::class.java.getConstructor(
                android.content.Context::class.java,
                WorkerParameters::class.java
            )
        )
    }

    private fun completionWorker(planId: String, userId: Int): NutritionPlanCompletionWorker =
        TestListenableWorkerBuilder.from(
            ApplicationProvider.getApplicationContext(),
            NutritionPlanCompletionWorker::class.java
        )
            .setInputData(NutritionPlanCompletionWorker.inputData(planId, userId))
            .setWorkerFactory(object : WorkerFactory() {
                override fun createWorker(
                    appContext: android.content.Context,
                    workerClassName: String,
                    workerParameters: WorkerParameters
                ): ListenableWorker? = if (workerClassName == NutritionPlanCompletionWorker::class.java.name) {
                    NutritionPlanCompletionWorker(appContext, workerParameters, processor)
                } else {
                    null
                }
            })
            .build()

    private suspend fun insertTracePlanAndIntake(
        userId: Int,
        planId: String,
        runId: String,
        status: NutritionPlanStatus,
        validUntil: LocalDate
    ): Fixture {
        database.agentRunDao().insert(
            AgentRun(
                id = runId,
                userId = userId,
                status = AgentRunStatus.SUCCEEDED,
                createdAtEpochMillis = CLOCK.millis(),
                finishedAtEpochMillis = CLOCK.millis()
            )
        )
        database.agentToolCallDao().insert(
            AgentToolCall(
                id = "tool-$runId",
                runId = runId,
                sequence = 1,
                toolName = "GetNutritionProfile",
                argumentsJson = "{}",
                resultJson = "{}",
                status = AgentToolCallStatus.SUCCEEDED,
                errorCode = null,
                startedAtEpochMillis = CLOCK.millis(),
                finishedAtEpochMillis = CLOCK.millis()
            )
        )
        database.nutritionPlanDao().insert(
            NutritionPlan(
                id = planId,
                userId = userId,
                runId = runId,
                status = status,
                schemaVersion = 1,
                validFromEpochDay = TODAY.minusDays(7).toEpochDay(),
                validUntilEpochDay = validUntil.toEpochDay(),
                contentJson = "{\"schemaVersion\":1}",
                cautionsJson = "[]",
                disclaimer = "General guidance only.",
                createdAtEpochMillis = CLOCK.millis()
            )
        )
        val intakeId = database.foodIntakeDao().insert(
            FoodIntake(
                patientUserId = userId,
                submissionDate = CLOCK.millis(),
                foodName = "Fixture intake",
                fruitServings = 1.0
            )
        )
        return Fixture(planId = planId, runId = runId, intakeId = intakeId)
    }

    private fun insertPatient(userId: Int) {
        val values = ContentValues()
        database.openHelper.writableDatabase.query("PRAGMA table_info(`patients`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                when (val column = cursor.getString(nameIndex)) {
                    "userId" -> values.put(column, userId)
                    "phoneNumber" -> values.put(column, "040000${userId.toString().padStart(4, '0')}")
                    "name" -> values.put(column, "Lifecycle fixture")
                    "sex" -> values.put(column, "Male")
                    else -> values.put(column, 1.0)
                }
            }
        }
        check(
            database.openHelper.writableDatabase.insert(
                "patients",
                SQLiteDatabase.CONFLICT_ABORT,
                values
            ) != -1L
        )
    }

    private data class Fixture(
        val planId: String,
        val runId: String,
        val intakeId: Long
    )

    private companion object {
        const val USER_ID = 310
        const val OTHER_USER_ID = 311
        const val MISSING_USER_ID = 312
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-10-10T12:00:00Z"), ZoneOffset.UTC)
        val TODAY: LocalDate = LocalDate.now(CLOCK)
    }
}
