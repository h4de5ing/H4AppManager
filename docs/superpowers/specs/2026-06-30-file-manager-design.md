# Web 文件管理器 (file.html) 设计

日期: 2026-06-30
状态: 已批准,待实现

## 背景与目标

IM 聊天功能已基本完成。现在将 Web 端的文件共享功能从聊天中分离出来,做成一个独立的 Web 版文件管理器 `file.html`,对远端(sdcard)文件进行增删改查。

## 范围

### 包含
- 新增 `file.html`(assets 内单文件 SPA),支持对 `/sdcard` 的列目录、下载、上传、删除、重命名、新建文件夹、多级目录导航。
- 新增 `FileService.kt`,承载所有 sdcard 文件操作与路径穿越防护。
- 扩展 `HttpFileServerService.kt` 路由,新增文件管理 REST 接口(均 POST + JSON)。
- 新增 `MANAGE_EXTERNAL_STORAGE` 权限声明及授权引导。
- 删除 `WebImActivity` 顶部上传按钮(`file_button`)。
- 删除 `index.html`(网页聊天)的上传按钮,文件共享完全转移到 `file.html`。

### 不包含
- 不修改聊天业务逻辑;聊天附件仍走内部 `chat_files`,与文件管理器并存。
- 不新增独立服务/端口;复用现有 `:8080` HTTP 服务器。
- 不改 WebSocket 协议;文件管理为纯 REST。

## 设计 §1 — 存储与权限

- **共享根目录**: `Environment.getExternalStorageDirectory()`,即 `/sdcard`。不建二级目录。
- **权限**:
  - `AndroidManifest.xml` 已有 `READ/WRITE_EXTERNAL_STORAGE`(旧版,保留)。
  - 新增 `MANAGE_EXTERNAL_STORAGE` 声明。
  - `FileService` 每次操作前检测 `Environment.isExternalStorageManager()`;未授权时,文件管理接口返回 `{success:false, code:"PERMISSION_DENIED"}`,`file.html` 据此显示授权提示页。
  - 授权引导:由原生侧提供入口(如 `MainActivity` 或 `WebImActivity` 增加一个「授权文件权限」入口),通过 `Intent.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION` 跳转系统设置。Web 端无法直接发起该 Intent,故提示用户去原生侧授权。
- **路径穿越防护**: 所有文件操作在 `FileService.resolve()` 中做 canonical path 校验,确保解析后路径仍以 `/sdcard` 的 canonicalPath 为前缀,否则拒绝(`../` 逃逸防护)。沿用并扩展现有 `ChatService.getSharedFile` 的思路。

## 设计 §2 — HTTP 路由 (REST over POST)

在 `HttpFileServerService.kt` 的 `:8080` 路由分发里新增。服务器只放行 GET/POST/OPTIONS,所有写操作统一 POST + JSON。`dir` 表示相对 `/sdcard` 的子路径(根目录传空串或 `/`)。

| 方法 | 路径 | 请求 body | 返回 |
|---|---|---|---|
| GET | `/api/files/list?dir=<rel>` | — | `{success, entries:[{name,type,size,modified,isDir}], cwd}` |
| POST | `/api/files/upload` | `{dir, name, data(base64 dataURL)}` | `{success, path}` |
| POST | `/api/files/delete` | `{dir, name, isDir}` | `{success}` |
| POST | `/api/files/rename` | `{dir, oldName, newName}` | `{success, path}` |
| POST | `/api/files/mkdir` | `{dir, name}` | `{success, path}` |
| GET | `/api/files/download?path=<rel>` | — | 文件流 + `Content-Disposition: attachment` |
| GET | `/file.html` | — | `serveAsset("file.html")` |

说明:
- **下载走新路径 `/api/files/download?path=<rel>`**,不改造现有 `/files/<name>`。这样聊天附件路由(`/files/` → `chat_files`)保持不变,两者隔离,避免冲突。
- `/api/info` 已返回 baseUrl,`file.html` 复用。

## 设计 §3 — 服务端组件

新增 `FileService.kt`(包 `com.github.appmanager.im`,与 `ChatService` 同层):

