package com.trinity.assistant.audio

class PcmRingBuffer(private val capacityBytes: Int) {
    private val data = ByteArray(capacityBytes.coerceAtLeast(2))
    private var length = 0
    private var head = 0

    fun write(src: ByteArray, offset: Int, count: Int) {
        if (count <= 0) {
            return
        }
        val size = data.size
        var remaining = count
        var from = offset
        while (remaining > 0) {
            val copy = minOf(remaining, size - head)
            System.arraycopy(src, from, data, head, copy)
            head = (head + copy) % size
            from += copy
            remaining -= copy
            length = minOf(size, length + copy)
        }
    }

    fun snapshot(): ByteArray {
        if (length == 0) {
            return ByteArray(0)
        }
        val out = ByteArray(length)
        val start = if (length == data.size) head else 0
        val first = minOf(length, data.size - start)
        System.arraycopy(data, start, out, 0, first)
        if (first < length) {
            System.arraycopy(data, 0, out, first, length - first)
        }
        return out
    }

    fun clear() {
        length = 0
        head = 0
    }
}
