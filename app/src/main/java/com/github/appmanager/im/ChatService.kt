package com.github.appmanager.im

import android.content.Context
import android.util.Base64
import android.util.Log
import java.io.File

class ChatService(private val context: Context) {
    companion object {
        private const val TAG = "ChatService"
        private const val FILES_DIR = "chat_files"
        private const val HISTORY_FILE = "history.jsonl"
        private const val HISTORY_LIMIT = 200
    }

    private val filesDir: File by lazy {
        File(context.filesDir, FILES_DIR).apply { mkdirs() }
    }

    private val historyFile: File by lazy {
        File(context.filesDir, HISTORY_FILE)
    }

    private val localDeviceId: String by lazy {
        context.packageName + "_" + android.os.Build.DEVICE + "_" + android.os.Build.MANUFACTURER
    }

    fun getDeviceId(): String = localDeviceId

    @Synchronized
    fun appendMessage(rawJson: String) {
        try {
            historyFile.appendText(rawJson.trimEnd() + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append message", e)
        }
    }

    @Synchronized
    fun loadRecentHistory(limit: Int = HISTORY_LIMIT): List<String> {
        return try {
            if (!historyFile.exists()) return emptyList()
            val lines = historyFile.readLines().filter { it.isNotBlank() }
            if (lines.size <= limit) lines else lines.takeLast(limit)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load history", e)
            emptyList()
        }
    }

    @Synchronized
    fun storeUploadedFile(name: String, data: String): String {
        val storedName = uniqueFileName(cleanFileName(name))
        val payload = stripDataUrlPrefix(data)
        val bytes = Base64.decode(payload, Base64.DEFAULT)
        val dest = File(filesDir, storedName)
        dest.outputStream().use { it.write(bytes) }
        return storedName
    }

    @Synchronized
    fun getSharedFile(name: String): File? {
        val safeName = cleanFileName(name)
        val candidate = File(filesDir, safeName)
        val root = filesDir.canonicalFile
        val file = candidate.canonicalFile
        return if (file.parentFile == root) file else null
    }

    @Synchronized
    fun clearAll() {
        try {
            if (historyFile.exists()) historyFile.delete()
            if (filesDir.exists()) {
                filesDir.listFiles()?.forEach { file ->
                    if (file.isDirectory) file.deleteRecursively() else file.delete()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear chat data", e)
            throw e
        }
    }

    private fun stripDataUrlPrefix(data: String): String {
        val comma = data.indexOf(',')
        return if (data.startsWith("data:") && comma >= 0) data.substring(comma + 1) else data
    }

    private fun cleanFileName(name: String): String {
        val trimmed = name.substringAfterLast('/').substringAfterLast('\\').trim()
        val cleaned = trimmed.replace(Regex("[\\u0000-\\u001F\\\\/:*?\"<>|]"), "_")
        return cleaned.ifBlank { "file_${System.currentTimeMillis()}" }
    }

    private fun uniqueFileName(name: String): String {
        val dot = name.lastIndexOf('.')
        val base = if (dot > 0) name.substring(0, dot) else name
        val ext = if (dot > 0) name.substring(dot) else ""
        var candidate = name
        var index = 1
        while (File(filesDir, candidate).exists()) {
            candidate = "${base}_$index$ext"
            index++
        }
        return candidate
    }
}

