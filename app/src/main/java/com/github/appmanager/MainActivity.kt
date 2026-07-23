package com.github.appmanager

import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.text.format.Formatter
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : ComponentActivity() {
    private companion object {
        /**
         * 仅保存在当前应用进程内。MainActivity 被关闭或因配置变化重建时可直接复用；
         * 应用进程被系统杀死后缓存自然释放，下次启动重新扫描。
         */
        @Volatile
        private var cachedApps: List<InstalledApp>? = null
    }

    private val allApps = mutableListOf<InstalledApp>()
    private val appAdapter = AppAdapter()
    private var pendingExportApp: InstalledApp? = null

    private lateinit var appListView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingView: View
    private lateinit var titleCountView: TextView
    private lateinit var searchInput: EditText
    private lateinit var showSystemAppsSwitch: Switch
    private lateinit var imButton: Button

    private var isServerBound = false
    private var serverConnection: ServiceConnection? = null

    private val exportApkLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        val app = pendingExportApp
        pendingExportApp = null
        if (uri == null || app == null) {
            return@registerForActivityResult
        }
        exportApk(app, uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        optimizeSystemBars()
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { view, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            view.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        appListView = findViewById(R.id.app_list)
        emptyView = findViewById(R.id.empty_view)
        loadingView = findViewById(R.id.loading_view)
        titleCountView = findViewById(R.id.title_count)
        searchInput = findViewById(R.id.search_input)
        showSystemAppsSwitch = findViewById(R.id.show_system_apps_switch)

        appListView.layoutManager = LinearLayoutManager(this)
        appListView.adapter = appAdapter

        searchInput.doAfterTextChanged {
            applyFilters()
        }
        showSystemAppsSwitch.setOnCheckedChangeListener { _, _ ->
            applyFilters()
        }

        imButton = findViewById(R.id.im_button)
        imButton.setOnClickListener {
            startImServiceAndActivity()
        }

        loadApps()
        startHttpFileServer()
        checkAndRequestStoragePermission()
    }

    /**
     * 文件管理器需要 MANAGE_EXTERNAL_STORAGE（所有文件访问）。该权限不能用 requestPermissions
     * 弹框申请，必须在 Android 端跳系统设置页授予。App 首次启动时若未授权，跳到授权页；
     * 用户授权后返回即可，Web 端 file.html 自然就能访问 sdcard。
     */
    private fun checkAndRequestStoragePermission() {
        val fs = com.github.appmanager.im.FileService(this)
        if (!fs.isPermissionGranted()) {
            fs.requestPermission()
        }
    }

    private fun loadApps() {
        val cached = cachedApps
        if (cached != null) {
            allApps.clear()
            allApps.addAll(cached)
            applyFilters()
            showLoading(false)
            return
        }

        showLoading(true)
        Thread {
            val apps = queryInstalledApps()
            cachedApps = apps
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                allApps.clear()
                allApps.addAll(apps)
                applyFilters()
                showLoading(false)
            }
        }.start()
    }

    private fun queryInstalledApps(): List<InstalledApp> {
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION") packageManager.getInstalledPackages(PackageManager.GET_SIGNATURES)
        }

        return packages.mapNotNull { packageInfo ->
            packageInfo.toInstalledAppOrNull()
        }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun PackageInfo.toInstalledAppOrNull(): InstalledApp? {
        val appInfo = applicationInfo ?: return null
        val label =
            appInfo.loadLabel(packageManager).toString().takeIf { it.isNotBlank() } ?: packageName
        val apkPath = appInfo.sourceDir.orEmpty()
        val apkFile = apkPath.takeIf { it.isNotBlank() }?.let(::File)

        return InstalledApp(
            label = label,
            packageName = packageName,
            versionName = versionName.orEmpty(),
            versionCode = PackageInfoCompat.getLongVersionCode(this),
            isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            icon = appInfo.loadIcon(packageManager),
            apkPath = apkPath,
            apkSizeBytes = apkFile?.takeIf(File::exists)?.length() ?: 0L,
            firstInstallTime = firstInstallTime,
            lastUpdateTime = lastUpdateTime,
            targetSdk = appInfo.targetSdkVersion,
            signatureDigests = buildSignatureDigests(this),
        )
    }

    private fun applyFilters() {
        val showSystemApps = showSystemAppsSwitch.isChecked
        val query = searchInput.text.toString().trim().lowercase(Locale.getDefault())

        val filteredApps = allApps.filter { app ->
            val matchesSystem = showSystemApps || !app.isSystem
            val matchesQuery = query.isBlank() || app.label.lowercase(Locale.getDefault())
                .contains(query) || app.packageName.lowercase(Locale.getDefault()).contains(query)
            matchesSystem && matchesQuery
        }

        appAdapter.submitList(filteredApps)
        emptyView.isVisible = filteredApps.isEmpty() && !loadingView.isVisible
        titleCountView.text = getString(R.string.app_count, filteredApps.size, allApps.size)
    }

    private fun showLoading(visible: Boolean) {
        loadingView.isVisible = visible
        if (visible) {
            emptyView.isVisible = false
        } else {
            emptyView.isVisible = appAdapter.itemCount == 0
        }
    }

    private fun showAppDetails(app: InstalledApp) {
        val appType = getString(if (app.isSystem) R.string.system_app else R.string.user_app)
        val versionName = app.versionName.ifBlank { getString(R.string.unknown_value) }
        val details = getString(
            R.string.app_details,
            app.packageName,
            appType,
            versionName,
            app.versionCode.toString(),
            Formatter.formatFileSize(this, app.apkSizeBytes),
            formatTime(app.firstInstallTime),
            formatTime(app.lastUpdateTime),
            app.targetSdk.toString(),
            app.apkPath,
            app.signatureDigests.md5,
            app.signatureDigests.sha1,
            app.signatureDigests.sha256,
        )

        val shareButton = Button(this).apply {
            text = getString(R.string.share_apk)
            isAllCaps = false
        }
        val dialog = AlertDialog.Builder(this)
            .setTitle(app.label).setIcon(app.icon).setMessage(details)
            .setView(shareButton)
            .setPositiveButton(R.string.export_apk) { _, _ ->
                pendingExportApp = app
                exportApkLauncher.launch(buildExportFileName(app))
            }.setNeutralButton(R.string.open_app) { _, _ ->
                launchApp(app)
            }.setNegativeButton(android.R.string.cancel, null).show()

        shareButton.setOnClickListener {
            dialog.dismiss()
            shareApk(app)
        }
    }

    private fun launchApp(app: InstalledApp) {
        val intent = packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent != null) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } catch (e: Exception) {
                Log.e("MainActivity", "Failed to launch ${app.packageName}", e)
                Toast.makeText(this, "启动失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            Toast.makeText(this, "该应用无可启动的入口", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportApk(app: InstalledApp, targetUri: Uri) {
        Thread {
            val result = runCatching {
                require(app.apkPath.isNotBlank()) { getString(R.string.export_source_missing) }
                contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                    FileInputStream(app.apkPath).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: error(getString(R.string.export_target_unavailable))
            }

            runOnUiThread {
                result.onSuccess {
                    Toast.makeText(
                        this,
                        getString(R.string.export_success, app.label),
                        Toast.LENGTH_LONG,
                    ).show()
                }.onFailure { throwable ->
                    Toast.makeText(
                        this,
                        getString(
                            R.string.export_failed,
                            throwable.message ?: getString(R.string.unknown_value),
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun shareApk(app: InstalledApp) {
        Toast.makeText(this, R.string.preparing_apk_share, Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching {
                require(app.apkPath.isNotBlank()) { getString(R.string.export_source_missing) }
                val source = File(app.apkPath)
                require(source.exists()) { getString(R.string.export_source_missing) }

                val shareDir = File(cacheDir, "shared_apks").apply { mkdirs() }
                val target = File(shareDir, buildExportFileName(app))
                source.copyTo(target, overwrite = true)
                FileProvider.getUriForFile(
                    this,
                    "$packageName.fileprovider",
                    target,
                )
            }

            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                result.onSuccess { uri ->
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.android.package-archive"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(getString(R.string.share_apk), uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    runCatching {
                        startActivity(
                            Intent.createChooser(
                                shareIntent,
                                getString(R.string.share_apk_chooser),
                            )
                        )
                    }.onFailure { throwable ->
                        Toast.makeText(
                            this,
                            getString(
                                R.string.share_apk_failed,
                                throwable.message ?: getString(R.string.unknown_value),
                            ),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }.onFailure { throwable ->
                    Toast.makeText(
                        this,
                        getString(
                            R.string.share_apk_failed,
                            throwable.message ?: getString(R.string.unknown_value),
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun buildExportFileName(app: InstalledApp): String {
        val versionName = app.versionName.ifBlank { getString(R.string.unknown_value) }
        return "${app.label}-${app.packageName}-${versionName}.apk".replace(
            Regex("[\\\\/:*?\"<>|]"),
            "_"
        )
    }

    private fun formatTime(timeMillis: Long): String {
        return DateFormat.getDateTimeInstance().format(Date(timeMillis))
    }

    private fun startHttpFileServer() {
        try {
            val intent = Intent(this, HttpFileServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            serverConnection = object : ServiceConnection {
                override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
                    isServerBound = true
                    Log.d("MainActivity", "HTTP File Server service bound")
                }

                override fun onServiceDisconnected(name: ComponentName?) {
                    isServerBound = false
                    Log.d("MainActivity", "HTTP File Server service unbound")
                }
            }.also { conn ->
                bindService(intent, conn, Context.BIND_AUTO_CREATE)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start HTTP server", e)
        }
    }

    private fun startImServiceAndActivity() {
        try {
            val intent = Intent(this, HttpFileServerService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(intent)
            } else {
                startService(intent)
            }
            Thread.sleep(500)
            val imIntent = Intent(this, WebImActivity::class.java)
            imIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            startActivity(imIntent)
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to start IM", e)
            Toast.makeText(this, "启动IM服务失败", Toast.LENGTH_SHORT).show()
        }
    }

    private fun optimizeSystemBars() {
        // 状态栏透明：内容绘制到状态栏下方，状态栏区域露出窗口浅白背景，
        // 故状态栏图标用深色（isAppearanceLightStatusBars=true）以保证可读。
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = getColor(R.color.surface)
        val controller = WindowInsetsControllerCompat(window, window.decorView)
        controller.isAppearanceLightStatusBars = true
        controller.isAppearanceLightNavigationBars = true
    }

    private fun buildSignatureDigests(packageInfo: PackageInfo): SignatureDigests {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            @Suppress("DEPRECATION") packageInfo.signatures?.map { it.toByteArray() }.orEmpty()
        }

        if (signatures.isEmpty()) {
            val unknown = getString(R.string.unknown_value)
            return SignatureDigests(
                md5 = unknown,
                sha1 = unknown,
                sha256 = unknown,
            )
        }

        return SignatureDigests(
            md5 = signatures.joinToString(" | ") { digest("MD5", it) },
            sha1 = signatures.joinToString(" | ") { digest("SHA-1", it) },
            sha256 = signatures.joinToString(" | ") { digest("SHA-256", it) },
        )
    }

    private fun digest(algorithm: String, bytes: ByteArray): String {
        val digest = MessageDigest.getInstance(algorithm).digest(bytes)
        return digest.joinToString(separator = ":") { byte ->
            "%02X".format(byte)
        }
    }

    override fun onStop() {
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            serverConnection?.let { conn ->
                unbindService(conn)
                isServerBound = false
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to unbind service", e)
        }
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppViewHolder>() {
        private val items = mutableListOf<InstalledApp>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val itemView =
                LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return AppViewHolder(itemView)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

        @SuppressLint("NotifyDataSetChanged")
        fun submitList(newItems: List<InstalledApp>) {
            items.clear()
            items.addAll(newItems)
            notifyDataSetChanged()
        }

        fun getItem(position: Int): InstalledApp = items[position]
    }

    private inner class AppViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val iconView: ImageView = itemView.findViewById(R.id.app_icon)
        private val nameView: TextView = itemView.findViewById(R.id.app_name)
        private val packageView: TextView = itemView.findViewById(R.id.app_package)
        private val metaView: TextView = itemView.findViewById(R.id.app_meta)

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    showAppDetails(appAdapter.getItem(position))
                }
            }
        }

        fun bind(app: InstalledApp) {
            iconView.setImageDrawable(app.icon)
            nameView.text = app.label
            packageView.text = app.packageName
            metaView.text = getString(
                R.string.app_meta,
                app.versionName.ifBlank { getString(R.string.unknown_value) },
                Formatter.formatFileSize(itemView.context, app.apkSizeBytes),
            )
        }
    }

    private data class InstalledApp(
        val label: String,
        val packageName: String,
        val versionName: String,
        val versionCode: Long,
        val isSystem: Boolean,
        val icon: Drawable,
        val apkPath: String,
        val apkSizeBytes: Long,
        val firstInstallTime: Long,
        val lastUpdateTime: Long,
        val targetSdk: Int,
        val signatureDigests: SignatureDigests,
    )

    private data class SignatureDigests(
        val md5: String,
        val sha1: String,
        val sha256: String,
    )
}
