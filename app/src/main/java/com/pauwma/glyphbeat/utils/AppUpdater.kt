package com.pauwma.glyphbeat.utils

import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.content.FileProvider
import com.pauwma.glyphbeat.core.AppConfig
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Checks GitHub releases for updates and downloads/installs APK files.
 */
object AppUpdater {

    private const val LOG_TAG = "AppUpdater"
    private const val GITHUB_API =
        "https://api.github.com/repos/Kuberwastaken/kubeats/releases/latest"

    data class ReleaseInfo(
        val tagName: String,
        val name: String,
        val body: String,
        val apkUrl: String?,
        val publishedAt: String
    )

    /**
     * Fetch the latest release info from GitHub.
     * Must be called from a background thread.
     */
    fun fetchLatestRelease(): ReleaseInfo? {
        return try {
            val url = URL(GITHUB_API)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github+json")
                connectTimeout = 10_000
                readTimeout = 10_000
            }

            val json = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()

            val obj = JSONObject(json)
            val assets = obj.getJSONArray("assets")
            var apkUrl: String? = null
            for (i in 0 until assets.length()) {
                val asset = assets.getJSONObject(i)
                if (asset.getString("name").endsWith(".apk")) {
                    apkUrl = asset.getString("browser_download_url")
                    break
                }
            }

            ReleaseInfo(
                tagName = obj.getString("tag_name"),
                name = obj.optString("name", obj.getString("tag_name")),
                body = obj.optString("body", ""),
                apkUrl = apkUrl,
                publishedAt = obj.optString("published_at", "")
            )
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to fetch release info: ${e.message}")
            null
        }
    }

    /**
     * Check if a release is newer than the current app version.
     * Compares the tag (e.g. "v1.6.7") against AppConfig.APP_VERSION.
     */
    fun isNewer(release: ReleaseInfo): Boolean {
        val remote = release.tagName.removePrefix("v").trim()
        val local = AppConfig.APP_VERSION.trim()
        return compareVersions(remote, local) > 0
    }

    private fun compareVersions(a: String, b: String): Int {
        val aParts = a.split(".").map { it.toIntOrNull() ?: 0 }
        val bParts = b.split(".").map { it.toIntOrNull() ?: 0 }
        val len = maxOf(aParts.size, bParts.size)
        for (i in 0 until len) {
            val av = aParts.getOrElse(i) { 0 }
            val bv = bParts.getOrElse(i) { 0 }
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    /**
     * Download the APK to cache and trigger the install intent.
     * Must be called from a background thread.
     * Returns the downloaded File, or null on failure.
     */
    fun downloadApk(
        context: Context,
        apkUrl: String,
        onProgress: (Float) -> Unit = {}
    ): File? {
        return try {
            val dir = File(context.cacheDir, "updates")
            dir.mkdirs()
            // Clean old downloads
            dir.listFiles()?.forEach { it.delete() }

            val outFile = File(dir, "kubeats-update.apk")

            val url = URL(apkUrl)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }

            val totalBytes = conn.contentLength.toLong()
            var downloaded = 0L

            conn.inputStream.use { input ->
                outFile.outputStream().use { output ->
                    val buf = ByteArray(8192)
                    var read: Int
                    while (input.read(buf).also { read = it } != -1) {
                        output.write(buf, 0, read)
                        downloaded += read
                        if (totalBytes > 0) {
                            onProgress(downloaded.toFloat() / totalBytes)
                        }
                    }
                }
            }
            conn.disconnect()

            Log.i(LOG_TAG, "APK downloaded: ${outFile.length()} bytes")
            outFile
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to download APK: ${e.message}")
            null
        }
    }

    /**
     * Launch the system package installer for the given APK file.
     */
    fun installApk(context: Context, apkFile: File) {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
