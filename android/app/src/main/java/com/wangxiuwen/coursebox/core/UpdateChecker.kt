package com.wangxiuwen.coursebox.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.core.content.FileProvider
import com.wangxiuwen.coursebox.BuildConfig
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

/**
 * One installable image inside a release. A release ships one apk per
 * flavour, and which one a device should take is usually — but not always —
 * the flavour it is already running, so the choice is surfaced instead of
 * being made silently. See [UpdateChecker.check].
 */
data class UpdateVariant(
    val asset: GhAsset,
    val isKiosk: Boolean,
    /** True for the image matching the flavour this build was compiled as. */
    val isOurs: Boolean,
) {
    val label: String get() = if (isKiosk) "课堂固定版（整机锁定）" else "普通版"

    val note: String get() = if (isKiosk) {
        "开机直接进课程, 锁住返回和桌面, 适合专用学习平板"
    } else {
        "普通 App, 可以随时退出, 适合手机和共用平板"
    }
}

data class UpdateAvailable(
    val release: GhRelease,
    /** The image pre-selected in the prompt: this build's own flavour. */
    val apkAsset: GhAsset,
    /** Everything the user may pick from — one entry when the choice is
     *  locked down (see [UpdateChecker.check]). */
    val variants: List<UpdateVariant>,
    val currentVersion: String,
    val latestVersion: String,
)

object UpdateChecker {
    private val json = Json { ignoreUnknownKeys = true }

    private fun isKioskAsset(name: String) = name.contains("kiosk", ignoreCase = true)

    private fun isOurFlavour(assetName: String, tag: String): Boolean =
        if (tag.isEmpty()) !isKioskAsset(assetName) else assetName.contains(tag, ignoreCase = true)

    /**
     * The images this build may install, own flavour first — that one is the
     * prompt's default. Pure, so the flavour rules are testable without a
     * network round-trip or a device.
     */
    internal fun variantsFor(
        assets: List<GhAsset>,
        tag: String = BuildConfig.UPDATE_ASSET_TAG,
        allowFlavourChange: Boolean = true,
    ): List<UpdateVariant> {
        val all = assets
            .filter {
                it.name.endsWith(".apk", ignoreCase = true) &&
                    !it.name.contains("unsigned", ignoreCase = true)
            }
            .map {
                UpdateVariant(
                    asset = it,
                    isKiosk = isKioskAsset(it.name),
                    isOurs = isOurFlavour(it.name, tag),
                )
            }
        val offered = if (allowFlavourChange) all else all.filter { it.isOurs }
        return offered.sortedByDescending { it.isOurs }
    }

    /**
     * A release carries one apk per flavour, so "the first apk" is not good
     * enough — and which one is right is a question only the person holding
     * the device can answer, so [check] hands back every image and the
     * prompt lets them pick, with their current flavour pre-selected.
     *
     * The one case where there is no choice is a provisioned kiosk device:
     * the normal apk ships no KioskAdminReceiver, so device ownership would
     * end up recorded against a component that no longer exists, unclearable
     * short of a factory reset. Such a device is offered the kiosk image
     * only, and nothing at all if the release does not carry one.
     *
     * Returns null for any failure (no network, no release, no apk, parse
     * error) — silent, so the UI can just skip prompting.
     *
     * @param allowFlavourChange false pins the device to its own image. Pass
     *   `!KioskController.isDeviceOwner(ctx)` — see above for why.
     */
    suspend fun check(
        currentVersion: String,
        allowFlavourChange: Boolean = true,
    ): UpdateAvailable? = withContext(Dispatchers.IO) {
        val release = fetchLatest() ?: return@withContext null
        if (release.prerelease || release.draft) return@withContext null

        // Our own image is the default. A release that predates the flavour
        // split carries a single untagged apk, which no kiosk build matches —
        // a provisioned kiosk device is then offered nothing, by design.
        val variants = variantsFor(release.assets, allowFlavourChange = allowFlavourChange)
        val default = variants.firstOrNull() ?: return@withContext null

        if (!isNewer(currentVersion, release.tag_name)) return@withContext null

        UpdateAvailable(
            release = release,
            apkAsset = default.asset,
            variants = variants,
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
     * [onProgress] receives (bytesSoFar, totalBytes) tuples roughly every
     * 64 KB so the caller can drive a progress bar. totalBytes is the
     * asset.size if known, otherwise the HTTP Content-Length, otherwise
     * -1 for unknown.
     *
     * Caller is responsible for triggering the install Intent later via
     * [install].
     */
    suspend fun download(
        ctx: Context,
        asset: GhAsset,
        onProgress: (bytesSoFar: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): File = withContext(Dispatchers.IO) {
        val outFile = File(ctx.cacheDir, "coursebox-update-${asset.name}")
        if (outFile.exists() && asset.size > 0 && outFile.length() == asset.size) {
            onProgress(asset.size, asset.size)
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
                val total = when {
                    asset.size > 0 -> asset.size
                    else -> c.getHeaderField("Content-Length")?.toLongOrNull() ?: -1L
                }
                val buf = ByteArray(64 * 1024)
                var written = 0L
                c.inputStream.use { input ->
                    tmp.outputStream().use { sink ->
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            sink.write(buf, 0, n)
                            written += n
                            onProgress(written, total)
                        }
                    }
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
