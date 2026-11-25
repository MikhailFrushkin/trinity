package com.trinity.assistant.service

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.ToneGenerator
import android.media.AudioManager
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.trinity.assistant.MainActivity
import com.trinity.assistant.R
import com.trinity.assistant.TrinityApp
import com.trinity.assistant.audio.EnergyVad
import com.trinity.assistant.audio.PcmRingBuffer
import com.trinity.assistant.audio.WavEncoder
import com.trinity.assistant.net.TrinityApi
import com.trinity.assistant.prefs.AppPrefs
import com.trinity.assistant.state.AssistantEvents
import com.trinity.assistant.vosk.ModelStore
import com.trinity.assistant.wake.StopPhrases
import com.trinity.assistant.wake.WakePhraseTracker
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import java.util.Locale
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

class WakeWordService : Service() {
    private val running = AtomicBoolean(false)
    private val api = TrinityApi()
    private val uploads = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "trinity-upload")
    }
    private lateinit var prefs: AppPrefs
    private var worker: Thread? = null
    private var tts: TextToSpeech? = null
    private var afterSpeak: (() -> Unit)? = null
    private val followUp = AtomicBoolean(false)
    private val ttsBusy = AtomicBoolean(false)
    private val powerOffRequested = AtomicBoolean(false)
    private val mainHandler = Handler(Looper.getMainLooper())
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGain: AutomaticGainControl? = null
    private var echoCanceler: AcousticEchoCanceler? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        prefs = AppPrefs(this)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                applyFeminineVoice(tts)
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit

                    override fun onDone(utteranceId: String?) {
                        mainHandler.post { finishSpeak() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        mainHandler.post { finishSpeak() }
                    }
                })
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopWork()
                stopSelf()
                return START_NOT_STICKY
            }
            else -> startWork()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        stopWork()
        releaseCaptureEffects()
        uploads.shutdownNow()
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }

    private fun startWork() {
        if (running.getAndSet(true)) {
            return
        }
        startAsForeground(getString(R.string.status_listening))
        AssistantEvents.setStatus(getString(R.string.status_preparing), listening = true)
        worker = Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
            runLoop()
        }, "trinity-audio").also { it.start() }
    }

    private fun stopWork() {
        running.set(false)
        worker?.interrupt()
        worker = null
        AssistantEvents.setStatus(getString(R.string.status_stopped), listening = false)
    }

    private fun runLoop() {
        val store = ModelStore(this)
        try {
            store.ensure { message ->
                AssistantEvents.setStatus(message, listening = true)
                AssistantEvents.log(message)
            }
        } catch (error: Exception) {
            AssistantEvents.log("модель: ${error.message}")
            AssistantEvents.setStatus(getString(R.string.status_error), listening = false)
            running.set(false)
            stopSelf()
            return
        }

        try {
            listen(store)
        } catch (error: Exception) {
            AssistantEvents.log("слух: ${error.message}")
            AssistantEvents.setStatus(getString(R.string.status_error), listening = false)
            running.set(false)
            stopSelf()
            return
        }
        if (powerOffRequested.get()) {
            AssistantEvents.setStatus(getString(R.string.status_stopped), listening = false)
            stopSelf()
        }
    }

    private fun listen(store: ModelStore) {
        val model = Model(store.modelDir().absolutePath)
        val recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        val tracker = WakePhraseTracker()
        val vad = EnergyVad()
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            AssistantEvents.log("микрофон недоступен: нет разрешения")
            AssistantEvents.setStatus(getString(R.string.status_error), listening = false)
            running.set(false)
            recognizer.close()
            model.close()
            stopSelf()
            return
        }
        val recorder = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 8,
            )
        } catch (e: SecurityException) {
            AssistantEvents.log("микрофон недоступен: ${e.message}")
            AssistantEvents.setStatus(getString(R.string.status_error), listening = false)
            running.set(false)
            recognizer.close()
            model.close()
            stopSelf()
            return
        }
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            AssistantEvents.log("микрофон недоступен")
            AssistantEvents.setStatus(getString(R.string.status_error), listening = false)
            running.set(false)
            recognizer.close()
            model.close()
            stopSelf()
            return
        }
        enableCaptureEffects(recorder.audioSessionId)

        val buffer = ByteArray(minBuffer)
        val command = ByteArrayOutputStream()
        val preroll = PcmRingBuffer(SAMPLE_RATE * 2 * PRE_ROLL_MS / 1000)
        var recording = false
        var followUpTake = false
        recorder.startRecording()
        notifyListening()
        AssistantEvents.setStatus(getString(R.string.status_listening), listening = true)
        AssistantEvents.log("слушаю «Ей, Тринити»")

        fun hypothesisFrom(chunk: ByteArray, bytes: Int): String {
            val ended = recognizer.acceptWaveForm(chunk, bytes)
            return if (ended) {
                extractText(recognizer.result, "text")
            } else {
                extractText(recognizer.partialResult, "partial")
            }
        }

        fun finishCommand() {
            val wav = WavEncoder.pcm16leToWav(command.toByteArray(), SAMPLE_RATE)
            recording = false
            followUpTake = false
            command.reset()
            recognizer.reset()
            notifyListening()
            AssistantEvents.setStatus(getString(R.string.status_listening), listening = true)
            AssistantEvents.log("отправляю ${wav.size} байт")
            uploads.execute { sendCommand(wav) }
        }

        fun beginCapture(usePreroll: Boolean, reason: String) {
            recording = true
            command.reset()
            recognizer.reset()
            if (followUpTake) {
                vad.reset(
                    silenceRms = 250.0,
                    silenceMs = 700,
                    minSpeechMs = 150,
                    maxMs = 6000,
                    noSpeechTimeoutMs = 3500,
                )
            } else {
                vad.reset(
                    silenceRms = 280.0,
                    silenceMs = 1400,
                    minSpeechMs = 200,
                    maxMs = 12000,
                    noSpeechTimeoutMs = 0,
                )
            }

            if (usePreroll) {
                val captured = preroll.snapshot()
                command.write(captured)
                vad.absorbPreroll(captured, captured.size, SAMPLE_RATE)
            }
            notifyRecording()
            val status = if (followUpTake) {
                getString(R.string.status_followup)
            } else {
                getString(R.string.status_recording)
            }
            AssistantEvents.setStatus(status, listening = true)
            AssistantEvents.log(reason)
            cueUser()
        }

        try {
            while (running.get() && !Thread.currentThread().isInterrupted) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read <= 0) {
                    continue
                }
                preroll.write(buffer, 0, read)
                if (!recording && !ttsBusy.get() && followUp.compareAndSet(true, false)) {
                    followUpTake = true
                    beginCapture(usePreroll = false, reason = "жду ответ без wake-word")
                }
                if (!recording) {
                    if (ttsBusy.get()) {
                        continue
                    }
                    val hypothesis = hypothesisFrom(buffer, read)
                    if (hypothesis.isNotBlank() && StopPhrases.isShutdown(hypothesis)) {
                        powerOffRequested.set(true)
                        running.set(false)
                        uploads.execute { sendCommand(WavEncoder.pcm16leToWav(preroll.snapshot(), SAMPLE_RATE)) }
                        break
                    }
                    if (hypothesis.isNotBlank() && tracker.onText(hypothesis, SystemClock.elapsedRealtime())) {
                        followUpTake = false
                        beginCapture(usePreroll = true, reason = "активация: $hypothesis")
                    }
                } else {
                    command.write(buffer, 0, read)
                    val live = hypothesisFrom(buffer, read)
                    if (live.isNotBlank() && StopPhrases.isShutdown(live)) {
                        powerOffRequested.set(true)
                        finishCommand()
                        running.set(false)
                        break
                    }
                    if (followUpTake && live.isNotBlank() && StopPhrases.isConversationStop(live)) {
                        finishCommand()
                        continue
                    }
                    if (vad.shouldStop(buffer, read, SAMPLE_RATE)) {
                        finishCommand()
                    }
                }
            }
        } finally {
            recorder.stop()
            recorder.release()
            releaseCaptureEffects()
            recognizer.close()
            model.close()
        }
    }

    private fun sendCommand(wav: ByteArray) {
        notifyUploading()
        try {
            val result = api.sendUtterance(
                baseUrl = prefs.serverUrl,
                wav = wav,
                deviceId = deviceId(),
                sessionId = prefs.sessionId,
            )
            AssistantEvents.setResult(result.transcript, result.replyText)
            AssistantEvents.log("текст: ${result.transcript}")
            speak(result.replyText) {
                if (result.shutdown || powerOffRequested.get()) {
                    powerOffRequested.set(true)
                    running.set(false)
                    mainHandler.post { stopSelf() }
                    return@speak
                }
                if (result.missing.isNotEmpty()) {
                    AssistantEvents.setStatus(getString(R.string.status_followup), listening = true)
                    updateNotification(getString(R.string.status_followup))
                    followUp.set(true)
                }
            }
        } catch (error: Exception) {
            AssistantEvents.log("ошибка: ${error.message}")
            AssistantEvents.setResult("", error.message.orEmpty())
        }
    }

    private fun applyFeminineVoice(engine: TextToSpeech?) {
        if (engine == null) {
            return
        }
        val langResult = engine.setLanguage(Locale("ru", "RU"))
        if (langResult == TextToSpeech.LANG_MISSING_DATA || langResult == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.language = Locale.getDefault()
        }
        engine.setPitch(1.30f)
        engine.setSpeechRate(0.98f)

        val voices = try {
            engine.voices
        } catch (_: Exception) {
            null
        }

        if (voices.isNullOrEmpty()) {
            return
        }

        val russian = voices.filter { voice ->
            val lang = voice.locale.language.lowercase(Locale.ROOT)
            (lang == "ru" || voice.locale.language.startsWith("ru")) &&
                !voice.features.contains(TextToSpeech.Engine.KEY_FEATURE_NOT_INSTALLED)
        }

        val femaleKeywords = listOf(
            "female", "woman", "girl", "fem", "rua", "ruc", "rud", "dfc", "sfg",
            "irina", "tatyana", "tatiana", "elena", "viktoria", "kseniya", "anna",
            "marina", "alisa", "nora", "milena", "natasha", "katya", "olga", "daria",
            "maria", "yulia", "julia", "sveta", "lilia", "lilya", "vera", "polina", "viktoriya"
        )
        val maleKeywords = listOf(
            "male", "man", "boy", "rub", "rue", "aleksandr", "alexandr", "pavel",
            "dmitry", "vladimir", "artem", "evgeny", "sergey", "mikhail", "yury",
            "ivan", "boris", "nikita", "maxim", "denis", "roman", "vitaly", "igor", "anton"
        )

        fun isFemale(voice: Voice): Boolean {
            val name = voice.name.lowercase(Locale.ROOT)
            val features = voice.features.map { it.lowercase(Locale.ROOT) }
            if (features.any { it.contains("female") || it.contains("woman") }) {
                return true
            }
            if (maleKeywords.any { name.contains(it) }) {
                return false
            }
            return femaleKeywords.any { name.contains(it) }
        }

        val candidates = if (russian.isNotEmpty()) russian else voices.toList()

        val female = candidates.firstOrNull { isFemale(it) }
            ?: candidates.firstOrNull { voice ->
                val name = voice.name.lowercase(Locale.ROOT)
                maleKeywords.none { name.contains(it) }
            }
            ?: candidates.maxByOrNull { it.quality }

        if (female != null) {
            try {
                engine.voice = female
                AssistantEvents.log("голос TTS: ${female.name}")
            } catch (e: Exception) {
                AssistantEvents.log("ошибка голоса: ${e.message}")
            }
        }
    }

    private fun enableCaptureEffects(sessionId: Int) {
        releaseCaptureEffects()
        if (NoiseSuppressor.isAvailable()) {
            noiseSuppressor = NoiseSuppressor.create(sessionId)?.also { it.enabled = true }
        }
        if (AutomaticGainControl.isAvailable()) {
            automaticGain = AutomaticGainControl.create(sessionId)?.also { it.enabled = true }
        }
        if (AcousticEchoCanceler.isAvailable()) {
            echoCanceler = AcousticEchoCanceler.create(sessionId)?.also { it.enabled = true }
        }
    }

    private fun releaseCaptureEffects() {
        noiseSuppressor?.release()
        automaticGain?.release()
        echoCanceler?.release()
        noiseSuppressor = null
        automaticGain = null
        echoCanceler = null
    }

    private fun speak(text: String, then: () -> Unit) {
        if (text.isBlank() || tts == null) {
            then()
            return
        }
        applyFeminineVoice(tts)
        ttsBusy.set(true)
        afterSpeak = then
        val id = UUID.randomUUID().toString()
        val started = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
        if (started != TextToSpeech.SUCCESS) {
            finishSpeak()
        }
    }

    private fun finishSpeak() {
        ttsBusy.set(false)
        val next = afterSpeak
        afterSpeak = null
        next?.invoke()
    }

    private fun cueUser() {
        try {
            val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
            tone.startTone(ToneGenerator.TONE_PROP_BEEP, 150)
            tone.release()
        } catch (_: Exception) {
        }
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            getSystemService(VIBRATOR_SERVICE) as Vibrator
        }
        vibrator.vibrate(VibrationEffect.createOneShot(80, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    @SuppressLint("HardwareIds")
    private fun deviceId(): String {
        return android.provider.Settings.Secure.getString(
            contentResolver,
            android.provider.Settings.Secure.ANDROID_ID,
        ) ?: "android"
    }

    private fun extractText(json: String, key: String): String {
        return try {
            JSONObject(json).optString(key).trim()
        } catch (_: Exception) {
            ""
        }
    }

    private fun startAsForeground(text: String) {
        val notification = buildNotification(text)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun notifyListening() {
        updateNotification(getString(R.string.status_listening))
    }

    private fun notifyRecording() {
        updateNotification(getString(R.string.status_recording))
    }

    private fun notifyUploading() {
        updateNotification(getString(R.string.status_uploading))
    }

    private fun updateNotification(text: String) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        manager.notify(NOTIFICATION_ID, buildNotification(text))
    }

    private fun buildNotification(text: String): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this,
            1,
            Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, TrinityApp.CHANNEL_ID)
            .setContentTitle(getString(R.string.app_name))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_trinity)
            .setOngoing(true)
            .setContentIntent(open)
            .addAction(0, getString(R.string.stop), stop)
            .build()
    }

    companion object {
        const val ACTION_STOP = "com.trinity.assistant.STOP"
        private const val NOTIFICATION_ID = 17
        const val SAMPLE_RATE = 16000
        private const val PRE_ROLL_MS = 2500
    }
}
