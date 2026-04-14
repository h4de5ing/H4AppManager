package com.github.appmanager

import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.isVisible
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import java.io.File
import java.io.FileInputStream
import java.text.DateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {
    private val allApps = mutableListOf<InstalledApp>()
    private val appAdapter = AppAdapter()
    private var pendingExportApp: InstalledApp? = null

    private lateinit var appListView: RecyclerView
    private lateinit var emptyView: TextView
    private lateinit var loadingView: View
    private lateinit var showSystemAppsSwitch: SwitchMaterial

    private val exportApkLauncher =
        registerForActivityResult(
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

        val toolbar = findViewById<MaterialToolbar>(R.id.toolbar)
        appListView = findViewById(R.id.app_list)
        emptyView = findViewById(R.id.empty_view)
        loadingView = findViewById(R.id.loading_view)
        showSystemAppsSwitch = findViewById(R.id.show_system_apps_switch)

        setSupportActionBar(toolbar)
        supportActionBar?.setTitle(R.string.app_name)

        appListView.layoutManager = LinearLayoutManager(this)
        appListView.adapter = appAdapter

        showSystemAppsSwitch.setOnCheckedChangeListener { _, _ ->
            applyFilter()
        }

        loadApps()
    }

    private fun loadApps() {
        showLoading(true)
        Thread {
            val apps = queryInstalledApps()
            runOnUiThread {
                allApps.clear()
                allApps.addAll(apps)
                applyFilter()
                showLoading(false)
            }
        }.start()
    }

    private fun queryInstalledApps(): List<InstalledApp> {
        val packages = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            packageManager.getInstalledPackages(PackageManager.PackageInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            packageManager.getInstalledPackages(0)
        }

        return packages.mapNotNull { packageInfo ->
            packageInfo.toInstalledAppOrNull()
        }.sortedBy { it.label.lowercase(Locale.getDefault()) }
    }

    private fun PackageInfo.toInstalledAppOrNull(): InstalledApp? {
        val appInfo = applicationInfo ?: return null
        val label = appInfo.loadLabel(packageManager).toString()
        val apkPath = appInfo.sourceDir.orEmpty()
        val apkFile = apkPath.takeIf { it.isNotBlank() }?.let(::File)

        return InstalledApp(
            label = label,
            packageName = this.packageName,
            versionName = versionName.orEmpty(),
            versionCode = PackageInfoCompat.getLongVersionCode(this),
            isSystem = appInfo.flags and ApplicationInfo.FLAG_SYSTEM != 0,
            icon = appInfo.loadIcon(packageManager),
            apkPath = apkPath,
            apkSizeBytes = apkFile?.takeIf(File::exists)?.length() ?: 0L,
            firstInstallTime = firstInstallTime,
            lastUpdateTime = lastUpdateTime,
            targetSdk = appInfo.targetSdkVersion,
        )
    }

    private fun applyFilter() {
        val filteredApps = if (showSystemAppsSwitch.isChecked) {
            allApps
        } else {
            allApps.filterNot { it.isSystem }
        }

        appAdapter.submitList(filteredApps)
        emptyView.isVisible = filteredApps.isEmpty() && !loadingView.isVisible
        supportActionBar?.subtitle =
            getString(R.string.app_count, filteredApps.size, allApps.size)
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
        )

        MaterialAlertDialogBuilder(this)
            .setTitle(app.label)
            .setIcon(app.icon)
            .setMessage(details)
            .setPositiveButton(R.string.export_apk) { _, _ ->
                pendingExportApp = app
                exportApkLauncher.launch(buildExportFileName(app))
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
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

    private fun buildExportFileName(app: InstalledApp): String {
        return "${app.label}-${app.packageName}.apk"
            .replace(Regex("[\\\\/:*?\"<>|]"), "_")
    }

    private fun formatTime(timeMillis: Long): String {
        return DateFormat.getDateTimeInstance().format(Date(timeMillis))
    }

    private inner class AppAdapter : RecyclerView.Adapter<AppViewHolder>() {
        private val items = mutableListOf<InstalledApp>()

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): AppViewHolder {
            val itemView = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app, parent, false)
            return AppViewHolder(itemView)
        }

        override fun onBindViewHolder(holder: AppViewHolder, position: Int) {
            holder.bind(items[position])
        }

        override fun getItemCount(): Int = items.size

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
                getString(if (app.isSystem) R.string.system_app else R.string.user_app),
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
    )
}
