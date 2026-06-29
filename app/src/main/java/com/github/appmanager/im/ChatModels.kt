package com.github.appmanager.im

import kotlinx.serialization.Serializable

/**
 * 聊天室消息。这是唯一会被序列化的对象：客户端把它序列化成一行 JSON 通过
 * WebSocket 发出，服务器原样转发（落盘 + 广播给其他连接），不做任何身份识别、
 * 不附加任何信封。客户端自己发的靠右显示，从 socket 收到的靠左显示，因此无需
 * sender/source 等身份字段。
 */
@Serializable
data class RoomMessage(
    val type: String,            // "TEXT" | "FILE" | "SYSTEM"
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val fileName: String? = null,
    val fileSize: Long? = null,
    val fileUrl: String? = null
)

/** 文件上传请求体：name 为文件名，data 为 data URL（data:...;base64,xxxx）。 */
@Serializable
data class UploadRequest(
    val name: String,
    val data: String
)
