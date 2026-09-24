package com.github.appmanager.ui

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.ClipData
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.text.format.Formatter
import android.util.Log
import android.util.TypedValue
import android.graphics.Typeface
import android.text.TextUtils
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.widget.doAfterTextChanged
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.github.appmanager.HttpFileServerService
import com.github.appmanager.LanAddress
import com.github.appmanager.R
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import java.text.DateFormat
import java.util.Date
import java.util.Locale

/** 「应用」页：已安装应用列表、筛选排序和详情弹层。 */
class AppsPage(
    private val activity: ComponentActivity,
    root: View,
    onServerChipClick: () -> Unit,
) {
    private companion object {
        const val TAG = "AppsPage"

        /**
         * 仅保存在当前应用进程内。页面重建时可直接复用；
         * 应用进程被系统杀死后缓存自然释放，下次启动重新扫描。
         */
        @Volatile
        private var cachedApps: List<InstalledApp>? = null
    }

    private enum class Filter { USER, SYSTEM, ALL }

    private enum class Sort(val labelRes: Int) {
        NAME(R.string.sort_name),
        SIZE(R.string.sort_size),
        UPDATED(R.string.sort_updated),
    }

    private val allApps = mutableListOf<InstalledApp>()
    private val appAdapter = AppAdapter()
    private var pendingExportApp: InstalledApp? = null
    private var filter = Filter.USER
    private val selectableBackground: Int by lazy {
        TypedValue().also {
            activity.theme.resolveAttribute(android.R.attr.selectableItemBackground, it, true)
        }.resourceId
    }
    private var sort = Sort.NAME

    private val appListView: RecyclerView = root.findViewById(R.id.app_list)
    private val emptyView: TextView = root.findViewById(R.id.empty_view)
    private val loadingView: View = root.findViewById(R.id.loading_view)
    private val titleCountView: TextView = root.findViewById(R.id.title_count)
    private val searchInput: EditText = root.findViewById(R.id.search_input)
    private val filterUser: TextView = root.findViewById(R.id.filter_user)
    private val filterSystem: TextView = root.findViewById(R.id.filter_system)
    private val filterAll: TextView = root.findViewById(R.id.filter_all)
    private val sortButton: TextView = root.findViewById(R.id.sort_button)
    private val serverDot: View = root.findViewById(R.id.server_dot)
    private val serverChipText: TextView = root.findViewById(R.id.server_chip_text)

    private val exportApkLauncher = activity.registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/vnd.android.package-archive")
    ) { uri ->
        val app = pendingExportApp
        pendingExportApp = null
        if (uri != null && app != null) {
            exportApk(app, uri)
        }
    }

    init {
        appListView.layoutManager = LinearLayoutManager(activity)
        appListView.adapter = appAdapter

        searchInput.doAfterTextChanged { applyFilters() }
        filterUser.setOnClickListener { selectFilter(Filter.USER) }
        filterSystem.setOnClickListener { selectFilter(Filter.SYSTEM) }
        filterAll.setOnClickListener { selectFilter(Filter.ALL) }
        sortButton.setOnClickListener {
            sort = Sort.entries[(sort.ordinal + 1) % Sort.entries.size]
            applyFilters()
        }
        root.findViewById<View>(R.id.server_chip).setOnClickListener { onServerChipClick() }

        refreshServerState()
        // 加载期间先显示 0，避免筛选芯片是空白气泡；加载完成后由 applyFilters 更新。
        updateFilterBar(userCount = 0, systemCount = 0)
        loadApps()
    }

    fun refreshServerState() {
        val (dotRes, textRes) = when {
            !HttpFileServerService.isRunning -> R.drawable.dot_off to R.string.server_stopped
            LanAddress.localIp(activity) == LanAddress.LOOPBACK ->
                R.drawable.dot_warn to R.string.server_local_only

            else -> R.drawable.dot_online to R.string.server_running
        }
        serverDot.setBackgroundResource(dotRes)
        serverChipText.setText(textRes)
    }

    private fun selectFilter(newFilter: Filter) {
        if (filter == newFilter) return
        filter = newFilter
        applyFilters()
    }

    private fun loadApps() {
        val cached = cachedApps
        if (cached != null) {
            allApps.clear()
            allApps.addAll(cached)
            showLoading(false)
            applyFilters()
            return
        }

        showLoading(true)
        Thread {
            val apps = queryInstalledApps()
            cachedApps = apps
            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                allApps.clear()
                allApps.addAll(apps)
                showLoading(false)
                applyFilters()
            }
        }.start()
    }

    private fun queryInstalledApps(): List<InstalledApp> {
        val packageManager = activity.packageManager
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(
                PackageManager.PackageInfoFlags.of(PackageManager.GET_SIGNING_CERTIFICATES.toLong())
            )
        } else {
            @Suppress("DEPRECATION") packageManager.getInstalledPackages(PackageManager.GET_SIGNATURES)
        }

        return packages.mapNotNull { packageInfo ->
            packageInfo.toInstalledAppOrNull(packageManager)
        }
    }

    private fun PackageInfo.toInstalledAppOrNull(packageManager: PackageManager): InstalledApp? {
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
        val query = searchInput.text.toString().trim().lowercase(Locale.getDefault())
        val matchingApps = allApps.filter { app ->
            query.isBlank() || app.label.lowercase(Locale.getDefault()).contains(query) ||
                app.packageName.lowercase(Locale.getDefault()).contains(query)
        }
        val systemCount = matchingApps.count { it.isSystem }
        val userCount = matchingApps.size - systemCount

        val filteredApps = matchingApps.filter { app ->
            when (filter) {
                Filter.USER -> !app.isSystem
                Filter.SYSTEM -> app.isSystem
                Filter.ALL -> true
            }
        }
        val sortedApps = when (sort) {
            Sort.NAME -> filteredApps.sortedBy { it.label.lowercase(Locale.getDefault()) }
            Sort.SIZE -> filteredApps.sortedByDescending { it.apkSizeBytes }
            Sort.UPDATED -> filteredApps.sortedByDescending { it.lastUpdateTime }
        }

        updateFilterBar(userCount, systemCount)

        appAdapter.submitList(sortedApps)
        appListView.scrollToPosition(0)
        emptyView.isVisible = sortedApps.isEmpty() && !loadingView.isVisible
        titleCountView.text = activity.getString(R.string.app_count, sortedApps.size, allApps.size)
    }

    private fun updateFilterBar(userCount: Int, systemCount: Int) {
        filterUser.text = activity.getString(R.string.filter_user, userCount)
        filterSystem.text = activity.getString(R.string.filter_system, systemCount)
        filterUser.isSelected = filter == Filter.USER
        filterSystem.isSelected = filter == Filter.SYSTEM
        filterAll.isSelected = filter == Filter.ALL
        sortButton.setText(sort.labelRes)
    }

    private fun showLoading(visible: Boolean) {
        loadingView.isVisible = visible
        emptyView.isVisible = !visible && appAdapter.itemCount == 0
    }

    // ---------- 详情弹层 ----------

    private fun showAppDetails(app: InstalledApp) {
        val sheet = LayoutInflater.from(activity).inflate(R.layout.dialog_app_details, null)
        val dialog = Dialog(activity, R.style.BottomSheetDialog)
        dialog.setContentView(sheet)
        dialog.setCanceledOnTouchOutside(true)
        dialog.window?.apply {
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
        }
        val basePaddingBottom = sheet.paddingBottom
        ViewCompat.setOnApplyWindowInsetsListener(sheet) { view, insets ->
            val navBars = insets.getInsets(WindowInsetsCompat.Type.navigationBars())
            view.setPadding(
                view.paddingLeft, view.paddingTop, view.paddingRight,
                basePaddingBottom + navBars.bottom,
            )
            insets
        }

        sheet.findViewById<ImageView>(R.id.detail_icon).setImageDrawable(app.icon)
        sheet.findViewById<TextView>(R.id.detail_name).text = app.label
        sheet.findViewById<TextView>(R.id.detail_package).apply {
            text = app.packageName
            setOnClickListener { activity.copyToClipboard(app.label, app.packageName) }
        }

        sheet.findViewById<View>(R.id.action_open).setOnClickListener {
            dialog.dismiss()
            launchApp(app)
        }
        sheet.findViewById<View>(R.id.action_share).setOnClickListener {
            dialog.dismiss()
            shareApk(app)
        }
        sheet.findViewById<View>(R.id.action_export).setOnClickListener {
            dialog.dismiss()
            pendingExportApp = app
            exportApkLauncher.launch(buildExportFileName(app))
        }
        sheet.findViewById<View>(R.id.action_settings).setOnClickListener {
            dialog.dismiss()
            openAppSettings(app)
        }

        val unknown = activity.getString(R.string.unknown_value)
        val basicGroup = sheet.findViewById<LinearLayout>(R.id.detail_basic_group)
        addDetailRow(
            basicGroup, R.string.detail_version,
            activity.getString(
                R.string.detail_version_value, app.versionName.ifBlank { unknown }, app.versionCode
            ),
        )
        addDetailRow(
            basicGroup, R.string.detail_type,
            activity.getString(if (app.isSystem) R.string.system_app else R.string.user_app),
        )
        addDetailRow(
            basicGroup, R.string.detail_size,
            Formatter.formatFileSize(activity, app.apkSizeBytes),
        )
        addDetailRow(basicGroup, R.string.detail_target_sdk, app.targetSdk.toString())
        addDetailRow(basicGroup, R.string.detail_install_time, formatTime(app.firstInstallTime))
        addDetailRow(basicGroup, R.string.detail_update_time, formatTime(app.lastUpdateTime))
        addDetailRow(basicGroup, R.string.detail_apk_path, app.apkPath, copyable = true)

        val signatureGroup = sheet.findViewById<LinearLayout>(R.id.detail_signature_group)
        addSignatureRow(signatureGroup, "MD5", app.signatureDigests.md5)
        addSignatureRow(signatureGroup, "SHA-1", app.signatureDigests.sha1)
        addSignatureRow(signatureGroup, "SHA-256", app.signatureDigests.sha256)

        dialog.show()
    }

    private fun addDetailRow(
        group: LinearLayout,
        labelRes: Int,
        value: String,
        copyable: Boolean = false,
    ) {
        val row = inflateDetailRow(group)
        val label = activity.getString(labelRes)
        row.findViewById<TextView>(R.id.row_label).text = label
        row.findViewById<TextView>(R.id.row_value).apply {
            text = value
            if (!copyable) {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            }
        }
        if (copyable) {
            makeCopyable(row, label, value)
        }
    }

    private fun addSignatureRow(group: LinearLayout, algorithm: String, value: String) {
        val row = inflateDetailRow(group)
        row.findViewById<TextView>(R.id.row_label).apply {
            text = algorithm
            width = activity.dp(52f)
            setTypeface(typeface, Typeface.BOLD)
            textSize = 10.5f
        }
        row.findViewById<TextView>(R.id.row_value).apply {
            text = value
            gravity = Gravity.START
            typeface = Typeface.MONOSPACE
            textSize = 10.5f
        }
        makeCopyable(row, algorithm, value)
    }

    private fun inflateDetailRow(group: LinearLayout): View {
        if (group.childCount > 0) {
            group.addView(View(activity).apply {
                setBackgroundColor(activity.getColor(R.color.line))
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, activity.dp(1f)))
        }
        return LayoutInflater.from(activity).inflate(R.layout.item_detail_row, group, false)
            .also(group::addView)
    }

    private fun makeCopyable(row: View, label: String, value: String) {
        row.findViewById<View>(R.id.row_copy).isVisible = true
        row.setBackgroundResource(selectableBackground)
        row.setOnClickListener { activity.copyToClipboard(label, value) }
    }

    // ---------- 操作 ----------

    private fun launchApp(app: InstalledApp) {
        val intent = activity.packageManager.getLaunchIntentForPackage(app.packageName)
        if (intent == null) {
            Toast.makeText(activity, R.string.launch_unavailable, Toast.LENGTH_SHORT).show()
            return
        }
        try {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            activity.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to launch ${app.packageName}", e)
            Toast.makeText(activity, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun openAppSettings(app: InstalledApp) {
        try {
            activity.startActivity(
                Intent(
                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                    Uri.fromParts("package", app.packageName, null),
                )
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to open settings for ${app.packageName}", e)
            Toast.makeText(activity, R.string.launch_failed, Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportApk(app: InstalledApp, targetUri: Uri) {
        Thread {
            val result = runCatching {
                require(app.apkPath.isNotBlank()) { activity.getString(R.string.export_source_missing) }
                activity.contentResolver.openOutputStream(targetUri)?.use { outputStream ->
                    FileInputStream(app.apkPath).use { inputStream ->
                        inputStream.copyTo(outputStream)
                    }
                } ?: error(activity.getString(R.string.export_target_unavailable))
            }

            activity.runOnUiThread {
                result.onSuccess {
                    Toast.makeText(
                        activity,
                        activity.getString(R.string.export_success, app.label),
                        Toast.LENGTH_LONG,
                    ).show()
                }.onFailure { throwable ->
                    Toast.makeText(
                        activity,
                        activity.getString(
                            R.string.export_failed,
                            throwable.message ?: activity.getString(R.string.unknown_value),
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun shareApk(app: InstalledApp) {
        Toast.makeText(activity, R.string.preparing_apk_share, Toast.LENGTH_SHORT).show()
        Thread {
            val result = runCatching {
                require(app.apkPath.isNotBlank()) { activity.getString(R.string.export_source_missing) }
                val source = File(app.apkPath)
                require(source.exists()) { activity.getString(R.string.export_source_missing) }

                val shareDir = File(activity.cacheDir, "shared_apks").apply { mkdirs() }
                val target = File(shareDir, buildExportFileName(app))
                source.copyTo(target, overwrite = true)
                FileProvider.getUriForFile(activity, "${activity.packageName}.fileprovider", target)
            }

            activity.runOnUiThread {
                if (activity.isDestroyed) return@runOnUiThread
                result.mapCatching { uri ->
                    val shareIntent = Intent(Intent.ACTION_SEND).apply {
                        type = "application/vnd.android.package-archive"
                        putExtra(Intent.EXTRA_STREAM, uri)
                        clipData = ClipData.newRawUri(activity.getString(R.string.share_apk), uri)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    activity.startActivity(
                        Intent.createChooser(shareIntent, activity.getString(R.string.share_apk_chooser))
                    )
                }.onFailure { throwable ->
                    Toast.makeText(
                        activity,
                        activity.getString(
                            R.string.share_apk_failed,
                            throwable.message ?: activity.getString(R.string.unknown_value),
                        ),
                        Toast.LENGTH_LONG,
                    ).show()
                }
            }
        }.start()
    }

    private fun buildExportFileName(app: InstalledApp): String {
        val versionName = app.versionName.ifBlank { activity.getString(R.string.unknown_value) }
        return "${app.label}-${app.packageName}-${versionName}.apk".replace(
            Regex("[\\\\/:*?\"<>|]"),
            "_"
        )
    }

    private fun formatTime(timeMillis: Long): String {
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            .format(Date(timeMillis))
    }

    private fun buildSignatureDigests(packageInfo: PackageInfo): SignatureDigests {
        val signatures = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            packageInfo.signingInfo?.apkContentsSigners?.map { it.toByteArray() }.orEmpty()
        } else {
            @Suppress("DEPRECATION") packageInfo.signatures?.map { it.toByteArray() }.orEmpty()
        }

        if (signatures.isEmpty()) {
            val unknown = activity.getString(R.string.unknown_value)
            return SignatureDigests(md5 = unknown, sha1 = unknown, sha256 = unknown)
        }

        return SignatureDigests(
            md5 = signatures.joinToString(" | ") { digest("MD5", it) },
            sha1 = signatures.joinToString(" | ") { digest("SHA-1", it) },
            sha256 = signatures.joinToString(" | ") { digest("SHA-256", it) },
        )
    }

    private fun digest(algorithm: String, bytes: ByteArray): String {
        val digest = MessageDigest.getInstance(algorithm).digest(bytes)
        return digest.joinToString(separator = ":") { byte -> "%02X".format(byte) }
    }

    // ---------- 列表 ----------

    private inner class AppAdapter : RecyclerView.Adapter<AppViewHolder>() {
        private val items = mutableListOf<InstalledApp>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val itemView =
                LayoutInflater.from(parent.context).inflate(R.layout.item_app, parent, false)
            return AppViewHolder(itemView)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            holder.bind(items[position], position, items.size)
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
        private val sizeView: TextView = itemView.findViewById(R.id.app_size)
        private val versionView: TextView = itemView.findViewById(R.id.app_version)
        private val dividerView: View = itemView.findViewById(R.id.app_divider)
        private val systemTag: Drawable =
            TagDrawable(itemView.context, itemView.context.getString(R.string.system_tag))

        init {
            itemView.setOnClickListener {
                val position = bindingAdapterPosition
                if (position != RecyclerView.NO_POSITION) {
                    showAppDetails(appAdapter.getItem(position))
                }
            }
        }

        fun bind(app: InstalledApp, position: Int, count: Int) {
            val isFirst = position == 0
            val isLast = position == count - 1
            itemView.setBackgroundResource(
                when {
                    isFirst && isLast -> R.drawable.bg_row_single
                    isFirst -> R.drawable.bg_row_top
                    isLast -> R.drawable.bg_row_bottom
                    else -> R.drawable.bg_row_middle
                }
            )
            dividerView.isVisible = !isFirst

            iconView.setImageDrawable(app.icon)
            nameView.text = app.label
            nameView.setCompoundDrawablesRelativeWithIntrinsicBounds(
                null, null, if (app.isSystem) systemTag else null, null
            )
            packageView.text = app.packageName
            sizeView.text = Formatter.formatShortFileSize(itemView.context, app.apkSizeBytes)
            versionView.text =
                app.versionName.ifBlank { itemView.context.getString(R.string.unknown_value) }
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
