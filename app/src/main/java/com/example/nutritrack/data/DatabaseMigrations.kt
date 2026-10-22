package com.example.nutritrack.data

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

object DatabaseMigrations {
    val MIGRATION_5_6 = object : Migration(5, 6) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `seed_metadata` (
                    `seedName` TEXT NOT NULL,
                    `version` INTEGER NOT NULL,
                    `importedAtEpochMillis` INTEGER NOT NULL,
                    `sourceRowCount` INTEGER NOT NULL,
                    PRIMARY KEY(`seedName`)
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_6_7 = object : Migration(6, 7) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `questionnaire_profiles` (
                    `userId` INTEGER NOT NULL,
                    `canEatFruits` INTEGER NOT NULL,
                    `canEatVegetables` INTEGER NOT NULL,
                    `canEatGrains` INTEGER NOT NULL,
                    `canEatRedMeat` INTEGER NOT NULL,
                    `canEatSeafood` INTEGER NOT NULL,
                    `canEatPoultry` INTEGER NOT NULL,
                    `canEatFish` INTEGER NOT NULL,
                    `canEatEggs` INTEGER NOT NULL,
                    `canEatNutsAndSeeds` INTEGER NOT NULL,
                    `selectedPersona` TEXT NOT NULL,
                    `biggestMealTime` TEXT NOT NULL,
                    `sleepTime` TEXT NOT NULL,
                    `wakeUpTime` TEXT NOT NULL,
                    `legacyPreferencesMigrationVersion` INTEGER NOT NULL,
                    `updatedAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`userId`),
                    FOREIGN KEY(`userId`) REFERENCES `patients`(`userId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
        }
    }

