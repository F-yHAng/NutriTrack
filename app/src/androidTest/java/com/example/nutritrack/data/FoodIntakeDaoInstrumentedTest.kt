package com.example.nutritrack.data

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class FoodIntakeDaoInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: NutrackDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NutrackDatabase::class.java).build()
        insertPatient(database, USER_ONE)
        insertPatient(database, USER_TWO)
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun multipleRecords_dateQueryIsStableAndIsolatedByUser() = runBlocking {
        val dao = database.foodIntakeDao()
        val olderId = dao.insert(intake(USER_ONE, DAY_START + 1_000, "Breakfast"))
        val firstSameTimeId = dao.insert(intake(USER_ONE, DAY_START + 2_000, "Lunch"))
        val secondSameTimeId = dao.insert(intake(USER_ONE, DAY_START + 2_000, "Snack"))
        dao.insert(intake(USER_ONE, NEXT_DAY_START, "Next day"))
        dao.insert(intake(USER_TWO, DAY_START + 3_000, "Other user"))

        val records = dao.observeByUserAndDateRange(
            userId = USER_ONE,
            startInclusive = DAY_START,
            endExclusive = NEXT_DAY_START
        ).first()

        assertEquals(listOf(secondSameTimeId, firstSameTimeId, olderId), records.map(FoodIntake::id))
        assertTrue(records.all { it.patientUserId == USER_ONE })
        assertEquals(4, dao.observeByUser(USER_ONE).first().size)
        assertEquals(1, dao.observeByUser(USER_TWO).first().size)
    }

    @Test
    fun scopedReadAndUpdate_cannotAccessAnotherUsersRecord() = runBlocking {
        val dao = database.foodIntakeDao()
        val recordId = dao.insert(intake(USER_ONE, DAY_START, "Original"))
        val original = requireNotNull(dao.getByIdForUser(recordId, USER_ONE))

        assertNull(dao.getByIdForUser(recordId, USER_TWO))
        assertFalse(
            dao.updateForUser(
                USER_TWO,
                original.copy(patientUserId = USER_TWO, foodName = "Unauthorized")
            )
        )
        assertEquals("Original", dao.getByIdForUser(recordId, USER_ONE)?.foodName)

        assertTrue(dao.updateForUser(USER_ONE, original.copy(foodName = "Updated")))
        assertEquals("Updated", dao.getByIdForUser(recordId, USER_ONE)?.foodName)
    }

    @Test
    fun fileDatabase_recordSurvivesCloseAndReopen() = runBlocking {
        val databaseName = "daily-intake-persistence-test"
        context.deleteDatabase(databaseName)
        var fileDatabase = Room.databaseBuilder(
            context,
            NutrackDatabase::class.java,
            databaseName
        ).build()
        insertPatient(fileDatabase, USER_ONE)
        val recordId = fileDatabase.foodIntakeDao().insert(
            intake(USER_ONE, DAY_START, "Persistent meal")
        )
        fileDatabase.close()

        fileDatabase = Room.databaseBuilder(
            context,
            NutrackDatabase::class.java,
            databaseName
        ).build()
        try {
            val restored = fileDatabase.foodIntakeDao().getByIdForUser(recordId, USER_ONE)
            assertEquals("Persistent meal", restored?.foodName)
            assertEquals(2.0, restored?.vegetablesServings ?: -1.0, 0.0)
        } finally {
            fileDatabase.close()
            context.deleteDatabase(databaseName)
        }
    }

    private fun intake(userId: Int, timestamp: Long, foodName: String) = FoodIntake(
        patientUserId = userId,
        submissionDate = timestamp,
        foodName = foodName,
        vegetablesServings = 2.0,
        waterMl = 500.0,
        totalBeverageMl = 500.0
    )

    private fun insertPatient(targetDatabase: NutrackDatabase, userId: Int) {
        val writableDatabase = targetDatabase.openHelper.writableDatabase
        val values = ContentValues()
        writableDatabase.query("PRAGMA table_info(`patients`)").use { cursor ->
            val columnNameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                when (val columnName = cursor.getString(columnNameIndex)) {
                    "userId" -> values.put(columnName, userId)
                    "phoneNumber" -> values.put(columnName, "0400000$userId")
                    "name" -> values.put(columnName, "User $userId")
                    "sex" -> values.put(columnName, "Male")
                    else -> values.put(columnName, 1.0)
                }
            }
        }
        assertTrue(
            writableDatabase.insert("patients", SQLiteDatabase.CONFLICT_ABORT, values) != -1L
        )
    }

    companion object {
        private const val USER_ONE = 301
        private const val USER_TWO = 302
        private const val DAY_START = 1_788_537_600_000L
        private const val NEXT_DAY_START = DAY_START + 86_400_000L
    }
}
