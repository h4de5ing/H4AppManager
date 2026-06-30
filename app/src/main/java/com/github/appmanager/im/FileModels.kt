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
