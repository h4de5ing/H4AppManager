package com.github.appmanager.im

import android.content.Context
import android.util.Log
import java.io.File

class ChatService(private val context: Context) {
    companion object {
        private const val TAG = "ChatService"
        private const val MESSAGES_DIR = "chat_messages"
        private const val FILES_DIR = "chat_files"
        private const val SESSIONS_FILE = "chat_sessions.json"
    }

    private val messagesDir: File by lazy {
        File(context.cacheDir, MESSAGES_DIR).apply { mkdirs() }
    }

    private val filesDir: File by lazy {
        File(context.cacheDir, FILES_DIR).apply { mkdirs() }
    }

    private val sessionsFile: File by lazy {
        File(context.cacheDir, SESSIONS_FILE)
    }

    private val localDeviceId: String by lazy {
        context.packageName + "_" + android.os.Build.DEVICE + "_" + android.os.Build.MANUFACTURER
    }

    fun getDeviceId(): String = localDeviceId

    fun sendMessage(message: ChatMessage, port: Int): Result<Boolean> {
        return runCatching {
            val messages = loadMessages()
            val updatedMessage = message.copy(id = System.currentTimeMillis())
            messages.add(updatedMessage)
            saveMessages(messages)
            updateSession(updatedMessage)
            true
        }.onFailure {
            Log.e(TAG, "Failed to send message", it)
        }
    }

    fun getMessages(receiver: String? = null): List<ChatMessage> {
        return try {
            val messages = loadMessages()
            if (receiver != null) {
                messages.filter { it.sender == localDeviceId && it.receiver == receiver ||
                    it.receiver == localDeviceId && it.sender == receiver }
            } else {
                messages.filter { it.sender == localDeviceId || it.receiver == localDeviceId }
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun uploadFile(fileName: String, fileSize: Long, filePath: String): Result<FileInfo> {
        return runCatching {
            val sourceFile = File(filePath)
            val destFile = File(filesDir, fileName)
            sourceFile.copyTo(destFile, overwrite = true)

            FileInfo(fileName, destFile.absolutePath, fileSize, getMimeType(fileName))
        }.onFailure {
            Log.e(TAG, "Failed to upload file", it)
        }
    }

    fun getFileList(): List<FileInfo> {
        return try {
            getFileListSync()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun deleteFile(fileName: String): Result<Boolean> {
        return runCatching {
            File(filesDir, fileName).delete()
        }
    }

    fun getFileUri(relativePath: String): String {
        return "file://${File(context.cacheDir, relativePath)}"
    }

    fun getAllMessagesJson(port: Int): String {
        return runCatching {
            val messages = loadMessages()
            ChatSerializer.serializeMessageList(messages)
        }.getOrElse { "[]" }
    }

    fun getAllFilesJson(): String {
        return runCatching {
            val files = getFileListSync()
            ChatSerializer.serializeFileList(files)
        }.getOrElse { "[]" }
    }

    fun getChatSessionsJson(): String {
        return runCatching {
            val sessions = loadSessions()
            ChatSerializer.serializeSessionList(sessions)
        }.getOrElse { "[]" }
    }

    private fun loadSessions(): MutableList<ChatSession> {
        return if (sessionsFile.exists() && sessionsFile.length() > 0) {
            ChatSerializer.deserializeSessionList(sessionsFile.readText()).toMutableList()
        } else {
            mutableListOf()
        }
    }

    private fun saveSessions(sessions: List<ChatSession>) {
        sessionsFile.writeText(ChatSerializer.serializeSessionList(sessions))
    }

    private fun updateSession(message: ChatMessage) {
        val sessions = loadSessions()
        val sessionId = getSessionId(message.sender, message.receiver)
        val idx = sessions.indexOfFirst { it.id == sessionId }
        if (idx == -1) {
            val session = ChatSession(
                id = sessionId,
                participants = listOf(message.sender, message.receiver),
                lastMessage = message,
                updatedAt = message.timestamp
            )
            sessions.add(session)
        } else {
            sessions[idx] = sessions[idx].copy(lastMessage = message, updatedAt = message.timestamp)
        }
        saveSessions(sessions)
    }

    private fun getSessionId(a: String, b: String): String {
        return if (a.compareTo(b) < 0) "$a|$b" else "$b|$a"
    }

    private fun loadMessages(): MutableList<ChatMessage> {
        val allMessages = mutableListOf<ChatMessage>()
        messagesDir.listFiles()?.forEach { file ->
            file.readText().takeIf { it.isNotBlank() }?.let { text ->
                ChatSerializer.deserializeMessage(text)?.let { msg ->
                    allMessages.add(msg)
                }
            }
        }
        return allMessages.sortedBy { it.timestamp }.toMutableList()
    }

    private fun saveMessages(messages: List<ChatMessage>) {
        messagesDir.listFiles()?.forEach { it.delete() }
        messages.forEach { msg ->
            val file = File(messagesDir, "${msg.id}.json")
            file.writeText(ChatSerializer.serializeSingle(msg))
        }
    }

    private fun getFileListSync(): List<FileInfo> {
        return filesDir.listFiles()?.filter { it.isFile }?.map { file ->
            FileInfo(
                name = file.name,
                path = file.absolutePath,
                size = file.length(),
                mimeType = getMimeType(file.name),
                uploadedAt = file.lastModified()
            )
        }?.sortedByDescending { it.uploadedAt } ?: emptyList()
    }

    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".txt") -> "text/plain"
            fileName.endsWith(".pdf") -> "application/pdf"
            fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
            fileName.endsWith(".png") -> "image/png"
            fileName.endsWith(".gif") -> "image/gif"
            fileName.endsWith(".zip") -> "application/zip"
            fileName.endsWith(".apk") -> "application/vnd.android.package-archive"
            fileName.endsWith(".mp3") -> "audio/mpeg"
            fileName.endsWith(".mp4") -> "video/mp4"
            fileName.endsWith(".json") -> "application/json"
            fileName.endsWith(".csv") -> "text/csv"
            fileName.endsWith(".doc") -> "application/msword"
            fileName.endsWith(".docx") -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
            else -> "application/octet-stream"
        }
    }
}
