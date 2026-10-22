package com.example.nutritrack

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.withTransaction
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.nutritrack.data.FoodIntake
import com.example.nutritrack.data.AgentRunStatus
import com.example.nutritrack.data.AgentToolCallStatus
import com.example.nutritrack.data.NutrackDatabase
import com.example.nutritrack.data.NutritionPlanStatus
import com.example.nutritrack.data.QuestionnaireProfile
import com.example.nutritrack.data.QuestionnaireProfileRules
import com.example.nutritrack.agent.AgentFailureCodec
import com.example.nutritrack.agent.NutritionPlanOutputParser
import com.example.nutritrack.agent.NutritionPlanningRequest
import com.example.nutritrack.agent.NutritionPlanningGoal
import java.security.MessageDigest
import java.time.LocalDate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Explicit acceptance-environment preparation. It only creates the fixed synthetic account and
 * never runs unless prepareRealAgentMvp=true and a temporary password argument are both supplied.
 */
@RunWith(AndroidJUnit4::class)
class RealAgentAcceptanceSetupTest {
    @Test
    fun prepareSyntheticAccountWithoutCallingFirebase() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ENABLE_ARGUMENT) == "true")
        val password = requireNotNull(arguments.getString(PASSWORD_ARGUMENT))
        require(password.length >= 12) { "A temporary synthetic password is required" }

        val application = ApplicationProvider.getApplicationContext<NutrackApplication>()
        application.repository.initializeDatabase()
        val database = NutrackDatabase.getDatabase(application)
        val baselineFingerprint = database.withTransaction {
            val existingPatient = database.patientDao().getPatientByUserId(SYNTHETIC_USER_ID)
            if (existingPatient == null) {
                insertSyntheticPatient(database)
                assertTrue(
                    application.repository.registerUser(
                        userId = SYNTHETIC_USER_ID,
                        phoneNumber = SYNTHETIC_PHONE,
                        name = SYNTHETIC_NAME,
                        password = password
                    )
                )
                database.questionnaireProfileDao().upsert(syntheticProfile())
                val now = System.currentTimeMillis()
                database.foodIntakeDao().insert(
                    FoodIntake(
                        patientUserId = SYNTHETIC_USER_ID,
                        submissionDate = now - ONE_DAY_MILLIS,
                        foodName = "Synthetic breakfast",
                        fruitServings = 1.0,
                        grainsServings = 2.0,
                        waterMl = 450.0,
                        sugarG = 6.0
                    )
                )
                database.foodIntakeDao().insert(
                    FoodIntake(
                        patientUserId = SYNTHETIC_USER_ID,
                        submissionDate = now,
                        foodName = "Synthetic lunch",
                        vegetablesServings = 2.0,
                        grainsServings = 1.0,
                        waterMl = 550.0,
                        sodiumMg = 620.0
                    )
                )
            } else {
                check(database.agentRunDao().getByUser(SYNTHETIC_USER_ID).isEmpty()) {
                    "Synthetic acceptance account has audit history; refusing to reset its baseline"
                }
                check(database.nutritionPlanDao().getByUser(SYNTHETIC_USER_ID).isEmpty()) {
                    "Synthetic acceptance account has a draft; refusing to reset its baseline"
                }
                check(database.userAccountDao().getUserById(SYNTHETIC_USER_ID) != null)
                check(
                    QuestionnaireProfileRules.isValid(
                        requireNotNull(
                            database.questionnaireProfileDao().getByUserId(SYNTHETIC_USER_ID)
                        )
                    )
                )
                check(
                    database.foodIntakeDao()
                        .getByUserAndDateRange(SYNTHETIC_USER_ID, 0L, Long.MAX_VALUE)
                        .size == 2
                )
            }
            businessFingerprint(database)
        }
        application.sessionManager.clearSession()

        assertNotNull(database.userAccountDao().getUserById(SYNTHETIC_USER_ID))
        assertTrue(
            QuestionnaireProfileRules.isValid(
                requireNotNull(database.questionnaireProfileDao().getByUserId(SYNTHETIC_USER_ID))
            )
        )
        assertEquals(
            2,
            database.foodIntakeDao()
                .getByUserAndDateRange(SYNTHETIC_USER_ID, 0L, Long.MAX_VALUE)
                .size
        )
        assertTrue(database.agentRunDao().getByUser(SYNTHETIC_USER_ID).isEmpty())
        assertTrue(database.nutritionPlanDao().getByUser(SYNTHETIC_USER_ID).isEmpty())
        application.openFileOutput(BUSINESS_FINGERPRINT_FILE, android.content.Context.MODE_PRIVATE)
            .bufferedWriter()
            .use { it.write(baselineFingerprint) }
    }

    private fun insertSyntheticPatient(database: NutrackDatabase) {
        val values = ContentValues()
        val sqlite = database.openHelper.writableDatabase
        sqlite.query("PRAGMA table_info(`patients`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                when (val column = cursor.getString(nameIndex)) {
                    "userId" -> values.put(column, SYNTHETIC_USER_ID)
                    "phoneNumber" -> values.put(column, SYNTHETIC_PHONE)
                    "name" -> values.put(column, SYNTHETIC_NAME)
                    "sex" -> values.put(column, "Female")
                    "waterTotalmL" -> values.put(column, 1_400.0)
                    "beverageTotalmL" -> values.put(column, 1_600.0)
                    else -> values.put(column, 1.0)
                }
            }
        }
        check(sqlite.insert("patients", SQLiteDatabase.CONFLICT_ABORT, values) != -1L) {
            "Unable to create the synthetic patient"
        }
    }

    private fun syntheticProfile() = QuestionnaireProfile(
        userId = SYNTHETIC_USER_ID,
        canEatFruits = true,
        canEatVegetables = true,
        canEatGrains = true,
        canEatRedMeat = false,
        canEatSeafood = false,
        canEatPoultry = true,
        canEatFish = false,
        canEatEggs = true,
        canEatNutsAndSeeds = true,
        selectedPersona = "Balance Seeker",
        biggestMealTime = "12:00",
        sleepTime = "22:00",
        wakeUpTime = "07:00",
        legacyPreferencesMigrationVersion =
            QuestionnaireProfileRules.LEGACY_PREFERENCES_MIGRATION_VERSION,
        updatedAtEpochMillis = System.currentTimeMillis()
    )

    private companion object {
        const val ENABLE_ARGUMENT = "prepareRealAgentMvp"
        const val PASSWORD_ARGUMENT = "syntheticPassword"
        const val SYNTHETIC_USER_ID = 988_401
        const val SYNTHETIC_PHONE = "0000000000"
        const val SYNTHETIC_NAME = "Agent Demo"
        const val ONE_DAY_MILLIS = 86_400_000L
        const val BUSINESS_FINGERPRINT_FILE = "real_agent_business_fingerprint.txt"
    }
}

