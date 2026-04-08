package com.example.parlor.llm

/**
 * Structured result produced by the respond_to_user tool call.
 *
 * Mirrors the tool_result dict in server.py:
 *   tool_result["transcription"] and tool_result["response"]
 */
data class LlmResponse(
    /** Exact transcription of what the user said, or null if unavailable. */
    val transcription: String?,
    /** Chinese conversational response (1-4 sentences). */
    val response: String,
)
