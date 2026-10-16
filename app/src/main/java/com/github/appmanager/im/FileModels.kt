package com.github.appmanager.im

import kotlinx.serialization.Serializable

/** 文件管理器列表条目：name 文件名，isDir 是否目录，size 字节数（目录为 0），modified 修改时间戳。 */
@Serializable
data class FileEntry(
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long
)

/** 列目录响应。失败时 success=false 且 code 给出原因。 */
@Serializable
data class FileListResponse(
    val success: Boolean,
    val cwd: String,
    val entries: List<FileEntry>,
    val code: String? = null,
    val message: String? = null
)

/** 文件操作（上传/删除/重命名/新建目录）通用响应。 */
@Serializable
data class FileOpResponse(
    val success: Boolean,
    val path: String? = null,
    val code: String? = null,
    val message: String? = null
)

@Serializable
data class FileUploadRequest(
    val dir: String = "",
    val name: String,
    val data: String
)

@Serializable
data class FileDeleteRequest(
    val dir: String = "",
    val name: String,
    val isDir: Boolean = false
)

@Serializable
data class FileRenameRequest(
    val dir: String = "",
    val oldName: String,
    val newName: String
)

@Serializable
data class FileMkdirRequest(
    val dir: String = "",
    val name: String
)

/** 文件/目录属性。childCount 仅目录有效；mimeType 由 HTTP 层按扩展名推断后填入。 */
@Serializable
data class FileInfo(
    val name: String,
    val path: String,
    val absolutePath: String,
    val isDir: Boolean,
    val size: Long,
    val modified: Long,
    val readable: Boolean,
    val writable: Boolean,
    val hidden: Boolean,
    val childCount: Int? = null,
    val mimeType: String? = null
)

@Serializable
data class FileInfoResponse(
    val success: Boolean,
    val info: FileInfo? = null,
    val code: String? = null,
    val message: String? = null
)

/** 文件摘要（小写十六进制），用于比对文件一致性。 */
@Serializable
data class FileHashResponse(
    val success: Boolean,
    val size: Long = 0,
    val md5: String? = null,
    val sha1: String? = null,
    val sha256: String? = null,
    val crc32: String? = null,
    val elapsedMs: Long = 0,
    val code: String? = null,
    val message: String? = null
)
