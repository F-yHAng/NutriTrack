package com.example.nutritrack.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AgentToolCallDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(call: AgentToolCall)

    @Query(
        "SELECT * FROM agent_tool_calls WHERE runId = :runId " +
            "ORDER BY sequence ASC"
    )
    suspend fun getByRun(runId: String): List<AgentToolCall>

    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM agent_tool_calls WHERE runId = :runId")
    suspend fun nextSequence(runId: String): Int

    @Query(
        "UPDATE agent_tool_calls SET status = :newStatus, resultJson = :resultJson, " +
            "errorCode = :errorCode, finishedAtEpochMillis = :finishedAtEpochMillis " +
            "WHERE id = :callId AND runId = :runId AND status = :expectedStatus"
    )
    suspend fun transitionStatus(
        callId: String,
        runId: String,
        expectedStatus: AgentToolCallStatus,
        newStatus: AgentToolCallStatus,
        resultJson: String?,
        errorCode: String?,
        finishedAtEpochMillis: Long
    ): Int

    @Query(
        "UPDATE agent_tool_calls SET status = 'FAILED', resultJson = NULL, " +
            "errorCode = :errorCode, finishedAtEpochMillis = :finishedAtEpochMillis " +
            "WHERE runId = :runId AND status = 'RUNNING'"
    )
    suspend fun failRunningCalls(
        runId: String,
        errorCode: String,
        finishedAtEpochMillis: Long
    ): Int
}
