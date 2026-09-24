package com.github.appmanager.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.text.format.Formatter
import android.view.View
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import com.github.appmanager.HttpFileServerService
import com.github.appmanager.ImWebSocketServer
import com.github.appmanager.LanAddress
import com.github.appmanager.R
import com.github.appmanager.im.ChatService
import com.github.appmanager.im.FileService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** 「局域网」页：服务地址、在线统计、网页入口、权限状态和清理入口。 */
class LanPage(
    private val activity: ComponentActivity,
    root: View,
    onClearAll: () -> Unit,
) {
    private val chatService = ChatService(activity)
    private val fileService = FileService(activity)
    private val statsExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    private val stateDot: View = root.findViewById(R.id.lan_state_dot)
    private val stateText: TextView = root.findViewById(R.id.lan_state_text)
    private val ipView: TextView = root.findViewById(R.id.lan_ip)
    private val portView: TextView = root.findViewById(R.id.lan_port)
    private val hintView: TextView = root.findViewById(R.id.lan_hint)
    private val statClients: TextView = root.findViewById(R.id.stat_clients)
    private val statMessages: TextView = root.findViewById(R.id.stat_messages)
    private val statFiles: TextView = root.findViewById(R.id.stat_files)
    private val permissionStatus: TextView = root.findViewById(R.id.lan_permission_status)

    private var destroyed = false

    init {
        root.findViewById<View>(R.id.lan_copy).setOnClickListener {
            activity.copyToClipboard(activity.getString(R.string.lan_copy), LanAddress.baseUrl(activity))
        }
        root.findViewById<View>(R.id.lan_share).setOnClickListener { shareAddress() }
        root.findViewById<View>(R.id.lan_link_chat).setOnClickListener { openInBrowser("/") }
        root.findViewById<View>(R.id.lan_link_files).setOnClickListener { openInBrowser("/file.html") }
        root.findViewById<View>(R.id.lan_permission_row).setOnClickListener {
            if (!fileService.isPermissionGranted()) fileService.requestPermission()
        }
        root.findViewById<View>(R.id.lan_clear_row).setOnClickListener { onClearAll() }
    }

    /** 刷新地址、运行状态和统计。统计涉及文件读取，放到后台线程。 */
    fun refresh() {
        val ip = LanAddress.localIp(activity)
        val running = HttpFileServerService.isRunning
        val lanReachable = ip != LanAddress.LOOPBACK

        when {
            !running -> setState(R.drawable.dot_off, R.string.server_stopped, R.color.danger)
            !lanReachable -> setState(R.drawable.dot_warn, R.string.lan_no_network, R.color.accentText)
            else -> setState(R.drawable.dot_online, R.string.server_running, R.color.success)
        }
        ipView.text = ip
        portView.text = activity.getString(R.string.lan_port_format, HttpFileServerService.serverPort)
        hintView.text = if (running) {
            activity.getString(
                R.string.lan_hint,
                HttpFileServerService.wsPort,
                formatUptime(System.currentTimeMillis() - HttpFileServerService.startedAt),
            )
        } else {
            activity.getString(R.string.lan_hint_stopped)
        }
        statClients.text = ImWebSocketServer.webClientCount.toString()
        refreshPermission()

        statsExecutor.execute {
            val messages = chatService.messageCount()
            val filesSize = chatService.sharedFilesSize()
            activity.runOnUiThread {
                if (destroyed) return@runOnUiThread
                statMessages.text = messages.toString()
                statFiles.text = Formatter.formatShortFileSize(activity, filesSize)
            }
        }
    }

    fun onDestroy() {
        destroyed = true
        statsExecutor.shutdownNow()
    }

    private fun setState(dotRes: Int, textRes: Int, colorRes: Int) {
        stateDot.setBackgroundResource(dotRes)
        stateText.setText(textRes)
        stateText.setTextColor(activity.getColor(colorRes))
    }

    private fun refreshPermission() {
        if (fileService.isPermissionGranted()) {
            permissionStatus.setText(R.string.lan_permission_granted)
            permissionStatus.setBackgroundResource(R.drawable.bg_status_ok)
            permissionStatus.setTextColor(activity.getColor(R.color.success))
        } else {
            permissionStatus.setText(R.string.lan_permission_request)
            permissionStatus.setBackgroundResource(R.drawable.bg_status_warn)
            permissionStatus.setTextColor(activity.getColor(R.color.accentText))
        }
    }

    private fun formatUptime(millis: Long): String {
        val totalMinutes = (millis / 60_000L).toInt()
        return when {
            totalMinutes < 1 -> activity.getString(R.string.uptime_just_now)
            totalMinutes < 60 -> activity.getString(R.string.uptime_minutes, totalMinutes)
            else -> activity.getString(R.string.uptime_hours, totalMinutes / 60, totalMinutes % 60)
        }
    }

    private fun shareAddress() {
        val text = activity.getString(R.string.lan_share_text, LanAddress.baseUrl(activity))
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        activity.startActivity(Intent.createChooser(intent, activity.getString(R.string.lan_share)))
    }

    private fun openInBrowser(path: String) {
        try {
            activity.startActivity(
                Intent(Intent.ACTION_VIEW, Uri.parse(LanAddress.baseUrl(activity) + path))
            )
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(activity, R.string.chat_file_no_app, Toast.LENGTH_SHORT).show()
        }
    }
}
