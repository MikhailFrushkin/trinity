package com.trinity.assistant.net

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class UtteranceResponse(
    val transcript: String,
    val replyText: String,
    val intent: String,
    val missing: List<String> = emptyList(),
    val shutdown: Boolean = false,
)

class TrinityApi(
    private val client: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build(),
) {
    fun sendUtterance(
        baseUrl: String,
        wav: ByteArray,
        deviceId: String,
        sessionId: String,
    ): UtteranceResponse {
        val root = baseUrl.trimEnd('/')
        val body = MultipartBody.Builder()
            .setType(MultipartBody.FORM)
            .addFormDataPart(
                "audio",
                "command.wav",
                wav.toRequestBody("audio/wav".toMediaType()),
            )
            .addFormDataPart("device_id", deviceId)
            .addFormDataPart("session_id", sessionId)
            .build()
        val request = Request.Builder()
            .url("$root/v1/utterance")
            .post(body)
            .build()
        client.newCall(request).execute().use { response ->
            val payload = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                throw IllegalStateException("сервер ${response.code}: $payload")
            }
            val json = JSONObject(payload)
            val missingJson = json.optJSONArray("missing")
            val missing = buildList {
                if (missingJson != null) {
                    for (index in 0 until missingJson.length()) {
                        add(missingJson.optString(index))
                    }
                }
            }
            return UtteranceResponse(
                transcript = json.optString("transcript"),
                replyText = json.optString("reply_text"),
                intent = json.optString("intent"),
                missing = missing,
                shutdown = json.optBoolean("shutdown"),
            )
        }
    }
}
