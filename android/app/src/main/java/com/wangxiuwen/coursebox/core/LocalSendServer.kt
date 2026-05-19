package com.wangxiuwen.coursebox.core

import android.content.Context
import android.os.Build
import android.util.Log
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.security.SecureRandom
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

private const val TAG = "LocalSendServer"
private const val LOCALSEND_PORT = 53317
private const val LOCALSEND_MULTICAST = "224.0.0.167"
private const val LOCALSEND_VERSION = "2.0"
private const val PREFS_NAME = "coursebox_localsend"
private const val PREF_FINGERPRINT = "localsend_fingerprint"

/**
 * LocalSend v2 protocol receiver, sharing the same `library.importLocalFile`
 * pipeline as [LanImportServer]. Runs on the standard LocalSend port
 * (53317) so an unmodified LocalSend desktop client can push a
 * `.coursebox.zip` directly to the phone.
 *
 * Endpoints (https://github.com/localsend/protocol):
 *  - POST /api/localsend/v2/info             → our device descriptor JSON
 *  - POST /api/localsend/v2/register         → same payload, active discovery
 *  - POST /api/localsend/v2/prepare-upload   → session+token map, only zips
 *  - POST /api/localsend/v2/upload           → raw bytes → ingest
 *  - POST /api/localsend/v2/cancel           → free session state
 *
 * One-shot UDP multicast announce is fired on `start()` so peers that are
 * already listening discover us; we do not re-announce on a timer.
 */
