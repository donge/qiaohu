package com.donge.qiaohu.tts

import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig

private const val TAG = "SherpaOnnxTts"

/**
 * Chinese TTS backed by sherpa-onnx + matcha-icefall-zh-baker.
 *
 * Replaces the previous KokoroTts + ChinesePhonemizer stack.
 * sherpa-onnx handles all text normalisation (numbers, dates, punctuation)
 * and phonemisation internally via espeak-ng — no handwritten PinyinTable needed.
 *
 * Model assets (in app/src/main/assets/):
 *   matcha-icefall-zh-baker/model-steps-3.onnx  (72 MB, acoustic model)
 *   matcha-icefall-zh-baker/lexicon.txt
 *   matcha-icefall-zh-baker/tokens.txt
 *   matcha-icefall-zh-baker/date.fst
 *   matcha-icefall-zh-baker/number.fst
 *   matcha-icefall-zh-baker/phone.fst
 *   matcha-icefall-zh-baker/dict/
 *   vocos-22khz-univ.onnx                       (51 MB, vocoder)
 *
 * Output: Float32 PCM, mono, 22 050 Hz.
 */
class SherpaOnnxTts(assetManager: AssetManager) : AutoCloseable {

    companion object {
        const val SAMPLE_RATE = 22_050
        private const val MODEL_DIR = "matcha-icefall-zh-baker"
        private const val ACOUSTIC_MODEL = "model-steps-3.onnx"
        private const val VOCODER = "vocos-22khz-univ.onnx"   // at assets root
        private const val LEXICON = "lexicon.txt"
        private const val SPEAKER_ID = 0
        const val DEFAULT_SPEED = 1.0f
    }

    val sampleRate: Int = SAMPLE_RATE

    private val tts: OfflineTts

    init {
        val config = getOfflineTtsConfig(
            modelDir          = MODEL_DIR,
            modelName         = "",               // VITS only, unused
            acousticModelName = ACOUSTIC_MODEL,
            vocoder           = VOCODER,          // relative to assets root
            voices            = "",               // Kokoro only, unused
            lexicon           = LEXICON,
            dataDir           = "",               // no espeak-ng-data for zh-baker
            dictDir           = "",
            ruleFsts          = "$MODEL_DIR/phone.fst,$MODEL_DIR/date.fst,$MODEL_DIR/number.fst",
            ruleFars          = "",
            numThreads        = 4,
        )
        tts = OfflineTts(assetManager = assetManager, config = config)
        Log.i(TAG, "SherpaOnnxTts ready — sampleRate=${tts.sampleRate()}, speakers=${tts.numSpeakers()}")
    }

    /**
     * Generate speech for [text].
     * Returns Float32 PCM array at [SAMPLE_RATE] Hz, or empty array on error.
     */
    fun generate(text: String, speed: Float = DEFAULT_SPEED): FloatArray {
        if (text.isBlank()) return FloatArray(0)
        Log.v(TAG, "TTS generate: $text")
        val audio = tts.generate(text = text, sid = SPEAKER_ID, speed = speed)
        Log.v(TAG, "TTS generated ${audio.samples.size} samples (${audio.samples.size / SAMPLE_RATE.toFloat()}s)")
        return audio.samples
    }

    /** Warm up the model by running a short inference. */
    fun warmUp() {
        Log.i(TAG, "TTS warmup…")
        runCatching { generate("你好") }
        Log.i(TAG, "TTS warmup done")
    }

    override fun close() {
        // OfflineTts does not implement AutoCloseable; sherpa-onnx manages its own lifecycle
        Log.i(TAG, "SherpaOnnxTts closed")
    }
}
