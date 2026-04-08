package com.example.parlor.tts

/**
 * Sentence splitter — Android/Kotlin equivalent of server.py SENTENCE_SPLIT_RE.
 *
 * Python original:
 *   SENTENCE_SPLIT_RE = re.compile(r"(?<=[。！？.!?])\s*")
 *
 * Splits on Chinese (。！？) and ASCII (.!?) sentence-ending punctuation,
 * consuming any following whitespace, so each resulting segment is a clean
 * sentence ready for TTS generation.
 */
object SentenceSplitter {

    private val SPLIT_RE = Regex("""(?<=[。！？.!?])\s*""")

    /**
     * Split [text] into individual sentences.
     * Empty strings and whitespace-only segments are filtered out.
     */
    fun split(text: String): List<String> =
        SPLIT_RE.split(text.trim())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
}
