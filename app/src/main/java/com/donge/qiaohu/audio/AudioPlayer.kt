package com.donge.qiaohu.audio

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.util.Log

private const val TAG = "AudioPlayer"

/**
 * Streams audio to the speaker via [AudioTrack] in streaming (MODE_STREAM) mode.
 *
 * Accepts Float32 PCM samples and converts them to Int16 before writing,
 * matching the output format produced by SherpaOnnxTts.
 *
 * Sample rate: 22 050 Hz (sherpa-onnx matcha-icefall-zh-baker output rate)
 * Channels:    mono
 * Encoding:    PCM_16BIT
 *
 * Usage:
 *   val player = AudioPlayer()
 *   player.write(pcmFloat32)   // call for each TTS sentence chunk
 *   player.stop()              // interrupt playback (barge-in)
 *   player.close()             // release at end of session
 */
class AudioPlayer : AutoCloseable {

    companion object {
        const val SAMPLE_RATE = 22_050
    }

    private val minBufSize = AudioTrack.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_OUT_MONO,
        AudioFormat.ENCODING_PCM_16BIT,
    )

    private var track: AudioTrack = buildTrack()

    private fun buildTrack(): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .setAudioFormat(
            AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                .build()
        )
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(minBufSize * 8)
        .build()
        .also { it.play() }

    /**
     * Write a chunk of Float32 PCM audio and play it immediately.
     * Blocks until the OS audio buffer accepts all samples.
     *
     * Called once per TTS sentence from the IO thread.
     * Mirrors server.py audio_chunk → base64 decode → AudioContext.decodeAudioData.
     */
    fun write(pcmFloat32: FloatArray) {
        if (track.state == AudioTrack.STATE_UNINITIALIZED) {
            Log.w(TAG, "AudioTrack uninitialised; rebuilding")
            track = buildTrack()
        }
        // Ensure track is playing (may have been paused by stop())
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }
        val int16 = ShortArray(pcmFloat32.size) { i ->
            (pcmFloat32[i].coerceIn(-1f, 1f) * 32767f).toInt().toShort()
        }
        val written = track.write(int16, 0, int16.size)
        if (written < 0) Log.w(TAG, "AudioTrack.write returned error code $written")
    }

    /**
     * Immediately stop playback and flush the audio buffer.
     * Used for barge-in: mirrors the client-side interruption in index.html
     * where AudioContext.suspend() is called.
     *
     * After stop(), write() will rebuild the track automatically.
     */
    fun stop() {
        if (track.playState == AudioTrack.PLAYSTATE_PLAYING) {
            track.pause()
            track.flush()
            Log.d(TAG, "Playback stopped and flushed")
        }
    }

    /** Resume playback after stop().  Usually not needed — write() rebuilds. */
    fun resume() {
        if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
            track.play()
        }
    }

    override fun close() {
        runCatching { track.stop() }
        runCatching { track.release() }
        Log.i(TAG, "AudioPlayer released")
    }
}