/** Reads only status/count metadata after the production UI run; it never calls Firebase. */
@RunWith(AndroidJUnit4::class)
class RealAgentAcceptanceVerificationTest {
    @Test
    fun verifyPersistedDraftAndBusinessIsolation() = runBlocking {
        val arguments = InstrumentationRegistry.getArguments()
        assumeTrue(arguments.getString(ENABLE_ARGUMENT) == "true")
        val application = ApplicationProvider.getApplicationContext<NutrackApplication>()
        val database = NutrackDatabase.getDatabase(application)
        val runs = database.agentRunDao().getByUser(SYNTHETIC_USER_ID)
        val run = runs.first()
        val calls = database.agentToolCallDao().getByRun(run.id)
        val plans = database.nutritionPlanDao().getByUser(SYNTHETIC_USER_ID)
        val baselineFingerprint = application.openFileInput(BUSINESS_FINGERPRINT_FILE)
            .bufferedReader()
            .use { it.readText() }
        val businessUnchanged = baselineFingerprint == businessFingerprint(database)
        val failure = AgentFailureCodec.decode(run.errorCode)

        application.openFileOutput(SAFE_OUTCOME_FILE, android.content.Context.MODE_PRIVATE)
            .bufferedWriter()
            .use { output ->
                output.appendLine("run=${run.status.name}")
                output.appendLine("runCount=${runs.size}")
                output.appendLine(
                    "durationMillis=${run.finishedAtEpochMillis?.minus(run.createdAtEpochMillis) ?: -1L}"
                )
                output.appendLine("toolCalls=${calls.size}")
                output.appendLine("succeeded=${calls.count { it.status == AgentToolCallStatus.SUCCEEDED }}")
                output.appendLine("failed=${calls.count { it.status == AgentToolCallStatus.FAILED }}")
                output.appendLine("tools=${calls.joinToString(",") { it.toolName }}")
                output.appendLine("drafts=${plans.count { it.status == NutritionPlanStatus.DRAFT }}")
                output.appendLine("failureStage=${failure?.stage?.name ?: "NONE"}")
                output.appendLine("errorCode=${failure?.code?.name ?: "NONE"}")
                output.appendLine(failure?.safetyDiagnostic?.safeSummary ?: "safetyDiagnostic=NOT_PROVIDED")
                output.appendLine("businessUnchanged=$businessUnchanged")
                runs.asReversed().forEachIndexed { index, previous ->
                    val previousCalls = database.agentToolCallDao().getByRun(previous.id)
                    val previousFailure = AgentFailureCodec.decode(previous.errorCode)
                    output.appendLine(
                        "historyRun=${index + 1} status=${previous.status.name}" +
                            " tools=${previousCalls.size}" +
                            " succeeded=${previousCalls.count { it.status == AgentToolCallStatus.SUCCEEDED }}" +
                            " stage=${previousFailure?.stage?.name ?: "NONE"}" +
                            " code=${previousFailure?.code?.name ?: "NONE"}"
                    )
                }
            }

        assertEquals(AgentRunStatus.SUCCEEDED, run.status)
        assertTrue(calls.isNotEmpty())
        assertTrue(calls.all { it.status == AgentToolCallStatus.SUCCEEDED })
        assertEquals(1, plans.count { it.status == NutritionPlanStatus.DRAFT })
        assertTrue(businessUnchanged)
        val draft = checkNotNull(database.nutritionPlanDao().getByRun(run.id))
        val parsed = NutritionPlanOutputParser().parse(
            draft.contentJson,
            NutritionPlanningRequest("Synthetic acceptance verification", NutritionPlanningGoal.USER_DEFINED,
                LocalDate.ofEpochDay(draft.validFromEpochDay), LocalDate.ofEpochDay(draft.validUntilEpochDay)),
            calls.filter { it.status == AgentToolCallStatus.SUCCEEDED }.map { it.id }.toSet()
        )
        assertTrue(parsed.plan.evidenceToolCallIds.isNotEmpty())
        assertTrue(parsed.plan.disclaimer.isNotBlank())
        assertEquals(draft.contentJson, parsed.normalizedJson)
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest((run.id + "|" + draft.contentJson).toByteArray())
        if (arguments.getString("verifyRestoredDraft") == "true") {
            val previous = application.openFileInput(DRAFT_FINGERPRINT_FILE).use { it.readBytes() }
            assertTrue(MessageDigest.isEqual(previous, fingerprint))
        } else {
            application.openFileOutput(DRAFT_FINGERPRINT_FILE, android.content.Context.MODE_PRIVATE)
                .use { it.write(fingerprint) }
        }
        application.openFileOutput(SAFE_OUTCOME_FILE, android.content.Context.MODE_APPEND)
            .bufferedWriter().use {
                it.appendLine("storedPlanRevalidated=true evidenceFromCurrentRun=true")
                it.appendLine("sameDraftRestored=${arguments.getString("verifyRestoredDraft") == "true"}")
            }
        Unit
    }

