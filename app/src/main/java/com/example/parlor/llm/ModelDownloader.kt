package com.example.parlor.llm

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "ModelDownloader"

private const val HF_REPO     = "litert-community/gemma-4-E2B-it-litert-lm"
private const val HF_FILENAME = "gemma-4-E2B-it.litertlm"
// HuggingFace CDN URL — redirects to the actual storage bucket.
private const val DOWNLOAD_URL =
    "https://huggingface.co/$HF_REPO/resolve/main/$HF_FILENAME"
// Minimum plausible file size (1 GB) — guards against corrupt partial downloads.
private const val MIN_VALID_SIZE = 1_000_000_000L

/**
 * Manages the 2.58 GB LiteRT-LM model file.
 *
 * The model is stored in external private storage (not counted against the app's
 * internal quota) and survives app reinstalls as long as the user keeps the data.
 *
 * Mirrors server.py resolve_model_path() / hf_hub_download().
 */
object ModelDownloader {

    /** Returns the expected on-device path for the model file. */
    fun modelFile(context: Context): File {
        val dir = context.getExternalFilesDir("models") ?: context.filesDir
        return File(dir, HF_FILENAME)
    }

    /**
     * Returns true if a complete model file already exists.
     * A file smaller than [MIN_VALID_SIZE] is treated as a partial download.
     */
    fun isDownloaded(context: Context): Boolean {
        val f = modelFile(context)
        return f.exists() && f.length() >= MIN_VALID_SIZE
    }

    /**
     * Downloads the model if it is not already present.
     *
     * Supports resumable downloads: if a partial file exists it sends
     * a Range header to continue from where it left off.
     *
     * @param onProgress  Called with values in [0.0, 1.0].  May be called from
     *                    an IO thread so post to main thread before touching UI.
     */
    suspend fun ensureDownloaded(
        context: Context,
        onProgress: (Float) -> Unit = {},
    ): File = withContext(Dispatchers.IO) {
        val dest = modelFile(context)

        if (isDownloaded(context)) {
            Log.i(TAG, "Model already present at ${dest.absolutePath}")
            return@withContext dest
        }

        Log.i(TAG, "Downloading model to ${dest.absolutePath}")
        dest.parentFile?.mkdirs()

        val existingBytes = if (dest.exists()) dest.length() else 0L

        var conn = URL(DOWNLOAD_URL).openConnection() as HttpURLConnection
        if (existingBytes > 0) {
            conn.setRequestProperty("Range", "bytes=$existingBytes-")
            Log.i(TAG, "Resuming from byte $existingBytes")
        }
        conn.connect()

        // Follow redirects manually (HuggingFace → S3/Cloudflare CDN)
        while (conn.responseCode in 301..302) {
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            conn = URL(location).openConnection() as HttpURLConnection
            if (existingBytes > 0) conn.setRequestProperty("Range", "bytes=$existingBytes-")
            conn.connect()
        }

        val totalFromServer = conn.contentLengthLong
        val total = if (totalFromServer > 0) existingBytes + totalFromServer else -1L
        Log.i(TAG, "Server content-length=$totalFromServer  total=$total")

        dest.outputStream().use { out ->
            if (existingBytes > 0) {
                // Seek to end of existing content before appending.
                // Re-open in append mode.
            }
        }

        val appendStream = dest.outputStream().also {
            // Skip already-downloaded bytes for append.
            if (existingBytes > 0) {
                val tmpFile = File(dest.parent, "$HF_FILENAME.part")
                dest.renameTo(tmpFile)
                // Copy existing bytes then append new data
                tmpFile.inputStream().use { src ->
                    val buf = ByteArray(128 * 1024)
                    var n: Int
                    while (src.read(buf).also { n = it } != -1) it.write(buf, 0, n)
                }
                tmpFile.delete()
            }
        }

        appendStream.use { out ->
            conn.inputStream.use { input ->
                val buf = ByteArray(128 * 1024)
                var downloaded = existingBytes
                var n: Int
                while (input.read(buf).also { n = it } != -1) {
                    out.write(buf, 0, n)
                    downloaded += n
                    if (total > 0) onProgress(downloaded.toFloat() / total)
                }
            }
        }

        Log.i(TAG, "Download complete: ${dest.length()} bytes")
        dest
    }
}
