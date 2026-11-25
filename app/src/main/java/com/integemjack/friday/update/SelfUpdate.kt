package com.integemjack.friday.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内自动更新：GitHub Releases 查最新版本 → 下载 APK（进度）→ 弹系统安装器。
 * 仓库：integemjack/friday.android；CI 每次 push main 自动 bump 版本并发 Release，
 * so releases/latest 永远是最新构建。
 */
object SelfUpdate {
    const val REPO_OWNER = "integemjack"
    const val REPO_NAME = "friday.android"
    private const val API_LATEST = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    private const val PAGE_URL = "https://github.com/$REPO_OWNER/$REPO_NAME/releases/latest"

    data class Release(
        val tag: String,          // v1.0.3
        val version: String,      // 1.0.3
        val notes: String,
        val apkUrl: String,
        val apkSize: Long,
        val htmlUrl: String,
        val publishedAt: String,
    )

    /** 更新状态（界面 banner 用） */
    sealed interface UpdateState {
        data object Idle : UpdateState
        data object Checking : UpdateState
        data class Available(val release: Release) : UpdateState
        data class Downloading(val fraction: Float, val read: Long, val total: Long) : UpdateState
        data class ReadyToInstall(val release: Release, val file: File) : UpdateState
        data class Failed(val message: String) : UpdateState
        data object UpToDate : UpdateState
    }

    val updateState = MutableStateFlow<UpdateState>(UpdateState.Idle)

