package com.github.appmanager

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.github.appmanager.im.ChatSerializer
import com.github.appmanager.im.ChatService
import com.github.appmanager.im.FileDeleteRequest
import com.github.appmanager.im.FileMkdirRequest
import com.github.appmanager.im.FileOpResponse
import com.github.appmanager.im.FileRenameRequest
import com.github.appmanager.im.FileService
import com.github.appmanager.im.FileUploadRequest
import kotlinx.serialization.json.Json
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

class HttpFileServerService : Service() {
    companion object {
        const val TAG = "HttpFileServerService"
        const val CHANNEL_ID = "HttpFileServerChannel"
        var serverPort: Int = 8080
        var wsPort: Int = 8081
        @Volatile
        private var httpRunning = false
        private var serverThread: Thread? = null
    }

    private var serverSocket: ServerSocket? = null
    private lateinit var chatService: ChatService
    private lateinit var fileService: FileService
    private var wsServer: ImWebSocketServer? = null

    private val fileJson = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        prettyPrint = false
    }

    override fun onCreate() {
        super.onCreate()
        chatService = ChatService(applicationContext)
        fileService = FileService(applicationContext)
        createNotificationChannel()
        startForeground(1, buildNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startHttpServerIfNeeded()
        startWebSocketServerIfNeeded()
        return START_STICKY
    }

    override fun onDestroy() {
        httpRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error closing server socket", e)
        }
        try {
            wsServer?.stop(1000)
        } catch (e: Exception) {
            Log.e(TAG, "Error stopping websocket server", e)
        }
        wsServer = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    @Synchronized
    private fun startHttpServerIfNeeded() {
        if (httpRunning && serverThread?.isAlive == true) return
        httpRunning = true
        serverThread = Thread { runHttpServer() }.apply {
            name = "H4HttpServer"
            isDaemon = true
            start()
        }
    }

    private fun runHttpServer() {
        try {
            serverSocket = ServerSocket().apply {
                reuseAddress = true
                bind(java.net.InetSocketAddress("0.0.0.0", serverPort))
            }
            Log.i(TAG, "HTTP Server started on port $serverPort")

            while (httpRunning) {
                try {
                    val socket = serverSocket?.accept()
                    if (socket != null) {
                        Thread { handleConnection(socket) }.apply {
                            name = "H4HttpClient"
                            isDaemon = true
                            start()
                        }
                    }
                } catch (e: Exception) {
                    if (httpRunning) {
                        Log.e(TAG, "Error accepting connection", e)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Server error", e)
        } finally {
            httpRunning = false
            serverThread = null
        }
    }

    private fun handleConnection(socket: Socket) {
        try {
            socket.soTimeout = 15000
            val buffer = ByteArray(8192)
            val bytesRead = socket.inputStream.read(buffer)
            if (bytesRead <= 0) return

            val request = String(buffer, 0, bytesRead, StandardCharsets.UTF_8)
            val firstLine = request.lineSequence().firstOrNull().orEmpty()
            val parts = firstLine.split(Regex("\\s+"))
            if (parts.size < 2) return

            val method = parts[0].uppercase()
            val rawPathOnly = parts[1].substringBefore('?')
            val queryString = parts[1].substringAfter('?', "")
            val path = decodeUri(rawPathOnly)

            if (method == "OPTIONS") {
                sendResponse(socket, 204, "text/plain", "")
                return
            }

            if (method != "GET" && method != "POST") {
                sendResponse(socket, 405, "text/plain", "Method not allowed")
                return
            }

            when {
                path == "/" || path == "/index.html" -> serveAsset(socket, "index.html", "text/html; charset=utf-8")
                path == "/file.html" -> serveAsset(socket, "file.html", "text/html; charset=utf-8")
                path == "/api/info" -> handleGetInfo(socket)
                path.startsWith("/api/upload") && method == "POST" -> handleUpload(socket, buffer, bytesRead)
                rawPathOnly.startsWith("/files/") -> {
                    val fileName = decodeUri(rawPathOnly.removePrefix("/files/"))
                    serveFileFromCache(socket, fileName)
                }
                path == "/api/files/list" && method == "GET" -> handleFileList(socket, queryString)
                path == "/api/files/download" && method == "GET" -> handleFileDownload(socket, queryString)
                path == "/api/files/request-permission" -> handleRequestPermission(socket)
                path == "/api/files/upload" && method == "POST" -> handleFileUpload(socket, buffer, bytesRead)
                path == "/api/files/delete" && method == "POST" -> handleFileOp(socket, buffer, bytesRead, ::handleFileDelete)
                path == "/api/files/rename" && method == "POST" -> handleFileOp(socket, buffer, bytesRead, ::handleFileRename)
                path == "/api/files/mkdir" && method == "POST" -> handleFileOp(socket, buffer, bytesRead, ::handleFileMkdir)
                else -> sendResponse(socket, 404, "text/plain", "Not found")
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
        val localId = jsonEscape(chatService.getDeviceId())
        val serverIp = getLocalIpAddress()
        val info = "{\"baseUrl\":\"http://$serverIp:$serverPort\",\"wsUrl\":\"ws://$serverIp:$wsPort\",\"localId\":\"$localId\"}"
        sendResponse(socket, 200, "application/json; charset=utf-8", info)
    }

    private fun handleUpload(socket: Socket, raw: ByteArray, rawLen: Int) {
        try {
            val body = readRequestBody(socket, raw, rawLen)
            val upload = ChatSerializer.deserializeUpload(body)
            if (upload == null) {
                sendResponse(socket, 400, "application/json", "{\"error\":\"invalid upload request\"}")
                return
            }

            val storedName = chatService.storeUploadedFile(upload.name, upload.data)
            val serverIp = getLocalIpAddress()
            val url = "http://$serverIp:$serverPort/files/${Uri.encode(storedName)}"
            sendResponse(
                socket,
                200,
                "application/json; charset=utf-8",
                "{\"success\":true,\"url\":\"${jsonEscape(url)}\",\"fileName\":\"${jsonEscape(storedName)}\"}"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Upload failed", e)
            sendResponse(socket, 500, "application/json", "{\"error\":\"${jsonEscape(e.message ?: "upload failed")}\"}")
        }
    }

    // ---------- 文件管理器接口 ----------

    private fun parseQueryDir(query: String): String {
        // 形如 dir=a/b；取值并 URL 解码，默认空串（根目录）
        return query.split("&").firstOrNull { it.startsWith("dir=") }
            ?.removePrefix("dir=")
            ?.let { decodeUri(it) }
            ?: ""
    }

    private fun parseQueryPath(query: String): String {
        return query.split("&").firstOrNull { it.startsWith("path=") }
            ?.removePrefix("path=")
            ?.let { decodeUri(it) }
            ?: ""
    }

    private fun handleFileList(socket: Socket, query: String) {
        val dir = parseQueryDir(query)
        if (!fileService.isPermissionGranted()) {
            sendResponse(socket, 403, "application/json; charset=utf-8",
                "{\"success\":false,\"cwd\":\"\",\"entries\":[],\"code\":\"PERMISSION_DENIED\"}")
            return
        }
        val result = fileService.list(dir)
        if (result == null) {
            sendResponse(socket, 404, "application/json; charset=utf-8",
                "{\"success\":false,\"cwd\":\"\",\"entries\":[],\"code\":\"NOT_FOUND\"}")
            return
        }
        val (cwd, entries) = result
        val entriesJson = entries.joinToString(",", "[", "]") { e ->
            "{\"name\":\"${jsonEscape(e.name)}\",\"isDir\":${e.isDir},\"size\":${e.size},\"modified\":${e.modified}}"
        }
        val body = "{\"success\":true,\"cwd\":\"${jsonEscape(cwd)}\",\"entries\":$entriesJson}"
        sendResponse(socket, 200, "application/json; charset=utf-8", body)
    }

    private fun handleRequestPermission(socket: Socket) {
        // 由 Web 端触发跳转系统授权页；Service 跳 Activity 需 NEW_TASK（在 FileService 内已加）。
        val already = fileService.isPermissionGranted()
        if (!already) {
            try {
                fileService.requestPermission()
            } catch (e: Exception) {
                Log.e(TAG, "requestPermission failed", e)
            }
        }
        sendResponse(socket, 200, "application/json; charset=utf-8",
            "{\"success\":true,\"granted\":$already}")
    }

    private fun handleFileDownload(socket: Socket, query: String) {
        val rel = parseQueryPath(query)
        if (!fileService.isPermissionGranted()) {
            sendResponse(socket, 403, "text/plain", "Permission denied")
            return
        }
        val file = fileService.resolveForDownload(rel)
        if (file == null) {
            sendResponse(socket, 404, "text/plain", "File not found")
            return
        }
        try {
            val response = StringBuilder()
            response.append("HTTP/1.1 200 OK\r\n")
            response.append("Content-Type: ${getMimeType(file.name)}\r\n")
            response.append("Content-Length: ${file.length()}\r\n")
            response.append("Content-Disposition: attachment; filename*=UTF-8''${Uri.encode(file.name)}\r\n")
            response.append("Access-Control-Allow-Origin: *\r\n")
            response.append("Connection: close\r\n")
            response.append("\r\n")
            socket.outputStream.write(response.toString().toByteArray(StandardCharsets.UTF_8))
            file.inputStream().use { input -> input.copyTo(socket.outputStream) }
            socket.outputStream.flush()
        } catch (e: Exception) {
            sendResponse(socket, 500, "text/plain", "Error serving file: ${e.message}")
        }
    }

    private fun handleFileUpload(socket: Socket, raw: ByteArray, rawLen: Int) {
        val body = readRequestBody(socket, raw, rawLen)
        val req = try { fileJson.decodeFromString(FileUploadRequest.serializer(), body) } catch (e: Exception) { null }
        if (req == null) {
            sendResponse(socket, 400, "application/json; charset=utf-8",
                "{\"success\":false,\"code\":\"INVALID_PATH\"}")
            return
        }
        val res = fileService.upload(req.dir, req.name, req.data)
        sendFileOpResponse(socket, res, if (res.success) 200 else opStatus(res))
    }

    private fun handleFileDelete(body: String): FileOpResponse {
        val req = try { fileJson.decodeFromString(FileDeleteRequest.serializer(), body) } catch (e: Exception) { null }
            ?: return FileOpResponse(success = false, code = "INVALID_PATH")
        return fileService.delete(req.dir, req.name, req.isDir)
    }

    private fun handleFileRename(body: String): FileOpResponse {
        val req = try { fileJson.decodeFromString(FileRenameRequest.serializer(), body) } catch (e: Exception) { null }
            ?: return FileOpResponse(success = false, code = "INVALID_PATH")
        return fileService.rename(req.dir, req.oldName, req.newName)
    }

    private fun handleFileMkdir(body: String): FileOpResponse {
        val req = try { fileJson.decodeFromString(FileMkdirRequest.serializer(), body) } catch (e: Exception) { null }
            ?: return FileOpResponse(success = false, code = "INVALID_PATH")
        return fileService.mkdir(req.dir, req.name)
    }

    private fun handleFileOp(
        socket: Socket,
        raw: ByteArray,
        rawLen: Int,
        op: (String) -> FileOpResponse
    ) {
        val body = readRequestBody(socket, raw, rawLen)
        val res = op(body)
        sendFileOpResponse(socket, res, if (res.success) 200 else opStatus(res))
    }

    private fun sendFileOpResponse(socket: Socket, res: FileOpResponse, status: Int) {
        val pathPart = res.path?.let { ",\"path\":\"${jsonEscape(it)}\"" } ?: ""
        val codePart = res.code?.let { ",\"code\":\"${it}\"" } ?: ""
        val msgPart = res.message?.let { ",\"message\":\"${jsonEscape(it)}\"" } ?: ""
        val body = "{\"success\":${res.success}$pathPart$codePart$msgPart}"
        sendResponse(socket, status, "application/json; charset=utf-8", body)
    }

    private fun opStatus(res: FileOpResponse): Int = when (res.code) {
        "PERMISSION_DENIED" -> 403
        "INVALID_PATH" -> 400
        "NOT_FOUND" -> 404
        "EXISTS" -> 409
        else -> 500
    }

    private fun serveAsset(socket: Socket, assetName: String, contentType: String) {
        try {
            val content = assets.open(assetName).use { input ->
                input.readBytes().toString(StandardCharsets.UTF_8)
            }
            sendResponse(socket, 200, contentType, content)
        } catch (e: Exception) {
            sendResponse(socket, 404, "text/plain", "Asset not found: $assetName")
        }
    }

    private fun serveFileFromCache(socket: Socket, fileName: String) {
        try {
            val file = chatService.getSharedFile(fileName)
            if (file == null || !file.exists() || !file.isFile) {
                sendResponse(socket, 404, "text/plain", "File not found")
                return
            }

            val response = StringBuilder()
            response.append("HTTP/1.1 200 OK\r\n")
            response.append("Content-Type: ${getMimeType(file.name)}\r\n")
            response.append("Content-Length: ${file.length()}\r\n")
            response.append("Content-Disposition: attachment; filename*=UTF-8''${Uri.encode(file.name)}\r\n")
            response.append("Access-Control-Allow-Origin: *\r\n")
            response.append("Connection: close\r\n")
            response.append("\r\n")

            socket.outputStream.write(response.toString().toByteArray(StandardCharsets.UTF_8))
            file.inputStream().use { input -> input.copyTo(socket.outputStream) }
            socket.outputStream.flush()
        } catch (e: Exception) {
            sendResponse(socket, 500, "text/plain", "Error serving file: ${e.message}")
        }
    }

    private fun readRequestBody(socket: Socket, raw: ByteArray, rawLen: Int): String {
        return try {
            val headerEnd = findHeaderEnd(raw, rawLen)
            if (headerEnd < 0) return ""
            val headerSection = String(raw, 0, headerEnd, StandardCharsets.UTF_8)
            val bodyOffset = headerEnd + 4
            val contentLength = Regex("Content-Length:\\s*(\\d+)", RegexOption.IGNORE_CASE)
                .find(headerSection)?.groups?.get(1)?.value?.toIntOrNull() ?: 0

            val alreadyRead = maxOf(0, rawLen - bodyOffset)
            if (contentLength <= 0) {
                return if (alreadyRead > 0) String(raw, bodyOffset, alreadyRead, StandardCharsets.UTF_8) else ""
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
        } catch (e: SocketTimeoutException) {
            ""
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read request body", e)
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
                204 -> "No Content"
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
            response.append("Access-Control-Allow-Methods: GET, POST, OPTIONS\r\n")
            response.append("Access-Control-Allow-Headers: Content-Type\r\n")
            response.append("Connection: close\r\n")
            response.append("\r\n")

            socket.outputStream.write(response.toString().toByteArray(StandardCharsets.UTF_8))
            if (contentBytes.isNotEmpty()) {
                socket.outputStream.write(contentBytes)
            }
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

    private fun jsonEscape(value: String): String {
        return buildString {
            value.forEach { ch ->
                when (ch) {
                    '\\' -> append("\\\\")
                    '"' -> append("\\\"")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else -> {
                        if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
                    }
                }
            }
        }
    }

    private fun getMimeType(fileName: String): String {
        val lower = fileName.lowercase()
        return when {
            lower.endsWith(".html") -> "text/html; charset=utf-8"
            lower.endsWith(".css") -> "text/css; charset=utf-8"
            lower.endsWith(".js") -> "application/javascript; charset=utf-8"
            lower.endsWith(".json") -> "application/json; charset=utf-8"
            lower.endsWith(".png") -> "image/png"
            lower.endsWith(".jpg") || lower.endsWith(".jpeg") -> "image/jpeg"
            lower.endsWith(".gif") -> "image/gif"
            lower.endsWith(".ico") -> "image/x-icon"
            lower.endsWith(".txt") -> "text/plain; charset=utf-8"
            lower.endsWith(".pdf") -> "application/pdf"
            lower.endsWith(".apk") -> "application/vnd.android.package-archive"
            else -> "application/octet-stream"
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces() ?: return "127.0.0.1"
            for (intf in interfaces) {
                if (!intf.isUp || intf.isLoopback) continue
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

    @Synchronized
    private fun startWebSocketServerIfNeeded() {
        val current = wsServer
        if (current != null && current.isRunning()) return
        try {
            wsServer = ImWebSocketServer(wsPort, chatService).also { server ->
                server.setReuseAddr(true)
                server.start()
            }
            Log.i(TAG, "WebSocket server start requested on port $wsPort")
        } catch (e: Exception) {
            wsServer = null
            Log.e(TAG, "Failed to start websocket server", e)
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
            .setContentTitle("H4 IM Server")
            .setContentText("HTTP :$serverPort  /  WS :$wsPort")
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setOngoing(true)
            .build()
    }
}

