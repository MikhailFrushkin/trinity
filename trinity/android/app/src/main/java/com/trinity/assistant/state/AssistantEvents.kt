package com.trinity.assistant.state

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArrayList

object AssistantEvents {
    data class Snapshot(
        val status: String = "остановлен",
        val lastTranscript: String = "",
        val lastReply: String = "",
        val log: List<String> = emptyList(),
        val listening: Boolean = false,
    )

    private val main = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<(Snapshot) -> Unit>()
    private val logLines = ArrayDeque<String>()

    @Volatile
    var snapshot: Snapshot = Snapshot()
        private set

    fun addListener(listener: (Snapshot) -> Unit) {
        listeners.add(listener)
        listener(snapshot)
    }

    fun removeListener(listener: (Snapshot) -> Unit) {
        listeners.remove(listener)
    }

    fun setStatus(status: String, listening: Boolean = snapshot.listening) {
        publish(snapshot.copy(status = status, listening = listening))
    }

    fun setResult(transcript: String, reply: String) {
        publish(snapshot.copy(lastTranscript = transcript, lastReply = reply))
    }

    fun log(line: String) {
        synchronized(logLines) {
            logLines.addLast(line)
            while (logLines.size > 40) {
                logLines.removeFirst()
            }
            publish(snapshot.copy(log = logLines.toList()))
        }
    }

    private fun publish(next: Snapshot) {
        snapshot = next
        main.post {
            listeners.forEach { it(next) }
        }
    }
}
