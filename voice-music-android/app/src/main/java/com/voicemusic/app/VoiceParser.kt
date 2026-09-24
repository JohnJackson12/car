package com.voicemusic.app

/**
 * Pure (Android-free) port of the wake-word / command parsing and grammar building from the
 * Windows app's voice_control.py. Kept free of Android classes so it can be unit-tested on a PC.
 */
data class VoiceCommand(val kind: String, val number: Int = 0, val number2: Int = 0, val text: String = "")

object VoiceParser {
    val NUMBER_WORDS = mapOf(
        "one" to 1, "two" to 2, "three" to 3, "four" to 4, "five" to 5,
        "six" to 6, "seven" to 7, "eight" to 8, "nine" to 9, "ten" to 10
    )
    private val NUMBER_TO_WORD = NUMBER_WORDS.entries.associate { it.value to it.key }

    val SKIP_STEPS = listOf(5, 10, 15, 20, 25, 30, 35, 40, 45, 50, 55, 60)
    val SKIP_WORDS = linkedMapOf(
        5 to "five", 10 to "ten", 15 to "fifteen", 20 to "twenty", 25 to "twenty five",
        30 to "thirty", 35 to "thirty five", 40 to "forty", 45 to "forty five",
        50 to "fifty", 55 to "fifty five", 60 to "sixty"
    )
    val MINUTE_STEPS = listOf(1, 2, 3, 4, 5)
    val TRIM_STEPS = listOf(0, 10, 20, 30, 40, 50, 60)
    val TRIM_WORDS = linkedMapOf(0 to "zero", 10 to "ten", 20 to "twenty", 30 to "thirty",
        40 to "forty", 50 to "fifty", 60 to "sixty")
    private val TRIM_WORD_TO_SECONDS = TRIM_WORDS.entries.associate { it.value to it.key }

    /** Minimum average per-word confidence to accept a command (same as the PC app). */
    const val MIN_COMMAND_CONFIDENCE = 0.55

    private fun isDigits(s: String) = s.isNotEmpty() && s.all { it in '0'..'9' }

    fun wordsToNumber(raw: String): Int? {
        val text = raw.trim()
        if (isDigits(text)) return text.toIntOrNull()
        NUMBER_WORDS[text]?.let { return it }
        for ((seconds, word) in SKIP_WORDS) if (word == text) return seconds
        return null
    }

    private fun trimNumber(token: String): Int? {
        TRIM_WORD_TO_SECONDS[token]?.let { return it }
        if (isDigits(token)) return token.toIntOrNull()
        return null
    }

    fun wakePhrases(wakeWord: String, aliases: List<String>): List<String> {
        val names = ArrayList<String>()
        names.add(wakeWord.lowercase().trim())
        for (a in aliases) {
            val al = a.lowercase().trim()
            if (al.isNotEmpty() && al !in names) names.add(al)
        }
        val phrases = LinkedHashSet<String>()
        for (n in names) { phrases.add(n); phrases.add("hey $n") }
        return phrases.sortedByDescending { it.length }
    }

    fun findMatchingWake(text: String, wakePhrases: List<String>): String? {
        for (w in wakePhrases) if (text == w || text.startsWith("$w ")) return w
        return null
    }

    fun normalizeTitle(text: String?): String {
        var t = (text ?: "").lowercase()
        t = t.replace(Regex("[^a-z0-9 ]+"), " ")
        return t.replace(Regex("\\s+"), " ").trim()
    }

    private val SIMPLE_BASE: Map<String, VoiceCommand> = mapOf(
        "undo" to VoiceCommand("undo"), "undo that" to VoiceCommand("undo"), "undo last" to VoiceCommand("undo"),
        "next" to VoiceCommand("next"), "next song" to VoiceCommand("next"),
        "skip song" to VoiceCommand("next"), "skip this song" to VoiceCommand("next"),
        "previous" to VoiceCommand("previous"), "previous song" to VoiceCommand("previous"),
        "go back" to VoiceCommand("previous"), "last song" to VoiceCommand("previous"),
        "pause" to VoiceCommand("pause"), "stop" to VoiceCommand("pause"),
        "play" to VoiceCommand("play"), "resume" to VoiceCommand("play"), "continue" to VoiceCommand("play"),
        "status" to VoiceCommand("status"), "now playing" to VoiceCommand("status"),
        "what is playing" to VoiceCommand("status"), "what song is this" to VoiceCommand("status")
    )
    private val DELETE_DEFAULTS = listOf("delete", "remove", "remove this", "remove this song", "delete this",
        "delete this song", "trash", "trash this", "trash this song", "trash it")
    private val APPROVE_DEFAULTS = listOf("keep", "keep it", "keep this", "keep this song", "yes", "good")

