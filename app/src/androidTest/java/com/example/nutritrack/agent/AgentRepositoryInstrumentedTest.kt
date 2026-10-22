package com.example.nutritrack.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.nutritrack.data.AgentRunStatus
import com.example.nutritrack.data.AgentToolCallStatus
import com.example.nutritrack.data.FoodIntake
import com.example.nutritrack.data.NutrackDatabase
import com.example.nutritrack.data.NutritionPlanStatus
import com.example.nutritrack.data.QuestionnaireProfile
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

@RunWith(AndroidJUnit4::class)
class AgentRepositoryInstrumentedTest {
    private lateinit var database: NutrackDatabase
    private lateinit var repository: AgentRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NutrackDatabase::class.java).build()
        insertPatient(USER_ONE, "Private Person", "0400000701")
        insertPatient(USER_TWO, "Other Person", "0400000702")
        repository = AgentRepository(
            database = database,
            patientDao = database.patientDao(),
            agentRunDao = database.agentRunDao(),
            agentToolCallDao = database.agentToolCallDao(),
            nutritionPlanDao = database.nutritionPlanDao(),
            clock = FIXED_CLOCK,
            idGenerator = SequenceIdGenerator()
        )
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun runTransitions_supportSuccessFailureAndCancellationOnlyFromRunning() = runBlocking {
        val successful = repository.startRun(USER_ONE)
        val plan = repository.completeRunWithDraftPlan(
            AgentContext(USER_ONE, successful.id),
            validPlan()
        )
        assertEquals(NutritionPlanStatus.DRAFT, plan.status)
        assertEquals(AgentRunStatus.SUCCEEDED, database.agentRunDao().getById(successful.id)?.status)

        val failed = repository.startRun(USER_ONE)
        repository.failRun(
            AgentContext(USER_ONE, failed.id),
            AgentFailure(
                AgentFailureStage.TOOL_EXECUTION,
                AgentFailureCode.TOOL_EXECUTION_FAILED
            )
        )
        assertEquals(AgentRunStatus.FAILED, database.agentRunDao().getById(failed.id)?.status)

        val cancelled = repository.startRun(USER_ONE)
        repository.cancelRun(AgentContext(USER_ONE, cancelled.id))
        assertEquals(AgentRunStatus.CANCELLED, database.agentRunDao().getById(cancelled.id)?.status)

        val repeated = runCatching {
            repository.cancelRun(AgentContext(USER_ONE, cancelled.id))
        }.exceptionOrNull()
        assertTrue(repeated is AgentRepositoryException)
        assertEquals(
            AgentRepositoryError.RUN_NOT_ACTIVE,
            (repeated as AgentRepositoryException).reason
        )
    }

    @Test
    fun trace_isOrderedReplayableAndCannotBeReadAcrossUsers() = runBlocking {
        val run = repository.startRun(USER_ONE)
        val context = AgentContext(USER_ONE, run.id)
        val first = repository.recordToolCall(
            context,
            AgentToolName.GET_NUTRITION_PROFILE,
            "{}",
            1_000L
        )
        repository.finishToolCall(
            context,
            first.id,
            AgentToolCallStatus.SUCCEEDED,
            """{"persona":"Mindful Eater"}""",
            null,
            1_001L
        )
        val second = repository.recordToolCall(
            context,
            AgentToolName.SEARCH_FRUIT_NUTRITION,
            """{"query":"apple"}""",
            1_002L
        )
        repository.finishToolCall(
            context,
            second.id,
            AgentToolCallStatus.FAILED,
            null,
            AgentToolErrorCode.FRUIT_TIMEOUT,
            1_003L
        )

        val trace = repository.getTrace(context)
        assertEquals(listOf(1, 2), trace.map { it.sequence })
        assertEquals(listOf(first.id, second.id), trace.map { it.id })
        assertEquals("{}", trace.first().argumentsJson)
        assertEquals("FRUIT_TIMEOUT", trace.last().errorCode)

        val denied = runCatching {
            repository.getTrace(AgentContext(USER_TWO, run.id))
        }.exceptionOrNull()
        assertTrue(denied is AgentRepositoryException)
        assertEquals(2, database.agentToolCallDao().getByRun(run.id).size)
    }

    @Test
    fun planAndSucceededTransition_rollBackTogetherWhenStatusWriteFails() = runBlocking {
        val run = repository.startRun(USER_ONE)
        database.openHelper.writableDatabase.execSQL(
            """
            CREATE TRIGGER reject_agent_success
            BEFORE UPDATE OF status ON agent_runs
            WHEN NEW.status = 'SUCCEEDED'
            BEGIN
                SELECT RAISE(ABORT, 'blocked by test');
            END
            """.trimIndent()
        )

        val failure = runCatching {
            repository.completeRunWithDraftPlan(
                AgentContext(USER_ONE, run.id),
                validPlan()
            )
        }.exceptionOrNull()

        assertNotNull(failure)
        assertNull(database.nutritionPlanDao().getByRun(run.id))
        assertEquals(AgentRunStatus.RUNNING, database.agentRunDao().getById(run.id)?.status)
    }

    @Test
    fun failedRun_preservesTraceAndExistingBusinessData() = runBlocking {
        database.questionnaireProfileDao().upsert(validProfile(USER_ONE))
        val intakeId = database.foodIntakeDao().insert(
            FoodIntake(
                patientUserId = USER_ONE,
                submissionDate = FIXED_CLOCK.millis(),
                foodName = "Private meal note",
                fruitServings = 2.0
            )
        )
        val run = repository.startRun(USER_ONE)
        val context = AgentContext(USER_ONE, run.id)
        val call = repository.recordToolCall(
            context,
            AgentToolName.GET_INTAKE_HISTORY,
            """{"startDate":"2026-09-01","endDate":"2026-09-02"}""",
            FIXED_CLOCK.millis()
        )
        repository.finishToolCall(
            context,
            call.id,
            AgentToolCallStatus.FAILED,
            null,
            AgentToolErrorCode.UNKNOWN_FAILURE,
            FIXED_CLOCK.millis()
        )
        repository.failRun(
            context,
            AgentFailure(
                AgentFailureStage.TOOL_EXECUTION,
                AgentFailureCode.TOOL_EXECUTION_FAILED
            )
        )

        assertEquals(1, repository.getTrace(context).size)
        assertNotNull(database.questionnaireProfileDao().getByUserId(USER_ONE))
        assertNotNull(database.foodIntakeDao().getByIdForUser(intakeId, USER_ONE))
        assertNotNull(database.patientDao().getPatientByUserId(USER_ONE))
    }

    @Test
    fun recoveryFailsOrphanedRunAndCallWithoutChangingBusinessData() = runBlocking {
        val profile = validProfile(USER_ONE)
        database.questionnaireProfileDao().upsert(profile)
        val intakeId = database.foodIntakeDao().insert(
            FoodIntake(
                patientUserId = USER_ONE,
                submissionDate = FIXED_CLOCK.millis(),
                foodName = "Demo meal",
                fruitServings = 1.0
            )
        )
        val run = repository.startRun(USER_ONE)
        val context = AgentContext(USER_ONE, run.id)
        val call = repository.recordToolCall(
            context = context,
            toolName = AgentToolName.GET_NUTRITION_PROFILE,
            argumentsJson = "{}",
            startedAtEpochMillis = FIXED_CLOCK.millis()
        )

        val recovered = repository.recoverInterruptedAndLoadLatest(USER_ONE)

        assertEquals(run.id, recovered?.run?.id)
        assertEquals(AgentRunStatus.FAILED, recovered?.run?.status)
        assertEquals(
            AgentFailure(
                AgentFailureStage.PROCESS_RECOVERY,
                AgentFailureCode.PROCESS_INTERRUPTED
            ),
            AgentFailureCodec.decode(recovered?.run?.errorCode)
        )
        assertEquals(AgentToolCallStatus.FAILED, recovered?.toolCalls?.single()?.status)
        assertEquals(
            AgentToolErrorCode.PROCESS_INTERRUPTED.name,
            recovered?.toolCalls?.single()?.errorCode
        )
        assertNull(recovered?.plan)
        assertEquals(profile, database.questionnaireProfileDao().getByUserId(USER_ONE))
        assertNotNull(database.foodIntakeDao().getByIdForUser(intakeId, USER_ONE))
        assertNotNull(database.patientDao().getPatientByUserId(USER_ONE))

        val repeated = repository.recoverInterruptedAndLoadLatest(USER_ONE)
        assertEquals(run.id, repeated?.run?.id)
        assertEquals(call.id, repeated?.toolCalls?.single()?.id)
        assertEquals(1, database.agentRunDao().getByUser(USER_ONE).size)
    }

    private fun validPlan() = DraftNutritionPlanInput(
        schemaVersion = 1,
        validFrom = LocalDate.of(2026, 9, 13),
        validUntil = LocalDate.of(2026, 9, 19),
        contentJson = """{"days":[]}""",
        cautions = listOf("General guidance only"),
        disclaimer = "This draft is not medical advice."
    )

    private fun validProfile(userId: Int) = QuestionnaireProfile(
        userId = userId,
        canEatFruits = true,
        canEatVegetables = true,
        canEatGrains = false,
        canEatRedMeat = false,
        canEatSeafood = false,
        canEatPoultry = false,
        canEatFish = false,
        canEatEggs = false,
        canEatNutsAndSeeds = false,
        selectedPersona = "Mindful Eater",
        biggestMealTime = "12:00",
        sleepTime = "22:00",
        wakeUpTime = "07:00",
        legacyPreferencesMigrationVersion = 1,
        updatedAtEpochMillis = FIXED_CLOCK.millis()
    )

    private fun insertPatient(userId: Int, name: String, phoneNumber: String) {
        val writableDatabase = database.openHelper.writableDatabase
        val values = ContentValues()
        writableDatabase.query("PRAGMA table_info(`patients`)").use { cursor ->
            val columnNameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                when (val columnName = cursor.getString(columnNameIndex)) {
                    "userId" -> values.put(columnName, userId)
                    "phoneNumber" -> values.put(columnName, phoneNumber)
                    "name" -> values.put(columnName, name)
                    "sex" -> values.put(columnName, "Male")
                    else -> values.put(columnName, 1.0)
                }
            }
        }
        assertTrue(writableDatabase.insert("patients", SQLiteDatabase.CONFLICT_ABORT, values) != -1L)
    }

    private class SequenceIdGenerator : IdGenerator {
        private var next = 0
        override fun newId(): String = "test-id-${++next}"
    }

    private companion object {
        const val USER_ONE = 701
        const val USER_TWO = 702
        val FIXED_CLOCK: Clock = Clock.fixed(
            Instant.parse("2026-09-12T10:00:00Z"),
            ZoneOffset.UTC
        )
    }
}
