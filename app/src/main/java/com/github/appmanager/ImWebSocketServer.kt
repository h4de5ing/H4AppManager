package com.github.appmanager

import android.util.Log
import com.github.appmanager.im.ChatSerializer
import com.github.appmanager.im.ChatService
import org.java_websocket.WebSocket
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import java.net.InetSocketAddress

class ImWebSocketServer(
    port: Int,
    private val chatService: ChatService
) : WebSocketServer(InetSocketAddress("0.0.0.0", port)) {

    companion object {
        const val TAG = "ImWebSocketServer"

        /** 当前在线的网页端数量（不含本机 App 自己的回环连接）。 */
        @Volatile
        var webClientCount: Int = 0
            private set
    }

    @Volatile
    private var running = false

    override fun onStart() {
        running = true
        Log.i(TAG, "WebSocket relay server started on ${address.hostString}:${address.port}")
    }

    fun isRunning(): Boolean = running

    override fun onOpen(conn: WebSocket, handshake: ClientHandshake) {
        Log.d(TAG, "Client connected: ${conn.remoteSocketAddress}")
        updateWebClientCount()
        chatService.loadRecentHistory().forEach { line -> conn.send(line) }
    }

    override fun onClose(conn: WebSocket, code: Int, reason: String?, remote: Boolean) {
        Log.d(TAG, "Client disconnected: ${conn.remoteSocketAddress} ($code)")
        updateWebClientCount(closing = conn)
    }

    private fun updateWebClientCount(closing: WebSocket? = null) {
        webClientCount = connections.count { other ->
            other !== closing && other.isOpen &&
                other.remoteSocketAddress?.address?.isLoopbackAddress == false
        }
    }

    override fun onMessage(conn: WebSocket, message: String) {
        val msg = ChatSerializer.deserializeMessage(message)
        if (msg?.type == "CLEAR") {
            chatService.clearAll()
            broadcastToOthers(conn, message)
            return
        }

        chatService.appendMessage(message)
        broadcastToOthers(conn, message)
    }

    private fun broadcastToOthers(sender: WebSocket, message: String) {
        connections.forEach { other ->
            if (other !== sender && other.isOpen) {
                other.send(message)
            }
        }
    }

    override fun onError(conn: WebSocket?, ex: Exception) {
        if (conn == null) {
            running = false
            webClientCount = 0
        }
        Log.e(TAG, "WebSocket error", ex)
    }
}