    private companion object {
        const val ENABLE_ARGUMENT = "verifyRealAgentMvp"
        const val SYNTHETIC_USER_ID = 988_401
        const val BUSINESS_FINGERPRINT_FILE = "real_agent_business_fingerprint.txt"
        const val SAFE_OUTCOME_FILE = "real_agent_ui_acceptance_status.txt"
        const val DRAFT_FINGERPRINT_FILE = "real_agent_draft_fingerprint.bin"
    }
}

private suspend fun businessFingerprint(database: NutrackDatabase): String {
    val digest = MessageDigest.getInstance("SHA-256")
    fun add(value: String) = digest.update(value.toByteArray(Charsets.UTF_8))
    val patient = requireNotNull(database.patientDao().getPatientByUserId(988_401))
    val profile = requireNotNull(database.questionnaireProfileDao().getByUserId(988_401))
    val account = requireNotNull(database.userAccountDao().getUserById(988_401))
    val intakes = database.foodIntakeDao().getByUserAndDateRange(988_401, 0L, Long.MAX_VALUE)
    add(patient.toString())
    add(profile.toString())
    add(account.userId.toString())
    add(account.phoneNumber)
    add(account.name)
    digest.update(account.passwordHash)
    digest.update(account.passwordSalt)
    add(account.passwordAlgorithm)
    add(account.passwordAlgorithmVersion.toString())
    add(account.passwordIterations.toString())
    add(account.passwordKeyLengthBits.toString())
    add(account.registrationDate.toString())
    intakes.forEach { add(it.toString()) }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}
