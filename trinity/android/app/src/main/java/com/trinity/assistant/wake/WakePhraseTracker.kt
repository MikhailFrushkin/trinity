package com.trinity.assistant.wake

class WakePhraseTracker(
    private val gapMs: Long = 1500,
    private val cooldownMs: Long = 800,
) {
    private var lastGreetingAt: Long = -1L
    private var lastTriggerAt: Long = Long.MIN_VALUE / 4

    fun onText(text: String, nowMs: Long): Boolean {
        if (nowMs - lastTriggerAt < cooldownMs) {
            return false
        }
        val words = tokenize(text)
        if (words.isEmpty()) {
            return false
        }
        if (hasGreetingNamePair(words)) {
            lastTriggerAt = nowMs
            lastGreetingAt = -1L
            return true
        }
        val last = words.last()
        when {
            last in GREETINGS -> lastGreetingAt = nowMs
            last in NAMES && lastGreetingAt >= 0L && nowMs - lastGreetingAt <= gapMs -> {
                lastTriggerAt = nowMs
                lastGreetingAt = -1L
                return true
            }
            else -> lastGreetingAt = -1L
        }
        return false
    }

    fun reset() {
        lastGreetingAt = -1L
        lastTriggerAt = Long.MIN_VALUE / 4
    }

    companion object {
        private val GREETINGS = setOf("ей", "эй", "hey", "хей", "hei")
        private val NAMES = setOf("тринити", "trinity")

        fun tokenize(text: String): List<String> {
            val normalized = text.lowercase().replace('ё', 'е')
            return normalized.split(Regex("[^a-zа-я]+")).filter { it.isNotEmpty() }
        }

        fun hasGreetingNamePair(words: List<String>): Boolean {
            for (index in 0 until words.size - 1) {
                if (words[index] in GREETINGS && words[index + 1] in NAMES) {
                    return true
                }
            }
            return false
        }
    }
}
