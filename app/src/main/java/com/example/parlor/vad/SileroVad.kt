package com.example.parlor.vad

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.util.Log
import java.io.File
import java.nio.FloatBuffer
import java.nio.LongBuffer

private const val TAG = "SileroVad"

/**
 * Silero VAD v5 wrapper using ONNX Runtime for Android.
 *
 * v5 API (from utils_vad.py OnnxWrapper):
 *   Inputs : "input"  float32 [1, context_size + chunk_samples]
 *            "state"  float32 [2, 1, 128]
 *            "sr"     int64   scalar
 *   Outputs: [0] output float32 — speech probability
 *            [1] state  float32 [2, 1, 128] — updated LSTM state
 *
 * The model prepends a 64-sample context window to each 512-sample chunk
 * (total input length = 576 samples at 16 kHz).
 *
 * Model file: assets/silero_vad.onnx (~2.2 MB)
 * Source:     https://github.com/snakers4/silero-vad
 */
class SileroVad(modelFile: File) : AutoCloseable {

    companion object {
        /** Audio samples per chunk (32 ms at 16 kHz). */
        const val CHUNK_SAMPLES = 512

        /** Context prepended to each chunk (matches v5 default for 16 kHz). */
        private const val CONTEXT_SAMPLES = 64

        /** Total input length per call = context + chunk. */
        private val INPUT_SAMPLES = CONTEXT_SAMPLES + CHUNK_SAMPLES   // 576

        /** Sample rate expected by the model. */
        const val SAMPLE_RATE = 16_000

        /** Recommended speech probability threshold. */
        const val DEFAULT_THRESHOLD = 0.5f
    }

    private val env: OrtEnvironment = OrtEnvironment.getEnvironment()
    private val session: OrtSession = env.createSession(
        modelFile.absolutePath,
        OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(1)
        },
    )

    // LSTM state — shape [2, 1, 128] (v5 uses 128 hidden units, merged h+c)
    private var state = FloatArray(2 * 1 * 128) { 0f }

    // Rolling context window — last CONTEXT_SAMPLES from the previous chunk
    private var context = FloatArray(CONTEXT_SAMPLES) { 0f }

    /**
     * Process one 32-ms audio frame.
     *
     * @param audioChunk Float32 PCM, exactly [CHUNK_SAMPLES] = 512 elements,
     *                   range [-1, 1], 16 kHz mono.
     * @return Speech probability in [0.0, 1.0].
     */
    fun process(audioChunk: FloatArray): Float {
        require(audioChunk.size == CHUNK_SAMPLES) {
            "Silero VAD requires exactly $CHUNK_SAMPLES samples, got ${audioChunk.size}"
        }

        // Prepend context window → input shape [1, 576]
        val inputData = context + audioChunk

        val inputTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(inputData),
            longArrayOf(1L, INPUT_SAMPLES.toLong()),
        )
        val stateTensor = OnnxTensor.createTensor(
            env,
            FloatBuffer.wrap(state),
            longArrayOf(2L, 1L, 128L),
        )
        val srTensor = OnnxTensor.createTensor(
            env,
            LongBuffer.wrap(longArrayOf(SAMPLE_RATE.toLong())),
            longArrayOf(1L),
        )

        val inputs = mapOf(
            "input" to inputTensor,
            "state" to stateTensor,
            "sr"    to srTensor,
        )

        val result = session.run(inputs)

        // Output 0: speech probability — shape [1] or scalar
        val outputTensor = result[0] as OnnxTensor
        val prob = outputTensor.floatBuffer.get(0)

        // Output 1: updated state — shape [2, 1, 128]
        val newStateBuf = (result[1] as OnnxTensor).floatBuffer
        newStateBuf.get(state)

        // Update context with last CONTEXT_SAMPLES of current chunk
        audioChunk.copyInto(context, destinationOffset = 0, startIndex = CHUNK_SAMPLES - CONTEXT_SAMPLES)

        inputTensor.close(); stateTensor.close(); srTensor.close()
        result.close()

        return prob
    }

    /**
     * Reset LSTM state and context.
     * Call between utterances so state from one recording does not bleed into the next.
     */
    fun reset() {
        state.fill(0f)
        context.fill(0f)
        Log.v(TAG, "VAD state reset")
    }

    override fun close() {
        runCatching { session.close() }
        runCatching { env.close() }
    }
}
