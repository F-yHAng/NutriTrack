package com.example.nutritrack.agent

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.nutritrack.data.AgentToolCallStatus
import com.example.nutritrack.data.FoodIntake
import com.example.nutritrack.data.FruitNutrition
import com.example.nutritrack.data.FruitNutritionFailure
import com.example.nutritrack.data.FruitNutritionGateway
import com.example.nutritrack.data.FruitNutritionResult
import com.example.nutritrack.data.NutrackDatabase
import com.example.nutritrack.data.QuestionnaireProfile
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AgentToolDispatcherInstrumentedTest {
    private lateinit var database: NutrackDatabase
    private lateinit var repository: AgentRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NutrackDatabase::class.java).build()
        insertPatient(USER_ONE, PRIVATE_NAME, PRIVATE_PHONE)
        insertPatient(USER_TWO, "Second Private Person", "0400000802")
        runBlocking {
            database.questionnaireProfileDao().upsert(validProfile(USER_ONE))
            database.questionnaireProfileDao().upsert(validProfile(USER_TWO))
            database.foodIntakeDao().insert(
                intake(USER_ONE, "Private meal note", fruit = 2.0, vegetables = 1.0)
            )
            database.foodIntakeDao().insert(
                intake(USER_TWO, "Other user's private meal", fruit = 50.0, vegetables = 50.0)
            )
        }
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
    fun fourReadOnlyTools_executeForTrustedUserAndPersistMinimalOrderedTrace() = runBlocking {
        val run = repository.startRun(USER_ONE)
        val context = AgentContext(USER_ONE, run.id)
        val dispatcher = dispatcher(FruitNutritionResult.Found(TEST_FRUIT))

        val results = listOf(
            dispatcher.dispatch(context, AgentToolInvocation("get_nutrition_profile", "{}")),
            dispatcher.dispatch(
                context,
                AgentToolInvocation(
                    "get_intake_history",
                    """{"startDate":"2026-09-01","endDate":"2026-09-02"}"""
                )
            ),
            dispatcher.dispatch(
                context,
                AgentToolInvocation(
                    "calculate_nutrition_trend",
                    """{"startDate":"2026-09-01","endDate":"2026-09-02"}"""
                )
            ),
            dispatcher.dispatch(
                context,
                AgentToolInvocation("search_fruit_nutrition", """{"query":"apple"}""")
            )
        )

        assertTrue(results.all { it is AgentDispatchResult.Success })
        val history = (results[1] as AgentDispatchResult.Success).result as AgentToolResult.IntakeHistory
        assertEquals(1, history.days.single().recordCount)
        assertEquals(2.0, history.days.single().fruitServings, 0.0)

        val trace = repository.getTrace(context)
        assertEquals(listOf(1, 2, 3, 4), trace.map { it.sequence })
        assertTrue(trace.all { it.status == AgentToolCallStatus.SUCCEEDED })
        val persistedAudit = trace.joinToString { "${it.argumentsJson}${it.resultJson.orEmpty()}" }
        listOf(
            PRIVATE_NAME,
            PRIVATE_PHONE,
            "Second Private Person",
            "0400000802",
            "Private meal note",
            "Other user's private meal",
            "userId",
            "phoneNumber",
            "password"
        ).forEach { forbidden -> assertFalse(persistedAudit.contains(forbidden)) }
    }

    @Test
    fun invalidAndUnknownCalls_areRejectedBeforeDataAccessAndAuditedWithoutRawPayload() = runBlocking {
        val run = repository.startRun(USER_ONE)
        val context = AgentContext(USER_ONE, run.id)
        val dataSource = CountingReadDataSource()
        val dispatcher = dispatcher(
            fruitResult = FruitNutritionResult.Empty,
            readDataSource = dataSource
        )

        val results = listOf(
            dispatcher.dispatch(
                context,
                AgentToolInvocation("unknown_tool", """{"phone":"$PRIVATE_PHONE"}""")
            ),
            dispatcher.dispatch(
                context,
                AgentToolInvocation("get_nutrition_profile", """{"userId":$USER_TWO}""")
            ),
            dispatcher.dispatch(
                context,
                AgentToolInvocation(
                    "get_intake_history",
                    """{"startDate":"2026-08-01","endDate":"2026-09-01"}"""
                )
            )
        )

        assertEquals(
            listOf(
                AgentToolErrorCode.UNKNOWN_TOOL,
                AgentToolErrorCode.USER_ID_NOT_ALLOWED,
                AgentToolErrorCode.DATE_RANGE_EXCEEDED
            ),
            results.map { (it as AgentDispatchResult.Rejected).errorCode }
        )
        assertEquals(0, dataSource.readCount)
        val trace = repository.getTrace(context)
        assertEquals(listOf(1, 2, 3), trace.map { it.sequence })
        assertTrue(trace.all { it.status == AgentToolCallStatus.FAILED })
        assertTrue(trace.all { it.argumentsJson == "{}" })
        assertFalse(trace.joinToString().contains(PRIVATE_PHONE))
        assertFalse(trace.joinToString().contains(USER_TWO.toString()))
    }

    @Test
    fun mismatchedContext_isRejectedWithoutReadingOrWritingAnotherUsersTrace() = runBlocking {
        val run = repository.startRun(USER_ONE)
        val dataSource = CountingReadDataSource()
        val result = dispatcher(
            FruitNutritionResult.Empty,
            dataSource
        ).dispatch(
            AgentContext(USER_TWO, run.id),
            AgentToolInvocation("get_nutrition_profile", "{}")
        )

        assertEquals(
            AgentToolErrorCode.CONTEXT_DENIED,
            (result as AgentDispatchResult.Rejected).errorCode
        )
        assertEquals(0, dataSource.readCount)
        assertTrue(database.agentToolCallDao().getByRun(run.id).isEmpty())
    }

    @Test
    fun fruitGatewayFailure_isMappedAndRecordedWithoutNetworkRetry() = runBlocking {
        val run = repository.startRun(USER_ONE)
        var requests = 0
        val gateway = FruitNutritionGateway {
            requests++
            FruitNutritionResult.Failure(FruitNutritionFailure.NO_NETWORK)
        }
        val dispatcher = AgentToolDispatcher(
            AgentToolRegistry(),
            repository,
            RoomAgentReadDataSource(
                database.patientDao(),
                database.questionnaireProfileDao(),
                database.foodIntakeDao(),
                ZoneOffset.UTC
            ),
            gateway,
            FIXED_CLOCK
        )
        val context = AgentContext(USER_ONE, run.id)

        val result = dispatcher.dispatch(
            context,
            AgentToolInvocation("search_fruit_nutrition", """{"query":"apple"}""")
        )

        assertEquals(1, requests)
        assertEquals(
            AgentToolErrorCode.FRUIT_NO_NETWORK,
            (result as AgentDispatchResult.Failure).errorCode
        )
        assertEquals("FRUIT_NO_NETWORK", repository.getTrace(context).single().errorCode)
    }

    private fun dispatcher(
        fruitResult: FruitNutritionResult,
        readDataSource: AgentReadDataSource = RoomAgentReadDataSource(
            database.patientDao(),
            database.questionnaireProfileDao(),
            database.foodIntakeDao(),
            ZoneOffset.UTC
        )
    ) = AgentToolDispatcher(
        registry = AgentToolRegistry(),
        repository = repository,
        readDataSource = readDataSource,
        fruitNutritionGateway = FruitNutritionGateway { fruitResult },
        clock = FIXED_CLOCK
    )

    private fun intake(
        userId: Int,
        foodName: String,
        fruit: Double,
        vegetables: Double
    ) = FoodIntake(
        patientUserId = userId,
        submissionDate = Instant.parse("2026-09-01T00:00:00Z").toEpochMilli(),
        foodName = foodName,
        fruitServings = fruit,
        vegetablesServings = vegetables,
        waterMl = 500.0,
        totalBeverageMl = 500.0,
        sugarG = 2.0
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

    private class CountingReadDataSource : AgentReadDataSource {
        var readCount = 0

        override suspend fun getNutritionProfile(userId: Int): AgentNutritionProfile? {
            readCount++
            return null
        }

        override suspend fun getIntakeHistory(
            userId: Int,
            startDate: java.time.LocalDate,
            endDate: java.time.LocalDate
        ): List<AgentDailyIntake> {
            readCount++
            return emptyList()
        }
    }

    private class SequenceIdGenerator : IdGenerator {
        private var next = 0
        override fun newId(): String = "dispatcher-id-${++next}"
    }

    private companion object {
        const val USER_ONE = 801
        const val USER_TWO = 802
        const val PRIVATE_NAME = "Private Person"
        const val PRIVATE_PHONE = "0400000801"
        val FIXED_CLOCK: Clock = Clock.fixed(
            Instant.parse("2026-09-12T10:00:00Z"),
            ZoneOffset.UTC
        )
        val TEST_FRUIT = FruitNutrition(
            name = "Apple",
            family = "Rosaceae",
            calories = 52.0,
            fat = 0.2,
            sugar = 10.3,
            carbohydrates = 13.8,
            protein = 0.3
        )
    }
}
