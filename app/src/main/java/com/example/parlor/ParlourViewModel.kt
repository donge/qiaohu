package com.example.parlor

import android.app.Application
import android.util.Log
import com.example.parlor.audio.AudioPlayer
import com.example.parlor.audio.MicRecorder
import com.example.parlor.llm.LlmEngine
import com.example.parlor.llm.ModelDownloader
import com.example.parlor.tts.SentenceSplitter
import com.example.parlor.tts.SherpaOnnxTts
import com.example.parlor.vad.SileroVad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.parlor.camera.CameraCapture
import kotlinx.coroutines.Job
import kotlinx.coroutines.withContext

private const val TAG = "ParlourViewModel"

// ── UI state ──────────────────────────────────────────────────────────────────

enum class AppState {
    SETUP,          // First launch — downloading model
    LOADING,        // Models loaded; warming up
    LISTENING,      // VAD running, waiting for speech
    PROCESSING,     // LLM inference in progress
    SPEAKING,       // TTS playing back response
    ERROR,          // Unrecoverable error
}

data class UiState(
    val appState: AppState = AppState.SETUP,
    val downloadProgress: Float = 0f,       // 0.0–1.0, only shown during SETUP
    val statusText: String = "正在准备…",
    val transcription: String = "",          // Last recognised user utterance
    val responseText: String = "",           // Last assistant response
    val llmLatencyMs: Long = 0,
    val ttsLatencyMs: Long = 0,
    val errorMessage: String = "",
    val cameraEnabled: Boolean = false,      // toggled by user
    val isStubMode: Boolean = false,         // true on emulator — shows debug trigger
)

// ── ViewModel ─────────────────────────────────────────────────────────────────

/**
 * Central coordinator for the Parlor Android app.
 *
 * Manages model lifecycle, the VAD→LLM→TTS pipeline, and exposes [uiState]
 * for the Compose UI layer.  Mirrors the event loop in server.py's
 * websocket_endpoint() but runs entirely in-process with no network I/O.
 */
class ParlourViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    // ── Core components (created lazily after models are ready) ──────────────
    private var llmEngine: LlmEngine? = null
    private var vad: SileroVad? = null
    private var sherpaOnnxTts: SherpaOnnxTts? = null
    private val audioPlayer = AudioPlayer()
    private var micRecorder: MicRecorder? = null
    var cameraCapture: CameraCapture? = null  // set by MainActivity after camera permission

    // ── Barge-in control ─────────────────────────────────────────────────────
    // AtomicBoolean mirrors the `interrupted` asyncio.Event in server.py
    private val interrupted = AtomicBoolean(false)
    private var ttsJob: Job? = null

    // ── Initialisation ───────────────────────────────────────────────────────

    init {
        viewModelScope.launch { initialise() }
    }

    private suspend fun initialise() {
        val ctx = getApplication<Application>()

        // Step 1: download LLM model (skipped if already present)
        setState(AppState.SETUP, "正在检查模型…")
        val modelFile = runCatching {
            ModelDownloader.ensureDownloaded(ctx) { progress ->
                _uiState.update { it.copy(downloadProgress = progress, statusText = "正在下载模型… ${(progress * 100).toInt()}%") }
            }
        }.getOrElse { e ->
            Log.e(TAG, "Model download failed", e)
            setState(AppState.LISTENING, errorMessage = "模型文件缺失，请将模型推送到设备: ${e.message}")
            return
        }

        // Step 2: copy VAD asset to internal storage
        setState(AppState.LOADING, "正在加载模型…")
        val vadFile = run {
            val outDir = ctx.filesDir
            val dst = java.io.File(outDir, "silero_vad.onnx")
            if (!dst.exists()) {
                ctx.assets.open("silero_vad.onnx").use { it.copyTo(dst.outputStream()) }
            }
            dst
        }

        // Step 3: initialise all models on IO thread
        withContext(Dispatchers.IO) {
            runCatching {
                vad = SileroVad(vadFile)
                Log.i(TAG, "Silero VAD ready")

                sherpaOnnxTts = SherpaOnnxTts(ctx.assets).also { it.warmUp() }
                Log.i(TAG, "Sherpa-ONNX TTS ready")

                val engine = LlmEngine(ctx).also {
                    it.initialize(modelFile.absolutePath)
                    it.warmUp()
                }
                llmEngine = engine
                Log.i(TAG, "LLM Engine ready")
                // Expose stub mode to UI so the debug trigger button can be shown
                if (engine.isStubMode) {
                    _uiState.update { s -> s.copy(isStubMode = true) }
                }
            }.onFailure { e ->
                Log.e(TAG, "Initialisation failed", e)
                setState(AppState.ERROR, errorMessage = e.message ?: "未知错误")
                return@withContext
            }
        }

        // Step 4: wire up mic recorder and start listening
        val resolvedVad = vad ?: return
        micRecorder = MicRecorder(resolvedVad) { wavBytes ->
            handleSpeech(wavBytes)
        }
        micRecorder?.start(viewModelScope)
        setState(AppState.LISTENING, "请开始说话…")
    }

    // ── Main pipeline ─────────────────────────────────────────────────────────

    /**
     * Called by [MicRecorder] on the IO thread whenever a complete utterance is
     * detected.  This is the Android equivalent of server.py's main while-loop.
     */
    private suspend fun handleSpeech(wavBytes: ByteArray) {
        val llm = llmEngine ?: return
        val tts = sherpaOnnxTts ?: return

        interrupted.set(false)
        setState(AppState.PROCESSING, "正在思考…")

        // Capture camera frame in parallel with LLM setup
        val imageBytes: ByteArray? = if (_uiState.value.cameraEnabled) {
            cameraCapture?.captureJpeg()
        } else null

        // LLM inference
        val llmStart = System.currentTimeMillis()
        val result = runCatching {
            llm.sendMessage(wavBytes, imageBytes)
        }.getOrElse { e ->
            Log.e(TAG, "LLM error", e)
            setState(AppState.LISTENING, errorMessage = "LLM 错误: ${e.message}")
            return
        }
        val llmMs = System.currentTimeMillis() - llmStart
        Log.i(TAG, "LLM ${llmMs}ms  transcription=${result.transcription}  response=${result.response}")

        if (interrupted.get()) {
            setState(AppState.LISTENING, "请开始说话…")
            return
        }

        // Update UI with text
        _uiState.update {
            it.copy(
                appState = AppState.SPEAKING,
                statusText = "正在回复…",
                transcription = result.transcription ?: "",
                responseText = result.response,
                llmLatencyMs = llmMs,
            )
        }

        // Streaming TTS: generate and play in parallel via a Channel.
        // Producer fills the channel while consumer plays — eliminates the
        // silence gap between sentences caused by sequential generate→play.
        val sentences = SentenceSplitter.split(result.response)
            .ifEmpty { listOf(result.response) }

        val ttsStart = System.currentTimeMillis()
        ttsJob = viewModelScope.launch(Dispatchers.IO) {
            val channel = kotlinx.coroutines.channels.Channel<FloatArray>(capacity = 1)

            // Producer: generate PCM for each sentence and send to channel
            val producer = launch {
                for ((i, sentence) in sentences.withIndex()) {
                    if (interrupted.get()) {
                        Log.d(TAG, "TTS producer interrupted at sentence $i")
                        break
                    }
                    val pcm = runCatching { tts.generate(sentence) }.getOrElse { e ->
                        Log.e(TAG, "TTS error on sentence $i", e)
                        null
                    } ?: break
                    channel.send(pcm)
                }
                channel.close()
            }

            // Consumer: play each PCM chunk as soon as it arrives
            for (pcm in channel) {
                if (interrupted.get()) break
                audioPlayer.resume()
                audioPlayer.write(pcm)
            }
            producer.join()

            val ttsMs = System.currentTimeMillis() - ttsStart
            Log.i(TAG, "TTS ${ttsMs}ms for ${sentences.size} sentences")
            _uiState.update { it.copy(ttsLatencyMs = ttsMs) }
        }

        ttsJob?.join()
        if (!interrupted.get()) {
            setState(AppState.LISTENING, "请开始说话…")
        }
    }

    // ── Barge-in ──────────────────────────────────────────────────────────────

    /**
     * Interrupt ongoing TTS playback.  Called by [MicRecorder] when new speech
     * is detected while the assistant is still speaking.
     *
     * Mirrors server.py: receiving {"type":"interrupt"} over WebSocket.
     */
    fun interrupt() {
        if (_uiState.value.appState == AppState.SPEAKING ||
            _uiState.value.appState == AppState.PROCESSING
        ) {
            Log.d(TAG, "Barge-in interrupt")
            interrupted.set(true)
            ttsJob?.cancel()
            audioPlayer.stop()
        }
    }

    // ── Camera toggle ─────────────────────────────────────────────────────────

    fun toggleCamera() {
        _uiState.update { it.copy(cameraEnabled = !it.cameraEnabled) }
    }

    // ── Debug / emulator testing ──────────────────────────────────────────────

    /**
     * Trigger the VAD→LLM→TTS pipeline with a synthetic 1-second silent WAV.
     * Only meaningful in emulator/stub mode where VAD cannot hear real audio.
     */
    fun debugTriggerSpeech() {
        viewModelScope.launch {
            // Build a minimal 16 kHz mono WAV of 1 second (16000 silent samples)
            val wavBytes = buildSilentWav(sampleRate = 16_000, durationMs = 1000)
            handleSpeech(wavBytes)
        }
    }

    private fun buildSilentWav(sampleRate: Int, durationMs: Int): ByteArray {
        val numSamples = sampleRate * durationMs / 1000
        val dataSize = numSamples * 2  // 16-bit PCM
        val buf = java.nio.ByteBuffer.allocate(44 + dataSize).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + dataSize)
        buf.put("WAVEfmt ".toByteArray())
        buf.putInt(16)          // PCM subchunk size
        buf.putShort(1)         // PCM format
        buf.putShort(1)         // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * 2)
        buf.putShort(2)         // block align
        buf.putShort(16)        // bits per sample
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        repeat(numSamples) { buf.putShort(0) }
        return buf.array()
    }

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        micRecorder?.stop()
        cameraCapture?.shutdown()
        audioPlayer.close()
        sherpaOnnxTts?.close()
        vad?.close()
        llmEngine?.close()
        Log.i(TAG, "ViewModel cleared — all resources released")
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun setState(
        state: AppState,
        statusText: String = _uiState.value.statusText,
        errorMessage: String = "",
    ) {
        _uiState.update { it.copy(appState = state, statusText = statusText, errorMessage = errorMessage) }
    }
}
