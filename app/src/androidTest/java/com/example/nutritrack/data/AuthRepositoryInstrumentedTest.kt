package com.example.nutritrack.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AuthRepositoryInstrumentedTest {
    private lateinit var database: NutrackDatabase
    private lateinit var repository: NutrackRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        database = Room.inMemoryDatabaseBuilder(context, NutrackDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        repository = NutrackRepository(
            context = context,
            database = database,
            patientDao = database.patientDao(),
            foodIntakeDao = database.foodIntakeDao(),
            userAccountDao = database.userAccountDao(),
            nutriCoachTipDao = database.nutriCoachTipDao(),
            seedMetadataDao = database.seedMetadataDao(),
            questionnaireProfileDao = database.questionnaireProfileDao()
        )
        runBlocking { repository.initializeDatabase() }
    }

    @After
    fun tearDown() {
        database.close()
    }

    @Test
    fun registrationAndLogin_useRoomBackedAccountState() = runBlocking {
        val patient = repository.getAllPatients().first().first()
        val userId = patient.userId

        assertTrue(repository.isRegistrationIdentityValid(userId, patient.phoneNumber))
        assertFalse(repository.isUserRegistered(userId))
        assertTrue(repository.registerUser(userId, patient.phoneNumber, "Test User", PASSWORD))
        assertTrue(repository.isUserRegistered(userId))
        assertEquals(userId, repository.validateLogin(userId, PASSWORD)?.userId)
        assertNull(repository.validateLogin(userId, "wrong"))
        assertEquals("Test User", repository.getUserAccount(userId)?.name)
    }

    @Test
    fun newRegistration_storesOnlyVersionedPasswordCredential() = runBlocking {
        val patient = repository.getAllPatients().first().first()
        assertTrue(repository.registerUser(patient.userId, patient.phoneNumber, "Secure User", PASSWORD))

        val account = requireNotNull(repository.getUserAccount(patient.userId))
        assertFalse(account.passwordHash.contentEquals(PASSWORD.toByteArray()))
        assertTrue(account.passwordSalt.size >= PasswordHasher.SALT_LENGTH_BYTES)
        assertEquals(PasswordHasher.ALGORITHM, account.passwordAlgorithm)
        assertEquals(PasswordHasher.ALGORITHM_VERSION, account.passwordAlgorithmVersion)
        assertEquals(PasswordHasher.DEFAULT_ITERATIONS, account.passwordIterations)
        assertEquals(PasswordHasher.DEFAULT_KEY_LENGTH_BITS, account.passwordKeyLengthBits)
        assertNotEquals(account.passwordHash.toList(), account.passwordSalt.toList())

        val columns = database.openHelper.writableDatabase
            .query("PRAGMA table_info(`user_accounts`)")
            .use { cursor ->
                buildSet {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
        assertFalse("The plaintext password column must not exist", "password" in columns)
        assertTrue("passwordHash" in columns)
        assertTrue("passwordSalt" in columns)
    }

    private companion object {
        const val PASSWORD = "Strong1!"
    }
}
