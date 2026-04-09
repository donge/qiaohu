package com.donge.qiaohu.llm

import com.google.ai.edge.litertlm.Tool
import com.google.ai.edge.litertlm.ToolParam
import com.google.ai.edge.litertlm.ToolSet

/**
 * Kotlin equivalent of the Python respond_to_user tool defined in server.py.
 *
 * The LiteRT-LM SDK inspects [@Tool] and [@ToolParam] annotations at runtime to
 * generate an OpenAPI-style schema that is passed to the model.  When the model
 * decides to call this tool the SDK invokes [respondToUser] synchronously (on the
 * same thread as sendMessage), so [lastResult] is populated before sendMessage()
 * returns to the caller.
 */
class RespondToUserToolSet : ToolSet {

    @Tool(description = "Respond to the user's voice message.")
    fun respond_to_user(
        @ToolParam(description = "Exact transcription of what the user said in the audio.")
        transcription: String,
        @ToolParam(
            description = "Your conversational response in Chinese. Keep it to 1-4 short sentences."
        )
        response: String,
    ): String {
        lastResult = LlmResponse(
            transcription = transcription.replace("<|\"|>", "").trim(),
            response = response.replace("<|\"|>", "").trim(),
        )
        return "OK"
    }

    companion object {
        @Volatile
        private var lastResult: LlmResponse? = null

        /** Called by LlmEngine before each sendMessage() to avoid stale state. */
        fun clearResult() {
            lastResult = null
        }

        /** Called by LlmEngine after sendMessage() returns. */
        fun getResult(): LlmResponse? = lastResult
    }
}
