package com.trinity.assistant.wake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StopPhrasesTest {
    @Test
    fun shouldDetectStopDuringDialog() {
        assertTrue(StopPhrases.isConversationStop("стоп"))
        assertTrue(StopPhrases.isConversationStop("отмена"))
        assertFalse(StopPhrases.isConversationStop("пятнадцать"))
    }

    @Test
    fun shouldShutdownOnTrinityStop() {
        assertTrue(StopPhrases.isShutdown("Тринити, стоп"))
        assertTrue(StopPhrases.isShutdown("эй тринити стоп"))
        assertFalse(StopPhrases.isShutdown("стоп"))
    }
}
