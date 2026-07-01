package com.github.appmanager.im

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import java.io.File

/**
 * 文件管理器后端：以外部存储根目录（/sdcard，即 Environment.getExternalStorageDirectory()）
 * 为根，对 Web 端 file.html 暴露增删改查。所有路径都做 canonical 校验，确保解析后的目标
 * 仍在根目录树内，避免 ../ 逃逸。
 *
 * 需要 MANAGE_EXTERNAL_STORAGE（isExternalStorageManager）才能真正读写；未授权时所有接口
 * 返回 PERMISSION_DENIED，由前端引导用户去原生侧授权。
 */
class FileService(private val context: Context) {
    companion object {
        private const val TAG = "FileService"
        const val CODE_PERMISSION_DENIED = "PERMISSION_DENIED"
        const val CODE_INVALID_PATH = "INVALID_PATH"
        const val CODE_NOT_FOUND = "NOT_FOUND"
        const val CODE_EXISTS = "EXISTS"
        const val CODE_IO_ERROR = "IO_ERROR"
    }

    val rootDir: File by lazy {
        Environment.getExternalStorageDirectory()
    }

    fun isPermissionGranted(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            // 低于 R：MANAGE_EXTERNAL_STORAGE 不存在，退回普通读写权限
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    /**
     * 跳转到系统「所有文件访问」授权页（API 30+），低版本跳应用详情页兜底。
     * 由 Service 调用，需 FLAG_ACTIVITY_NEW_TASK。已授权则不跳。
     */
    fun requestPermission() {
        if (isPermissionGranted()) return
        try {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            } else {
                // 低版本用普通读写权限，这里直接跳应用详情页便于用户手动给权限
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "requestPermission failed", e)
        }
    }

    /** 把相对路径拼到根，做 canonical 前缀校验。失败或越界返回 null。 */
    private fun resolve(rel: String): File? {
        return try {
            val relTrim = rel.trim().trim('/').replace("\\", "/")
            val root = rootDir.canonicalFile
            val target = if (relTrim.isEmpty()) root else File(root, relTrim).canonicalFile
            val rootPath = root.absolutePath
            val targetPath = target.absolutePath
            // 必须等于根或是根的子路径（带分隔符），防止 /sdcardX 之类前缀假匹配
            if (targetPath == rootPath || targetPath.startsWith(rootPath + File.separator)) {
                target
            } else {
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "resolve failed for rel=$rel", e)
            null
        }
    }

    /** 列目录。返回 Pair(cwd相对路径, 条目列表)；无权限返回 null。 */
    fun list(rel: String): Pair<String, List<FileEntry>>? {
        if (!isPermissionGranted()) return null
        val dir = resolve(rel) ?: return Pair("", emptyList())
        if (!dir.exists() || !dir.isDirectory) return null
        val cwdRel = relativeToRoot(dir)
        val entries = dir.listFiles()?.map { f ->
            FileEntry(
                name = f.name,
                isDir = f.isDirectory,
                size = if (f.isFile) f.length() else 0L,
                modified = f.lastModified()
            )
        }?.sortedWith(compareByDescending<FileEntry> { it.isDir }.thenBy { it.name.lowercase() })
            ?: emptyList()
        return Pair(cwdRel, entries)
    }

    fun upload(rel: String, name: String, dataUrl: String): FileOpResponse {
        if (!isPermissionGranted()) return permDenied()
        val dir = resolve(rel) ?: return invalidPath()
        if (!dir.exists() || !dir.isDirectory) return notFound()
        val cleanName = sanitizeName(name) ?: return invalidPath()
        val dest = File(dir, cleanName)

        return try {
            val payload = stripDataUrlPrefix(dataUrl)
            val bytes = Base64.decode(payload, Base64.DEFAULT)
            dest.outputStream().use { it.write(bytes) }
            FileOpResponse(success = true, path = relativeToRoot(dest))
        } catch (e: Exception) {
            Log.e(TAG, "upload failed", e)
            FileOpResponse(success = false, code = CODE_IO_ERROR, message = e.message)
        }
    }

