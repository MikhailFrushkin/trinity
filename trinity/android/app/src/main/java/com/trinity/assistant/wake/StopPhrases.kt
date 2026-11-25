package com.trinity.assistant.wake

object StopPhrases {
    private val STOP_WORDS = setOf(
        "стоп",
        "stop",
        "отмена",
        "хватит",
        "замолчи",
        "выключись",
        "отключись",
        "закончи",
    )
    private val NAMES = setOf("тринити", "trinity")

    fun isConversationStop(text: String): Boolean {
        val words = WakePhraseTracker.tokenize(text)
        return words.any { it in STOP_WORDS }
    }

    fun isShutdown(text: String): Boolean {
        val words = WakePhraseTracker.tokenize(text)
        return words.any { it in STOP_WORDS } && words.any { it in NAMES }
    }
}
