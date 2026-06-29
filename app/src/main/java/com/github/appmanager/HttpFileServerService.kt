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
import com.github.appmanager.im.ChatMessage
import com.github.appmanager.im.ChatSerializer
import com.github.appmanager.im.ChatService
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class HttpFileServerService : Service() {
    companion object {
        const val TAG = "HttpFileServerService"
        const val CHANNEL_ID = "HttpFileServerChannel"
        var serverPort: Int = 8080
        private var serverRunning = false
        private var serverThread: Thread? = null
    }

    private var serverSocket: ServerSocket? = null
    private lateinit var chatService: ChatService

    override fun onCreate() {
        super.onCreate()
        chatService = ChatService(applicationContext)
        createNotificationChannel()
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!serverRunning) {
            serverRunning = true
            serverThread = Thread { runServer() }.apply { start() }
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun runServer() {
        try {
            serverSocket = ServerSocket(serverPort)
            Log.i(TAG, "HTTP File Server started on port $serverPort")

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
                path.startsWith("/api/") -> {
                    handleApiRequest(socket, path, request)
                }
                path.startsWith("/files/") -> {
                    serveFileFromCache(socket, path.removePrefix("/files/"))
                }
                path.startsWith("/chat/") -> {
                    handleChatRequest(socket, path, request)
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

    private fun handleApiRequest(socket: Socket, path: String, request: String) {
        when {
            path == "/api/messages" -> {
                if (request.contains("POST")) {
                    handlePostMessage(socket)
                } else {
                    handleGetMessages(socket)
                }
            }
            path == "/api/files" -> {
                handleGetFiles(socket)
            }
            path == "/api/sessions" -> {
                handleGetSessions(socket)
            }
            path == "/api/info" -> {
                handleGetInfo(socket)
            }
            path.startsWith("/api/upload") -> {
                handleUpload(socket)
            }
            else -> {
                sendResponse(socket, 404, "application/json", "{\"error\":\"not found\"}")
            }
        }
    }

    private fun handleGetMessages(socket: Socket) {
        val messages = loadMessagesFile()
        val json = ChatSerializer.serializeMessageList(messages)
        sendResponse(socket, 200, "application/json", json)
    }

    private fun handlePostMessage(socket: Socket) {
        try {
            val body = readRequestBody(socket)
            val msg = ChatSerializer.deserializeMessage(body)
            if (msg != null) {
                val newMsg = msg.copy(id = System.currentTimeMillis())
                val messages = loadMessagesFile()
                messages.add(newMsg)
                saveMessagesFile(messages)
                chatService.updateSession(newMsg)
                sendResponse(socket, 200, "application/json", "{\"success\":true}")
            } else {
                sendResponse(socket, 400, "application/json", "{\"error\":\"invalid message\"}")
            }
        } catch (e: Exception) {
            sendResponse(socket, 500, "application/json", "{\"error\":\"${e.message}\"}")
        }
    }

    private fun handleGetFiles(socket: Socket) {
        val files = chatService.getAllFilesJson()
        sendResponse(socket, 200, "application/json", files)
    }

    private fun handleGetSessions(socket: Socket) {
        val sessions = chatService.getChatSessionsJson()
        sendResponse(socket, 200, "application/json", sessions)
    }

    private fun handleGetInfo(socket: Socket) {
        val localId = chatService.getDeviceId()
        val clientIp = getSocketIpAddress(socket)
        val info = """{"baseUrl":"http://$clientIp:$serverPort","localId":"$localId","port":$serverPort}"""
        sendResponse(socket, 200, "application/json", info)
    }

    private fun getSocketIpAddress(socket: Socket): String {
        try {
            return socket.inetAddress.hostAddress ?: "127.0.0.1"
        } catch (e: Exception) {
            return "127.0.0.1"
        }
    }

    private fun handleUpload(socket: Socket) {
        try {
            val body = readRequestBody(socket)
            val uploadData = ChatSerializer.deserializeMessage(body)
            if (uploadData != null && uploadData.filePath != null) {
                val srcFile = File(uploadData.filePath)
                if (srcFile.exists()) {
                    val destFile = File(chatService.getFilesDir2(), srcFile.name)
                    srcFile.copyTo(destFile, overwrite = true)
                    chatService.uploadFile(srcFile.name, srcFile.length(), destFile.absolutePath)
                    sendResponse(socket, 200, "application/json", """{"success":true,"fileName":"${srcFile.name}"}""")
                } else {
                    sendResponse(socket, 404, "application/json", """{"error":"file not found"}""")
                }
            } else {
                sendResponse(socket, 400, "application/json", """{"error":"invalid upload request"}""")
            }
        } catch (e: Exception) {
            sendResponse(socket, 500, "application/json", """{"error":"${e.message}"}""")
        }
    }

    private fun handleChatRequest(socket: Socket, path: String, request: String) {
        when {
            path == "/chat/messages" -> handleGetMessages(socket)
            path.startsWith("/chat/file/") -> {
                val fileName = path.removePrefix("/chat/file/")
                serveFileFromCache(socket, "chat_files/$fileName")
            }
            else -> sendResponse(socket, 404, "text/plain", "Not found")
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
            val file = File(applicationContext.cacheDir, relativePath)
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

    private fun readRequestBody(socket: Socket): String {
        try {
            val contentLength = try {
                val buffer = ByteArray(1024)
                val bytesRead = socket.inputStream.read(buffer)
                if (bytesRead <= 0) return ""
                val requestStr = String(buffer, 0, bytesRead)
                val headersEnd = requestStr.indexOf("\r\n\r\n")
                if (headersEnd > 0) {
                    val headerSection = requestStr.substring(0, headersEnd)
                    val clMatch = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE).find(headerSection)
                    clMatch?.groups?.get(1)?.value?.toIntOrNull() ?: 0
                } else {
                    0
                }
            } catch (e: Exception) {
                0
            }

            if (contentLength > 0) {
                val bodyBuffer = CharArray(contentLength)
                val reader = socket.inputStream.reader()
                reader.read(bodyBuffer)
                return String(bodyBuffer)
            }
            return ""
        } catch (e: Exception) {
            return ""
        }
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
            .setContentTitle("HTTP File Server")
            .setContentText("Running on port $serverPort")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }

    private fun loadMessagesFile(): MutableList<ChatMessage> {
        val messagesDir = File(applicationContext.cacheDir, "chat_messages")
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

    private fun saveMessagesFile(messages: List<ChatMessage>) {
        val messagesDir = File(applicationContext.cacheDir, "chat_messages")
        messagesDir.listFiles()?.forEach { it.delete() }
        messages.forEach { msg ->
            val file = File(messagesDir, "${msg.id}.json")
            file.writeText(ChatSerializer.serializeSingle(msg))
        }
    }
}
