package com.github.appmanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.github.appmanager.im.ChatSerializer
import com.github.appmanager.im.ChatService
import java.io.File
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class HttpFileServerService : Service() {
    companion object {
        const val TAG = "HttpFileServerService"
        const val CHANNEL_ID = "HttpFileServerChannel"
        var serverPort: Int = 8080          // HTTP：页面 / 文件上传 / 文件下载
        var wsPort: Int = 8081              // WebSocket：消息实时转发
        private var serverRunning = false
        private var serverThread: Thread? = null
    }

    private var serverSocket: ServerSocket? = null
    private lateinit var chatService: ChatService
    private var wsServer: ImWebSocketServer? = null

    override fun onCreate() {
        super.onCreate()
        chatService = ChatService(applicationContext)
        createNotificationChannel()
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!serverRunning) {
            serverRunning = true
            serverThread = Thread { runHttpServer() }.apply { start() }
            startWebSocketServer()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        serverRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        try {
            wsServer?.stop()
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping websocket server", e)
        }
        wsServer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---------- HTTP 服务器（仅静态页面 / info / 文件上传 / 文件下载） ----------

    private fun runHttpServer() {
        try {
            serverSocket = ServerSocket(serverPort)
            Log.i(TAG, "HTTP Server started on port $serverPort")

            while (serverRunning) {
                try {
                    val socket = serverSocket?.accept()
                    if (socket != null) {
                        Thread { handleConnection(socket) }.start()
                    }
                } catch (e: Exception) {
                    if (serverRunning) {
                        Log.e(TAG, "Error accepting connection", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error", e)
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            val buffer = ByteArray(4096)
            val bytesRead = socket.inputStream.read(buffer)
            if (bytesRead <= 0) return

            val request = String(buffer, 0, bytesRead)
            val firstLine = request.lines().firstOrNull() ?: ""
            val parts = firstLine.split(Regex("\\s+"))
            if (parts.size < 2) return

            val method = parts[0]
            val rawPath = parts[1]
            val path = decodeUri(rawPath)

            if (method != "GET" && method != "POST") {
                sendResponse(socket, 405, "text/plain", "Method not allowed")
                socket.close()
                return
            }

            when {
                path == "/" || path == "/index.html" -> {
                    serveAsset(socket, "index.html", "text/html")
                }
                path == "/api/info" -> {
                    handleGetInfo(socket)
                }
                path.startsWith("/api/upload") && method == "POST" -> {
                    handleUpload(socket, buffer, bytesRead)
                }
                path.startsWith("/files/") -> {
                    serveFileFromCache(socket, "chat_files/" + path.removePrefix("/files/"))
                }
                else -> {
                    sendResponse(socket, 404, "text/plain", "Not found")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling connection", e)
        } finally {
            try {
                socket.close()
            } catch (e: Exception) {
                // ignore
            }
        }
    }

    private fun handleGetInfo(socket: Socket) {
        val localId = chatService.getDeviceId()
        val serverIp = getLocalIpAddress()
        val info = """{"baseUrl":"http://$serverIp:$serverPort","wsUrl":"ws://$serverIp:$wsPort","localId":"$localId"}"""
        sendResponse(socket, 200, "application/json", info)
    }

    private fun handleUpload(socket: Socket, raw: ByteArray, rawLen: Int) {
        try {
            val body = readRequestBody(socket, raw, rawLen)
            val upload = ChatSerializer.deserializeUpload(body)
            if (upload != null) {
                chatService.storeUploadedFile(upload.name, upload.data)
                val serverIp = getLocalIpAddress()
                val url = "http://$serverIp:$serverPort/files/${URLDecoder.decode(upload.name, "UTF-8")}"
                val nameEscaped = upload.name.replace("\\", "\\\\").replace("\"", "\\\"")
                sendResponse(socket, 200, "application/json", "{\"success\":true,\"url\":\"$url\",\"fileName\":\"$nameEscaped\"}")
            } else {
                sendResponse(socket, 400, "application/json", "{\"error\":\"invalid upload request\"}")
            }
        } catch (e: Exception) {
            sendResponse(socket, 500, "application/json", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun serveAsset(socket: Socket, assetName: String, contentType: String) {
        try {
            val inputStream = assets.open(assetName)
            val content = inputStream.bufferedReader().readText()
            sendResponse(socket, 200, contentType, content)
        } catch (e: Exception) {
            sendResponse(socket, 404, "text/plain", "Asset not found: $assetName")
        }
    }

    private fun serveFileFromCache(socket: Socket, relativePath: String) {
        try {
            val file = File(applicationContext.filesDir, relativePath)
            if (!file.exists() || !file.isFile) {
                sendResponse(socket, 404, "text/plain", "File not found")
                return
            }

            val mimeType = getMimeType(file.name)
            val fileLength = file.length()

            val response = StringBuilder()
            response.append("HTTP/1.1 200 OK\r\n")
            response.append("Content-Type: $mimeType\r\n")
            response.append("Content-Length: $fileLength\r\n")
            response.append("Access-Control-Allow-Origin: *\r\n")
            response.append("Connection: close\r\n")
            response.append("\r\n")

            socket.outputStream.write(response.toString().toByteArray(StandardCharsets.UTF_8))
            file.inputStream().use { input ->
                input.copyTo(socket.outputStream)
            }
            socket.outputStream.flush()
        } catch (e: Exception) {
            sendResponse(socket, 500, "text/plain", "Error serving file: ${e.message}")
        }
    }

    /**
     * 从已读取的请求字节中提取 body。handleConnection 的首次 read 通常已把
     * 「请求头 + body」整体读入 raw（短消息必然如此）；此处从 raw 切出 body，
     * 仅在 body 超出首次读取时从流中补齐。绝不再对 socket 做无谓的第二次 read，
     * 否则客户端发完即等响应、无更多数据，会永久阻塞导致请求 pending。
     * 全程按字节处理，避免多字节字符导致 Content-Length 与字符串长度不匹配。
     */
    private fun readRequestBody(socket: Socket, raw: ByteArray, rawLen: Int): String {
        return try {
            val headerEnd = findHeaderEnd(raw, rawLen)
            if (headerEnd < 0) return ""
            val headerSection = String(raw, 0, headerEnd, StandardCharsets.UTF_8)
            val bodyOffset = headerEnd + 4
            val contentLength = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(headerSection)?.groups?.get(1)?.value?.toIntOrNull() ?: 0

            val alreadyRead = rawLen - bodyOffset
            if (contentLength <= 0) {
                return String(raw, bodyOffset, maxOf(0, alreadyRead), StandardCharsets.UTF_8)
            }
            if (alreadyRead >= contentLength) {
                return String(raw, bodyOffset, contentLength, StandardCharsets.UTF_8)
            }

            val remainder = contentLength - alreadyRead
            val rest = ByteArray(remainder)
            var read = 0
            while (read < remainder) {
                val n = socket.inputStream.read(rest, read, remainder - read)
                if (n <= 0) break
                read += n
            }
            val full = ByteArray(alreadyRead + read)
            System.arraycopy(raw, bodyOffset, full, 0, alreadyRead)
            System.arraycopy(rest, 0, full, alreadyRead, read)
            String(full, 0, alreadyRead + read, StandardCharsets.UTF_8)
        } catch (e: Exception) {
            ""
        }
    }

    private fun findHeaderEnd(raw: ByteArray, len: Int): Int {
        val end = len - 3
        var i = 0
        while (i < end) {
            if (raw[i] == 13.toByte() && raw[i + 1] == 10.toByte() &&
                raw[i + 2] == 13.toByte() && raw[i + 3] == 10.toByte()
            ) {
                return i
            }
            i++
        }
        return -1
    }

    private fun sendResponse(socket: Socket, statusCode: Int, contentType: String, content: String) {
        try {
            val statusText = when (statusCode) {
                200 -> "OK"
                400 -> "Bad Request"
                404 -> "Not Found"
                405 -> "Method Not Allowed"
                500 -> "Internal Server Error"
                else -> "Unknown"
            }

            val contentBytes = content.toByteArray(StandardCharsets.UTF_8)
            val response = StringBuilder()
            response.append("HTTP/1.1 $statusCode $statusText\r\n")
            response.append("Content-Type: $contentType\r\n")
            response.append("Content-Length: ${contentBytes.size}\r\n")
            response.append("Access-Control-Allow-Origin: *\r\n")
            response.append("Access-Control-Allow-Methods: GET, POST, PUT, DELETE, OPTIONS\r\n")
            response.append("Access-Control-Allow-Headers: Content-Type\r\n")
            response.append("Connection: close\r\n")
            response.append("\r\n")

            socket.outputStream.write(response.toString().toByteArray(StandardCharsets.UTF_8))
            socket.outputStream.write(contentBytes)
            socket.outputStream.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Error sending response", e)
        }
    }

    private fun decodeUri(encoded: String): String {
        return try {
            URLDecoder.decode(encoded, "UTF-8")
        } catch (e: Exception) {
            encoded
        }
    }

    private fun getMimeType(fileName: String): String {
        return when {
            fileName.endsWith(".html") -> "text/html"
            fileName.endsWith(".css") -> "text/css"
            fileName.endsWith(".js") -> "application/javascript"
            fileName.endsWith(".json") -> "application/json"
            fileName.endsWith(".png") -> "image/png"
            fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
            fileName.endsWith(".gif") -> "image/gif"
            fileName.endsWith(".ico") -> "image/x-icon"
            fileName.endsWith(".txt") -> "text/plain"
            fileName.endsWith(".pdf") -> "application/pdf"
            fileName.endsWith(".apk") -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                for (addr in intf.inetAddresses) {
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: continue
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting local ip", e)
        }
        return "127.0.0.1"
    }

    // ---------- WebSocket 转发服务器 ----------

    private fun startWebSocketServer() {
        if (wsServer != null) return
        wsServer = ImWebSocketServer(wsPort, chatService).also { it.start() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "HTTP File Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "HTTP file server is running"
            }
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("H4 IM Server")
            .setContentText("HTTP :$serverPort  /  WS :$wsPort")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}
