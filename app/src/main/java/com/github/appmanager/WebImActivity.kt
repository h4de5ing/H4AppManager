package com.github.appmanager

import android.app.AlertDialog
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
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
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import com.github.appmanager.im.ChatSerializer
import com.github.appmanager.im.ChatService
import com.github.appmanager.im.RoomMessage
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.URI
import java.util.Collections

class WebImActivity : ComponentActivity() {
    companion object {
        private const val TAG = "WebImActivity"
        private const val WS_URL = "ws://127.0.0.1:8081"
    }

    private lateinit var chatService: ChatService
    private var isServerRunning = false

    // (消息, 是否为本端发出) —— 本端发出靠右，socket 收到靠左，无需身份字段。
    private val messageList = mutableListOf<Pair<RoomMessage, Boolean>>()

    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var clearButton: ImageButton
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
    private var reconnectScheduled = false

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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_im)
        optimizeSystemBars()

        chatService = ChatService(this)

        messageInput = findViewById(R.id.message_input)
        sendButton = findViewById(R.id.send_button)
        clearButton = findViewById(R.id.clear_button)
        messageScrollView = findViewById(R.id.message_scroll_view)
        messageContainer = findViewById(R.id.message_container)
        statusView = findViewById(R.id.status_view)
        retryBtn = findViewById(R.id.retry_btn)
        progressBar = findViewById(R.id.progress_bar)
        serverUrlText = findViewById(R.id.server_url_text)
        serverUrl = buildServerUrl()
        serverUrlText.text = serverUrl
        setupListeners()
        checkAndStartServer()
        registerNetworkCallback()
    }

    private fun optimizeSystemBars() {
        // 状态栏透明：内容绘制到状态栏下方，状态栏区域露出窗口浅白背景，
        // 故状态栏图标用深色（isAppearanceLightStatusBars=true）以保证可读。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = ContextCompat.getColor(this, R.color.surface)
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
        // 应用状态栏 inset，避免顶部工具栏被状态栏遮挡。
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }
    }

    private fun setupListeners() {
        sendButton.setOnClickListener { sendMessage() }
        clearButton.setOnClickListener { confirmClearAll() }
        retryBtn.setOnClickListener {
            reconnectScheduled = false
            connectWs()
        }
        messageInput.setOnEditorActionListener { _, _, _ -> sendMessage(); true }
    }

    // ---------- WebSocket 客户端 ----------

    private fun connectWs() {
        if (destroyed) return
        ensureServerServiceStarted()

        val existing = wsClient
        if (existing != null && !existing.isClosed && !existing.isClosing) {
            showStatus(false, false)
            return
        }

        showStatus(true, false)
        val client = object : WebSocketClient(URI(WS_URL)) {
            override fun onOpen(handshake: ServerHandshake?) {
                mainHandler.post {
                    // 清空列表，等待服务器推送的近期历史填充（重连时避免重复）。
                    messageList.clear()
                    renderMessages()
                    reconnectScheduled = false
                    showStatus(false, false)
                }
            }

            override fun onMessage(message: String?) {
                val msg = message?.let { ChatSerializer.deserializeMessage(it) } ?: return
                mainHandler.post {
                    if (msg.type == "CLEAR") {
                        messageList.clear()
                    } else {
                        messageList.add(msg to false)
                    }
                    renderMessages()
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                mainHandler.post {
                    if (wsClient === this) wsClient = null
                    showStatus(true, true)
                }
                scheduleReconnect()
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "WebSocket error", ex)
                mainHandler.post {
                    if (wsClient === this && isClosed) wsClient = null
                    showStatus(true, true)
                }
                scheduleReconnect()
            }
        }
        wsClient = client
        try {
            client.connect()
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            showStatus(true, true)
            scheduleReconnect()
        }
    }

    private fun showStatus(loading: Boolean, disconnected: Boolean) {
        statusView.visibility = if (loading) View.VISIBLE else View.GONE
        retryBtn.visibility = if (disconnected) View.VISIBLE else View.GONE
    }

    private fun scheduleReconnect() {
        if (destroyed || reconnectScheduled) return
        reconnectScheduled = true
        mainHandler.postDelayed({
            reconnectScheduled = false
            connectWs()
        }, 3000)
    }

    private fun confirmClearAll() {
        AlertDialog.Builder(this)
            .setTitle("清理所有")
            .setMessage("确定要清空所有聊天记录和共享文件吗？此操作不可恢复。")
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton("清理") { _, _ -> clearAllData() }
            .show()
    }

    private fun clearAllData() {
        Thread {
            try {
                chatService.clearAll()
                val clearMsg = RoomMessage(type = "CLEAR", content = "", timestamp = System.currentTimeMillis())
                wsClient?.takeIf { it.isOpen }?.send(ChatSerializer.serializeMessage(clearMsg))
                runOnUiThread {
                    messageList.clear()
                    renderMessages()
                    Toast.makeText(this, "已清理所有记录和文件", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to clear all data", e)
                runOnUiThread { Toast.makeText(this, "清理失败", Toast.LENGTH_SHORT).show() }
            }
        }.start()
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
            // 允许长按选中文本复制
            setTextIsSelectable(true)
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
        if (ensureServerServiceStarted()) {
            mainHandler.postDelayed({ connectWs() }, 1000)
        } else {
            statusView.visibility = View.VISIBLE
            retryBtn.visibility = View.VISIBLE
        }
    }

    private fun ensureServerServiceStarted(): Boolean {
        return try {
            val intent = Intent(this, HttpFileServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            isServerRunning = true
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
            false
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








