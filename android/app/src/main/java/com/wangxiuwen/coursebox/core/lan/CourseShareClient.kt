package com.wangxiuwen.coursebox.core.lan

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID

private const val TAG = "CourseShareClient"

/**
 * Sender side. Drops every bit of LocalSend's prepare-upload + token +
 * session bureaucracy and just streams `PUT http://peer:38723/raw?name=…`
 * — the same endpoint the browser upload page already uses. Receiver's
 * [com.wangxiuwen.coursebox.core.LanImportServer] handles import as if a
 * desktop browser had pushed the file.
 *
 * Discovery (LocalSend mDNS + UDP multicast) still announces the peer so
 * the sender can pick from a list instead of typing an IP; only the
 * actual transfer is simplified.
 */
object CourseShareClient {
    /** Receiver's LanImportServer port. Hard-coded to match LanImportServer.SERVER_PORT. */
    const val LAN_PORT = 38723

    sealed interface Result {
        data class Ok(val sentFiles: Int) : Result
        data class Rejected(val httpCode: Int, val message: String) : Result
        data class IoError(val cause: Throwable) : Result
    }

    /**
     * Ping the peer's LanImportServer root path to confirm it's listening.
     * The HTML upload page sits at `GET /`; a 200 means we can push.
     */
    suspend fun probe(host: String, port: Int = LAN_PORT): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val c = (URL("http://$host:$port/").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 3000
                readTimeout = 3000
            }
            try { c.responseCode in 200..299 } finally { runCatching { c.disconnect() } }
        }.onFailure { Log.w(TAG, "probe $host:$port: ${it.message}") }.getOrDefault(false)
    }

    /**
     * Send each [FileSpec] sequentially. Each PUT corresponds to one
     * lan-import session on the receiver — for a multi-part .cx the
     * receiver matches partN filenames against the package's
     * multipart_parts list and merges them under the same course id.
     */
    suspend fun sendFiles(
        ctx: Context,
        host: String,
        port: Int,
        files: List<FileSpec>,
        onProgress: (fileId: String, sent: Long, total: Long) -> Unit = { _, _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        if (files.isEmpty()) return@withContext Result.Ok(0)
        var sent = 0
        for (f in files) {
            val url = "http://$host:$port/raw?name=${URLEncoder.encode(f.fileName, "UTF-8")}"
            val err = postBytes(url, f) { bytes -> onProgress(f.id, bytes, f.size) }
            if (err != null) return@withContext Result.IoError(err)
            sent += 1
        }
        Result.Ok(sent)
    }

    /**
     * One file in a send batch. The File must remain readable for the
     * duration of the send (we keep it as a path so we can stream from
     * disk without buffering everything in memory).
     */
    data class FileSpec(
        val id: String = UUID.randomUUID().toString(),
        val source: File,
        val fileName: String = source.name,
        val size: Long = source.length(),
    )

    private fun postBytes(
        url: String,
        spec: FileSpec,
        onSent: (Long) -> Unit,
    ): Throwable? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            doOutput = true
            connectTimeout = 5000
            // No read timeout — multi-GB transfers over Wi-Fi can take a while.
            readTimeout = 0
            setRequestProperty("Content-Type", "application/octet-stream")
            setRequestProperty("User-Agent", "coursebox-share")
        }
        try {
            setFixedLengthStreamingMode(c, spec.size)
            val buf = ByteArray(64 * 1024)
            var written = 0L
            spec.source.inputStream().use { input ->
                c.outputStream.use { sink ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        sink.write(buf, 0, n)
                        written += n
                        onSent(written)
                    }
                }
            }
            if (c.responseCode !in 200..299) {
                error("HTTP ${c.responseCode}: ${c.errorStream?.bufferedReader()?.readText()?.take(200)}")
            }
        } finally {
            runCatching { c.disconnect() }
        }
    }.exceptionOrNull()

    private fun setFixedLengthStreamingMode(c: HttpURLConnection, len: Long) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            c.setFixedLengthStreamingMode(len)
        } else {
            c.setFixedLengthStreamingMode(len.toInt())
        }
    }

}
