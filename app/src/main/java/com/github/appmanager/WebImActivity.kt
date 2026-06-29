package com.github.appmanager

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.view.View
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts

class WebImActivity : ComponentActivity() {
    companion object {
        private const val TAG = "WebImActivity"
    }

    private lateinit var webView: WebView
    private var isServerRunning = false

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            isServerRunning = true
            Log.d(TAG, "HTTP File Server service connected")
            loadWebView()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServerRunning = false
            Log.d(TAG, "HTTP File Server service disconnected")
        }
    }

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val allGranted = permissions.values.all { it }
        if (allGranted) {
            startOrBindServer()
        } else {
            Toast.makeText(this, "需要存储权限以使用文件传输功能", Toast.LENGTH_LONG).show()
            startOrBindServer()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_web_im)

        webView = findViewById(R.id.webView)
        val statusView = findViewById<View>(R.id.status_view)
        val retryBtn = findViewById<View>(R.id.retry_btn)

        retryBtn.setOnClickListener {
            startOrBindServer()
        }

        setupWebView()
        checkAndStartServer(statusView, retryBtn)
    }

    private fun setupWebView() {
        val settings: WebSettings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.allowFileAccess = true
        settings.allowContentAccess = true
        settings.cacheMode = WebSettings.LOAD_DEFAULT
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            settings.mediaPlaybackRequiresUserGesture = false
        }

        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView?,
                request: WebResourceRequest?
            ): Boolean {
                request?.url?.let { url ->
                    when {
                        url.scheme == "file" -> {
                            return false
                        }
                        url.scheme == "http" || url.scheme == "https" -> {
                            return false
                        }
                        else -> {
                            val intent = Intent(Intent.ACTION_VIEW, url)
                            startActivity(intent)
                            return true
                        }
                    }
                }
                return false
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                Log.d(TAG, "Page loaded: $url")
            }
        }

        webView.setOnLongClickListener { true }
    }

    private fun loadWebView() {
        val baseUrl = getServerBaseUrl()
        Log.d(TAG, "Loading IM page at: $baseUrl")
        webView.loadUrl(baseUrl)
    }

    private fun getServerBaseUrl(): String {
        return "http://127.0.0.1:${HttpFileServerService.serverPort}/"
    }

    private fun checkAndStartServer(statusView: View, retryBtn: View) {
        if (isServerRunning) {
            statusView.visibility = View.GONE
            retryBtn.visibility = View.GONE
            loadWebView()
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

    override fun onResume() {
        super.onResume()
        if (isServerRunning && !webView.url.isNullOrEmpty()) {
            webView.reload()
        }
    }

    override fun onPause() {
        super.onPause()
        try {
            unbindService(connection)
        } catch (e: Exception) {
            // ignore
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

    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
}
