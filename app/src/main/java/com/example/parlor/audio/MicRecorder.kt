package com.example.parlor.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.example.parlor.vad.SileroVad
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "MicRecorder"

/**
 * Reads from the microphone at 16 kHz, feeds every 32-ms chunk to [SileroVad],
 * and emits a complete WAV [ByteArray] whenever a speech segment ends.
 *
 * Mirrors the VAD-gated audio collection logic from server.py / index.html.
 *
 * State machine:
 *   SILENCE → (prob ≥ THRESHOLD) → SPEAKING → (silence for SILENCE_TIMEOUT_MS) → emit + SILENCE
 *
 * @param vad           Silero VAD instance (shared, caller owns lifecycle).
 * @param onSpeechReady Suspend callback invoked with raw WAV bytes when a
 *                      complete utterance is detected.  Called from an IO thread.
 */
class MicRecorder(
    private val vad: SileroVad,
    private val onSpeechReady: suspend (ByteArray) -> Unit,
) {
    companion object {
        private const val SAMPLE_RATE      = SileroVad.SAMPLE_RATE          // 16 000
        private const val CHUNK_SAMPLES    = SileroVad.CHUNK_SAMPLES         // 512  (32 ms)
        private const val THRESHOLD        = SileroVad.DEFAULT_THRESHOLD     // 0.5
        // How many consecutive silent frames trigger utterance end
        private const val SILENCE_FRAMES   = 800 / 32   // ≈ 800 ms
        // Minimum speech duration to be worth sending (avoids click artefacts)
        private const val MIN_SPEECH_FRAMES = 250 / 32  // ≈ 250 ms
    }

    private var record: AudioRecord? = null
    private var recordingJob: Job? = null

    /**
     * Start mic capture and VAD loop.  Safe to call from any thread.
     * Does nothing if already running.
     */
    @SuppressLint("MissingPermission")
    fun start(scope: CoroutineScope) {
        if (recordingJob?.isActive == true) return

        val minBuf = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
        )
        val bufSize = maxOf(minBuf, CHUNK_SAMPLES * Float.SIZE_BYTES * 8)

        record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_FLOAT,
            bufSize,
        ).also { it.startRecording() }

        Log.i(TAG, "Recording started (bufSize=$bufSize)")

        recordingJob = scope.launch(Dispatchers.IO) {
            val speechFrames = mutableListOf<FloatArray>()
            var silentFrameCount = 0
            var speaking = false

            while (isActive) {
                val chunk = FloatArray(CHUNK_SAMPLES)
                val read = record?.read(chunk, 0, CHUNK_SAMPLES, AudioRecord.READ_BLOCKING) ?: break
                if (read < CHUNK_SAMPLES) continue

                val prob = vad.process(chunk)

                when {
                    prob >= THRESHOLD -> {
                        // Active speech
                        if (!speaking) Log.v(TAG, "Speech start (prob=${"%.2f".format(prob)})")
                        speaking = true
                        silentFrameCount = 0
                        speechFrames.add(chunk)
                    }

                    speaking -> {
                        // Trailing silence after speech
                        speechFrames.add(chunk)
                        silentFrameCount++
                        if (silentFrameCount >= SILENCE_FRAMES) {
                            Log.v(TAG, "Speech end (frames=${speechFrames.size})")
                            if (speechFrames.size >= MIN_SPEECH_FRAMES) {
                                val wav = framesToWav(speechFrames)
                                onSpeechReady(wav)
                            }
                            speechFrames.clear()
                            silentFrameCount = 0
                            speaking = false
                            vad.reset()
                        }
                    }
                    // else: still in silence, nothing to do
                }
            }
        }
    }

    /** Stop recording and release the AudioRecord resource. */
    fun stop() {
        recordingJob?.cancel()
        recordingJob = null
        record?.stop()
        record?.release()
        record = null
        Log.i(TAG, "Recording stopped")
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Flatten the list of Float32 PCM frames and encode as a standard WAV file.
     * The output is 16-bit PCM (Int16), mono, 16 kHz — the same format that
     * LiteRT-LM's AudioBytes content type expects.
     */
    private fun framesToWav(frames: List<FloatArray>): ByteArray {
        val allPcm = FloatArray(frames.sumOf { it.size }).also { dest ->
            var off = 0
            for (f in frames) { f.copyInto(dest, off); off += f.size }
        }
        return pcmFloat32ToWav(allPcm, SAMPLE_RATE)
    }

    /**
     * Encode a Float32 PCM array as a WAV ByteArray (16-bit PCM, little-endian).
     *
     * WAV header layout:
     *   Offset  Size  Content
     *    0       4    "RIFF"
     *    4       4    file size - 8
     *    8       4    "WAVE"
     *   12       4    "fmt "
     *   16       4    16  (PCM sub-chunk size)
     *   20       2    1   (PCM audio format)
     *   22       2    numChannels
     *   24       4    sampleRate
     *   28       4    byteRate
     *   32       2    blockAlign
     *   34       2    bitsPerSample
     *   36       4    "data"
     *   40       4    data size in bytes
     *   44       N    PCM samples (Int16, little-endian)
     */
    private fun pcmFloat32ToWav(pcm: FloatArray, sampleRate: Int): ByteArray {
        val int16 = ShortArray(pcm.size) { idx ->
            (pcm[idx].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        val dataSize = int16.size * Short.SIZE_BYTES
        val out = ByteArrayOutputStream(44 + dataSize)
        val buf = ByteBuffer.allocate(44 + dataSize).order(ByteOrder.LITTLE_ENDIAN)
        buf.put("RIFF".toByteArray())
        buf.putInt(36 + dataSize)
        buf.put("WAVE".toByteArray())
        buf.put("fmt ".toByteArray())
        buf.putInt(16)
        buf.putShort(1)          // PCM
        buf.putShort(1)          // mono
        buf.putInt(sampleRate)
        buf.putInt(sampleRate * Short.SIZE_BYTES)  // byteRate
        buf.putShort(Short.SIZE_BYTES.toShort())   // blockAlign
        buf.putShort(16)                            // bitsPerSample
        buf.put("data".toByteArray())
        buf.putInt(dataSize)
        for (s in int16) buf.putShort(s)
        out.write(buf.array())
        return out.toByteArray()
    }
}
