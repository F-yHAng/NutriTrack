package com.example.nutritrack.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AgentRunDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(run: AgentRun)

    @Query("SELECT * FROM agent_runs WHERE id = :runId")
    suspend fun getById(runId: String): AgentRun?

    @Query(
        "SELECT * FROM agent_runs WHERE userId = :userId AND status = 'RUNNING' " +
            "ORDER BY createdAtEpochMillis ASC, id ASC LIMIT 1"
    )
    suspend fun getRunningByUser(userId: Int): AgentRun?

    @Query(
        "SELECT * FROM agent_runs WHERE userId = :userId " +
            "ORDER BY createdAtEpochMillis DESC, id DESC"
    )
    suspend fun getByUser(userId: Int): List<AgentRun>

    @Query(
        "UPDATE agent_runs SET status = :newStatus, " +
            "finishedAtEpochMillis = :finishedAtEpochMillis, errorCode = :errorCode " +
            "WHERE id = :runId AND userId = :userId AND status = :expectedStatus"
    )
    suspend fun transitionStatus(
        runId: String,
        userId: Int,
        expectedStatus: AgentRunStatus,
        newStatus: AgentRunStatus,
        finishedAtEpochMillis: Long,
        errorCode: String?
    ): Int
}
