package com.lantaservice.intra.data.update

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.core.content.FileProvider
import com.lantaservice.intra.BuildConfig
import com.lantaservice.intra.data.network.IntraResilientDns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

data class AppUpdateInfo(
    val versionName: String,
    val tagName: String,
    val title: String,
    val changelog: String,
    val downloadUrl: String,
    val sizeBytes: Long = 0L
)

sealed interface UpdateUiState {
    data object Idle : UpdateUiState
    data class Available(val info: AppUpdateInfo) : UpdateUiState
    data class Downloading(
        val info: AppUpdateInfo,
        val progress: Float,
        val downloadedBytes: Long,
        val totalBytes: Long
    ) : UpdateUiState
    data class ReadyToInstall(val info: AppUpdateInfo, val apkFile: File) : UpdateUiState
    data class Error(val info: AppUpdateInfo, val message: String) : UpdateUiState
}

/**
 * Background GitHub Releases update checker and in-app APK downloader/installer.
 * Never attaches IntraService Authorization credentials to GitHub requests.
 * Automatically falls back to GitHub HTML release endpoints if api.github.com is rate-limited.
 */
class AppUpdateManager(
    private val context: Context,
    resilientDns: IntraResilientDns
) {

    private val githubClient: OkHttpClient = OkHttpClient.Builder()
        .dns(resilientDns)
        .followRedirects(true)
        .followSslRedirects(true)
        .connectTimeout(8, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<UpdateUiState>(UpdateUiState.Idle)
    val state: StateFlow<UpdateUiState> = _state.asStateFlow()

    private val _isDialogDismissed = MutableStateFlow(false)
    val isDialogDismissed: StateFlow<Boolean> = _isDialogDismissed.asStateFlow()

    private var pendingInstallApk: File? = null

    suspend fun checkForUpdateInBackground(
        currentVersion: String = BuildConfig.VERSION_NAME,
        repo: String = BuildConfig.GITHUB_REPO
    ) = withContext(Dispatchers.IO) {
        val found = runCatching { fetchLatestRelease(repo, currentVersion) }.getOrNull()
        if (found != null && compareVersions(found.versionName, currentVersion) > 0) {
            _isDialogDismissed.value = false
            _state.value = UpdateUiState.Available(found)
        }
    }

    fun dismissDialog() {
        _isDialogDismissed.value = true
    }

    fun reopenDialog() {
        _isDialogDismissed.value = false
    }

    suspend fun downloadUpdate(info: AppUpdateInfo) = withContext(Dispatchers.IO) {
        try {
            _state.value = UpdateUiState.Downloading(
                info = info,
                progress = 0f,
                downloadedBytes = 0L,
                totalBytes = info.sizeBytes
            )
            val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
            val safeVer = info.versionName.replace(Regex("[^0-9A-Za-z._-]"), "_")
            val targetFile = File(updatesDir, "IntraService-Mobile-$safeVer.apk")
            if (targetFile.exists()) targetFile.delete()

            val req = Request.Builder()
                .url(info.downloadUrl)
                .header("User-Agent", "IntraService-Mobile/${BuildConfig.VERSION_NAME}")
                .get()
                .build()

            githubClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    throw IllegalStateException("Ошибка скачивания (HTTP ${resp.code})")
                }
                val body = resp.body ?: throw IllegalStateException("Пустой ответ при загрузке APK")
                val total = body.contentLength().takeIf { it > 0L } ?: info.sizeBytes
                var downloaded = 0L
                val buf = ByteArray(16 * 1024)

                body.byteStream().use { input ->
                    FileOutputStream(targetFile).use { output ->
                        while (true) {
                            val read = input.read(buf)
                            if (read <= 0) break
                            output.write(buf, 0, read)
                            downloaded += read
                            val frac = if (total > 0L) {
                                (downloaded.toFloat() / total.toFloat()).coerceIn(0f, 1f)
                            } else {
                                -1f
                            }
                            _state.value = UpdateUiState.Downloading(
                                info = info,
                                progress = frac,
                                downloadedBytes = downloaded,
                                totalBytes = total
                            )
                        }
                    }
                }
            }

            if (!isValidApkZipHeader(targetFile)) {
                runCatching { targetFile.delete() }
                throw IllegalStateException("Загруженный файл поврежден или не является APK")
            }

            _state.value = UpdateUiState.ReadyToInstall(info, targetFile)
        } catch (t: Throwable) {
            _state.value = UpdateUiState.Error(
                info = info,
                message = t.message?.takeIf { it.isNotBlank() } ?: "Не удалось загрузить обновление"
            )
        }
    }

    fun launchInstaller(apkFile: File): Boolean {
        if (!apkFile.exists()) return false
        if (!context.packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = apkFile
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            runCatching { context.startActivity(permIntent) }
            return false
        }
        pendingInstallApk = null
        val contentUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(contentUri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(installIntent)
        return true
    }

    fun resumePendingInstallIfPermitted() {
        val pending = pendingInstallApk ?: return
        if (context.packageManager.canRequestPackageInstalls()) {
            launchInstaller(pending)
        }
    }

    private fun fetchLatestRelease(repo: String, currentVersion: String): AppUpdateInfo? {
        // 1. Try GitHub Releases API
        val apiReq = Request.Builder()
            .url("https://api.github.com/repos/$repo/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "IntraService-Mobile/$currentVersion")
            .get()
            .build()

        val apiResult = runCatching {
            githubClient.newCall(apiReq).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string().orEmpty()
                    parseReleaseApiJson(body)
                } else {
                    null
                }
            }
        }.getOrNull()
        if (apiResult != null) return apiResult

        // 2. Rate-limit-proof fallback via GitHub releases HTML redirect & expanded_assets
        val latestPageReq = Request.Builder()
            .url("https://github.com/$repo/releases/latest")
            .header("User-Agent", "IntraService-Mobile/$currentVersion")
            .get()
            .build()

        val resolvedTag = runCatching {
            githubClient.newCall(latestPageReq).execute().use { resp ->
                val finalUrl = resp.request.url.toString()
                val marker = "/releases/tag/"
                val idx = finalUrl.indexOf(marker)
                if (idx >= 0) {
                    finalUrl.substring(idx + marker.length).substringBefore('?').substringBefore('#').trim('/')
                } else {
                    null
                }
            }
        }.getOrNull() ?: return null

        if (resolvedTag.isBlank()) return null

        val assetsUrl = "https://github.com/$repo/releases/expanded_assets/$resolvedTag"
        val html = runCatching {
            githubClient.newCall(
                Request.Builder()
                    .url(assetsUrl)
                    .header("User-Agent", "IntraService-Mobile/$currentVersion")
                    .get()
                    .build()
            ).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string().orEmpty() else ""
            }
        }.getOrDefault("")

        return parseExpandedAssetsHtml(html, resolvedTag, repo)
    }

    private fun isValidApkZipHeader(file: File): Boolean {
        if (!file.exists() || file.length() < 1024L) return false
        return runCatching {
            file.inputStream().use { s ->
                val header = ByteArray(4)
                val n = s.read(header)
                n == 4 &&
                    header[0] == 0x50.toByte() &&
                    header[1] == 0x4B.toByte() &&
                    header[2] == 0x03.toByte() &&
                    header[3] == 0x04.toByte()
            }
        }.getOrDefault(false)
    }

    companion object {
        private val updateJson = Json { ignoreUnknownKeys = true }

        fun normalizeVersion(raw: String): String {
            return raw.trim().removePrefix("v").removePrefix("V")
        }

        fun compareVersions(remote: String, local: String): Int {
            val rParts = normalizeVersion(remote).split('.').map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
            val lParts = normalizeVersion(local).split('.').map { it.takeWhile { ch -> ch.isDigit() }.toIntOrNull() ?: 0 }
            val maxLen = maxOf(rParts.size, lParts.size)
            for (i in 0 until maxLen) {
                val r = rParts.getOrElse(i) { 0 }
                val l = lParts.getOrElse(i) { 0 }
                if (r != l) return r.compareTo(l)
            }
            return 0
        }

        fun parseReleaseApiJson(jsonText: String): AppUpdateInfo? {
            if (jsonText.isBlank()) return null
            return runCatching {
                val root = updateJson.parseToJsonElement(jsonText).jsonObject
                if (root["draft"]?.jsonPrimitive?.contentOrNull == "true") return null
                val tag = root["tag_name"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
                if (tag.isEmpty()) return null
                val version = normalizeVersion(tag)
                val title = root["name"]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }
                    ?: "IntraService Mobile $version"
                val changelog = root["body"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

                val assets = root["assets"]?.jsonArray.orEmpty()
                val apkAsset = assets.mapNotNull { it as? JsonObject }.firstOrNull { asset ->
                    val name = asset["name"]?.jsonPrimitive?.contentOrNull.orEmpty()
                    name.endsWith(".apk", ignoreCase = true)
                } ?: return null

                val downloadUrl = apkAsset["browser_download_url"]?.jsonPrimitive?.contentOrNull.orEmpty()
                if (downloadUrl.isBlank()) return null
                val size = apkAsset["size"]?.jsonPrimitive?.longOrNull ?: 0L

                AppUpdateInfo(
                    versionName = version,
                    tagName = tag,
                    title = title,
                    changelog = changelog,
                    downloadUrl = downloadUrl,
                    sizeBytes = size
                )
            }.getOrNull()
        }

        fun parseExpandedAssetsHtml(html: String, tagName: String, repo: String): AppUpdateInfo? {
            val cleanTag = tagName.trim()
            if (cleanTag.isEmpty()) return null
            val version = normalizeVersion(cleanTag)
            val pattern = Regex("""href=["'](/${Regex.escape(repo)}/releases/download/[^"']+\.apk)["']""")
            val match = pattern.find(html)
            val downloadUrl = if (match != null) {
                "https://github.com${match.groupValues[1]}"
            } else {
                "https://github.com/$repo/releases/download/$cleanTag/IntraService-Mobile-$version.apk"
            }
            return AppUpdateInfo(
                versionName = version,
                tagName = cleanTag,
                title = "IntraService Mobile $version",
                changelog = "Доступна новая версия приложения для скачивания и установки.",
                downloadUrl = downloadUrl,
                sizeBytes = 0L
            )
        }
    }
}
