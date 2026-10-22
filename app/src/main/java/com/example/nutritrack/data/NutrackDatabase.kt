package com.example.nutritrack.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        Patient::class,
        FoodIntake::class,
        UserAccount::class,
        NutriCoachTip::class,
        SeedMetadata::class,
        QuestionnaireProfile::class,
        AgentRun::class,
        AgentToolCall::class,
        NutritionPlan::class
    ],
    version = 12,
    exportSchema = true
)
@TypeConverters(AgentPersistenceConverters::class)
abstract class NutrackDatabase : RoomDatabase() {
    
    abstract fun patientDao(): PatientDao
    abstract fun foodIntakeDao(): FoodIntakeDao
    abstract fun userAccountDao(): UserAccountDao
    abstract fun nutriCoachTipDao(): NutriCoachTipDao
    abstract fun seedMetadataDao(): SeedMetadataDao
    abstract fun questionnaireProfileDao(): QuestionnaireProfileDao
    abstract fun agentRunDao(): AgentRunDao
    abstract fun agentToolCallDao(): AgentToolCallDao
    abstract fun nutritionPlanDao(): NutritionPlanDao
    
    companion object {
        @Volatile
        private var INSTANCE: NutrackDatabase? = null
        
        fun getDatabase(context: Context): NutrackDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    NutrackDatabase::class.java,
                    "nutritrack_database"
                )
                .addMigrations(
                    DatabaseMigrations.MIGRATION_5_6,
                    DatabaseMigrations.MIGRATION_6_7,
                    DatabaseMigrations.MIGRATION_7_8,
                    DatabaseMigrations.MIGRATION_8_9,
                    DatabaseMigrations.MIGRATION_9_10,
                    DatabaseMigrations.MIGRATION_10_11,
                    DatabaseMigrations.MIGRATION_11_12
                )
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
} 
