package com.example.nutritrack.agent

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.nutritrack.data.AgentRun
import com.example.nutritrack.data.AgentRunStatus
import com.example.nutritrack.data.AgentToolCall
import com.example.nutritrack.data.AgentToolCallStatus
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NutritionPlanActionDispatcherInstrumentedTest {
    private lateinit var database: NutrackDatabase
    private lateinit var dispatcher: RoomNutritionPlanActionDispatcher

    @Before
    fun setUp() = runBlocking {
        database = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            NutrackDatabase::class.java
        ).build()
        insertPatient(USER_ID)
        insertTraceAndDraft()
        dispatcher = RoomNutritionPlanActionDispatcher(
            database = database,
            nutritionPlanDao = database.nutritionPlanDao(),
            clock = CLOCK
        )
    }

    @After
    fun tearDown() {
        if (::database.isInitialized) database.close()
    }

    @Test
    fun draftHasNoBusinessSideEffectsUntilConfirmation() = runBlocking {
        val plan = checkNotNull(database.nutritionPlanDao().getByRun(RUN_ID))

        assertEquals(NutritionPlanStatus.DRAFT, plan.status)
        assertNull(plan.confirmedAtEpochMillis)
        assertNull(plan.confirmationActionId)
        assertEquals("Fixture patient", database.patientDao().getPatientByUserId(USER_ID)?.name)
        assertTrue(database.foodIntakeDao().getByUserAndDateRange(USER_ID, 0L, Long.MAX_VALUE).isEmpty())
        assertNull(database.questionnaireProfileDao().getByUserId(USER_ID))
        assertNull(database.userAccountDao().getUserById(USER_ID))
    }

    @Test
    fun confirmIsIdempotentAcrossDispatcherRecreationAndPreservesBusinessData() = runBlocking {
        val first = dispatcher.confirmDraft(USER_ID, PLAN_ID)
        assertTrue(first is NutritionPlanActionResult.Applied)
        val confirmed = checkNotNull(database.nutritionPlanDao().getByRun(RUN_ID))
        assertEquals(NutritionPlanStatus.CONFIRMED, confirmed.status)
        assertEquals(CLOCK.millis(), confirmed.confirmedAtEpochMillis)
        assertEquals("confirm:$PLAN_ID", confirmed.confirmationActionId)

        val recreatedDispatcher = RoomNutritionPlanActionDispatcher(
            database = database,
            nutritionPlanDao = database.nutritionPlanDao(),
            clock = Clock.fixed(Instant.parse("2026-09-29T10:00:00Z"), ZoneOffset.UTC)
        )
        val repeated = recreatedDispatcher.confirmDraft(USER_ID, PLAN_ID)
        assertTrue(repeated is NutritionPlanActionResult.AlreadyApplied)
        assertEquals(confirmed, database.nutritionPlanDao().getByRun(RUN_ID))

        assertEquals("Fixture patient", database.patientDao().getPatientByUserId(USER_ID)?.name)
        assertTrue(database.foodIntakeDao().getByUserAndDateRange(USER_ID, 0L, Long.MAX_VALUE).isEmpty())
        assertNull(database.questionnaireProfileDao().getByUserId(USER_ID))
        assertNull(database.userAccountDao().getUserById(USER_ID))
        assertEquals(AgentRunStatus.SUCCEEDED, database.agentRunDao().getById(RUN_ID)?.status)
        assertEquals(1, database.agentToolCallDao().getByRun(RUN_ID).size)
    }

    @Test
    fun abandonedDraftCannotBeConfirmedAndRetainsAgentAudit() = runBlocking {
        val abandoned = dispatcher.abandonDraft(USER_ID, PLAN_ID)
        assertTrue(abandoned is NutritionPlanActionResult.Applied)
        assertEquals(
            NutritionPlanStatus.ABANDONED,
            database.nutritionPlanDao().getByRun(RUN_ID)?.status
        )

        val confirmation = dispatcher.confirmDraft(USER_ID, PLAN_ID)
        assertTrue(confirmation is NutritionPlanActionResult.Rejected)
        assertEquals(
            NutritionPlanStatus.ABANDONED,
            database.nutritionPlanDao().getByRun(RUN_ID)?.status
        )
        assertEquals(AgentRunStatus.SUCCEEDED, database.agentRunDao().getById(RUN_ID)?.status)
        assertEquals(1, database.agentToolCallDao().getByRun(RUN_ID).size)
    }

    @Test
    fun confirmedPlanCanBeAbandonedIdempotentlyWithoutRemovingAudit() = runBlocking {
        assertTrue(dispatcher.confirmDraft(USER_ID, PLAN_ID) is NutritionPlanActionResult.Applied)
        val first = dispatcher.abandonConfirmedPlan(USER_ID, PLAN_ID)
        assertTrue(first is NutritionPlanActionResult.Applied)
        val abandoned = checkNotNull(database.nutritionPlanDao().getByRun(RUN_ID))
        assertEquals(NutritionPlanStatus.ABANDONED, abandoned.status)
        assertEquals(CLOCK.millis(), abandoned.updatedAtEpochMillis)
        assertEquals("confirm:$PLAN_ID", abandoned.confirmationActionId)

        val repeated = dispatcher.abandonConfirmedPlan(USER_ID, PLAN_ID)
        assertTrue(repeated is NutritionPlanActionResult.AlreadyApplied)
        assertEquals(abandoned, database.nutritionPlanDao().getByRun(RUN_ID))
        assertEquals(AgentRunStatus.SUCCEEDED, database.agentRunDao().getById(RUN_ID)?.status)
        assertEquals(1, database.agentToolCallDao().getByRun(RUN_ID).size)
    }

    @Test
    fun confirmationFailureLeavesDraftRecoverableAndDoesNotReportSuccess() = runBlocking {
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_plan_confirmation
            BEFORE UPDATE OF status ON nutrition_plans
            WHEN NEW.status = 'CONFIRMED'
            BEGIN
                SELECT RAISE(ABORT, 'test confirmation failure');
            END
            """.trimIndent()
        )

        val result = dispatcher.confirmDraft(USER_ID, PLAN_ID)

        assertTrue(result is NutritionPlanActionResult.Failed)
        val draft = checkNotNull(database.nutritionPlanDao().getByRun(RUN_ID))
        assertEquals(NutritionPlanStatus.DRAFT, draft.status)
        assertNull(draft.confirmedAtEpochMillis)
        assertNull(draft.confirmationActionId)
    }

    @Test
    fun otherUserCannotConfirmThisUsersDraft() = runBlocking {
        insertPatient(OTHER_USER_ID)

        val result = dispatcher.confirmDraft(OTHER_USER_ID, PLAN_ID)

        assertTrue(result is NutritionPlanActionResult.NotFound)
        assertEquals(NutritionPlanStatus.DRAFT, database.nutritionPlanDao().getByRun(RUN_ID)?.status)
    }

    private suspend fun insertTraceAndDraft() {
        database.agentRunDao().insert(
            AgentRun(
                id = RUN_ID,
                userId = USER_ID,
                status = AgentRunStatus.SUCCEEDED,
                createdAtEpochMillis = CLOCK.millis(),
                finishedAtEpochMillis = CLOCK.millis()
            )
        )
        database.agentToolCallDao().insert(
            AgentToolCall(
                id = TOOL_CALL_ID,
                runId = RUN_ID,
                sequence = 1,
                toolName = "get_nutrition_profile",
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
                id = PLAN_ID,
                userId = USER_ID,
                runId = RUN_ID,
                status = NutritionPlanStatus.DRAFT,
                schemaVersion = 1,
                validFromEpochDay = LocalDate.of(2026, 9, 28).toEpochDay(),
                validUntilEpochDay = LocalDate.of(2026, 10, 4).toEpochDay(),
                contentJson = "{\"schemaVersion\":1}",
                cautionsJson = "[]",
                disclaimer = "General guidance only.",
                createdAtEpochMillis = CLOCK.millis()
            )
        )
    }

    private fun insertPatient(userId: Int) {
        val values = ContentValues()
        database.openHelper.writableDatabase.query("PRAGMA table_info(`patients`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                when (val column = cursor.getString(nameIndex)) {
                    "userId" -> values.put(column, userId)
                    "phoneNumber" -> values.put(column, "0400000000")
                    "name" -> values.put(column, "Fixture patient")
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

    private companion object {
        const val USER_ID = 191
        const val OTHER_USER_ID = 192
        const val RUN_ID = "run-confirmation"
        const val TOOL_CALL_ID = "tool-confirmation"
        const val PLAN_ID = "plan-confirmation"
        val CLOCK: Clock = Clock.fixed(Instant.parse("2026-09-28T09:00:00Z"), ZoneOffset.UTC)
    }
}