    fun delete(rel: String, name: String, isDir: Boolean): FileOpResponse {
        if (!isPermissionGranted()) return permDenied()
        val dir = resolve(rel) ?: return invalidPath()
        val cleanName = sanitizeName(name) ?: return invalidPath()
        val target = File(dir, cleanName)
        if (!target.exists()) return notFound()
        // 再次校验 target 自身没逃逸
        if (resolve(relativeToRoot(target)) == null) return invalidPath()

        return try {
            val ok = if (target.isDirectory) target.deleteRecursively() else target.delete()
            if (ok) FileOpResponse(success = true, path = relativeToRoot(target))
            else FileOpResponse(success = false, code = CODE_IO_ERROR, message = "delete returned false")
        } catch (e: Exception) {
            Log.e(TAG, "delete failed", e)
            FileOpResponse(success = false, code = CODE_IO_ERROR, message = e.message)
        }
    }

    fun rename(rel: String, oldName: String, newName: String): FileOpResponse {
        if (!isPermissionGranted()) return permDenied()
        val dir = resolve(rel) ?: return invalidPath()
        val cleanOld = sanitizeName(oldName) ?: return invalidPath()
        val cleanNew = sanitizeName(newName) ?: return invalidPath()
        val src = File(dir, cleanOld)
        val dst = File(dir, cleanNew)
        if (!src.exists()) return notFound()
        if (dst.exists()) return FileOpResponse(success = false, code = CODE_EXISTS, message = "目标已存在")
        // 校验 dst 没逃逸
        if (resolve(relativeToRoot(dst)) == null) return invalidPath()

        return try {
            val ok = src.renameTo(dst)
            if (ok) FileOpResponse(success = true, path = relativeToRoot(dst))
            else FileOpResponse(success = false, code = CODE_IO_ERROR, message = "rename returned false")
        } catch (e: Exception) {
            Log.e(TAG, "rename failed", e)
            FileOpResponse(success = false, code = CODE_IO_ERROR, message = e.message)
        }
    }

    fun mkdir(rel: String, name: String): FileOpResponse {
        if (!isPermissionGranted()) return permDenied()
        val dir = resolve(rel) ?: return invalidPath()
        val cleanName = sanitizeName(name) ?: return invalidPath()
        val target = File(dir, cleanName)
        if (target.exists()) return FileOpResponse(success = false, code = CODE_EXISTS, message = "已存在")
        if (resolve(relativeToRoot(target)) == null) return invalidPath()

        return try {
            val ok = target.mkdirs()
            if (ok) FileOpResponse(success = true, path = relativeToRoot(target))
            else FileOpResponse(success = false, code = CODE_IO_ERROR, message = "mkdirs returned false")
        } catch (e: Exception) {
            Log.e(TAG, "mkdir failed", e)
            FileOpResponse(success = false, code = CODE_IO_ERROR, message = e.message)
        }
    }

    /** 供下载用：解析相对路径到 File，带权限与穿越校验。不存在或为目录返回 null。 */
    fun resolveForDownload(rel: String): File? {
        if (!isPermissionGranted()) return null
        val file = resolve(rel) ?: return null
        if (!file.exists() || !file.isFile) return null
        return file
    }

    private fun relativeToRoot(file: File): String {
        return try {
            val rootPath = rootDir.canonicalFile.absolutePath
            val p = file.canonicalFile.absolutePath
            if (p == rootPath) ""
            else p.removePrefix(rootPath + File.separator).replace(File.separator, "/")
        } catch (e: Exception) {
            ""
        }
    }

    private fun sanitizeName(name: String): String? {
        val trimmed = name.trim().substringAfterLast('/').substringAfterLast('\\').trim()
        if (trimmed.isEmpty() || trimmed == "." || trimmed == "..") return null
        // 禁止路径分隔符与非法字符
        if (trimmed.contains('/') || trimmed.contains('\\')) return null
        return trimmed.replace(Regex("[\\u0000-\\u001F]"), "_")
    }

    private fun stripDataUrlPrefix(data: String): String {
        val comma = data.indexOf(',')
        return if (data.startsWith("data:") && comma >= 0) data.substring(comma + 1) else data
    }

    private fun permDenied() = FileOpResponse(success = false, code = CODE_PERMISSION_DENIED, message = "需要所有文件访问权限")
    private fun invalidPath() = FileOpResponse(success = false, code = CODE_INVALID_PATH, message = "非法路径")
    private fun notFound() = FileOpResponse(success = false, code = CODE_NOT_FOUND, message = "不存在")
}
