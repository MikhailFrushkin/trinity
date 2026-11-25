package com.trinity.assistant.audio

import kotlin.math.sqrt

class EnergyVad(
    var silenceRms: Double = 280.0,
    var silenceMs: Int = 1000,
    var minSpeechMs: Int = 200,
    var maxMs: Int = 12000,
    var noSpeechTimeoutMs: Int = 0,
) {
    private var heardSpeechMs = 0
    private var silenceRunMs = 0
    private var totalMs = 0

    fun reset(
        silenceRms: Double = 280.0,
        silenceMs: Int = 1000,
        minSpeechMs: Int = 200,
        maxMs: Int = 12000,
        noSpeechTimeoutMs: Int = 0,
    ) {
        this.silenceRms = silenceRms
        this.silenceMs = silenceMs
        this.minSpeechMs = minSpeechMs
        this.maxMs = maxMs
        this.noSpeechTimeoutMs = noSpeechTimeoutMs
        heardSpeechMs = 0
        silenceRunMs = 0
        totalMs = 0
    }

    fun absorbPreroll(pcm: ByteArray, bytes: Int, sampleRate: Int) {
        val durationMs = if (sampleRate == 0) 0 else (bytes / 2) * 1000 / sampleRate
        val rms = rms(pcm, bytes)
        if (rms >= silenceRms) {
            heardSpeechMs += durationMs
        }
        silenceRunMs = 0
    }

    fun absorb(pcm: ByteArray, bytes: Int, sampleRate: Int): Boolean {
        return shouldStop(pcm, bytes, sampleRate)
    }

    fun shouldStop(pcm: ByteArray, bytes: Int, sampleRate: Int): Boolean {
        val durationMs = if (sampleRate == 0) 0 else (bytes / 2) * 1000 / sampleRate
        totalMs += durationMs
        val rms = rms(pcm, bytes)
        if (rms >= silenceRms) {
            heardSpeechMs += durationMs
            silenceRunMs = 0
        } else if (heardSpeechMs >= minSpeechMs) {
            silenceRunMs += durationMs
        }

        if (totalMs >= maxMs) {
            return true
        }

        if (noSpeechTimeoutMs > 0 && heardSpeechMs < minSpeechMs && totalMs >= noSpeechTimeoutMs) {
            return true
        }

        return heardSpeechMs >= minSpeechMs && silenceRunMs >= silenceMs
    }

    companion object {
        fun rms(pcm: ByteArray, bytes: Int): Double {
            if (bytes < 2) {
                return 0.0
            }
            var sum = 0.0
            var samples = 0
            var index = 0
            while (index + 1 < bytes) {
                val sample = (pcm[index].toInt() and 0xFF) or (pcm[index + 1].toInt() shl 8)
                val signed = sample.toShort().toInt()
                sum += signed.toDouble() * signed
                samples += 1
                index += 2
            }
            if (samples == 0) {
                return 0.0
            }
            return sqrt(sum / samples)
        }
    }
}