    fun parseCommand(
        remainder: String, maxRating: Int = 5, songTitles: Map<String, String>? = null,
        approveWord: String = "", deleteWord: String = ""
    ): VoiceCommand? {
        if (remainder.isEmpty()) return null
        val simple = HashMap(SIMPLE_BASE)
        val dw = deleteWord.trim().lowercase()
        if (dw.isNotEmpty()) simple[dw] = VoiceCommand("delete")
        else for (w in DELETE_DEFAULTS) simple[w] = VoiceCommand("delete")
        val aw = approveWord.trim().lowercase()
        if (aw.isNotEmpty()) simple[aw] = VoiceCommand("approve")
        else for (w in APPROVE_DEFAULTS) simple[w] = VoiceCommand("approve")
        simple[remainder]?.let { return it }

        if ((remainder.startsWith("rate ") || remainder.startsWith("rating ")) && maxRating > 0) {
            val tail = remainder.substringAfter(" ").trim()
            val r = wordsToNumber(tail)
            return if (r != null && r in 1..maxRating) VoiceCommand("rate", r) else null
        }

        if (remainder.startsWith("play ") && !songTitles.isNullOrEmpty()) {
            val key = remainder.substring("play ".length).trim()
            val orig = songTitles[key]
            return if (orig != null) VoiceCommand("play_song", text = orig) else null
        }

        Regex("^trim (\\S+) (\\S+)$").matchEntire(remainder)?.let { m ->
            val front = trimNumber(m.groupValues[1])
            val end = trimNumber(m.groupValues[2])
            return if (front != null && end != null && front in TRIM_STEPS && end in TRIM_STEPS && (front != 0 || end != 0))
                VoiceCommand("trim", front, end) else null
        }

        Regex("^skip all songs (.+) minutes?$").matchEntire(remainder)?.let { m ->
            val n = wordsToNumber(m.groupValues[1].trim())
            return if (n != null && n in MINUTE_STEPS) VoiceCommand("skip_all", n * 60) else null
        }
        Regex("^skip all songs (.+)$").matchEntire(remainder)?.let { m ->
            val n = wordsToNumber(m.groupValues[1].trim())
            return if (n != null && n in SKIP_STEPS) VoiceCommand("skip_all", n) else null
        }
        Regex("^skip (.+) minutes?$").matchEntire(remainder)?.let { m ->
            val n = wordsToNumber(m.groupValues[1].trim())
            return if (n != null && n in MINUTE_STEPS) VoiceCommand("skip_current", n * 60) else null
        }
        Regex("^skip (.+) seconds?$").matchEntire(remainder)?.let { m ->
            val n = wordsToNumber(m.groupValues[1].trim())
            return if (n != null && n in SKIP_STEPS) VoiceCommand("skip_current", n) else null
        }
        Regex("^skip (.+)$").matchEntire(remainder)?.let { m ->
            val n = wordsToNumber(m.groupValues[1].trim())
            return if (n != null && n in SKIP_STEPS) VoiceCommand("skip_current", n) else null
        }

        val r = wordsToNumber(remainder)
        if (r != null && r in 1..maxRating) return VoiceCommand("rate", r)
        return null
    }

    fun buildGrammarPhrases(
        wakePhrases: List<String>, maxRating: Int, songTitleKeys: Collection<String>? = null,
        approveWord: String = "", deleteWord: String = ""
    ): List<String> {
        val dw = deleteWord.trim().lowercase()
        val aw = approveWord.trim().lowercase()
        val p = ArrayList<String>()
        for (wake in wakePhrases) {
            p.add(wake)
            for (n in 1..maxRating) {
                val word = NUMBER_TO_WORD[n] ?: n.toString()
                p.add("$wake $word"); p.add("$wake $n")
                p.add("$wake rate $word"); p.add("$wake rate $n")
                p.add("$wake rating $word"); p.add("$wake rating $n")
            }
            for ((seconds, word) in SKIP_WORDS) {
                p.add("$wake skip $word"); p.add("$wake skip $seconds"); p.add("$wake skip $word seconds")
                p.add("$wake skip all songs $word"); p.add("$wake skip all songs $seconds")
                p.add("$wake skip all songs $word seconds")
            }
            for (m in MINUTE_STEPS) {
                val word = NUMBER_TO_WORD[m] ?: m.toString()
                val suffix = if (m != 1) "s" else ""
                p.add("$wake skip $word minute$suffix")
                p.add("$wake skip all songs $word minute$suffix")
            }
            for (f in TRIM_STEPS) for (e in TRIM_STEPS) if (f != 0 || e != 0)
                p.add("$wake trim ${TRIM_WORDS[f]} ${TRIM_WORDS[e]}")
            songTitleKeys?.forEach { p.add("$wake play $it") }
            for (x in listOf("undo", "undo that", "undo last", "next", "next song", "skip song", "skip this song",
                "previous", "previous song", "go back", "last song", "pause", "stop", "play", "resume", "continue",
                "status", "now playing", "what is playing", "what song is this")) p.add("$wake $x")
            if (dw.isNotEmpty()) p.add("$wake $dw") else for (x in DELETE_DEFAULTS) p.add("$wake $x")
            if (aw.isNotEmpty()) p.add("$wake $aw") else for (x in APPROVE_DEFAULTS) p.add("$wake $x")
        }
        return p
    }
}
