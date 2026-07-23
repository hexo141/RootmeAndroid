package com.hexo141.rootmeandroid

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.yield
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

/// GitHub Release 信息（仅保留更新检查所需的字段）
data class ReleaseInfo(
    val tagName: String,
    val apkName: String,
    val apkUrl: String,
    val publishedAt: String
)

/// 后台请求 GitHub latest release 接口并解析出 APK 下载信息。
/// 仅挑选名为 "${tag_name}.apk" 的资产（CI workflow 重命名后的产物），
/// 旧的 "app-release.apk" 会被忽略。
suspend fun checkLatestRelease(repo: String = "hexo141/RootmeAndroid"): ReleaseInfo? =
    withContext(Dispatchers.IO) {
        runCatching {
            val conn = (URL("https://api.github.com/repos/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
                connectTimeout = 10000
                readTimeout = 10000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            conn.inputStream.use { stream ->
                val text = BufferedReader(InputStreamReader(stream)).readText()
                val json = JSONObject(text)
                val tag = json.optString("tag_name")
                if (tag.isEmpty()) return@runCatching null

                val assets = json.optJSONArray("assets") ?: return@runCatching null
                var picked: JSONObject? = null
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name == "$tag.apk") {
                        picked = a
                        break
                    }
                }
                // 回退：选第一个 v*.apk
                if (picked == null) {
                    for (i in 0 until assets.length()) {
                        val a = assets.optJSONObject(i) ?: continue
                        val name = a.optString("name")
                        if (name.endsWith(".apk") && name.startsWith("v")) {
                            picked = a
                            break
                        }
                    }
                }
                if (picked == null) return@runCatching null
                ReleaseInfo(
                    tagName = tag,
                    apkName = picked.optString("name"),
                    apkUrl = picked.optString("browser_download_url"),
                    publishedAt = json.optString("published_at")
                )
            }
        }.getOrNull()
    }

/// 比较版本号：仅比较去除 'v' 前缀后的数字部分。
/// current 形如 "1.0"（本地默认）或 "v20260723"（CI 产物）；remote 形如 "v20260723"。
/// remote 数字更大则返回 true。
fun isVersionNewer(current: String, remote: String): Boolean {
    val remoteDigits = remote.trimStart('v', 'V').filter { it.isDigit() }
    val currentDigits = current.trimStart('v', 'V').filter { it.isDigit() }
    val remoteNum = remoteDigits.toLongOrNull() ?: return false
    val currentNum = currentDigits.toLongOrNull() ?: return true
    return remoteNum > currentNum
}

/// 下载 APK 到目标文件，支持断点续传（暂停后重新调用会从已下载位置继续）。
/// 期间回调进度 0f..1f（若服务端未返回 Content-Length 则回调 -1f）。
/// 通过 yield() 确保协程可在读取间隙被取消（暂停）。
suspend fun downloadApkResumable(url: String, destFile: File, onProgress: (Float) -> Unit): File =
    withContext(Dispatchers.IO) {
        // 断点续传：如果文件已存在则从当前大小继续
        val existingSize = if (destFile.exists()) destFile.length() else 0L
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15000
            readTimeout = 60000
            requestMethod = "GET"
            instanceFollowRedirects = true
            if (existingSize > 0) {
                setRequestProperty("Range", "bytes=$existingSize-")
            }
        }
        val isResuming = conn.responseCode == HttpURLConnection.HTTP_PARTIAL
        val contentLength = conn.contentLengthLong
        val total = if (isResuming) existingSize + contentLength else contentLength

        conn.inputStream.use { input ->
            FileOutputStream(destFile, isResuming).use { output ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var downloaded = if (isResuming) existingSize else 0L
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    downloaded += bytesRead
                    onProgress(if (total > 0) downloaded.toFloat() / total else -1f)
                    // 允许协程在此时被取消（暂停）
                    yield()
                }
            }
        }
        destFile
    }

/// 触发系统 APK 安装界面（通过 FileProvider 暴露 cacheDir 中的 APK）。
fun installApk(context: Context, apkFile: File) {
    val authority = "${context.packageName}.fileprovider"
    val uri: Uri = FileProvider.getUriForFile(context, authority, apkFile)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/vnd.android.package-archive")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
    context.startActivity(intent)
}
