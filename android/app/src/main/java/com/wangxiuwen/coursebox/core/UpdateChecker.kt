package com.wangxiuwen.coursebox.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

private const val TAG = "UpdateChecker"
private const val REPO = "wangxiuwen/coursebox"
private const val LATEST_URL = "https://api.github.com/repos/$REPO/releases/latest"

@Serializable
data class GhAsset(
    val name: String = "",
    val browser_download_url: String = "",
    val size: Long = 0,
)

@Serializable
data class GhRelease(
    val tag_name: String = "",
    val name: String = "",
    val body: String = "",
    val html_url: String = "",
    val assets: List<GhAsset> = emptyList(),
    val prerelease: Boolean = false,
    val draft: Boolean = false,
)

data class UpdateAvailable(
    val release: GhRelease,
    val apkAsset: GhAsset,
    val currentVersion: String,
    val latestVersion: String,
)

object UpdateChecker {
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Hit GitHub Releases API for the latest release, compare to the local
     * versionName. Returns non-null when a newer stable release ships an
     * Android APK asset. Returns null for any failure (no network, no
     * release, no matching asset, parse error) — silent so the UI can just
     * skip prompting.
     */
    suspend fun check(currentVersion: String): UpdateAvailable? = withContext(Dispatchers.IO) {
        val release = fetchLatest() ?: return@withContext null
        if (release.prerelease || release.draft) return@withContext null

        val apkAsset = release.assets.firstOrNull {
            it.name.endsWith(".apk", ignoreCase = true) &&
                !it.name.contains("unsigned", ignoreCase = true)
        } ?: return@withContext null

        if (!isNewer(currentVersion, release.tag_name)) return@withContext null

        UpdateAvailable(
            release = release,
            apkAsset = apkAsset,
            currentVersion = currentVersion,
            latestVersion = release.tag_name.removePrefix("v"),
        )
    }

    private fun fetchLatest(): GhRelease? = runCatching {
        val conn = (URL(LATEST_URL).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 5000
            readTimeout = 8000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "coursebox-android")
        }
        conn.use { c ->
            if (c.responseCode !in 200..299) {
                Log.w(TAG, "GitHub HTTP ${c.responseCode}")
                return@runCatching null
            }
            val body = c.inputStream.bufferedReader().readText()
            json.decodeFromString<GhRelease>(body)
        }
    }.onFailure { Log.w(TAG, "fetchLatest failed: ${it.message}") }.getOrNull()

    private inline fun <T : HttpURLConnection, R> T.use(block: (T) -> R): R = try {
        block(this)
    } finally {
        runCatching { disconnect() }
    }

    /** Semver-ish comparison. `vX.Y.Z` or `X.Y.Z` both fine; non-numeric
     *  segments compare lexicographically as a tie-breaker. */
    internal fun isNewer(current: String, latestTag: String): Boolean {
        val a = parseSemver(current)
        val b = parseSemver(latestTag)
        val n = maxOf(a.size, b.size)
        for (i in 0 until n) {
            val ai = a.getOrNull(i) ?: 0
            val bi = b.getOrNull(i) ?: 0
            if (bi > ai) return true
            if (bi < ai) return false
        }
        return false
    }

    private fun parseSemver(v: String): List<Int> {
        val clean = v.removePrefix("v").substringBefore('-').substringBefore('+')
        return clean.split('.').mapNotNull { it.toIntOrNull() }
    }

    /**
     * Download the APK to the app's cache and return the file. Idempotent —
     * if a complete download for this asset is already on disk (matched by
     * filename + size), skip the network round-trip. The size check is
     * what makes this safe to call on every launch.
     *
     * Caller is responsible for triggering the install Intent later via
     * [install]. The two are split so the UI can do the long-running
     * download silently in the background, then prompt the user only when
     * the APK is ready to install (faster, less awkward than the old
     * "tap 立即更新 → stare at a spinner for 30s" flow).
     */
    suspend fun download(ctx: Context, asset: GhAsset): File = withContext(Dispatchers.IO) {
        val outFile = File(ctx.cacheDir, "coursebox-update-${asset.name}")
        if (outFile.exists() && asset.size > 0 && outFile.length() == asset.size) {
            return@withContext outFile
        }
        // Stale or partial — start fresh.
        outFile.delete()

        // .part rename guard so a killed download never leaves a half-file
        // looking valid on next launch. We size-check before renaming and
        // delete on any failure.
        val tmp = File(ctx.cacheDir, outFile.name + ".part")
        tmp.delete()

        val conn = (URL(asset.browser_download_url).openConnection() as HttpURLConnection).apply {
            instanceFollowRedirects = true
            connectTimeout = 5000
            readTimeout = 30000
            setRequestProperty("User-Agent", "coursebox-android")
        }
        try {
            conn.use { c ->
                if (c.responseCode !in 200..299) error("下载失败 HTTP ${c.responseCode}")
                c.inputStream.use { input ->
                    tmp.outputStream().use { input.copyTo(it) }
                }
            }
            if (asset.size > 0 && tmp.length() != asset.size) {
                error("short read: got ${tmp.length()}, expected ${asset.size}")
            }
            if (!tmp.renameTo(outFile)) error("rename failed")
            outFile
        } catch (e: Throwable) {
            runCatching { tmp.delete() }
            throw e
        }
    }

    /**
     * Fire the system Package Installer with the already-downloaded APK.
     * Requires the user to confirm in the OS install dialog — that
     * confirmation is mandatory on Android and not something we can skip
     * for unsigned sideloads.
     */
    fun install(ctx: Context, apk: File) {
        val authority = "${ctx.packageName}.fileprovider"
        val uri: Uri = FileProvider.getUriForFile(ctx, authority, apk)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ctx.startActivity(intent)
    }

    /** Kept as a deprecated alias so any caller that still wires the old
     *  one-shot path keeps compiling. New code should split it. */
    @Deprecated("Use download(ctx,asset) then install(ctx,file)")
    suspend fun downloadAndInstall(ctx: Context, asset: GhAsset) {
        val apk = download(ctx, asset)
        install(ctx, apk)
    }
}
