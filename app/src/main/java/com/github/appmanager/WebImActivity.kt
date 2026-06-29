package com.github.appmanager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.github.appmanager.im.ChatSerializer
import com.github.appmanager.im.ChatService
import com.github.appmanager.im.RoomMessage
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.File
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.net.URLEncoder
import java.util.Collections

class WebImActivity : ComponentActivity() {
    companion object {
        private const val TAG = "WebImActivity"
        private const val WS_URL = "ws://localhost:8081"
    }

    private lateinit var chatService: ChatService
    private var isServerRunning = false

    // (消息, 是否为本端发出) —— 本端发出靠右，socket 收到靠左，无需身份字段。
    private val messageList = mutableListOf<Pair<RoomMessage, Boolean>>()

    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var fileButton: ImageButton
    private lateinit var messageScrollView: ScrollView
    private lateinit var messageContainer: LinearLayout
    private lateinit var statusView: View
    private lateinit var retryBtn: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var serverUrlText: TextView
    private var serverUrl: String = ""

    private val mainHandler = Handler(Looper.getMainLooper())
    private var wsClient: WebSocketClient? = null
    private var destroyed = false
    private var networkCallback: ConnectivityManager.NetworkCallback? = null

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            isServerRunning = true
            Log.d(TAG, "HTTP File Server service connected")
            initChat()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServerRunning = false
            Log.d(TAG, "HTTP File Server service disconnected")
        }
    }

    private val pickFileLauncher = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        uri?.let { handleFileSelected(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_im)
        optimizeSystemBars()

        chatService = ChatService(this)

        messageInput = findViewById(R.id.message_input)
        sendButton = findViewById(R.id.send_button)
        fileButton = findViewById(R.id.file_button)
        messageScrollView = findViewById(R.id.message_scroll_view)
        messageContainer = findViewById(R.id.message_container)
        statusView = findViewById(R.id.status_view)
        retryBtn = findViewById(R.id.retry_btn)
        progressBar = findViewById(R.id.progress_bar)
        serverUrlText = findViewById(R.id.server_url_text)
        // 共享聊天室：单频道，无需接收者选择器，直接隐藏。
        findViewById<View>(R.id.receiver_spinner).visibility = View.GONE
        serverUrl = buildServerUrl()
        serverUrlText.text = serverUrl
        setupListeners()
        checkAndStartServer()
        registerNetworkCallback()
    }

    private fun optimizeSystemBars() {
        window.statusBarColor = ContextCompat.getColor(this, R.color.colorPrimaryDark)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.surface)
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = true
    }

    private fun setupListeners() {
        sendButton.setOnClickListener { sendMessage() }
        fileButton.setOnClickListener { pickFileLauncher.launch("*/*") }
        retryBtn.setOnClickListener { connectWs() }
        messageInput.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
    }

    // ---------- WebSocket 客户端 ----------

    private fun connectWs() {
        if (destroyed) return
        try {
            wsClient?.close()
        } catch (e: Exception) {
            // ignore
        }

        showStatus(true, false)
        val client = object : WebSocketClient(URI(WS_URL)) {
            override fun onOpen(handshake: ServerHandshake?) {
                mainHandler.post {
                    // 清空列表，等待服务器推送的近期历史填充（重连时避免重复）。
                    messageList.clear()
                    renderMessages()
                    showStatus(false, false)
                }
            }

            override fun onMessage(message: String?) {
                val msg = message?.let { ChatSerializer.deserializeMessage(it) } ?: return
                mainHandler.post {
                    messageList.add(msg to false)
                    renderMessages()
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                mainHandler.post { showStatus(true, true) }
                if (!destroyed) {
                    mainHandler.postDelayed({ connectWs() }, 3000)
                }
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "WebSocket error", ex)
            }
        }
        wsClient = client
        try {
            client.connect()
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            showStatus(true, true)
            mainHandler.postDelayed({ connectWs() }, 3000)
        }
    }

    private fun showStatus(loading: Boolean, disconnected: Boolean) {
        statusView.visibility = if (loading) View.VISIBLE else View.GONE
        retryBtn.visibility = if (disconnected) View.VISIBLE else View.GONE
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isBlank()) return
        val client = wsClient
        if (client == null || !client.isOpen) {
            Toast.makeText(this, "未连接", Toast.LENGTH_SHORT).show()
            return
        }

        val msg = RoomMessage(type = "TEXT", content = text, timestamp = System.currentTimeMillis())
        client.send(ChatSerializer.serializeMessage(msg))
        messageList.add(msg to true)   // 本端发出靠右
        renderMessages()
        messageInput.text.clear()
    }

    private fun handleFileSelected(uri: Uri) {
        val file = resolveUriToFile(uri) ?: return
        val client = wsClient
        if (client == null || !client.isOpen) {
            Toast.makeText(this, "未连接", Toast.LENGTH_SHORT).show()
            return
        }

        Thread {
            // 原生端直接拷贝文件到共享目录（同进程，免去 base64），再发 FILE 消息。
            val name = chatService.storeLocalFile(file)
            val fileUrl = serverUrl + "/files/" + URLEncoder.encode(name, "UTF-8")
            val msg = RoomMessage(
                type = "FILE",
                content = "",
                timestamp = System.currentTimeMillis(),
                fileName = name,
                fileSize = file.length(),
                fileUrl = fileUrl
            )
            client.send(ChatSerializer.serializeMessage(msg))
            runOnUiThread {
                messageList.add(msg to true)
                renderMessages()
            }
        }.start()
    }

    private fun resolveUriToFile(uri: Uri): File? {
        return try {
            val tempFile = File(cacheDir, "temp_${System.currentTimeMillis()}")
            contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            tempFile
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve URI to file", e)
            null
        }
    }

    // ---------- 渲染 ----------

    private fun renderMessages() {
        messageContainer.removeAllViews()
        messageList.forEach { (msg, isSent) ->
            messageContainer.addView(createMessageView(msg, isSent))
        }
        scrollToBottom()
    }

    private fun createMessageView(msg: RoomMessage, isSent: Boolean): View {
        val layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        layoutParams.setMargins(0, 4, 0, 4)
        layoutParams.gravity =
            if (isSent) android.view.Gravity.END else android.view.Gravity.START

        val containerView = LinearLayout(this).apply {
            this.layoutParams = layoutParams
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(
                if (isSent) getColor(R.color.colorPrimary) else getColor(R.color.surface)
            )
            elevation = 2f
        }

        val sourceLabel = TextView(this).apply {
            text = if (isSent) "手机" else "网页"
            textSize = 10f
            setTextColor(
                if (isSent) getColor(R.color.surface) else getColor(R.color.textSecondary)
            )
            setPadding(8, 0, 8, 2)
        }
        containerView.addView(sourceLabel)

        val contentText = TextView(this).apply {
            text = if (msg.type == "FILE" && msg.fileName != null) {
                "文件: ${msg.fileName}\n大小: ${formatFileSize(msg.fileSize ?: 0L)}"
            } else {
                msg.content
            }
            textSize = 14f
            setTextColor(
                if (isSent) getColor(R.color.surface) else getColor(R.color.textPrimary)
            )
            setPadding(8, 4, 8, 4)
        }
        containerView.addView(contentText)

        val timeText = TextView(this).apply {
            val d = java.util.Date(msg.timestamp)
            text = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(d)
            textSize = 10f
            setTextColor(
                if (isSent) getColor(R.color.surface) else getColor(R.color.textSecondary)
            )
            gravity = android.view.Gravity.END
            setPadding(0, 4, 0, 0)
        }
        containerView.addView(timeText)

        return containerView
    }

    private fun formatFileSize(bytes: Long): String {
        return when {
            bytes < 1024 -> "$bytes B"
            bytes < 1048576 -> String.format("%.1f KB", bytes / 1024.0)
            else -> String.format("%.1f MB", bytes / 1048576.0)
        }
    }

    private fun scrollToBottom() {
        messageScrollView.post {
            messageScrollView.fullScroll(View.FOCUS_DOWN)
        }
    }

    // ---------- 服务与生命周期 ----------

    private fun checkAndStartServer() {
        if (isServerRunning) {
            initChat()
            return
        }

        val intent = Intent(this, HttpFileServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }

        try {
            bindService(intent, connection, Context.BIND_AUTO_CREATE)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to bind service", e)
            statusView.visibility = View.VISIBLE
            retryBtn.visibility = View.VISIBLE
        }
    }

    private fun initChat() {
        connectWs()
    }

    private fun buildServerUrl(): String {
        val port = HttpFileServerService.serverPort
        val ipAddress = getLocalIpAddress()
        return "http://$ipAddress:$port"
    }

    private fun refreshServerUrl() {
        serverUrl = buildServerUrl()
        serverUrlText.text = serverUrl
    }

    /**
     * 监听网络变化：手机移动到不同局域网时 IP 会变，刷新屏幕上展示的服务器地址，
     * 让 web 端总能拿到当前可达的 URL。
     */
    private fun registerNetworkCallback() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread { refreshServerUrl() }
            }

            override fun onLost(network: Network) {
                runOnUiThread { refreshServerUrl() }
            }
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.e(TAG, "registerNetworkCallback failed", e)
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val wifiManager = application.getSystemService(Context.WIFI_SERVICE) as WifiManager
            val wifiInfo = wifiManager.connectionInfo
            val ipInt = wifiInfo.ipAddress
            if (ipInt != 0) {
                return InetAddress.getByAddress(
                    byteArrayOf(
                        ipInt.toByte(),
                        (ipInt shr 8).toByte(),
                        (ipInt shr 16).toByte(),
                        (ipInt shr 24).toByte()
                    )
                ).hostAddress ?: "127.0.0.1"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get WiFi IP", e)
        }

        try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            for (nic in interfaces) {
                val addresses = Collections.list(nic.inetAddresses)
                for (addr in addresses) {
                    if (!addr.isLoopbackAddress && addr is InetAddress) {
                        val ip = addr.hostAddress ?: continue
                        if (ip.startsWith("192.168.") || ip.startsWith("10.") || ip.startsWith("172.")) {
                            return ip
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get network IP", e)
        }

        return "127.0.0.1"
    }

    override fun onResume() {
        super.onResume()
        val client = wsClient
        if (isServerRunning && (client == null || client.isClosed || client.isClosing)) {
            connectWs()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        destroyed = true
        try {
            wsClient?.close()
        } catch (e: Exception) {
            // ignore
        }
        networkCallback?.let { cb ->
            try {
                (getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
                    ?.unregisterNetworkCallback(cb)
            } catch (e: Exception) {
                // ignore
            }
        }
        networkCallback = null
        try {
            unbindService(connection)
        } catch (e: Exception) {
            // ignore
        }
    }
}
