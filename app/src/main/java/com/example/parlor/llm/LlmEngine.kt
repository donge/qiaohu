package com.example.parlor.llm

import android.content.Context
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.tool
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private const val TAG = "LlmEngine"

/**
 * Wraps the LiteRT-LM Engine and Conversation lifecycle.
 *
 * On emulators (where LiteRT-LM native libs crash due to unsupported CPU
 * instructions), the engine falls back to a stub that echoes canned responses
 * so the full VAD → LLM → TTS pipeline can still be exercised.
 *
 * Mirrors server.py:
 *   - Engine initialisation with GPU + vision + audio backends
 *   - System prompt (same Chinese text as SYSTEM_PROMPT in server.py)
 *   - respond_to_user tool registration
 *   - sendMessage() → returns structured LlmResponse
 */
class LlmEngine(private val context: Context) : AutoCloseable {

    private lateinit var engine: Engine
    private lateinit var conversation: Conversation

    /** True when we are running in an Android emulator. */
    private val isEmulator: Boolean get() =
        Build.FINGERPRINT.startsWith("generic") ||
        Build.FINGERPRINT.startsWith("unknown") ||
        Build.MODEL.contains("Emulator") ||
        Build.MODEL.contains("Android SDK") ||
        Build.HARDWARE == "goldfish" ||
        Build.HARDWARE == "ranchu" ||
        Build.PRODUCT.contains("sdk") ||
        Build.PRODUCT.contains("emulator")

    /** True after a successful initialize(); false means stub mode is active. */
    var isStubMode = false
        private set

    // Must match server.py SYSTEM_PROMPT exactly so model behaviour is identical.
    private val systemPrompt = """
        你是一个友好的中文AI语音助手。用户正在通过麦克风与你对话，并可能向你展示摄像头画面。
        你必须始终使用 respond_to_user 工具来回复。
        首先准确转录用户说的内容，然后用中文写出你的回应。
        回应请保持简洁，1到4句话即可。
    """.trimIndent()

    // Rotating canned responses used in stub mode (emulator testing).
    private val stubResponses = listOf(
        "您好！我是 Parlor 语音助手，目前运行在模拟器存根模式。",
        "这是一条测试回复，用于验证 TTS 和 VAD 流程是否正常工作。",
        "模拟器不支持 LiteRT-LM GPU 加速，请在真机上体验完整功能。",
        "音频和 TTS 管线已就绪，等待部署到骁龙 8 Gen2 设备上。",
    )
    private var stubIndex = 0

    /**
     * Load the model and create a Conversation.
     * Must be called on a background thread / IO coroutine — can take up to 10 s.
     *
     * On emulators: LiteRT-LM's native library uses CPU instructions (SVE/dotprod)
     * that the Android emulator hypervisor does not support, causing an unrecoverable
     * SIGILL *inside* the JNI library before any Java exception can be thrown.
     * Therefore we skip Engine initialisation entirely on emulators and use stub mode.
     */
    suspend fun initialize(modelPath: String) = withContext(Dispatchers.IO) {
        if (isEmulator) {
            Log.w(TAG, "Emulator detected — LiteRT-LM requires real ARM hardware. Enabling stub mode.")
            isStubMode = true
            return@withContext
        }
        initEngine(modelPath, useGpu = true)
    }

    private fun initEngine(modelPath: String, useGpu: Boolean) {
        Log.i(TAG, "Initialising engine from $modelPath  gpu=$useGpu")
        val mainBackend = if (useGpu) Backend.GPU() else Backend.CPU()
        val engineConfig = EngineConfig(
            modelPath = modelPath,
            backend = mainBackend,
            visionBackend = mainBackend,
            audioBackend = Backend.CPU(),
            // Storing compiled artefacts speeds up subsequent loads significantly.
            cacheDir = context.cacheDir.path,
        )
        engine = Engine(engineConfig)
        engine.initialize()
        Log.i(TAG, "Engine ready")

        val toolSet = RespondToUserToolSet()
        val convConfig = ConversationConfig(
            systemInstruction = Contents.of(systemPrompt),
            tools = listOf(tool(toolSet)),
        )
        conversation = engine.createConversation(convConfig)
        Log.i(TAG, "Conversation created")
    }

    /**
     * Send a multimodal turn and wait for the model to call respond_to_user.
     *
     * @param audioBytes  16 kHz mono WAV bytes, or null if no audio this turn.
     * @param imageBytes  JPEG bytes from the camera, or null if no image.
     * @return [LlmResponse] with transcription + Chinese text response.
     */
    suspend fun sendMessage(
        audioBytes: ByteArray?,
        imageBytes: ByteArray?,
    ): LlmResponse = withContext(Dispatchers.IO) {
        if (isStubMode) {
            // Simulate ~1 s inference latency so the UI state machine behaves realistically.
            delay(800)
            val text = stubResponses[stubIndex % stubResponses.size]
            stubIndex++
            Log.i(TAG, "Stub response: $text")
            return@withContext LlmResponse(transcription = "[模拟器存根]", response = text)
        }

        // Clear the tool result from any previous turn.
        RespondToUserToolSet.clearResult()

        val contentList = buildList {
            if (audioBytes != null) add(Content.AudioBytes(audioBytes))
            if (imageBytes != null) add(Content.ImageBytes(imageBytes))
            add(Content.Text(buildUserPrompt(audioBytes != null, imageBytes != null)))
        }

        val message = conversation.sendMessage(Contents.of(contentList))

        // Tool result is populated synchronously by the SDK before sendMessage() returns.
        RespondToUserToolSet.getResult() ?: run {
            // Fallback: model responded without invoking the tool.
            // Extract the first Text content from the response Message.
            val raw = message.contents.contents
                .filterIsInstance<Content.Text>()
                .firstOrNull()?.text ?: ""
            Log.w(TAG, "Tool not called; using raw response: $raw")
            LlmResponse(transcription = null, response = raw)
        }
    }

    /**
     * Send a warmup message to prime the KV-cache and JIT-compiled paths.
     * Should be called once after [initialize] before the first real turn.
     */
    suspend fun warmUp() = withContext(Dispatchers.IO) {
        if (isStubMode) {
            Log.i(TAG, "Stub mode — skipping LLM warmup")
            return@withContext
        }
        Log.i(TAG, "Warming up LLM…")
        RespondToUserToolSet.clearResult()
        conversation.sendMessage("你好")
        Log.i(TAG, "LLM warmup done")
    }

    override fun close() {
        if (::conversation.isInitialized) runCatching { conversation.close() }
        if (::engine.isInitialized) runCatching { engine.close() }
        Log.i(TAG, "Engine closed")
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    // Mirrors the content-building logic in server.py websocket_endpoint()
    private fun buildUserPrompt(hasAudio: Boolean, hasImage: Boolean): String = when {
        hasAudio && hasImage ->
            "用户刚刚对你说话（音频），同时展示了摄像头画面（图像）。请回应用户所说的内容，如果相关请结合你看到的内容。"
        hasAudio ->
            "用户刚刚对你说话，请回应他们所说的内容。"
        hasImage ->
            "用户正在向你展示摄像头画面，请描述你看到的内容。"
        else ->
            "你好！"
    }
}
