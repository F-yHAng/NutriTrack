package com.example.nutritrack.agent

enum class AgentTraceStage {
    PROMPT_01, FUNCTION_CALLS_02, TOOL_RESULT_03, EVIDENCE_04, RAW_PLAN_05, VALIDATION_06
}

/** Lazy payloads: the release implementation neither evaluates nor stores them. */
fun interface AgentTraceLogger {
    fun record(stage: AgentTraceStage, payload: () -> String)

    companion object {
        val None = AgentTraceLogger { _, _ -> }
    }
}
