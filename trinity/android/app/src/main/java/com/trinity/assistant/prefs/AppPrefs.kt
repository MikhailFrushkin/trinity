package com.trinity.assistant.prefs

import android.content.Context

class AppPrefs(context: Context) {
    private val prefs = context.getSharedPreferences("trinity", Context.MODE_PRIVATE)

    var serverUrl: String
        get() = prefs.getString(KEY_URL, DEFAULT_URL) ?: DEFAULT_URL
        set(value) {
            prefs.edit().putString(KEY_URL, value.trim()).apply()
        }

    val sessionId: String
        get() {
            val existing = prefs.getString(KEY_SESSION, null)
            if (!existing.isNullOrBlank()) {
                return existing
            }
            val created = java.util.UUID.randomUUID().toString()
            prefs.edit().putString(KEY_SESSION, created).apply()
            return created
        }

    companion object {
        const val DEFAULT_URL = "http://10.8.1.5:8000"
        private const val KEY_URL = "server_url"
        private const val KEY_SESSION = "session_id"
    }
}