- **`rootDir: File`** — `Environment.getExternalStorageDirectory()`,懒加载,不创建子目录。
- **`isPermissionGranted(): Boolean`** — `Environment.isExternalStorageManager()`。
- **`resolve(rel: String): File?`** — 拼接相对路径到 root,canonical 校验前缀,失败返回 null。
- **`list(rel): List<Entry>?`** — 返回 `Entry(name, isDir, size, modified)`。空目录返回空列表;路径不存在返回 null(路由回 404)。
- **`upload(rel, name, base64Data) / delete(rel, name, isDir) / rename(rel, old, new) / mkdir(rel, name)`** — 写操作,先 `resolve` 再校验;递归删除用 `walkBottomUp().forEach { it.delete() }`。
- **`serveFile(rel, socket)`** — 从 root 解析任意相对路径,带穿越防护,MIME 类型复用现有 `getMimeType()`。

`HttpFileServerService.kt` 路由分发加新分支,转发到 `FileService`。原有聊天路由 `/api/upload`、`/files/`(聊天附件)**保留不动**,聊天附件仍走内部 `chat_files`,文件管理器走 `/sdcard`,两者并存。

`Entry` 数据类:`data class Entry(val name: String, val isDir: Boolean, val size: Long, val modified: Long)`,JSON 序列化沿用 `ChatSerializer` 的手动转义风格或新增同类辅助。

## 设计 §4 — file.html 前端

单文件 SPA,放进 `app/src/main/assets/file.html`,无外部依赖(纯原生 JS + 内联 CSS,与 `index.html` 风格一致)。

- **布局**: 顶部工具栏(当前路径面包屑 + 「返回上级」按钮 + 「新建文件夹」按钮 + 「上传」按钮),下方文件/文件夹列表表格(图标 + 名称 + 大小 + 修改时间 + 操作列[下载/重命名/删除])。
- **状态**: `cwd` 为相对 `/sdcard` 的当前路径(根目录为空串)。每次操作后重新调 `/api/files/list?dir=<cwd>` 刷新列表。
- **导航**: 点击文件夹 → `cwd` 追加目录名重新列目录;面包屑点击任一段 → 跳到对应层级;「返回上级」→ `cwd` 去掉最后一段。
- **上传**: `<input type="file">` 读为 base64 dataURL,POST 到 `/api/files/upload` 带 `dir=cwd`;上传中显示进度提示,完成后刷新。
- **下载**: `<a href="/api/files/download?path=<relPath>" download>` 走 GET,浏览器自带下载。
- **重命名/新建文件夹**: `prompt()` 收集新名,调对应接口。
- **删除**: `confirm()` 二次确认后调 `/api/files/delete`。
- **错误处理**: 接口返回 `{success:false}` 或 HTTP 非 200,顶部显示红色提示条。
- **服务地址**: 启动时 `GET /api/info` 拿 baseUrl(同源可直接相对路径,沿用 index.html 模式)。
- **权限提示**: `list` 返回 `{success:false, code:"PERMISSION_DENIED"}` 时,显示「需要所有文件访问权限」提示 + 引导去原生侧授权。

## 设计 §5 — 入口与聊天端改动

- `WebImActivity.kt`: 删除顶部 `file_button`(ImageButton)及其点击监听、`pickFileLauncher`、`handleFileSelected` 相关逻辑。
- `activity_web_im.xml`: 删除 `file_button` 节点。
- `index.html`: 删除上传按钮(`btn-file`)、`fileInput`、`handleFileSelect` 及上传相关 JS。文件共享完全转移至 `file.html`。
- `file.html` 入口: 手动访问 `http://<ip>:8080/file.html`。无原生按钮跳转。

## 错误处理

- 路径不存在: list 返回 404 + `{success:false, code:"NOT_FOUND"}`。
- 路径穿越: 所有写/读操作返回 400 + `{success:false, code:"INVALID_PATH"}`。
- 权限未授予: 返回 403 + `{success:false, code:"PERMISSION_DENIED"}`。
- 上传/删除/重命名/新建失败: 返回 500 + `{success:false, code:"IO_ERROR", message}`。
- `file.html` 统一在顶部提示条展示错误。

## 测试

- 手动验证:
  1. 未授权时访问 `file.html` 显示权限提示;授权后能列目录。
  2. 根目录列 `/sdcard` 内容;进入子目录、面包屑跳转、返回上级正常。
  3. 上传文件后出现在列表;下载文件内容一致。
  4. 新建文件夹、重命名、删除(含非空文件夹递归删除)生效。
  5. 路径穿越(`/api/files/list?dir=../`)被拒。
  6. 聊天附件上传/下载(`/api/upload`、`/files/`)仍正常,未受影响。
  7. `WebImActivity` 与 `index.html` 上传按钮已移除,聊天收发文本正常。
