package com.github.appmanager

import android.util.Log
import com.github.appmanager.im.ChatService
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

/**
 * 纯转发型 WebSocket 服务器。各客户端（手机原生 UI 走 localhost、web 走局域网）
 * 直连此处发送消息。服务器只做两件事：
 *  1. 收到一条消息 → 原样追加到历史 → 转发给除发送者外的所有其他连接。
 *  2. 新连接建立 → 推送近期历史。
 * 不做身份识别、不附加任何信封；消息体对服务器是不透明字符串。
 */
class ImWebSocketServer(
    port: Int,
    private val chatService: ChatService
) : WebSocketServer(InetSocketAddress(port)) {

    companion object {
        const val TAG = "ImWebSocketServer"
    }

    override fun onStart() {
        Log.i(TAG, "WebSocket relay server started on port ${address.port}")
    }

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d(TAG, "Client connected: ${conn.remoteSocketAddress}")
        // 向新连接推送近期历史（均为「接收」侧，无身份区分）。
        chatService.loadRecentHistory().forEach { line ->
            conn.send(line)
        }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "Client disconnected: ${conn.remoteSocketAddress} ($code)")
    }

    override fun onMessage(conn: WebSocket, message: String) {
        // 落盘 + 转发给除发送者外的所有连接（发送者自己本地已显示，避免重复）。
        chatService.appendMessage(message)
        connections.forEach { other ->
            if (other !== conn && other.isOpen) {
                other.send(message)
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        Log.e(TAG, "WebSocket error", ex)
    }
}
