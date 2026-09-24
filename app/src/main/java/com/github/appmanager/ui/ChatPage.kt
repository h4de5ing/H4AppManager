package com.github.appmanager.ui

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.text.format.DateUtils
import android.text.format.Formatter
import android.util.Log
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.webkit.MimeTypeMap
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.PopupMenu
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.appmanager.ImWebSocketServer
import com.github.appmanager.LanAddress
import com.github.appmanager.R
import com.github.appmanager.im.ChatSerializer
import com.github.appmanager.im.ChatService
import com.github.appmanager.im.RoomMessage
import org.java_websocket.client.WebSocketClient
import org.java_websocket.handshake.ServerHandshake
import java.io.File
import java.net.URI
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/**
 * 「聊天」页：本机作为 WebSocket 客户端连到自己的中转服务，与网页端共用一个聊天室。
 * 本端发出的消息靠右，从 socket 收到的靠左。
 */
class ChatPage(
    private val activity: ComponentActivity,
    root: View,
    private val onUnreadChanged: (Int) -> Unit,
) {
    private companion object {
        const val TAG = "ChatPage"
        const val WS_URL = "ws://127.0.0.1:8081"
        const val RECONNECT_DELAY_MS = 3000L
        const val FIRST_CONNECT_DELAY_MS = 800L

        /** 连接建立后这段时间内收到的是服务器回放的历史记录，不计入未读。 */
        const val HISTORY_REPLAY_WINDOW_MS = 1500L

        /** 相邻消息间隔超过该值时插入时间分隔。 */
        const val TIME_DIVIDER_GAP_MS = 5 * 60 * 1000L

        const val TYPE_TEXT = 0
        const val TYPE_FILE = 1
        const val TYPE_SYSTEM = 2
    }

    private data class ChatItem(
        val message: RoomMessage,
        val outgoing: Boolean,
        var failed: Boolean = false,
    )

    private val chatService = ChatService(activity)
    private val items = mutableListOf<ChatItem>()
    private val adapter = MessageAdapter()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val messageList: RecyclerView = root.findViewById(R.id.message_list)
    private val emptyView: View = root.findViewById(R.id.chat_empty)
    private val statusDot: View = root.findViewById(R.id.chat_status_dot)
    private val statusText: TextView = root.findViewById(R.id.chat_status_text)
    private val banner: View = root.findViewById(R.id.chat_banner)
    private val messageInput: EditText = root.findViewById(R.id.message_input)
    private val sendButton: ImageButton = root.findViewById(R.id.send_button)

    private var wsClient: WebSocketClient? = null
    private var connected = false
    private var everConnected = false
    private var openedAt = 0L
    private var reconnectScheduled = false
    private var destroyed = false
    private var shown = false
    private var unread = 0

    private val pickFileLauncher = activity.registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(::sendFile) }

    init {
        messageList.layoutManager = LinearLayoutManager(activity).apply { stackFromEnd = true }
        messageList.adapter = adapter

        sendButton.setOnClickListener { sendText() }
        messageInput.setOnEditorActionListener { _, _, _ -> sendText(); true }
        root.findViewById<View>(R.id.attach_button).setOnClickListener {
            pickFileLauncher.launch(arrayOf("*/*"))
        }
        root.findViewById<View>(R.id.chat_banner_retry).setOnClickListener {
            reconnectScheduled = false
            connectWs()
        }
        root.findViewById<View>(R.id.chat_copy_button).setOnClickListener {
            activity.copyToClipboard(
                activity.getString(R.string.chat_copy_address), LanAddress.baseUrl(activity)
            )
        }
        root.findViewById<View>(R.id.chat_more_button).setOnClickListener(::showMoreMenu)

        updateConnectionUi()
        updateEmptyState()
        // 给服务留出启动 WebSocket 的时间；期间视同「已安排重连」，避免 onResume 抢先连接。
        reconnectScheduled = true
        mainHandler.postDelayed({
            reconnectScheduled = false
            connectWs()
        }, FIRST_CONNECT_DELAY_MS)
    }

    fun setShown(isShown: Boolean) {
        shown = isShown
        if (isShown && unread > 0) {
            unread = 0
            onUnreadChanged(0)
        }
    }

    /** 周期性调用，刷新在线网页端数量等状态。 */
    fun refreshStatus() {
        updateConnectionUi()
    }

    fun onResume() {
        val client = wsClient
        if (!reconnectScheduled && (client == null || client.isClosed)) {
            connectWs()
        }
    }

    fun onDestroy() {
        destroyed = true
        mainHandler.removeCallbacksAndMessages(null)
        try {
            wsClient?.close()
        } catch (_: Exception) {
            // ignore
        }
    }

    fun confirmClearAll() {
        AlertDialog.Builder(activity)
            .setTitle(R.string.clear_all)
            .setMessage(R.string.clear_all_message)
            .setNegativeButton(android.R.string.cancel, null)
            .setPositiveButton(R.string.clear_confirm) { _, _ -> clearAllData() }
            .show()
    }

    // ---------- WebSocket 客户端 ----------

    private fun connectWs() {
        if (destroyed) return
        val existing = wsClient
        if (existing != null && !existing.isClosed) return

        val client = object : WebSocketClient(URI(WS_URL)) {
            override fun onOpen(handshake: ServerHandshake?) {
                mainHandler.post {
                    // 清空列表，等待服务器推送的近期历史填充（重连时避免重复）。
                    clearItems()
                    connected = true
                    everConnected = true
                    openedAt = System.currentTimeMillis()
                    reconnectScheduled = false
                    updateConnectionUi()
                }
            }

            override fun onMessage(message: String?) {
                val msg = message?.let { ChatSerializer.deserializeMessage(it) } ?: return
                mainHandler.post {
                    if (msg.type == "CLEAR") {
                        clearItems()
                    } else {
                        appendItem(ChatItem(msg, outgoing = false))
                        val isLive = System.currentTimeMillis() - openedAt > HISTORY_REPLAY_WINDOW_MS
                        if (isLive && !shown) {
                            unread++
                            onUnreadChanged(unread)
                        }
                    }
                }
            }

            override fun onClose(code: Int, reason: String?, remote: Boolean) {
                mainHandler.post { onDisconnected(this) }
            }

            override fun onError(ex: Exception?) {
                Log.e(TAG, "WebSocket error", ex)
                mainHandler.post { if (isClosed) onDisconnected(this) }
            }
        }
        wsClient = client
        try {
            client.connect()
        } catch (e: Exception) {
            Log.e(TAG, "connect failed", e)
            onDisconnected(client)
        }
    }

    private fun onDisconnected(client: WebSocketClient) {
        if (wsClient !== client) return
        wsClient = null
        connected = false
        updateConnectionUi()
        scheduleReconnect()
    }

    private fun scheduleReconnect() {
        if (destroyed || reconnectScheduled) return
        reconnectScheduled = true
        mainHandler.postDelayed({
            reconnectScheduled = false
            connectWs()
        }, RECONNECT_DELAY_MS)
    }

    private fun updateConnectionUi() {
        val clients = ImWebSocketServer.webClientCount
        when {
            connected -> {
                statusDot.setBackgroundResource(R.drawable.dot_online)
                statusText.text = if (clients > 0) {
                    activity.getString(R.string.chat_connected_clients, clients)
                } else {
                    activity.getString(R.string.chat_connected_alone)
                }
            }

            everConnected -> {
                statusDot.setBackgroundResource(R.drawable.dot_warn)
                statusText.setText(R.string.chat_reconnecting)
            }

            else -> {
                statusDot.setBackgroundResource(R.drawable.dot_warn)
                statusText.setText(R.string.chat_connecting)
            }
        }
        // 首次连接时不打扰；只有连上后又断开才显示顶部提示条，历史消息保持可见。
        banner.isVisible = !connected && everConnected
        sendButton.alpha = if (connected) 1f else 0.4f
    }

    // ---------- 发送 ----------

    private fun sendText() {
        val text = messageInput.text.toString().trim()
        if (text.isBlank()) return
        val msg = RoomMessage(type = "TEXT", content = text, timestamp = System.currentTimeMillis())
        val item = ChatItem(msg, outgoing = true)
        item.failed = !trySend(msg)
        appendItem(item)
        messageInput.text.clear()
    }

    private fun trySend(msg: RoomMessage): Boolean {
        val client = wsClient
        if (client == null || !client.isOpen) return false
        return try {
            client.send(ChatSerializer.serializeMessage(msg))
            true
        } catch (e: Exception) {
            Log.e(TAG, "send failed", e)
            false
        }
    }

    private fun resend(position: Int) {
        val item = items.getOrNull(position) ?: return
        if (!trySend(item.message)) {
            Toast.makeText(activity, R.string.chat_not_connected, Toast.LENGTH_SHORT).show()
            return
        }
        item.failed = false
        adapter.notifyItemChanged(position)
    }

    private fun sendFile(uri: Uri) {
        Toast.makeText(activity, R.string.chat_file_sending, Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching {
                val name = queryDisplayName(uri) ?: "file_${System.currentTimeMillis()}"
                val input = activity.contentResolver.openInputStream(uri)
                    ?: error(activity.getString(R.string.chat_file_missing))
                val stored = input.use { chatService.storeFile(name, it) }
                RoomMessage(
                    type = "FILE",
                    content = stored.name,
                    timestamp = System.currentTimeMillis(),
                    fileName = stored.name,
                    fileSize = stored.length(),
                    fileUrl = "${LanAddress.baseUrl(activity)}/files/${Uri.encode(stored.name)}",
                )
            }
            activity.runOnUiThread {
                if (destroyed) return@runOnUiThread
                result.onSuccess { msg ->
                    val item = ChatItem(msg, outgoing = true)
                    item.failed = !trySend(msg)
                    appendItem(item)
                }.onFailure { throwable ->
                    Log.e(TAG, "sendFile failed", throwable)
                    Toast.makeText(
                        activity,
                        activity.getString(
                            R.string.chat_file_failed,
                            throwable.message ?: activity.getString(R.string.unknown_value),
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun queryDisplayName(uri: Uri): String? {
        return activity.contentResolver.query(
            uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }

    private fun openFile(msg: RoomMessage) {
        val storedName = msg.fileUrl?.substringAfter("/files/", "")?.takeIf { it.isNotBlank() }
            ?.let(Uri::decode) ?: msg.fileName.orEmpty()
        val localFile = chatService.getSharedFile(storedName)?.takeIf(File::isFile)
        val intent = if (localFile != null) {
            val uri = FileProvider.getUriForFile(
                activity, "${activity.packageName}.fileprovider", localFile
            )
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, mimeTypeOf(localFile.name))
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        } else {
            val url = msg.fileUrl
            if (url.isNullOrBlank()) {
                Toast.makeText(activity, R.string.chat_file_missing, Toast.LENGTH_SHORT).show()
                return
            }
            Intent(Intent.ACTION_VIEW, Uri.parse(url))
        }
        try {
            activity.startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.chat_file_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun mimeTypeOf(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }

    // ---------- 菜单与清理 ----------

    private fun showMoreMenu(anchor: View) {
        PopupMenu(activity, anchor).apply {
            menu.add(0, 1, 0, R.string.chat_open_web)
            menu.add(0, 2, 1, R.string.clear_all)
            setOnMenuItemClickListener { item ->
                when (item.itemId) {
                    1 -> openInBrowser()
                    2 -> confirmClearAll()
                }
                true
            }
        }.show()
    }

    private fun openInBrowser() {
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(LanAddress.baseUrl(activity))))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.chat_file_no_app, Toast.LENGTH_SHORT).show()
        }
    }

    private fun clearAllData() {
        Thread {
            val result = runCatching {
                chatService.clearAll()
                val clearMsg = RoomMessage(
                    type = "CLEAR", content = "", timestamp = System.currentTimeMillis()
                )
                wsClient?.takeIf { it.isOpen }?.send(ChatSerializer.serializeMessage(clearMsg))
            }
            activity.runOnUiThread {
                if (destroyed) return@runOnUiThread
                result.onSuccess {
                    clearItems()
                    Toast.makeText(activity, R.string.clear_done, Toast.LENGTH_SHORT).show()
                }.onFailure { e ->
                    Log.e(TAG, "Failed to clear all data", e)
                    Toast.makeText(activity, R.string.clear_failed, Toast.LENGTH_SHORT).show()
                }
            }
        }.start()
    }

    // ---------- 列表 ----------

    private fun appendItem(item: ChatItem) {
        items.add(item)
        adapter.notifyItemInserted(items.lastIndex)
        messageList.scrollToPosition(items.lastIndex)
        updateEmptyState()
    }

    private fun updateEmptyState() {
        emptyView.isVisible = items.isEmpty()
    }

    private fun formatClock(timestamp: Long): String =
        DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(timestamp))

    private fun formatDivider(timestamp: Long): String {
        return if (DateUtils.isToday(timestamp)) {
            activity.getString(R.string.chat_today, formatClock(timestamp))
        } else {
            DateUtils.formatDateTime(
                activity, timestamp,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
            )
        }
    }

    private fun fileBadge(name: String): Pair<String, Int> {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.ROOT)
        val color = when (ext) {
            "apk" -> 0xFF16A34A
            "pdf" -> 0xFFDC2626
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic" -> 0xFF2563EB
            "mp4", "mkv", "mov", "avi", "mp3", "flac", "wav", "m4a" -> 0xFF7C3AED
            "zip", "rar", "7z", "tar", "gz" -> 0xFFD97706
            "doc", "docx", "txt", "md" -> 0xFF0EA5E9
            "xls", "xlsx", "csv" -> 0xFF15803D
            else -> 0xFF0F766E
        }.toInt()
        val label = ext.uppercase(Locale.ROOT).take(4).ifBlank { "FILE" }
        return label to color
    }

    @SuppressLint("NotifyDataSetChanged")
    private fun clearItems() {
        items.clear()
        adapter.notifyDataSetChanged()
        updateEmptyState()
    }

    private inner class MessageAdapter : RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        override fun getItemCount(): Int = items.size

        override fun getItemViewType(position: Int): Int {
            val msg = items[position].message
            return when {
                msg.type == "SYSTEM" -> TYPE_SYSTEM
                msg.type == "FILE" && msg.fileName != null -> TYPE_FILE
                else -> TYPE_TEXT
            }
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
            val inflater = LayoutInflater.from(parent.context)
            return when (viewType) {
                TYPE_SYSTEM -> SystemHolder(inflater.inflate(R.layout.item_msg_system, parent, false))
                TYPE_FILE -> FileHolder(inflater.inflate(R.layout.item_msg_file, parent, false))
                else -> TextHolder(inflater.inflate(R.layout.item_msg_text, parent, false))
            }
        }

        override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
            val item = items[position]
            when (holder) {
                is SystemHolder -> holder.bind(item)
                is FileHolder -> holder.bind(item, position)
                is TextHolder -> holder.bind(item, position)
            }
        }
    }

    private class SystemHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val textView: TextView = itemView.findViewById(R.id.msg_system)

        fun bind(item: ChatItem) {
            textView.text = item.message.content
        }
    }

    /** 文字与文件消息共用的外层：时间分隔、左右对齐、发送者与时间行。 */
    private abstract inner class BubbleHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val dayView: TextView = itemView.findViewById(R.id.msg_day)
        private val column: LinearLayout = itemView.findViewById(R.id.msg_column)
        private val senderView: View = itemView.findViewById(R.id.msg_sender)
        private val timeView: TextView = itemView.findViewById(R.id.msg_time)

        protected fun bindFrame(item: ChatItem, position: Int) {
            val timestamp = item.message.timestamp
            val previous = items.getOrNull(position - 1)?.message?.timestamp
            val showDivider = previous == null || timestamp - previous > TIME_DIVIDER_GAP_MS
            dayView.isVisible = showDivider
            if (showDivider) dayView.text = formatDivider(timestamp)

            (column.layoutParams as LinearLayout.LayoutParams).gravity =
                if (item.outgoing) Gravity.END else Gravity.START
            column.gravity = if (item.outgoing) Gravity.END else Gravity.START
            senderView.isVisible = !item.outgoing

            if (item.failed) {
                timeView.setText(R.string.chat_send_failed)
                timeView.setTextColor(activity.getColor(R.color.danger))
                timeView.setOnClickListener {
                    val pos = bindingAdapterPosition
                    if (pos != RecyclerView.NO_POSITION) resend(pos)
                }
            } else {
                timeView.text = formatClock(timestamp)
                timeView.setTextColor(activity.getColor(R.color.textTertiary))
                timeView.setOnClickListener(null)
                timeView.isClickable = false
            }
            itemView.alpha = if (item.failed) 0.6f else 1f
        }
    }

    private inner class TextHolder(itemView: View) : BubbleHolder(itemView) {
        private val bubble: TextView = itemView.findViewById(R.id.msg_bubble)

        init {
            bubble.maxWidth = (activity.resources.displayMetrics.widthPixels * 0.78f).toInt()
        }

        fun bind(item: ChatItem, position: Int) {
            bindFrame(item, position)
            bubble.text = item.message.content
            if (item.outgoing) {
                bubble.setBackgroundResource(R.drawable.bg_bubble_out)
                bubble.setTextColor(activity.getColor(R.color.onPrimary))
                bubble.elevation = 0f
            } else {
                bubble.setBackgroundResource(R.drawable.bg_bubble_in)
                bubble.setTextColor(activity.getColor(R.color.textPrimary))
                bubble.elevation = activity.dp(1f).toFloat()
            }
        }
    }

    private inner class FileHolder(itemView: View) : BubbleHolder(itemView) {
        private val card: View = itemView.findViewById(R.id.file_card)
        private val typeView: TextView = itemView.findViewById(R.id.file_type)
        private val nameView: TextView = itemView.findViewById(R.id.file_name)
        private val metaView: TextView = itemView.findViewById(R.id.file_meta)

        fun bind(item: ChatItem, position: Int) {
            bindFrame(item, position)
            val msg = item.message
            val name = msg.fileName.orEmpty()
            val (label, color) = fileBadge(name)
            typeView.text = label
            typeView.backgroundTintList = ColorStateList.valueOf(color)
            nameView.text = name
            metaView.text = Formatter.formatShortFileSize(activity, msg.fileSize ?: 0L)
            card.setOnClickListener { openFile(msg) }
        }
    }
}
