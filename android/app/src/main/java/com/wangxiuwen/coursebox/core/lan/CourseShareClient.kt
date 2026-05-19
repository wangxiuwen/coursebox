package com.wangxiuwen.coursebox.core.lan

import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID

private const val TAG = "CourseShareClient"

/**
 * Sender side of LocalSend v2 over plain HTTP — no okhttp, no TLS, no
 * fingerprint check. Targets another coursebox instance (which runs HTTP
 * on port 53317 when the 局域网导入 page is open).
 *
 * Why HTTP only: the v2 spec lets a peer advertise `protocol: "http"`
 * and clients honour it. Our receiver does. Shipping HTTPS + self-signed
 * certs is the larger LocalSend port from 599player — saved for later.
 *
 * The client streams each .cx (and .cx.partN) one at a time. Multi-part
 * packs travel as separate files; the receiver attaches them to the
 * existing record via its multipart_parts list.
 */
object CourseShareClient {

    sealed interface Result {
        data class Ok(val sessionId: String) : Result
        data class Rejected(val httpCode: Int, val message: String) : Result
        data class IoError(val cause: Throwable) : Result
    }

    /**
     * Probe the peer at `http://host:port/api/localsend/v2/info`. Returns
     * the InfoDto on success — used to verify there's a coursebox /
     * LocalSend receiver before kicking off the actual transfer.
     */
    suspend fun probe(host: String, port: Int = LocalSend.PORT): InfoDto? =
        withContext(Dispatchers.IO) {
            runCatching {
                val url = URL("http://$host:$port${LocalSend.BASE_PATH}/info")
                val c = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "POST"
                    doOutput = true
                    connectTimeout = 3000
                    readTimeout = 5000
                    setRequestProperty("Content-Type", "application/json")
                }
                c.outputStream.use { it.write("{}".toByteArray()) }
                try {
                    if (c.responseCode !in 200..299) return@withContext null
                    val body = c.inputStream.bufferedReader().readText()
                    InfoDto.parse(JSONObject(body))
                } finally {
                    runCatching { c.disconnect() }
                }
            }.onFailure { Log.w(TAG, "probe $host:$port: ${it.message}") }.getOrNull()
        }

    /**
     * Send a list of files to peer in one LocalSend session.
     * onProgress fires per-file with (fileId, bytesSent, totalBytes).
     */
    suspend fun sendFiles(
        ctx: Context,
        host: String,
        port: Int,
        files: List<FileSpec>,
        onProgress: (fileId: String, sent: Long, total: Long) -> Unit = { _, _, _ -> },
    ): Result = withContext(Dispatchers.IO) {
        val self = selfInfo(ctx)
        val dtos = files.associate { f ->
            f.id to FileDto(
                id = f.id,
                fileName = f.fileName,
                size = f.size,
                fileType = "application/octet-stream",
            )
        }
        // 1. prepare-upload
        val prep = postJson(
            "http://$host:$port${LocalSend.BASE_PATH}/prepare-upload",
            PrepareUploadRequest(self, dtos).toJson(),
        ).getOrElse { return@withContext Result.IoError(it) }
        val resp = PrepareUploadResponse.parse(prep)
        if (resp.sessionId.isBlank() || resp.tokens.isEmpty()) {
            return@withContext Result.Rejected(0, "对方拒绝接收 (无 token)")
        }
        // 2. upload each file
        for (f in files) {
            val token = resp.tokens[f.id] ?: continue
            val url = "http://$host:$port${LocalSend.BASE_PATH}/upload" +
                "?sessionId=${resp.sessionId}&fileId=${f.id}&token=$token"
            val err = postBytes(url, f) { sent -> onProgress(f.id, sent, f.size) }
            if (err != null) return@withContext Result.IoError(err)
        }
        Result.Ok(resp.sessionId)
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

    private fun postJson(url: String, body: JSONObject): kotlin.Result<JSONObject> = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 5000
            readTimeout = 10000
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("User-Agent", "coursebox-share")
        }
        val payload = body.toString().toByteArray()
        try {
            setFixedLengthStreamingMode(c, payload.size.toLong())
            c.outputStream.use { it.write(payload) }
            if (c.responseCode !in 200..299) {
                error("HTTP ${c.responseCode}: ${c.errorStream?.bufferedReader()?.readText()?.take(200)}")
            }
            val text = c.inputStream.bufferedReader().readText()
            JSONObject(text)
        } finally {
            runCatching { c.disconnect() }
        }
    }

    private fun postBytes(
        url: String,
        spec: FileSpec,
        onSent: (Long) -> Unit,
    ): Throwable? = runCatching {
        val c = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
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

    private fun selfInfo(ctx: Context): InfoDto {
        // Stable per-install fingerprint via filesDir name (not a cert
        // sha — we don't ship TLS — but the field is required and the
        // value just has to be unique per peer).
        val fp = ctx.packageName + "@" + (Build.SERIAL ?: Build.MODEL.orEmpty()).hashCode()
        return InfoDto(
            alias = "课程盒子 · ${Build.MODEL ?: "Android"}",
            deviceModel = Build.MODEL,
            deviceType = DeviceType.Mobile,
            fingerprint = "send-" + fp.hashCode().toUInt().toString(16),
            port = LocalSend.PORT,
            protocol = "http",
            download = false,
        )
    }
}
