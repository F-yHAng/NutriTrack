package com.example.nutritrack.data

import androidx.room.Entity
import androidx.room.ColumnInfo
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter

enum class AgentRunStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

enum class AgentToolCallStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}

enum class NutritionPlanStatus {
    DRAFT,
    CONFIRMED,
    /** Reserved for a future explicitly activated plan; completion treats it like CONFIRMED. */
    ACTIVE,
    COMPLETED,
    ABANDONED
}

class AgentPersistenceConverters {
    @TypeConverter
    fun agentRunStatusToString(value: AgentRunStatus): String = value.name

    @TypeConverter
    fun stringToAgentRunStatus(value: String): AgentRunStatus = AgentRunStatus.valueOf(value)

    @TypeConverter
    fun agentToolCallStatusToString(value: AgentToolCallStatus): String = value.name

    @TypeConverter
    fun stringToAgentToolCallStatus(value: String): AgentToolCallStatus =
        AgentToolCallStatus.valueOf(value)

    @TypeConverter
    fun nutritionPlanStatusToString(value: NutritionPlanStatus): String = value.name

    @TypeConverter
    fun stringToNutritionPlanStatus(value: String): NutritionPlanStatus =
        NutritionPlanStatus.valueOf(value)
}

@Entity(
    tableName = "agent_runs",
    foreignKeys = [
        ForeignKey(
            entity = Patient::class,
            parentColumns = ["userId"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["userId"]),
        Index(value = ["createdAtEpochMillis"])
    ]
)
data class AgentRun(
    @PrimaryKey
    val id: String,
    val userId: Int,
    val status: AgentRunStatus,
    val createdAtEpochMillis: Long,
    val finishedAtEpochMillis: Long? = null,
    val errorCode: String? = null
)

@Entity(
    tableName = "agent_tool_calls",
    foreignKeys = [
        ForeignKey(
            entity = AgentRun::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["runId", "sequence"], unique = true),
        Index(value = ["startedAtEpochMillis"])
    ]
)
data class AgentToolCall(
    @PrimaryKey
    val id: String,
    val runId: String,
    val sequence: Int,
    val toolName: String,
    val argumentsJson: String,
    val resultJson: String?,
    val status: AgentToolCallStatus,
    val errorCode: String?,
    val startedAtEpochMillis: Long,
    val finishedAtEpochMillis: Long?
)

@Entity(
    tableName = "nutrition_plans",
    foreignKeys = [
        ForeignKey(
            entity = Patient::class,
            parentColumns = ["userId"],
            childColumns = ["userId"],
            onDelete = ForeignKey.CASCADE
        ),
        ForeignKey(
            entity = AgentRun::class,
            parentColumns = ["id"],
            childColumns = ["runId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["userId"]),
        Index(value = ["runId"], unique = true),
        Index(value = ["createdAtEpochMillis"]),
        Index(value = ["confirmationActionId"], unique = true)
    ]
)
data class NutritionPlan(
    @PrimaryKey
    val id: String,
    val userId: Int,
    val runId: String,
    val status: NutritionPlanStatus,
    val schemaVersion: Int,
    val validFromEpochDay: Long,
    val validUntilEpochDay: Long,
    val contentJson: String,
    val cautionsJson: String,
    val disclaimer: String,
    val createdAtEpochMillis: Long,
    val confirmedAtEpochMillis: Long? = null,
    val confirmationActionId: String? = null,
    @ColumnInfo(defaultValue = "0")
    val updatedAtEpochMillis: Long = createdAtEpochMillis,
    val completedAtEpochMillis: Long? = null
)