class LocalSendServer(
    private val ctx: Context,
    private val library: CourseLibrary,
    private val onProgress: (status: String) -> Unit,
) : NanoHTTPD(LOCALSEND_PORT) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val fingerprint: String = loadOrCreateFingerprint(ctx)
    private val alias: String = "课程盒子 · ${Build.MODEL ?: "Android"}"

    /** sessionId → (fileId → token). Used to validate /upload calls. */
    private val sessions = ConcurrentHashMap<String, MutableMap<String, FileSlot>>()

    private data class FileSlot(val token: String, val fileName: String, val size: Long)

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri
        val method = session.method
        return try {
            when {
                method == Method.POST && (uri == "/api/localsend/v2/info" ||
                    uri == "/api/localsend/v2/register") -> respondInfo()
                method == Method.POST && uri == "/api/localsend/v2/prepare-upload" ->
                    handlePrepare(session)
                method == Method.POST && uri == "/api/localsend/v2/upload" ->
                    handleUpload(session)
                method == Method.POST && uri == "/api/localsend/v2/cancel" ->
                    handleCancel(session)
                else -> json(Response.Status.NOT_FOUND, """{"message":"not found"}""")
            }
        } catch (e: Throwable) {
            Log.w(TAG, "localsend serve fail $method $uri", e)
            json(Response.Status.INTERNAL_ERROR, """{"message":"${e.message ?: "error"}"}""")
        }
    }

    private fun respondInfo(): Response = json(Response.Status.OK, deviceJson().toString())

    private fun deviceJson(): JSONObject = JSONObject().apply {
        put("alias", alias)
        put("version", LOCALSEND_VERSION)
        put("deviceModel", Build.MODEL ?: "Android")
        put("deviceType", "mobile")
        put("fingerprint", fingerprint)
        put("port", LOCALSEND_PORT)
        put("protocol", "http")
        put("download", false)
        put("announce", true)
    }

    private fun handlePrepare(session: IHTTPSession): Response {
        val body = readBody(session)
        val root = runCatching { JSONObject(body) }.getOrNull()
            ?: return json(Response.Status.BAD_REQUEST, """{"message":"bad json"}""")
        val files = root.optJSONObject("files")
            ?: return json(Response.Status.BAD_REQUEST, """{"message":"missing files"}""")

        val accepted = LinkedHashMap<String, FileSlot>()
        val tokenMap = JSONObject()
        val keys = files.keys()
        while (keys.hasNext()) {
            val fileId = keys.next()
            val f = files.optJSONObject(fileId) ?: continue
            val fileName = f.optString("fileName", "")
            // Only .coursebox.zip / .zip — anything else is a no-op.
            if (!isZip(fileName)) continue
            val size = f.optLong("size", 0L)
            val token = randomHex(16)
            accepted[fileId] = FileSlot(token = token, fileName = fileName, size = size)
            tokenMap.put(fileId, token)
        }
        if (accepted.isEmpty()) {
            // 204: no files we want. Per spec a sender that gets nothing back
            // just doesn't upload anything; we still return 200 with empty
            // files{} so older clients don't choke.
            val empty = JSONObject().apply {
                put("sessionId", UUID.randomUUID().toString())
                put("files", JSONObject())
            }
            return json(Response.Status.OK, empty.toString())
        }
        val sessionId = UUID.randomUUID().toString()
        sessions[sessionId] = accepted
        val resp = JSONObject().apply {
            put("sessionId", sessionId)
            put("files", tokenMap)
        }
        onProgress("收到 LocalSend 请求：${accepted.size} 个文件")
        return json(Response.Status.OK, resp.toString())
    }

    private fun handleUpload(session: IHTTPSession): Response {
        val params = session.parameters
        val sessionId = params["sessionId"]?.firstOrNull()
            ?: return json(Response.Status.BAD_REQUEST, """{"message":"missing sessionId"}""")
        val fileId = params["fileId"]?.firstOrNull()
            ?: return json(Response.Status.BAD_REQUEST, """{"message":"missing fileId"}""")
        val token = params["token"]?.firstOrNull()
            ?: return json(Response.Status.BAD_REQUEST, """{"message":"missing token"}""")

        val slots = sessions[sessionId]
            ?: return json(Response.Status.FORBIDDEN, """{"message":"unknown session"}""")
        val slot = slots[fileId]
            ?: return json(Response.Status.FORBIDDEN, """{"message":"unknown fileId"}""")
        if (slot.token != token) {
            return json(Response.Status.FORBIDDEN, """{"message":"bad token"}""")
        }

        // Read body length from header — NanoHTTPD's raw inputStream limit is
        // bounded by Content-Length, so copyTo will stop at the body end.
        val contentLen = session.headers["content-length"]?.toLongOrNull() ?: -1L
        val out = File(ctx.cacheDir, "localsend-${System.currentTimeMillis()}-${safeName(slot.fileName)}")
        var written = 0L
        session.inputStream.use { input ->
            out.outputStream().use { sink ->
                val buf = ByteArray(64 * 1024)
                var remaining = if (contentLen > 0) contentLen else Long.MAX_VALUE
                while (remaining > 0) {
                    val toRead = if (remaining > buf.size) buf.size else remaining.toInt()
                    val n = input.read(buf, 0, toRead)
                    if (n <= 0) break
                    sink.write(buf, 0, n)
                    written += n
                    remaining -= n
                }
            }
        }
        if (written == 0L) {
            runCatching { out.delete() }
            return json(Response.Status.BAD_REQUEST, """{"message":"empty body"}""")
        }
        // Remove the slot; once all are gone, drop the session.
        slots.remove(fileId)
        if (slots.isEmpty()) sessions.remove(sessionId)
        ingest(out, slot.fileName)
        return json(Response.Status.OK, """{"message":"ok"}""")
    }

    private fun handleCancel(session: IHTTPSession): Response {
        val sessionId = session.parameters["sessionId"]?.firstOrNull()
        if (sessionId != null) sessions.remove(sessionId)
        return json(Response.Status.OK, """{"message":"ok"}""")
    }

    private fun ingest(src: File, label: String) {
        onProgress("收到 $label，正在导入…")
        scope.launch {
            runCatching { library.importLocalFile(src, sourceLabel = "localsend://$label") }
                .onSuccess { res ->
                    val titles = res.packages.joinToString { it.title }
                    onProgress("✓ 已导入：$titles（${res.addedObjects} 个对象）")
                }
                .onFailure { onProgress("导入失败：${it.message}") }
            runCatching { src.delete() }
        }
    }

    override fun start() {
        super.start(SOCKET_READ_TIMEOUT, false)
        scope.launch { runCatching { announceOnce() } }
    }

    /** Convenience kept symmetric with NanoHTTPD's start signature. */
    override fun start(timeout: Int, daemon: Boolean) {
        super.start(timeout, daemon)
        scope.launch { runCatching { announceOnce() } }
    }

    override fun stop() {
        super.stop()
        sessions.clear()
        runCatching { scope.coroutineContext.cancel() }
    }

    /**
     * One-shot UDP multicast announce on 224.0.0.167:53317. We fire it once
     * on start; we do not re-announce on a timer and we do not listen for
     * peer announcements (active discovery via POST /register is enough for
     * a sender to talk to us once it knows our IP).
     */
    private fun announceOnce() {
        try {
            val payload = deviceJson().toString().toByteArray(Charsets.UTF_8)
            val group = InetAddress.getByName(LOCALSEND_MULTICAST)
            MulticastSocket().use { sock ->
                sock.timeToLive = 1
                val packet = DatagramPacket(payload, payload.size, InetSocketAddress(group, LOCALSEND_PORT))
                sock.send(packet)
            }
        } catch (e: Throwable) {
            Log.d(TAG, "multicast announce skipped: ${e.message}")
        }
    }

    private fun readBody(session: IHTTPSession): String {
        val contentLen = session.headers["content-length"]?.toIntOrNull() ?: 0
        if (contentLen <= 0) return ""
        val buf = ByteArray(contentLen)
        var read = 0
        val input = session.inputStream
        while (read < contentLen) {
            val n = input.read(buf, read, contentLen - read)
            if (n <= 0) break
            read += n
        }
        return String(buf, 0, read, Charsets.UTF_8)
    }

    private fun json(status: Response.Status, body: String): Response =
        newFixedLengthResponse(status, "application/json; charset=utf-8", body)

    private fun isZip(name: String): Boolean {
        val n = name.lowercase()
        // .cx is the current format; .coursebox.zip kept for any old
        // sender still around. Multi-part suffixes (.cx.part0/.cx.part1)
        // are also accepted so a multi-part course can travel piece by
        // piece over LocalSend.
        return n.endsWith(".cx") ||
            Regex(".*\\.cx\\.part\\d+$").matches(n) ||
            n.endsWith(".coursebox.zip")
    }

    private fun safeName(name: String): String =
        name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(80).ifBlank { "import.zip" }

    companion object {
        const val PORT = LOCALSEND_PORT

        private fun loadOrCreateFingerprint(ctx: Context): String {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val existing = prefs.getString(PREF_FINGERPRINT, null)
            if (!existing.isNullOrBlank()) return existing
            val fresh = randomHex(32)
            prefs.edit().putString(PREF_FINGERPRINT, fresh).apply()
            return fresh
        }

        private fun randomHex(bytes: Int): String {
            val b = ByteArray(bytes)
            SecureRandom().nextBytes(b)
            return buildString(b.size * 2) {
                for (x in b) {
                    append(((x.toInt() shr 4) and 0xF).toString(16))
                    append((x.toInt() and 0xF).toString(16))
                }
            }
        }
    }
}

// Bridge runCatching cancel call above to the Job underlying the scope.
private fun kotlin.coroutines.CoroutineContext.cancel() {
    this[kotlinx.coroutines.Job]?.cancel()
}
