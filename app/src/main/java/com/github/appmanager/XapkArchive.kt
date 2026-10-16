package com.github.appmanager

import java.io.File
import java.io.OutputStream
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** 生成 XAPK 所需的应用元数据，直接取自已安装应用的 PackageInfo。 */
data class XapkMetadata(
    val packageName: String,
    val label: String,
    val versionCode: Long,
    val versionName: String,
    val minSdkVersion: Int,
    val targetSdkVersion: Int,
    val iconPng: ByteArray?,
)

/** 一个待打包的 APK：[id] 为 base 或 split 名称（如 config.arm64_v8a）。 */
data class XapkApk(val file: File, val id: String)

/**
 * 将 base APK 与 split APK 打包成 XAPK（xapk_version 2）写入 [output]。
 * 第一个 APK 必须是 base，其余为 split；返回打包的 APK 数量。
 */
fun writeXapk(metadata: XapkMetadata, apks: List<XapkApk>, output: OutputStream): Int {
    require(apks.isNotEmpty()) { "没有可打包的 APK" }
    apks.forEach { require(it.file.isFile) { "APK 不存在: ${it.file.absolutePath}" } }
    val entryNames = uniqueApkEntryNames(apks)
    val totalSize = apks.sumOf { it.file.length() }
    val manifest = buildXapkManifest(metadata, apks, entryNames, totalSize)

    ZipOutputStream(output.buffered()).use { zip ->
        zip.setLevel(1)
        apks.forEachIndexed { index, apk -> zip.putFile(entryNames[index], apk.file) }
        metadata.iconPng?.let { zip.putBytes("icon.png", it) }
        zip.putBytes("manifest.json", manifest.toByteArray(Charsets.UTF_8))
    }
    return apks.size
}

private fun ZipOutputStream.putFile(entryName: String, file: File) {
    putNextEntry(ZipEntry(entryName))
    file.inputStream().buffered().use { it.copyTo(this) }
    closeEntry()
}

private fun ZipOutputStream.putBytes(entryName: String, bytes: ByteArray) {
    putNextEntry(ZipEntry(entryName))
    write(bytes)
    closeEntry()
}

/** base 固定为 base.apk，split 使用 split_<id>.apk，与常见 XAPK 安装器的约定一致。 */
private fun uniqueApkEntryNames(apks: List<XapkApk>): List<String> {
    val used = hashSetOf<String>()
    return apks.mapIndexed { index, apk ->
        val stem = if (index == 0) "base" else "split_${apk.id}"
        val safe = stem.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".apk"
        if (used.add(safe.lowercase(Locale.ROOT))) safe
        else "${index}_$safe".also { used += it.lowercase(Locale.ROOT) }
    }
}

private fun buildXapkManifest(
    metadata: XapkMetadata,
    apks: List<XapkApk>,
    entryNames: List<String>,
    totalSize: Long,
): String {
    val splitApks = entryNames.mapIndexed { index, name ->
        val id = if (index == 0) "base" else apks[index].id
        "{\"file\":${json(name)},\"id\":${json(id)}}"
    }
    val splitConfigs = apks.drop(1).map { it.id }
    return buildString {
        append('{')
        append("\"xapk_version\":2")
        append(",\"package_name\":").append(json(metadata.packageName))
        append(",\"name\":").append(json(metadata.label))
        append(",\"version_code\":").append(json(metadata.versionCode.toString()))
        append(",\"version_name\":").append(json(metadata.versionName))
        append(",\"min_sdk_version\":").append(json(metadata.minSdkVersion.toString()))
        append(",\"target_sdk_version\":").append(json(metadata.targetSdkVersion.toString()))
        append(",\"split_configs\":[").append(splitConfigs.joinToString(",") { json(it) }).append(']')
        append(",\"total_size\":").append(totalSize)
        if (metadata.iconPng != null) append(",\"icon\":\"icon.png\"")
        append(",\"split_apks\":[").append(splitApks.joinToString(",")).append(']')
        append('}')
    }
}

private fun json(value: String): String = buildString {
    append('"')
    value.forEach { char ->
        when (char) {
            '"' -> append("\\\"")
            '\\' -> append("\\\\")
            '\b' -> append("\\b")
            '\u000C' -> append("\\f")
            '\n' -> append("\\n")
            '\r' -> append("\\r")
            '\t' -> append("\\t")
            else -> if (char.code < 0x20) append("\\u%04x".format(char.code)) else append(char)
        }
    }
    append('"')
}
