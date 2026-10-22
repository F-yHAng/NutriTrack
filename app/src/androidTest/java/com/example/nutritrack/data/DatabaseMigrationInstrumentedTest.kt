package com.example.nutritrack.data

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DatabaseMigrationInstrumentedTest {
    @get:Rule
    val migrationHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NutrackDatabase::class.java,
        emptyList()
    )

    @Test
    fun migrate5To6_preservesExistingDataAndCreatesEmptyMetadataTable() {
        val databaseName = "nutritrack-migration-5-6"
        deleteDatabase(databaseName)
        migrationHelper.createDatabase(databaseName, 5).apply {
            insertPatient(userId = 42, name = "Existing User")
            execSQL(
                """
                INSERT INTO user_accounts
                    (userId, phoneNumber, name, password, registrationDate, lastLoginTime)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(42, "0400000000", "Existing User", "existing-password", 100L, 200L)
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            6,
            true,
            DatabaseMigrations.MIGRATION_5_6
        ).use { migratedDatabase ->
            migratedDatabase.query("SELECT name FROM patients WHERE userId = 42").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Existing User", cursor.getString(0))
                assertFalse(cursor.moveToNext())
            }
            migratedDatabase.query(
                "SELECT lastLoginTime FROM user_accounts WHERE userId = 42"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(200L, cursor.getLong(0))
            }
            migratedDatabase.query("SELECT COUNT(*) FROM seed_metadata").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate6To7_createsQuestionnaireProfileTable() {
        val databaseName = "nutritrack-migration-6-7-schema"
        deleteDatabase(databaseName)
        migrationHelper.createDatabase(databaseName, 6).close()

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            7,
            true,
            DatabaseMigrations.MIGRATION_6_7
        ).use { migratedDatabase ->
            migratedDatabase.query("SELECT COUNT(*) FROM questionnaire_profiles").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(0, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate6To7_preservesAllVersion6Data() {
        val databaseName = "nutritrack-migration-6-7-data"
        deleteDatabase(databaseName)
        migrationHelper.createDatabase(databaseName, 6).apply {
            insertPatient(userId = 42, name = "Preserved Patient")
            insertFixtureRow(
                tableName = "user_accounts",
                overrides = mapOf(
                    "userId" to 42,
                    "phoneNumber" to "0400000042",
                    "name" to "Preserved Account",
                    "password" to "existing-password",
                    "registrationDate" to 100L,
                    "lastLoginTime" to 200L
                )
            )
            insertFixtureRow(
                tableName = "food_intake",
                overrides = mapOf(
                    "id" to 7L,
                    "patientUserId" to 42,
                    "submissionDate" to 300L,
                    "questionnaireResponses" to "preserved-intake"
                )
            )
            insertFixtureRow(
                tableName = "nutricoach_tips",
                overrides = mapOf(
                    "id" to 9L,
                    "patientUserId" to 42,
                    "tipContent" to "Preserved tip",
                    "tipType" to "general_nutrition",
                    "timestamp" to 400L,
                    "aiModel" to "existing-model"
                )
            )
            insertFixtureRow(
                tableName = "seed_metadata",
                overrides = mapOf(
                    "seedName" to "patient_csv",
                    "version" to 1,
                    "importedAtEpochMillis" to 500L,
                    "sourceRowCount" to 9
                )
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            7,
            true,
            DatabaseMigrations.MIGRATION_6_7
        ).use { migratedDatabase ->
            assertSingleValue(migratedDatabase, "SELECT name FROM patients WHERE userId = 42", "Preserved Patient")
            assertSingleValue(migratedDatabase, "SELECT name FROM user_accounts WHERE userId = 42", "Preserved Account")
            assertSingleValue(migratedDatabase, "SELECT questionnaireResponses FROM food_intake WHERE id = 7", "preserved-intake")
            assertSingleValue(migratedDatabase, "SELECT tipContent FROM nutricoach_tips WHERE id = 9", "Preserved tip")
            assertSingleValue(migratedDatabase, "SELECT sourceRowCount FROM seed_metadata WHERE seedName = 'patient_csv'", 9)
        }
    }

    @Test
    fun migrate7To8_addsFoodNameAndPreservesAllVersion7Data() {
        val databaseName = "nutritrack-migration-7-8"
        deleteDatabase(databaseName)
        migrationHelper.createDatabase(databaseName, 7).apply {
            insertPatient(userId = 84, name = "Version Seven Patient")
            insertFixtureRow(
                "user_accounts",
                mapOf("userId" to 84, "name" to "Version Seven Account")
            )
            insertFixtureRow(
                "food_intake",
                mapOf(
                    "id" to 17L,
                    "patientUserId" to 84,
                    "submissionDate" to 600L,
                    "vegetablesServings" to 2.5,
                    "questionnaireResponses" to "preserved-v7-payload"
                )
            )
            insertFixtureRow(
                "nutricoach_tips",
                mapOf("id" to 19L, "patientUserId" to 84, "tipContent" to "Version seven tip")
            )
            insertFixtureRow(
                "seed_metadata",
                mapOf("seedName" to "patient_csv", "version" to 1, "sourceRowCount" to 9)
            )
            insertFixtureRow(
                "questionnaire_profiles",
                mapOf(
                    "userId" to 84,
                    "selectedPersona" to "Mindful Eater",
                    "biggestMealTime" to "12:00",
                    "sleepTime" to "22:00",
                    "wakeUpTime" to "07:00"
                )
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            8,
            true,
            DatabaseMigrations.MIGRATION_7_8
        ).use { migratedDatabase ->
            assertSingleValue(migratedDatabase, "SELECT name FROM patients WHERE userId = 84", "Version Seven Patient")
            assertSingleValue(migratedDatabase, "SELECT name FROM user_accounts WHERE userId = 84", "Version Seven Account")
            assertSingleValue(migratedDatabase, "SELECT CAST(vegetablesServings AS TEXT) FROM food_intake WHERE id = 17", "2.5")
            assertSingleValue(migratedDatabase, "SELECT questionnaireResponses FROM food_intake WHERE id = 17", "preserved-v7-payload")
            assertSingleValue(migratedDatabase, "SELECT foodName FROM food_intake WHERE id = 17", "Legacy intake")
            assertSingleValue(migratedDatabase, "SELECT tipContent FROM nutricoach_tips WHERE id = 19", "Version seven tip")
            assertSingleValue(migratedDatabase, "SELECT sourceRowCount FROM seed_metadata WHERE seedName = 'patient_csv'", 9)
            assertSingleValue(migratedDatabase, "SELECT selectedPersona FROM questionnaire_profiles WHERE userId = 84", "Mindful Eater")
        }
    }

    @Test
    fun migrate8To9_hashesLegacyPasswordAndPreservesAccount() {
        val databaseName = "nutritrack-migration-8-9"
        deleteDatabase(databaseName)
        migrationHelper.createDatabase(databaseName, 8).apply {
            insertPatient(userId = 108, name = "Legacy Account Patient")
            execSQL(
                """
                INSERT INTO user_accounts
                    (userId, phoneNumber, name, password, registrationDate, lastLoginTime)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(108, "0400000108", "Legacy Account", LEGACY_PASSWORD, 123L, 456L)
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            9,
            true,
            DatabaseMigrations.MIGRATION_8_9
        ).use { migratedDatabase ->
            val columns = migratedDatabase.query("PRAGMA table_info(`user_accounts`)").use { cursor ->
                buildSet {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            assertFalse("The plaintext password column must be removed", "password" in columns)
            assertTrue("passwordHash" in columns)
            assertTrue("passwordSalt" in columns)
            assertSingleValue(migratedDatabase, "SELECT name FROM user_accounts WHERE userId = 108", "Legacy Account")
            assertSingleValue(migratedDatabase, "SELECT registrationDate FROM user_accounts WHERE userId = 108", 123)
            assertSingleValue(migratedDatabase, "SELECT lastLoginTime FROM user_accounts WHERE userId = 108", 456)
            assertSingleValue(
                migratedDatabase,
                "SELECT passwordAlgorithm FROM user_accounts WHERE userId = 108",
                PasswordHasher.ALGORITHM
            )
            assertSingleValue(
                migratedDatabase,
                "SELECT passwordIterations FROM user_accounts WHERE userId = 108",
                PasswordHasher.DEFAULT_ITERATIONS
            )
        }

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val roomDatabase = Room.databaseBuilder(context, NutrackDatabase::class.java, databaseName)
            .addMigrations(
                DatabaseMigrations.MIGRATION_8_9,
                DatabaseMigrations.MIGRATION_9_10,
                DatabaseMigrations.MIGRATION_10_11,
                DatabaseMigrations.MIGRATION_11_12
            )
            .build()
        try {
            roomDatabase.let { database ->
                val repository = NutrackRepository(
                    context = context,
                    database = database,
                    patientDao = database.patientDao(),
                    foodIntakeDao = database.foodIntakeDao(),
                    userAccountDao = database.userAccountDao(),
                    nutriCoachTipDao = database.nutriCoachTipDao(),
                    seedMetadataDao = database.seedMetadataDao(),
                    questionnaireProfileDao = database.questionnaireProfileDao()
                )
                kotlinx.coroutines.runBlocking {
                    assertEquals(108, repository.validateLogin(108, LEGACY_PASSWORD)?.userId)
                    assertEquals(null, repository.validateLogin(108, "wrong-password"))
                }
            }
        } finally {
            roomDatabase.close()
        }
    }

    @Test
    fun migrate8To9_invalidLegacyPasswordRollsBackWithoutChangingSchema() {
        val databaseName = "nutritrack-migration-8-9-rollback"
        deleteDatabase(databaseName)
        migrationHelper.createDatabase(databaseName, 8).apply {
            insertPatient(userId = 109, name = "Invalid Legacy Account")
            execSQL(
                """
                INSERT INTO user_accounts
                    (userId, phoneNumber, name, password, registrationDate, lastLoginTime)
                VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(109, "0400000109", "Invalid Legacy Account", "", 123L, 456L)
            )
            close()
        }

        val failure = runCatching {
            migrationHelper.runMigrationsAndValidate(
                databaseName,
                9,
                true,
                DatabaseMigrations.MIGRATION_8_9
            ).close()
        }.exceptionOrNull()
        assertNotNull("An empty legacy password must fail migration", failure)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        SQLiteDatabase.openDatabase(
            context.getDatabasePath(databaseName).path,
            null,
            SQLiteDatabase.OPEN_READONLY
        ).use { database ->
            assertEquals(8, database.version)
            val columns = database.rawQuery("PRAGMA table_info(`user_accounts`)", null).use { cursor ->
                buildSet {
                    val nameIndex = cursor.getColumnIndexOrThrow("name")
                    while (cursor.moveToNext()) add(cursor.getString(nameIndex))
                }
            }
            assertTrue("The v8 table must remain after rollback", "password" in columns)
            database.rawQuery(
                "SELECT COUNT(*) FROM user_accounts WHERE userId = 109",
                null
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(1, cursor.getInt(0))
            }
        }
    }

    @Test
    fun migrate9To10_preservesAllVersion9DataAndCreatesAgentTables() {
        val databaseName = "nutritrack-migration-9-10"
        deleteDatabase(databaseName)
        migrationHelper.createDatabase(databaseName, 9).apply {
            insertPatient(userId = 144, name = "Version Nine Patient")
            execSQL(
                """
                INSERT INTO user_accounts (
                    userId, phoneNumber, name, passwordHash, passwordSalt,
                    passwordAlgorithm, passwordAlgorithmVersion, passwordIterations,
                    passwordKeyLengthBits, registrationDate, lastLoginTime
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    144,
                    "0400000144",
                    "Version Nine Account",
                    byteArrayOf(1, 2, 3),
                    byteArrayOf(4, 5, 6),
                    PasswordHasher.ALGORITHM,
                    PasswordHasher.ALGORITHM_VERSION,
                    PasswordHasher.DEFAULT_ITERATIONS,
                    PasswordHasher.DEFAULT_KEY_LENGTH_BITS,
                    100L,
                    200L
                )
            )
            insertFixtureRow(
                "food_intake",
                mapOf(
                    "id" to 37L,
                    "patientUserId" to 144,
                    "submissionDate" to 300L,
                    "foodName" to "Preserved meal",
                    "questionnaireResponses" to "preserved-v9-payload"
                )
            )
            insertFixtureRow(
                "nutricoach_tips",
                mapOf("id" to 39L, "patientUserId" to 144, "tipContent" to "Version nine tip")
            )
            insertFixtureRow(
                "seed_metadata",
                mapOf("seedName" to "patient_csv", "version" to 1, "sourceRowCount" to 9)
            )
            insertFixtureRow(
                "questionnaire_profiles",
                mapOf(
                    "userId" to 144,
                    "selectedPersona" to "Mindful Eater",
                    "biggestMealTime" to "12:00",
                    "sleepTime" to "22:00",
                    "wakeUpTime" to "07:00"
                )
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            10,
            true,
            DatabaseMigrations.MIGRATION_9_10
        ).use { migratedDatabase ->
            assertSingleValue(
                migratedDatabase,
                "SELECT name FROM patients WHERE userId = 144",
                "Version Nine Patient"
            )
            assertSingleValue(
                migratedDatabase,
                "SELECT name FROM user_accounts WHERE userId = 144",
                "Version Nine Account"
            )
            assertSingleValue(
                migratedDatabase,
                "SELECT foodName FROM food_intake WHERE id = 37",
                "Preserved meal"
            )
            assertSingleValue(
                migratedDatabase,
                "SELECT tipContent FROM nutricoach_tips WHERE id = 39",
                "Version nine tip"
            )
            assertSingleValue(
                migratedDatabase,
                "SELECT sourceRowCount FROM seed_metadata WHERE seedName = 'patient_csv'",
                9
            )
            assertSingleValue(
                migratedDatabase,
                "SELECT selectedPersona FROM questionnaire_profiles WHERE userId = 144",
                "Mindful Eater"
            )
            assertSingleValue(migratedDatabase, "SELECT COUNT(*) FROM agent_runs", 0)
            assertSingleValue(migratedDatabase, "SELECT COUNT(*) FROM agent_tool_calls", 0)
            assertSingleValue(migratedDatabase, "SELECT COUNT(*) FROM nutrition_plans", 0)
        }
    }

    @Test
    fun migrate10To11_preservesDraftAndAddsNullableConfirmationMetadata() {
        val databaseName = "nutritrack-migration-10-11"
        deleteDatabase(databaseName)
        migrationHelper.createDatabase(databaseName, 10).apply {
            insertPatient(userId = 155, name = "Version Ten Patient")
            execSQL(
                """
                INSERT INTO agent_runs (
                    id, userId, status, createdAtEpochMillis, finishedAtEpochMillis, errorCode
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf("v10-run", 155, "SUCCEEDED", 100L, 200L, null)
            )
            execSQL(
                """
                INSERT INTO agent_tool_calls (
                    id, runId, sequence, toolName, argumentsJson, resultJson,
                    status, errorCode, startedAtEpochMillis, finishedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf("v10-call", "v10-run", 1, "get_nutrition_profile", "{}", "{}", "SUCCEEDED", null, 110L, 120L)
            )
            execSQL(
                """
                INSERT INTO nutrition_plans (
                    id, userId, runId, status, schemaVersion, validFromEpochDay,
                    validUntilEpochDay, contentJson, cautionsJson, disclaimer, createdAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    "v10-plan", 155, "v10-run", "DRAFT", 1, 20_000L, 20_006L,
                    "{\"schemaVersion\":1}", "[]", "General guidance only.", 200L
                )
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            11,
            true,
            DatabaseMigrations.MIGRATION_10_11
        ).use { migratedDatabase ->
            assertSingleValue(
                migratedDatabase,
                "SELECT name FROM patients WHERE userId = 155",
                "Version Ten Patient"
            )
            assertSingleValue(migratedDatabase, "SELECT status FROM agent_runs WHERE id = 'v10-run'", "SUCCEEDED")
            assertSingleValue(migratedDatabase, "SELECT toolName FROM agent_tool_calls WHERE id = 'v10-call'", "get_nutrition_profile")
            assertSingleValue(migratedDatabase, "SELECT status FROM nutrition_plans WHERE id = 'v10-plan'", "DRAFT")
            assertSingleValue(migratedDatabase, "SELECT contentJson FROM nutrition_plans WHERE id = 'v10-plan'", "{\"schemaVersion\":1}")
            migratedDatabase.query(
                "SELECT confirmedAtEpochMillis, confirmationActionId FROM nutrition_plans WHERE id = 'v10-plan'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
                assertTrue(cursor.isNull(1))
            }
        }
    }

    @Test
    fun migrate10To11_matchesFreshVersion11AgentSchema() {
        val migratedName = "nutritrack-migration-10-11-schema"
        val freshName = "nutritrack-fresh-11-schema"
        deleteDatabase(migratedName)
        deleteDatabase(freshName)
        migrationHelper.createDatabase(migratedName, 10).close()

        val migratedSignature = migrationHelper.runMigrationsAndValidate(
            migratedName,
            11,
            true,
            DatabaseMigrations.MIGRATION_10_11
        ).use(::agentSchemaSignature)

        val freshSignature = migrationHelper.createDatabase(freshName, 11)
            .use(::agentSchemaSignature)
        assertEquals(migratedSignature, freshSignature)
    }

    @Test
    fun migrate11To12_preservesPlanLifecycleDataAndAddsCompletionMetadata() {
        val databaseName = "nutritrack-migration-11-12"
        deleteDatabase(databaseName)
        migrationHelper.createDatabase(databaseName, 11).apply {
            insertPatient(userId = 166, name = "Version Eleven Patient")
            execSQL(
                """
                INSERT INTO agent_runs (
                    id, userId, status, createdAtEpochMillis, finishedAtEpochMillis, errorCode
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf("v11-run", 166, "SUCCEEDED", 100L, 200L, null)
            )
            execSQL(
                """
                INSERT INTO agent_runs (
                    id, userId, status, createdAtEpochMillis, finishedAtEpochMillis, errorCode
                ) VALUES (?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf("v11-draft-run", 166, "SUCCEEDED", 300L, 400L, null)
            )
            execSQL(
                """
                INSERT INTO agent_tool_calls (
                    id, runId, sequence, toolName, argumentsJson, resultJson,
                    status, errorCode, startedAtEpochMillis, finishedAtEpochMillis
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf("v11-call", "v11-run", 1, "get_nutrition_profile", "{}", "{}", "SUCCEEDED", null, 110L, 120L)
            )
            execSQL(
                """
                INSERT INTO nutrition_plans (
                    id, userId, runId, status, schemaVersion, validFromEpochDay,
                    validUntilEpochDay, contentJson, cautionsJson, disclaimer, createdAtEpochMillis,
                    confirmedAtEpochMillis, confirmationActionId
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    "v11-plan", 166, "v11-run", "CONFIRMED", 1, 20_000L, 20_006L,
                    "{\"schemaVersion\":1}", "[]", "General guidance only.", 200L,
                    250L, "confirm:v11-plan"
                )
            )
            execSQL(
                """
                INSERT INTO nutrition_plans (
                    id, userId, runId, status, schemaVersion, validFromEpochDay,
                    validUntilEpochDay, contentJson, cautionsJson, disclaimer, createdAtEpochMillis,
                    confirmedAtEpochMillis, confirmationActionId
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                arrayOf(
                    "v11-draft-plan", 166, "v11-draft-run", "DRAFT", 1, 20_000L, 20_006L,
                    "{\"schemaVersion\":1}", "[]", "General guidance only.", 333L,
                    null, null
                )
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            12,
            true,
            DatabaseMigrations.MIGRATION_11_12
        ).use { migratedDatabase ->
            assertSingleValue(migratedDatabase, "SELECT status FROM nutrition_plans WHERE id = 'v11-plan'", "CONFIRMED")
            assertSingleValue(migratedDatabase, "SELECT confirmationActionId FROM nutrition_plans WHERE id = 'v11-plan'", "confirm:v11-plan")
            assertSingleValue(migratedDatabase, "SELECT updatedAtEpochMillis FROM nutrition_plans WHERE id = 'v11-plan'", 250L)
            assertSingleValue(
                migratedDatabase,
                "SELECT updatedAtEpochMillis FROM nutrition_plans WHERE id = 'v11-draft-plan'",
                333L
            )
            migratedDatabase.query(
                "SELECT completedAtEpochMillis FROM nutrition_plans WHERE id = 'v11-plan'"
            ).use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertTrue(cursor.isNull(0))
            }
            assertSingleValue(migratedDatabase, "SELECT toolName FROM agent_tool_calls WHERE id = 'v11-call'", "get_nutrition_profile")
        }
    }

    @Test
    fun migrate11To12_matchesFreshVersion12AgentSchema() {
        val migratedName = "nutritrack-migration-11-12-schema"
        val freshName = "nutritrack-fresh-12-schema"
        deleteDatabase(migratedName)
        deleteDatabase(freshName)
        migrationHelper.createDatabase(migratedName, 11).close()

        val migratedSignature = migrationHelper.runMigrationsAndValidate(
            migratedName,
            12,
            true,
            DatabaseMigrations.MIGRATION_11_12
        ).use(::agentSchemaSignature)

        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val fresh = Room.databaseBuilder(context, NutrackDatabase::class.java, freshName).build()
        try {
            assertEquals(migratedSignature, agentSchemaSignature(fresh.openHelper.writableDatabase))
        } finally {
            fresh.close()
        }
    }

    @Test
    fun migrate5To12_runsCompleteChainAndPreservesOriginalBusinessData() {
        val databaseName = "nutritrack-migration-5-12-chain"
        deleteDatabase(databaseName)
        migrationHelper.createDatabase(databaseName, 5).apply {
            insertPatient(userId = 126, name = "Version Five Patient")
            insertFixtureRow(
                "user_accounts",
                mapOf(
                    "userId" to 126,
                    "phoneNumber" to "0400000126",
                    "name" to "Version Five Account",
                    "password" to "existing-password",
                    "registrationDate" to 100L,
                    "lastLoginTime" to 200L
                )
            )
            insertFixtureRow(
                "food_intake",
                mapOf(
                    "id" to 27L,
                    "patientUserId" to 126,
                    "submissionDate" to 300L,
                    "questionnaireResponses" to "preserved-v5-payload"
                )
            )
            insertFixtureRow(
                "nutricoach_tips",
                mapOf(
                    "id" to 29L,
                    "patientUserId" to 126,
                    "tipContent" to "Version five tip"
                )
            )
            close()
        }

        migrationHelper.runMigrationsAndValidate(
            databaseName,
            12,
            true,
            DatabaseMigrations.MIGRATION_5_6,
            DatabaseMigrations.MIGRATION_6_7,
            DatabaseMigrations.MIGRATION_7_8,
            DatabaseMigrations.MIGRATION_8_9,
            DatabaseMigrations.MIGRATION_9_10,
            DatabaseMigrations.MIGRATION_10_11,
            DatabaseMigrations.MIGRATION_11_12
        ).use { migratedDatabase ->
            assertSingleValue(
                migratedDatabase,
                "SELECT name FROM patients WHERE userId = 126",
                "Version Five Patient"
            )
            assertSingleValue(
                migratedDatabase,
                "SELECT name FROM user_accounts WHERE userId = 126",
                "Version Five Account"
            )
            assertSingleValue(
                migratedDatabase,
                "SELECT questionnaireResponses FROM food_intake WHERE id = 27",
                "preserved-v5-payload"
            )
            assertSingleValue(
                migratedDatabase,
                "SELECT foodName FROM food_intake WHERE id = 27",
                "Legacy intake"
            )
            assertSingleValue(
                migratedDatabase,
                "SELECT tipContent FROM nutricoach_tips WHERE id = 29",
                "Version five tip"
            )
            assertSingleValue(migratedDatabase, "SELECT COUNT(*) FROM seed_metadata", 0)
            assertSingleValue(migratedDatabase, "SELECT COUNT(*) FROM questionnaire_profiles", 0)
            assertSingleValue(migratedDatabase, "SELECT COUNT(*) FROM agent_runs", 0)
            assertSingleValue(migratedDatabase, "SELECT COUNT(*) FROM agent_tool_calls", 0)
            assertSingleValue(migratedDatabase, "SELECT COUNT(*) FROM nutrition_plans", 0)
        }
    }

    private fun agentSchemaSignature(database: SupportSQLiteDatabase): List<String> =
        listOf("agent_runs", "agent_tool_calls", "nutrition_plans").flatMap { tableName ->
            buildList {
                database.query("PRAGMA table_info(`$tableName`)").use { cursor ->
                    while (cursor.moveToNext()) {
                        add(
                            listOf(
                                "column",
                                tableName,
                                cursor.getString(cursor.getColumnIndexOrThrow("name")),
                                cursor.getString(cursor.getColumnIndexOrThrow("type")),
                                cursor.getInt(cursor.getColumnIndexOrThrow("notnull")).toString(),
                                cursor.getInt(cursor.getColumnIndexOrThrow("pk")).toString(),
                                cursor.getString(cursor.getColumnIndexOrThrow("dflt_value")) ?: "NULL"
                            ).joinToString("|")
                        )
                    }
                }
                database.query("PRAGMA foreign_key_list(`$tableName`)").use { cursor ->
                    while (cursor.moveToNext()) {
                        add(
                            listOf(
                                "foreignKey",
                                tableName,
                                cursor.getString(cursor.getColumnIndexOrThrow("table")),
                                cursor.getString(cursor.getColumnIndexOrThrow("from")),
                                cursor.getString(cursor.getColumnIndexOrThrow("to")),
                                cursor.getString(cursor.getColumnIndexOrThrow("on_update")),
                                cursor.getString(cursor.getColumnIndexOrThrow("on_delete"))
                            ).joinToString("|")
                        )
                    }
                }
                database.query("PRAGMA index_list(`$tableName`)").use { cursor ->
                    while (cursor.moveToNext()) {
                        val indexName = cursor.getString(cursor.getColumnIndexOrThrow("name"))
                        if (indexName.startsWith("sqlite_autoindex")) continue
                        val unique = cursor.getInt(cursor.getColumnIndexOrThrow("unique"))
                        val columns = database.query("PRAGMA index_info(`$indexName`)").use { indexCursor ->
                            buildList {
                                while (indexCursor.moveToNext()) {
                                    add(indexCursor.getString(indexCursor.getColumnIndexOrThrow("name")))
                                }
                            }
                        }
                        add("index|$tableName|$indexName|$unique|${columns.joinToString(",")}")
                    }
                }
            }.sorted()
        }

    private fun SupportSQLiteDatabase.insertPatient(userId: Int, name: String) {
        val values = ContentValues()
        query("PRAGMA table_info(`patients`)").use { cursor ->
            val columnNameIndex = cursor.getColumnIndexOrThrow("name")
            while (cursor.moveToNext()) {
                when (val columnName = cursor.getString(columnNameIndex)) {
                    "userId" -> values.put(columnName, userId)
                    "phoneNumber" -> values.put(columnName, "0400000000")
                    "name" -> values.put(columnName, name)
                    "sex" -> values.put(columnName, "Male")
                    else -> values.put(columnName, 1.0)
                }
            }
        }
        val rowId = insert("patients", SQLiteDatabase.CONFLICT_ABORT, values)
        check(rowId != -1L) { "Failed to create the v5 patient fixture" }
    }

    private fun SupportSQLiteDatabase.insertFixtureRow(
        tableName: String,
        overrides: Map<String, Any>
    ) {
        val values = ContentValues()
        query("PRAGMA table_info(`$tableName`)").use { cursor ->
            val nameIndex = cursor.getColumnIndexOrThrow("name")
            val typeIndex = cursor.getColumnIndexOrThrow("type")
            while (cursor.moveToNext()) {
                val columnName = cursor.getString(nameIndex)
                when (val value = overrides[columnName]) {
                    is Int -> values.put(columnName, value)
                    is Long -> values.put(columnName, value)
                    is Double -> values.put(columnName, value)
                    is String -> values.put(columnName, value)
                    null -> when (cursor.getString(typeIndex)) {
                        "INTEGER" -> values.put(columnName, 1L)
                        "REAL" -> values.put(columnName, 1.0)
                        "TEXT" -> values.put(columnName, "fixture")
                    }
                    else -> error("Unsupported fixture value for $columnName")
                }
            }
        }
        check(insert(tableName, SQLiteDatabase.CONFLICT_ABORT, values) != -1L) {
            "Failed to create the $tableName fixture"
        }
    }

    private fun assertSingleValue(
        database: SupportSQLiteDatabase,
        query: String,
        expected: Any
    ) {
        database.query(query).use { cursor ->
            assertTrue(cursor.moveToFirst())
            when (expected) {
                is Int -> assertEquals(expected, cursor.getInt(0))
                is Long -> assertEquals(expected, cursor.getLong(0))
                is String -> assertEquals(expected, cursor.getString(0))
                else -> error("Unsupported assertion type")
            }
            assertFalse(cursor.moveToNext())
        }
    }

    private fun deleteDatabase(databaseName: String) {
        InstrumentationRegistry.getInstrumentation().targetContext.deleteDatabase(databaseName)
    }

    private companion object {
        const val LEGACY_PASSWORD = "Legacy1!"
    }
}
