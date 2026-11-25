package com.trinity.assistant.vosk

import android.content.Context
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

class ModelStore(private val context: Context) {
    fun modelDir(): File = File(context.filesDir, MODEL_DIR_NAME)

    fun isReady(): Boolean {
        val dir = modelDir()
        return File(dir, "am/final.mdl").exists() || File(dir, "conf/model.conf").exists()
    }

    fun ensure(onProgress: (String) -> Unit) {
        if (isReady()) {
            return
        }
        onProgress("скачиваю модель Vosk…")
        val zipFile = File(context.cacheDir, "vosk-model-small-ru-0.22.zip")
        download(zipFile)
        onProgress("распаковываю модель…")
        val targetRoot = context.filesDir
        unzip(zipFile, targetRoot)
        zipFile.delete()
        if (!isReady()) {
            throw IllegalStateException("модель Vosk не найдена после распаковки")
        }
    }

    private fun download(dest: File) {
        val client = OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.MINUTES)
            .build()
        val request = Request.Builder().url(MODEL_URL).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw IllegalStateException("не удалось скачать модель: ${response.code}")
            }
            val body = response.body ?: throw IllegalStateException("пустое тело модели")
            dest.outputStream().use { out ->
                body.byteStream().copyTo(out)
            }
        }
    }

    private fun unzip(zipFile: File, targetRoot: File) {
        val rootPath = targetRoot.canonicalPath
        ZipInputStream(zipFile.inputStream()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                val outFile = File(targetRoot, entry.name).canonicalFile
                if (!outFile.path.startsWith(rootPath)) {
                    throw SecurityException("некорректный путь в архиве")
                }
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { output ->
                        zip.copyTo(output)
                    }
                }
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }
    }

    companion object {
        const val MODEL_DIR_NAME = "vosk-model-small-ru-0.22"
        const val MODEL_URL =
            "https://alphacephei.com/vosk/models/vosk-model-small-ru-0.22.zip"
    }
}