    val MIGRATION_7_8 = object : Migration(7, 8) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE `food_intake` ADD COLUMN `foodName` TEXT NOT NULL " +
                    "DEFAULT 'Legacy intake'"
            )
            database.execSQL("DROP INDEX IF EXISTS `index_food_intake_patientUserId`")
            database.execSQL(
                """
                CREATE INDEX IF NOT EXISTS `index_food_intake_patientUserId_submissionDate`
                ON `food_intake` (`patientUserId`, `submissionDate`)
                """.trimIndent()
            )
        }
    }

    val MIGRATION_8_9 = object : Migration(8, 9) {
        override fun migrate(database: SupportSQLiteDatabase) {
            val passwordHasher = PasswordHasher()
            val migratedAccounts = database.query(
                """
                SELECT userId, phoneNumber, name, password, registrationDate, lastLoginTime
                FROM user_accounts
                ORDER BY userId
                """.trimIndent()
            ).use { cursor ->
                buildList {
                    while (cursor.moveToNext()) {
                        val userId = cursor.getInt(0)
                        val phoneNumber = cursor.getString(1)
                            ?: throw IllegalStateException("Account $userId has no phone number")
                        val name = cursor.getString(2)
                            ?: throw IllegalStateException("Account $userId has no name")
                        val password = cursor.getString(3)
                            ?: throw IllegalStateException("Account $userId has no password")
                        check(userId > 0) { "Account has an invalid user ID" }
                        check(phoneNumber.isNotBlank()) { "Account $userId has an invalid phone number" }
                        check(password.isNotEmpty()) { "Account $userId has an empty password" }

                        val credential = passwordHasher.hash(password)
                        add(
                            MigratedAccount(
                                userId = userId,
                                phoneNumber = phoneNumber,
                                name = name,
                                credential = credential,
                                registrationDate = cursor.getLong(4),
                                lastLoginTime = cursor.getLong(5)
                            )
                        )
                    }
                }
            }

            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `user_accounts_new` (
                    `userId` INTEGER NOT NULL,
                    `phoneNumber` TEXT NOT NULL,
                    `name` TEXT NOT NULL,
                    `passwordHash` BLOB NOT NULL,
                    `passwordSalt` BLOB NOT NULL,
                    `passwordAlgorithm` TEXT NOT NULL,
                    `passwordAlgorithmVersion` INTEGER NOT NULL,
                    `passwordIterations` INTEGER NOT NULL,
                    `passwordKeyLengthBits` INTEGER NOT NULL,
                    `registrationDate` INTEGER NOT NULL,
                    `lastLoginTime` INTEGER NOT NULL,
                    PRIMARY KEY(`userId`),
                    FOREIGN KEY(`userId`) REFERENCES `patients`(`userId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )

            database.compileStatement(
                """
                INSERT INTO user_accounts_new (
                    userId, phoneNumber, name, passwordHash, passwordSalt,
                    passwordAlgorithm, passwordAlgorithmVersion, passwordIterations,
                    passwordKeyLengthBits, registrationDate, lastLoginTime
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { statement ->
                migratedAccounts.forEach { account ->
                    statement.clearBindings()
                    statement.bindLong(1, account.userId.toLong())
                    statement.bindString(2, account.phoneNumber)
                    statement.bindString(3, account.name)
                    statement.bindBlob(4, account.credential.hash)
                    statement.bindBlob(5, account.credential.salt)
                    statement.bindString(6, account.credential.algorithm)
                    statement.bindLong(7, account.credential.algorithmVersion.toLong())
                    statement.bindLong(8, account.credential.iterations.toLong())
                    statement.bindLong(9, account.credential.keyLengthBits.toLong())
                    statement.bindLong(10, account.registrationDate)
                    statement.bindLong(11, account.lastLoginTime)
                    check(statement.executeInsert() != -1L) {
                        "Failed to migrate account ${account.userId}"
                    }
                }
            }

            database.execSQL("DROP TABLE `user_accounts`")
            database.execSQL("ALTER TABLE `user_accounts_new` RENAME TO `user_accounts`")
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_user_accounts_userId` " +
                    "ON `user_accounts` (`userId`)"
            )
        }
    }

    val MIGRATION_9_10 = object : Migration(9, 10) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_runs` (
                    `id` TEXT NOT NULL,
                    `userId` INTEGER NOT NULL,
                    `status` TEXT NOT NULL,
                    `createdAtEpochMillis` INTEGER NOT NULL,
                    `finishedAtEpochMillis` INTEGER,
                    `errorCode` TEXT,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`userId`) REFERENCES `patients`(`userId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_runs_userId` " +
                    "ON `agent_runs` (`userId`)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_runs_createdAtEpochMillis` " +
                    "ON `agent_runs` (`createdAtEpochMillis`)"
            )

            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `agent_tool_calls` (
                    `id` TEXT NOT NULL,
                    `runId` TEXT NOT NULL,
                    `sequence` INTEGER NOT NULL,
                    `toolName` TEXT NOT NULL,
                    `argumentsJson` TEXT NOT NULL,
                    `resultJson` TEXT,
                    `status` TEXT NOT NULL,
                    `errorCode` TEXT,
                    `startedAtEpochMillis` INTEGER NOT NULL,
                    `finishedAtEpochMillis` INTEGER,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_agent_tool_calls_runId_sequence` " +
                    "ON `agent_tool_calls` (`runId`, `sequence`)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_agent_tool_calls_startedAtEpochMillis` " +
                    "ON `agent_tool_calls` (`startedAtEpochMillis`)"
            )

            database.execSQL(
                """
                CREATE TABLE IF NOT EXISTS `nutrition_plans` (
                    `id` TEXT NOT NULL,
                    `userId` INTEGER NOT NULL,
                    `runId` TEXT NOT NULL,
                    `status` TEXT NOT NULL,
                    `schemaVersion` INTEGER NOT NULL,
                    `validFromEpochDay` INTEGER NOT NULL,
                    `validUntilEpochDay` INTEGER NOT NULL,
                    `contentJson` TEXT NOT NULL,
                    `cautionsJson` TEXT NOT NULL,
                    `disclaimer` TEXT NOT NULL,
                    `createdAtEpochMillis` INTEGER NOT NULL,
                    PRIMARY KEY(`id`),
                    FOREIGN KEY(`userId`) REFERENCES `patients`(`userId`)
                        ON UPDATE NO ACTION ON DELETE CASCADE,
                    FOREIGN KEY(`runId`) REFERENCES `agent_runs`(`id`)
                        ON UPDATE NO ACTION ON DELETE CASCADE
                )
                """.trimIndent()
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_nutrition_plans_userId` " +
                    "ON `nutrition_plans` (`userId`)"
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_nutrition_plans_runId` " +
                    "ON `nutrition_plans` (`runId`)"
            )
            database.execSQL(
                "CREATE INDEX IF NOT EXISTS `index_nutrition_plans_createdAtEpochMillis` " +
                    "ON `nutrition_plans` (`createdAtEpochMillis`)"
            )
        }
    }

    val MIGRATION_10_11 = object : Migration(10, 11) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE `nutrition_plans` ADD COLUMN `confirmedAtEpochMillis` INTEGER"
            )
            database.execSQL(
                "ALTER TABLE `nutrition_plans` ADD COLUMN `confirmationActionId` TEXT"
            )
            database.execSQL(
                "CREATE UNIQUE INDEX IF NOT EXISTS `index_nutrition_plans_confirmationActionId` " +
                    "ON `nutrition_plans` (`confirmationActionId`)"
            )
        }
    }

    val MIGRATION_11_12 = object : Migration(11, 12) {
        override fun migrate(database: SupportSQLiteDatabase) {
            database.execSQL(
                "ALTER TABLE `nutrition_plans` ADD COLUMN `updatedAtEpochMillis` INTEGER NOT NULL DEFAULT 0"
            )
            database.execSQL(
                "UPDATE `nutrition_plans` SET `updatedAtEpochMillis` = " +
                    "COALESCE(`confirmedAtEpochMillis`, `createdAtEpochMillis`)"
            )
            database.execSQL(
                "ALTER TABLE `nutrition_plans` ADD COLUMN `completedAtEpochMillis` INTEGER"
            )
        }
    }

    private data class MigratedAccount(
        val userId: Int,
        val phoneNumber: String,
        val name: String,
        val credential: PasswordCredential,
        val registrationDate: Long,
        val lastLoginTime: Long
    )
}
