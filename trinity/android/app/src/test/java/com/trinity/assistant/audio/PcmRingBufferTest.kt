package com.trinity.assistant.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Test

class PcmRingBufferTest {
    @Test
    fun shouldKeepLatestBytesWhenCapacityExceeded() {
        val ring = PcmRingBuffer(4)
        ring.write(byteArrayOf(1, 2, 3), 0, 3)
        ring.write(byteArrayOf(4, 5), 0, 2)

        assertArrayEquals(byteArrayOf(2, 3, 4, 5), ring.snapshot())
    }
}
