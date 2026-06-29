package com.github.appmanager.im

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Base64

/**
 * 聊天室存储：消息历史（append-only .jsonl）与上传文件。服务器作为唯一存储点，
 * 每条转发的消息原样追加一行；新客户端连上时读取近期历史。
 *
 * 消息内容对服务器是不透明字符串——服务器只负责「追加 + 转发」，不解析、不识别身份。
 */
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

    fun getFilesDir2(): File = filesDir

    /** 追加一行消息（原始 JSON 字符串）到历史文件。 */
    @Synchronized
    fun appendMessage(rawJson: String) {
        try {
            historyFile.appendText(rawJson + "\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to append message", e)
        }
    }

    /** 读取最近的若干条历史消息（原始 JSON 字符串，按时间正序）。 */
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

    /**
     * 存储上传文件。data 为 data URL（data:<mime>;base64,<payload>）或纯 base64。
     * @return 落地后的文件名（与原始 name 一致）。
     */
    @Synchronized
    fun storeUploadedFile(name: String, data: String): String {
        val payload = stripDataUrlPrefix(data)
        val bytes = Base64.getMimeDecoder().decode(payload)
        val dest = File(filesDir, name)
        dest.outputStream().use { it.write(bytes) }
        return name
    }

    /** 原生端直接拷贝本地文件到共享文件目录，免去 base64 往返。返回落地文件名。 */
    @Synchronized
    fun storeLocalFile(source: File): String {
        val dest = File(filesDir, source.name)
        source.copyTo(dest, overwrite = true)
        return source.name
    }

    private fun stripDataUrlPrefix(data: String): String {
        val comma = data.indexOf(',')
        return if (data.startsWith("data:") && comma >= 0) data.substring(comma + 1) else data
    }
}
