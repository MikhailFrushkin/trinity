package com.trinity.assistant.wake

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTrackerTest {
    @Test
    fun shouldTriggerOnHeyTrinity() {
        val tracker = WakePhraseTracker()
        assertTrue(tracker.onText("Ей, Тринити", 0))
    }

    @Test
    fun shouldTriggerOnLatinHeyTrinity() {
        val tracker = WakePhraseTracker()
        assertTrue(tracker.onText("hey trinity", 0))
    }

    @Test
    fun shouldNotTriggerOnNameAlone() {
        val tracker = WakePhraseTracker()
        assertFalse(tracker.onText("Тринити", 0))
        assertFalse(tracker.onText("приложение Тринити", 0))
        assertFalse(tracker.onText("trinity", 0))
    }

    @Test
    fun shouldTriggerWhenGreetingAndNameSplitInTime() {
        val tracker = WakePhraseTracker()
        assertFalse(tracker.onText("ей", 0))
        assertTrue(tracker.onText("тринити", 1400))
    }

    @Test
    fun shouldNotTriggerWhenGapIsTooLong() {
        val tracker = WakePhraseTracker()
        assertFalse(tracker.onText("ей", 0))
        assertFalse(tracker.onText("тринити", 1600))
    }

    @Test
    fun shouldIgnoreWakeDuringCooldown() {
        val tracker = WakePhraseTracker()
        assertTrue(tracker.onText("эй тринити", 0))
        assertFalse(tracker.onText("эй тринити", 700))
        assertTrue(tracker.onText("эй тринити", 800))
    }

    @Test
    fun shouldTriggerWhenCommandFollowsWakeInSamePhrase() {
        val tracker = WakePhraseTracker()
        assertTrue(tracker.onText("эй тринити размести заказ 166655 на адрес 12", 0))
    }
}
