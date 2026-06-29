package com.github.appmanager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import com.github.appmanager.im.ChatMessage
import com.github.appmanager.im.ChatService
import com.github.appmanager.im.MessageType
import java.io.File

class WebImActivity : ComponentActivity() {
    companion object {
        private const val TAG = "WebImActivity"
    }

    private lateinit var chatService: ChatService
    private var isServerRunning = false
    private var currentReceiver: String = ""
    private val messageList = mutableListOf<ChatMessage>()

    private lateinit var messageInput: EditText
    private lateinit var sendButton: ImageButton
    private lateinit var fileButton: ImageButton
    private lateinit var receiverSpinner: Spinner
    private lateinit var messageScrollView: ScrollView
    private lateinit var messageContainer: LinearLayout
    private lateinit var statusView: View
    private lateinit var retryBtn: Button
    private lateinit var progressBar: ProgressBar

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
        receiverSpinner = findViewById(R.id.receiver_spinner)
        messageScrollView = findViewById(R.id.message_scroll_view)
        messageContainer = findViewById(R.id.message_container)
        statusView = findViewById(R.id.status_view)
        retryBtn = findViewById(R.id.retry_btn)
        progressBar = findViewById(R.id.progress_bar)

        currentReceiver = intent.getStringExtra("receiver") ?: chatService.getDeviceId()

        setupReceiverSpinner()
        setupListeners()
        checkAndStartServer()
    }

    private fun optimizeSystemBars() {
        window.statusBarColor = ContextCompat.getColor(this, R.color.colorPrimaryDark)
        window.navigationBarColor = ContextCompat.getColor(this, R.color.surface)
        val controller = androidx.core.view.WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = false
        controller.isAppearanceLightNavigationBars = true
    }

    private fun setupReceiverSpinner() {
        val receivers =
            listOf(currentReceiver, chatService.getDeviceId()).distinct().filter { it.isNotBlank() }

        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, receivers)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        receiverSpinner.adapter = adapter

        receiverSpinner.onItemSelectedListener =
            object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(
                    parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long
                ) {
                    currentReceiver = receivers[position]
                    loadMessages()
                }

                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
    }

    private fun setupListeners() {
        sendButton.setOnClickListener {
            sendMessage()
        }

        fileButton.setOnClickListener {
            pickFileLauncher.launch("*/*")
        }

        retryBtn.setOnClickListener {
            startOrBindServer()
        }

        messageInput.setOnEditorActionListener { _, _, _ ->
            sendMessage()
            true
        }
    }

    private fun sendMessage() {
        val text = messageInput.text.toString().trim()
        if (text.isBlank()) return

        val msg = ChatMessage(
            sender = chatService.getDeviceId(),
            receiver = currentReceiver,
            type = MessageType.TEXT,
            content = text,
            timestamp = System.currentTimeMillis()
        )

        Thread {
            chatService.sendMessage(msg, HttpFileServerService.serverPort)
            runOnUiThread {
                messageList.add(msg)
                renderMessages()
            }
        }.start()

        messageInput.text.clear()
    }

    private fun handleFileSelected(uri: Uri) {
        val filePath = resolveUriToFile(uri)?.absolutePath ?: return
        val file = File(filePath)

        val msg = ChatMessage(
            sender = chatService.getDeviceId(),
            receiver = currentReceiver,
            type = MessageType.FILE,
            content = "文件: ${file.name}",
            timestamp = System.currentTimeMillis(),
            fileName = file.name,
            fileSize = file.length(),
            filePath = filePath
        )

        messageList.add(msg)
        renderMessages()
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

    private fun loadMessages() {
        messageContainer.removeAllViews()
        messageList.clear()

        Thread {
            val messages = chatService.getMessages(currentReceiver)
            runOnUiThread {
                messageList.addAll(messages)
                renderMessages()
            }
        }.start()
    }

    private fun renderMessages() {
        messageContainer.removeAllViews()
        messageList.forEach { msg ->
            val messageView = createMessageView(msg)
            messageContainer.addView(messageView)
        }
        scrollToBottom()
    }

    private fun createMessageView(msg: ChatMessage): View {
        val layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
        layoutParams.setMargins(0, 4, 0, 4)
        layoutParams.gravity =
            if (msg.sender == chatService.getDeviceId()) android.view.Gravity.END else android.view.Gravity.START

        val containerView = LinearLayout(this).apply {
            this.layoutParams = layoutParams
            orientation = LinearLayout.VERTICAL
            setPadding(12, 8, 12, 8)
            setBackgroundColor(
                if (msg.sender == chatService.getDeviceId()) getColor(R.color.colorPrimary) else getColor(
                    R.color.surface
                )
            )
            elevation = 2f
        }

        val contentText = TextView(this).apply {
            text = if (msg.type == MessageType.FILE && msg.fileName != null) {
                "文件: ${msg.fileName}\n大小: ${formatFileSize(msg.fileSize)}"
            } else {
                msg.content
            }
            textSize = 14f
            setTextColor(
                if (msg.sender == chatService.getDeviceId()) getColor(R.color.surface) else getColor(
                    R.color.textPrimary
                )
            )
            setPadding(8, 4, 8, 4)
        }
        containerView.addView(contentText)

        val timeText = TextView(this).apply {
            val d = java.util.Date(msg.timestamp)
            text = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(d)
            textSize = 10f
            setTextColor(
                if (msg.sender == chatService.getDeviceId()) getColor(R.color.surface) else getColor(
                    R.color.textSecondary
                )
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

    private fun checkAndStartServer() {
        if (isServerRunning) {
            statusView.visibility = View.GONE
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

    private fun startOrBindServer() {
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
            Toast.makeText(this, "服务绑定失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun initChat() {
        statusView.visibility = View.GONE
        loadMessages()
    }

    override fun onResume() {
        super.onResume()
        if (isServerRunning) {
            loadMessages()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unbindService(connection)
        } catch (e: Exception) {
            // ignore
        }
    }
}
