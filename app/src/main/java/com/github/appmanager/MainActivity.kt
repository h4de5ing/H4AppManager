package com.github.appmanager

import android.content.Intent
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import com.github.appmanager.ui.AppsPage
import com.github.appmanager.ui.ChatPage
import com.github.appmanager.ui.LanPage
import com.github.appmanager.ui.isNightMode

/** 宿主页：底部导航切换「应用 / 聊天 / 局域网」三个页面。 */
class MainActivity : ComponentActivity() {
    private companion object {
        const val TAG = "MainActivity"
        const val KEY_TAB = "tab"
        const val TAB_APPS = 0
        const val TAB_CHAT = 1
        const val TAB_LAN = 2
        const val STATUS_REFRESH_MS = 2000L
    }

    private lateinit var appsPage: AppsPage
    private lateinit var chatPage: ChatPage
    private lateinit var lanPage: LanPage
    private lateinit var pages: List<View>
    private lateinit var navItems: List<View>
    private lateinit var chatBadge: TextView
    private lateinit var bottomNav: View

    private var currentTab = TAB_APPS
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    /** 前台期间定时刷新服务状态、在线人数等。 */
    private val statusTicker = object : Runnable {
        override fun run() {
            refreshStatus()
            mainHandler.postDelayed(this, STATUS_REFRESH_MS)
        }
    }

    private val backToAppsCallback = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            selectTab(TAB_APPS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        optimizeSystemBars()

        bottomNav = findViewById(R.id.bottom_nav)
        chatBadge = findViewById(R.id.chat_badge)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            val ime = insets.getInsets(WindowInsetsCompat.Type.ime())
            val imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime())
            // 键盘弹出时隐藏底部导航，让输入栏紧贴键盘。
            bottomNav.isVisible = !imeVisible
            view.setPadding(
                systemBars.left, systemBars.top, systemBars.right,
                maxOf(systemBars.bottom, ime.bottom),
            )
            insets
        }

        startHttpFileServer()

        appsPage = AppsPage(this, findViewById(R.id.page_apps)) { selectTab(TAB_LAN) }
        chatPage = ChatPage(this, findViewById(R.id.page_chat), ::updateChatBadge)
        lanPage = LanPage(this, findViewById(R.id.page_lan)) { chatPage.confirmClearAll() }

        pages = listOf(
            findViewById(R.id.page_apps),
            findViewById(R.id.page_chat),
            findViewById(R.id.page_lan),
        )
        navItems = listOf(
            findViewById(R.id.nav_apps),
            findViewById(R.id.nav_chat),
            findViewById(R.id.nav_lan),
        )
        navItems.forEachIndexed { index, item -> item.setOnClickListener { selectTab(index) } }
        onBackPressedDispatcher.addCallback(this, backToAppsCallback)

        selectTab(savedInstanceState?.getInt(KEY_TAB, TAB_APPS) ?: TAB_APPS)
        registerNetworkCallback()
    }

    override fun onResume() {
        super.onResume()
        chatPage.onResume()
        mainHandler.post(statusTicker)
    }

    override fun onPause() {
        super.onPause()
        mainHandler.removeCallbacks(statusTicker)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(KEY_TAB, currentTab)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacksAndMessages(null)
        chatPage.onDestroy()
        lanPage.onDestroy()
        unregisterNetworkCallback()
    }

    private fun selectTab(tab: Int) {
        currentTab = tab
        pages.forEachIndexed { index, page -> page.isVisible = index == tab }
        navItems.forEachIndexed { index, item -> item.isSelected = index == tab }
        chatPage.setShown(tab == TAB_CHAT)
        backToAppsCallback.isEnabled = tab != TAB_APPS
        if (tab == TAB_LAN) lanPage.refresh()
    }

    private fun refreshStatus() {
        appsPage.refreshServerState()
        chatPage.refreshStatus()
        if (currentTab == TAB_LAN) lanPage.refresh()
    }

    private fun updateChatBadge(count: Int) {
        chatBadge.isVisible = count > 0
        chatBadge.text = if (count > 99) "99+" else count.toString()
    }

    private fun startHttpFileServer() {
        try {
            val intent = Intent(this, HttpFileServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start HTTP server", e)
        }
    }

    /** 切换 Wi-Fi 后 IP 会变，及时刷新各页展示的地址。 */
    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                mainHandler.post { refreshStatus() }
            }

            override fun onLost(network: Network) {
                mainHandler.post { refreshStatus() }
            }
        }
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            networkCallback = callback
        } catch (e: Exception) {
            Log.e(TAG, "registerNetworkCallback failed", e)
        }
    }

    private fun unregisterNetworkCallback() {
        val callback = networkCallback ?: return
        networkCallback = null
        try {
            getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // ignore
        }
    }

    private fun optimizeSystemBars() {
        // 内容绘制到状态栏下方，状态栏区域露出页面背景；图标颜色跟随深浅色模式。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val lightBars = !isNightMode()
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = lightBars
        controller.isAppearanceLightNavigationBars = lightBars
    }
}
