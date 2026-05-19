package com.wangxiuwen.coursebox.core.lan

import org.json.JSONObject

/**
 * LocalSend v2 wire DTOs — field names match the spec exactly so
 * coursebox interops with any LocalSend client (Android / iOS / Mac /
 * Linux / Win / Web).
 *
 * Ported from 599player; trimmed to just the pieces the coursebox
 * sender needs (info, file, prepare-upload). org.json is built into
 * Android — no extra dep.
 */
object LocalSend {
    const val VERSION = "2.1"
    const val PORT = 53317
    const val MULTICAST_GROUP = "224.0.0.167"
    const val MULTICAST_PORT = 53317
    const val BASE_PATH = "/api/localsend/v2"
}

enum class DeviceType(val wire: String) {
    Mobile("mobile"), Desktop("desktop"), Web("web"),
    Headless("headless"), Server("server");
    companion object {
        fun parse(s: String?): DeviceType = entries.firstOrNull { it.wire == s } ?: Mobile
    }
}

data class InfoDto(
    val alias: String,
    val version: String = LocalSend.VERSION,
    val deviceModel: String? = null,
    val deviceType: DeviceType = DeviceType.Mobile,
    /** SHA-256(cert DER) hex lowercase for HTTPS peers; ours is a random
     *  hex id for HTTP since we don't ship a self-signed cert yet. */
    val fingerprint: String,
    val port: Int = LocalSend.PORT,
    /** "https" or "http". coursebox's receiver is http today. */
    val protocol: String = "http",
    val download: Boolean = false,
    val announce: Boolean? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("alias", alias)
        put("version", version)
        if (deviceModel != null) put("deviceModel", deviceModel)
        put("deviceType", deviceType.wire)
        put("fingerprint", fingerprint)
        put("port", port)
        put("protocol", protocol)
        put("download", download)
        if (announce != null) put("announce", announce)
    }

    companion object {
        fun parse(o: JSONObject): InfoDto = InfoDto(
            alias = o.optString("alias", ""),
            version = o.optString("version", LocalSend.VERSION),
            deviceModel = o.optString("deviceModel").takeIf { it.isNotEmpty() },
            deviceType = DeviceType.parse(o.optString("deviceType")),
            fingerprint = o.optString("fingerprint", ""),
            port = o.optInt("port", LocalSend.PORT),
            protocol = o.optString("protocol", "http"),
            download = o.optBoolean("download", false),
            announce = if (o.has("announce")) o.optBoolean("announce") else null,
        )
    }
}

data class FileDto(
    val id: String,
    val fileName: String,
    val size: Long,
    val fileType: String,
    val hash: String? = null,
    val preview: String? = null,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id)
        put("fileName", fileName)
        put("size", size)
        put("fileType", fileType)
        if (hash != null) put("hash", hash)
        if (preview != null) put("preview", preview)
    }
}

data class PrepareUploadRequest(
    val info: InfoDto,
    val files: Map<String, FileDto>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("info", info.toJson())
        val fmap = JSONObject()
        files.forEach { (id, f) -> fmap.put(id, f.toJson()) }
        put("files", fmap)
    }
}

data class PrepareUploadResponse(
    val sessionId: String,
    val tokens: Map<String, String>,
) {
    companion object {
        fun parse(o: JSONObject): PrepareUploadResponse {
            val sid = o.optString("sessionId")
            val fmap = o.optJSONObject("files") ?: JSONObject()
            val tokens = mutableMapOf<String, String>()
            fmap.keys().forEach { id -> tokens[id] = fmap.optString(id) }
            return PrepareUploadResponse(sid, tokens)
        }
    }
}
