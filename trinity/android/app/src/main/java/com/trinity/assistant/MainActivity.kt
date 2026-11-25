package com.trinity.assistant

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.trinity.assistant.databinding.ActivityMainBinding
import com.trinity.assistant.prefs.AppPrefs
import com.trinity.assistant.service.WakeWordService
import com.trinity.assistant.state.AssistantEvents

class MainActivity : AppCompatActivity() {
    private lateinit var binding: ActivityMainBinding
    private lateinit var prefs: AppPrefs
    private val listener: (AssistantEvents.Snapshot) -> Unit = { snapshot ->
        binding.statusText.text = snapshot.status
        binding.transcriptText.text = snapshot.lastTranscript.ifBlank { "—" }
        binding.replyText.text = snapshot.lastReply.ifBlank { "—" }
        binding.logText.text = formatColoredLog(snapshot.log)
        binding.toggleButton.text = if (snapshot.listening) {
            getString(R.string.stop)
        } else {
            getString(R.string.start)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        prefs = AppPrefs(this)
        binding.serverUrl.setText(prefs.serverUrl)
        binding.toggleButton.setOnClickListener {
            if (AssistantEvents.snapshot.listening) {
                stopListening()
            } else {
                prefs.serverUrl = binding.serverUrl.text?.toString().orEmpty()
                if (hasAudioPermission()) {
                    requestBatteryExemption()
                    startListening()
                } else {
                    requestNeededPermissions()
                }
            }
        }
        binding.saveUrlButton.setOnClickListener {
            prefs.serverUrl = binding.serverUrl.text?.toString().orEmpty()
            Toast.makeText(this, R.string.saved, Toast.LENGTH_SHORT).show()
        }
    }

    override fun onStart() {
        super.onStart()
        AssistantEvents.addListener(listener)
    }

    override fun onStop() {
        AssistantEvents.removeListener(listener)
        super.onStop()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS &&
            grantResults.isNotEmpty() &&
            grantResults.all { it == PackageManager.PERMISSION_GRANTED }
        ) {
            requestBatteryExemption()
            startListening()
        } else if (requestCode == REQ_PERMISSIONS) {
            Toast.makeText(this, R.string.need_mic, Toast.LENGTH_LONG).show()
        }
    }

    private fun startListening() {
        prefs.serverUrl = binding.serverUrl.text?.toString().orEmpty()
        val intent = Intent(this, WakeWordService::class.java)
        ContextCompat.startForegroundService(this, intent)
    }

    private fun stopListening() {
        val intent = Intent(this, WakeWordService::class.java).setAction(WakeWordService.ACTION_STOP)
        startService(intent)
    }

    private fun hasAudioPermission(): Boolean {
        val mic = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
        if (mic != PackageManager.PERMISSION_GRANTED) {
            return false
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val notify = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            return notify == PackageManager.PERMISSION_GRANTED
        }
        return true
    }

    private fun requestNeededPermissions() {
        val needed = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            needed += Manifest.permission.POST_NOTIFICATIONS
        }
        ActivityCompat.requestPermissions(this, needed.toTypedArray(), REQ_PERMISSIONS)
    }

    private fun requestBatteryExemption() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            return
        }
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
        intent.data = Uri.parse("package:$packageName")
        startActivity(intent)
    }

    private fun formatColoredLog(logLines: List<String>): CharSequence {
        if (logLines.isEmpty()) {
            return "—"
        }
        val builder = SpannableStringBuilder()
        logLines.forEachIndexed { index, line ->
            val start = builder.length
            builder.append(line)
            val end = builder.length

            val color = when {
                line.contains("ошибка", ignoreCase = true) ||
                    line.contains("недоступен", ignoreCase = true) -> Color.parseColor("#F87171")
                line.contains("активация:", ignoreCase = true) ||
                    line.contains("слушаю", ignoreCase = true) ||
                    line.contains("голос TTS:", ignoreCase = true) -> Color.parseColor("#4ADE80")
                line.contains("отправляю", ignoreCase = true) ||
                    line.contains("запись", ignoreCase = true) ||
                    line.contains("жду ответ", ignoreCase = true) -> Color.parseColor("#38BDF8")
                line.contains("текст:", ignoreCase = true) -> Color.parseColor("#FACC15")
                else -> Color.parseColor("#CBD5E1")
            }

            builder.setSpan(
                ForegroundColorSpan(color),
                start,
                end,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            if (index < logLines.size - 1) {
                builder.append("\n")
            }
        }
        return builder
    }

    companion object {
        private const val REQ_PERMISSIONS = 42
    }
}
