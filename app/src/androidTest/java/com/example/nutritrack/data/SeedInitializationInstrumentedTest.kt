package com.example.nutritrack.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeedInitializationInstrumentedTest {
    private lateinit var context: Context
    private lateinit var database: NutrackDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, NutrackDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun initializeTwice_importsExactlyOnce() = runBlocking {
        val repository = createRepository(database.seedMetadataDao())

        assertEquals(SeedInitializationResult.IMPORTED, repository.initializeDatabase())
        assertEquals(9, database.patientDao().getPatientCount())
        assertEquals(
            SeedInitializationResult.ALREADY_INITIALIZED,
            repository.initializeDatabase()
        )
        assertEquals(9, database.patientDao().getPatientCount())
        assertEquals(
            9,
            database.seedMetadataDao()
                .getByName(NutrackRepository.SEED_NAME)
                ?.sourceRowCount
        )
    }

    @Test
    fun metadataFailure_rollsBackPatientsAndAllowsRetry() = runBlocking {
        val failingMetadataDao = object : SeedMetadataDao {
            override suspend fun getByName(seedName: String): SeedMetadata? = null

            override suspend fun upsert(metadata: SeedMetadata) {
                error("simulated metadata write failure")
            }

            override suspend fun deleteByName(seedName: String) = Unit
        }
        val failingRepository = createRepository(failingMetadataDao)

        try {
            failingRepository.initializeDatabase()
            throw AssertionError("Expected seed initialization to fail")
        } catch (expected: IllegalStateException) {
            assertEquals("simulated metadata write failure", expected.message)
        }
        assertEquals(0, database.patientDao().getPatientCount())
        assertEquals(
            SeedInitializationResult.IMPORTED,
            createRepository(database.seedMetadataDao()).initializeDatabase()
        )
        assertEquals(9, database.patientDao().getPatientCount())
        assertNotNull(database.seedMetadataDao().getByName(NutrackRepository.SEED_NAME))
    }

    private fun createRepository(seedMetadataDao: SeedMetadataDao): NutrackRepository {
        return NutrackRepository(
            context = context,
            database = database,
            patientDao = database.patientDao(),
            foodIntakeDao = database.foodIntakeDao(),
            userAccountDao = database.userAccountDao(),
            nutriCoachTipDao = database.nutriCoachTipDao(),
            seedMetadataDao = seedMetadataDao,
            questionnaireProfileDao = database.questionnaireProfileDao(),
            nowEpochMillis = { 1_788_516_325_000L }
        )
    }
}
