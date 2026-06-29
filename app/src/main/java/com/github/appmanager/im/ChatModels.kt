package com.github.appmanager.im

import kotlinx.serialization.Serializable

@Serializable
data class ChatMessage(
    val id: Long = System.currentTimeMillis(),
    val sender: String,
    val receiver: String,
    val type: MessageType,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val fileName: String? = null,
    val fileSize: Long = 0L,
    val filePath: String? = null,
    val status: MessageStatus = MessageStatus.SENT
)

@Serializable
enum class MessageType {
    TEXT, FILE, IMAGE, SYSTEM
}

@Serializable
enum class MessageStatus {
    SENT, DELIVERED, READ, FAILED
}

@Serializable
data class ChatSession(
    val id: String,
    val participants: List<String>,
    val lastMessage: ChatMessage? = null,
    val updatedAt: Long = System.currentTimeMillis()
)

@Serializable
data class FileInfo(
    val name: String,
    val path: String,
    val size: Long,
    val mimeType: String,
    val uploadedAt: Long = System.currentTimeMillis()
)