    /**
     * 查最新 Release；失败返回 null。先问 GitHub 接口（带版本说明、大小）；接口不通——尤其是被限流
     * （403 / 429：不登录时每个出口 IP 每小时只给 60 次，路由器上的代理、公司网这类很多人共用的出口很快就用完，
     * 2026-11-25 小米 17 就这样卡在 1.0.30 半个多月）——改走网页 [fromRedirect]，那个不算接口次数
     */
    suspend fun fetchLatest(): Release? = withContext(Dispatchers.IO) {
        val api = runCatching {
            val conn = URL(API_LATEST).openConnection() as HttpURLConnection
            conn.connectTimeout = 15_000
            conn.readTimeout = 20_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            try {
                if (conn.responseCode == 200) conn.inputStream.bufferedReader().readText() else null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
        // 接口回了 200 就以它为准（安装包还没传上去时是 null：等下一次查，别去下一个还没有的包）
        if (api != null) parseRelease(api) else fromRedirect()
    }

    /**
     * 接口用不了时的退路：网页 releases/latest 会 302 跳到 …/releases/tag/v1.0.38，从跳转地址拿版本；
     * 安装包的名字是 CI 定死的 friday-android-v<版本>.apk（.github/workflows/release.yml）。没有版本说明和大小
     * （下载时按回来的 Content-Length 算进度）
     */
    private fun fromRedirect(): Release? = runCatching {
        val conn = URL(PAGE_URL).openConnection() as HttpURLConnection
        conn.instanceFollowRedirects = false
        conn.connectTimeout = 15_000
        conn.readTimeout = 20_000
        try {
            releaseFromLocation(if (conn.responseCode in 300..399) conn.getHeaderField("Location") else null)
        } finally {
            conn.disconnect()
        }
    }.getOrNull()

    /** 网页 releases/latest 跳去的地址（…/releases/tag/v1.0.38）→ 那个版本；认不出是 null */
    internal fun releaseFromLocation(location: String?): Release? {
        val tag = location?.substringAfter("/releases/tag/", "")
            ?.substringBefore('?')?.substringBefore('#')?.trim('/')
            ?.takeIf { it.length > 1 && it.startsWith("v") && it.drop(1).all { c -> c.isDigit() || c == '.' } }
            ?: return null
        val base = "https://github.com/$REPO_OWNER/$REPO_NAME/releases"
        return Release(
            tag = tag,
            version = tag.removePrefix("v"),
            notes = "",
            apkUrl = "$base/download/$tag/friday-android-$tag.apk",
            apkSize = 0L,
            htmlUrl = "$base/tag/$tag",
            publishedAt = "",
        )
    }

    internal fun parseRelease(body: String): Release? {
        val obj = runCatching { Json.parseToJsonElement(body).jsonObject }.getOrNull() ?: return null
        val tag = obj["tag_name"]?.jsonPrimitive?.contentOrNull ?: return null
        val version = tag.removePrefix("v")
        val apk = obj["assets"]?.jsonArray?.firstOrNull { asset ->
            val name = (asset.jsonObject["name"]?.jsonPrimitive?.contentOrNull) ?: ""
            name.endsWith(".apk")
        } ?: return null
        val apkObj = apk.jsonObject
        return Release(
            tag = tag,
            version = version,
            notes = obj["body"]?.jsonPrimitive?.contentOrNull ?: "",
            apkUrl = apkObj["browser_download_url"]?.jsonPrimitive?.contentOrNull ?: return null,
            apkSize = apkObj["size"]?.jsonPrimitive?.contentOrNull?.toLongOrNull() ?: 0L,
            htmlUrl = obj["html_url"]?.jsonPrimitive?.contentOrNull ?: PAGE_URL,
            publishedAt = obj["published_at"]?.jsonPrimitive?.contentOrNull ?: "",
        )
    }

    /** 语义化版本比较：remote > current ? */
    fun isNewer(current: String, remote: String): Boolean {
        val c = current.split('.').map { it.toIntOrNull() ?: 0 }
        val r = remote.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(c.size, r.size)) {
            val a = c.getOrElse(i) { 0 }
            val b = r.getOrElse(i) { 0 }
            if (b != a) return b > a
        }
        return false
    }

    /**
     * 检查更新（发现新版 → 状态 Available；已经是最新 → UpToDate）。
     * 自动管线入口：App 启动与每 6 小时调一次。
     */
    suspend fun checkForUpdate(currentVersion: String, autoDownload: Boolean = true) {
        updateState.value = UpdateState.Checking
        val release = fetchLatest()
        if (release == null) {
            updateState.value = UpdateState.Failed("检查更新失败（网络或 GitHub API）")
            return
        }
        if (!isNewer(currentVersion, release.version)) {
            updateState.value = UpdateState.UpToDate
            return
        }
        updateState.value = UpdateState.Available(release)
        if (autoDownload) downloadApk(release)
    }

    /** 下载 APK 到 cacheDir，进度实时进 updateState */
    suspend fun downloadApk(release: Release): File? = withContext(Dispatchers.IO) {
        try {
            val dir = File(UpdateContext.cacheDir, "updates").apply { mkdirs() }
            val file = File(dir, "friday-${release.tag}.apk")
            val conn = URL(release.apkUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 20_000
            conn.readTimeout = 60_000
            if (conn.responseCode != 200) {
                updateState.value = UpdateState.Failed("下载失败 HTTP ${conn.responseCode}")
                return@withContext null
            }
            val total = conn.contentLengthLong.let { if (it > 0) it else release.apkSize }
            val input = conn.inputStream
            val tmp = File(file.absolutePath + ".part")
            var read = 0L
            input.use { ins ->
                tmp.outputStream().use { outs ->
                    val buf = ByteArray(64 * 1024)
                    var lastTick = 0L
                    while (true) {
                        val n = ins.read(buf)
                        if (n < 0) break
                        outs.write(buf, 0, n)
                        read += n
                        val now = System.currentTimeMillis()
                        if (now - lastTick > 150) { // 进度节流
                            lastTick = now
                            updateState.value = UpdateState.Downloading(
                                fraction = if (total > 0) read.toFloat() / total else 0f,
                                read = read, total = total,
                            )
                        }
                    }
                }
            }
            conn.disconnect()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            updateState.value = UpdateState.ReadyToInstall(release, file)
            file
        } catch (e: IOException) {
            updateState.value = UpdateState.Failed("下载失败：${e.message}")
            null
        }
    }

    /** 能不能直接发起安装（Android 8+ 要「未知来源/安装未知应用」权限） */
    fun canInstall(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) context.packageManager.canRequestPackageInstalls() else true

    /** 引导用户给本应用开「安装未知应用」 */
    fun unknownSourcesIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
        } else {
            Intent(Settings.ACTION_SECURITY_SETTINGS)
        }

    /** 弹系统安装器安装下载好的 APK */
    fun installApk(context: Context, file: File): Boolean = runCatching {
        val uri: Uri = FileProvider.getUriForFile(context, "${context.packageName}.updatefile", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
        true
    }.getOrDefault(false)
}

/** 下载目录的 Context 注入（避免 SelfUpdate 里持有静态 Context 泄漏，由 App 初始化） */
object UpdateContext {
    lateinit var cacheDir: File
}
