package com.trinity.assistant.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class EnergyVadTest {
    @Test
    fun shouldStopAfterSpeechThenSilence() {
        val vad = EnergyVad(silenceRms = 100.0, silenceMs = 200, minSpeechMs = 100, maxMs = 8000)
        val loud = ByteArray(3200) { 80 }
        val quiet = ByteArray(6400) { 0 }

        assertFalse(vad.shouldStop(loud, loud.size, 16000))
        assertTrue(vad.shouldStop(quiet, quiet.size, 16000))
    }
}
