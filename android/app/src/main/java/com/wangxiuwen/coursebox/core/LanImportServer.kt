package com.wangxiuwen.coursebox.core

import android.content.Context
import android.util.Log
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import android.graphics.Bitmap
import android.graphics.Color

private const val TAG = "LanImportServer"
private const val SERVER_PORT = 38723

/**
 * One-shot HTTP receiver for `.coursebox.zip` files. Hosts a minimal upload
 * page on the phone's LAN IP so any browser on the same Wi-Fi (or another
 * coursebox device) can push a zip in via multipart POST.
 *
 *  - GET /            → upload form (works in any browser)
 *  - POST /upload     → save multipart to cache, hand off to CourseLibrary
 *
 * The endpoint also accepts a LocalSend-style raw octet-stream PUT so a
 * desktop helper can integrate later without a form.
 */
class LanImportServer(
    private val ctx: Context,
    private val library: CourseLibrary,
    private val onProgress: (status: String) -> Unit,
) : NanoHTTPD(SERVER_PORT) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * LocalSend v2 receiver, runs on port 53317 in parallel. We lifecycle it
     * alongside this server so a desktop running LocalSend can push a zip
     * directly without the browser upload page.
     */
    private val localSend = LocalSendServer(ctx, library, onProgress)

    override fun start(timeout: Int, daemon: Boolean) {
        super.start(timeout, daemon)
        runCatching { localSend.start(timeout, daemon) }
            .onFailure { Log.w(TAG, "localsend start fail", it) }
    }

    override fun serve(session: IHTTPSession): Response = when {
        session.method == Method.GET && session.uri == "/" -> page()
        session.method == Method.POST && session.uri == "/upload" -> handleMultipart(session)
        session.method == Method.PUT && session.uri.startsWith("/raw") -> handleRaw(session)
        session.method == Method.GET && session.uri == "/apk" -> serveApk()
        else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
    }

    private fun serveApk(): Response {
        val apk = File(ctx.applicationInfo.sourceDir)
        if (!apk.exists()) {
            return newFixedLengthResponse(
                Response.Status.NOT_FOUND, "text/plain", "apk not found",
            )
        }
        val filename = "coursebox-${com.wangxiuwen.coursebox.BuildConfig.VERSION_NAME}.apk"
        val resp = newFixedLengthResponse(
            Response.Status.OK,
            "application/vnd.android.package-archive",
            apk.inputStream(),
            apk.length(),
        )
        resp.addHeader("Content-Disposition", "attachment; filename=\"$filename\"")
        return resp
    }

    private fun page(): Response {
        val ver = com.wangxiuwen.coursebox.BuildConfig.VERSION_NAME
        val html = """
            <!doctype html>
            <html lang="zh">
            <head>
              <meta charset="utf-8">
              <meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">
              <title>课程盒子 · 局域网共享</title>
              <style>
                *, *::before, *::after { box-sizing: border-box; }
                html, body { margin: 0; padding: 0; }
                body {
                  font-family: -apple-system, BlinkMacSystemFont, "Helvetica Neue", "PingFang SC", sans-serif;
                  background: #F5F4F1; color: #111;
                  padding: 20px 16px 40px;
                  -webkit-text-size-adjust: 100%;
                }
                .card {
                  max-width: 520px; margin: 0 auto;
                  background: #fff; border-radius: 16px;
                  padding: 22px 20px;
                  box-shadow: 0 1px 3px rgba(0,0,0,0.06);
                }
                .card + .card { margin-top: 16px; }
                h1 { margin: 0 0 6px; font-size: 20px; }
                h2 { margin: 0 0 6px; font-size: 17px; }
                .sub { color: #6B7280; margin: 0 0 16px; font-size: 13px; line-height: 1.5; }
                .drop {
                  display: block; width: 100%;
                  border: 2px dashed #C8C7C0; border-radius: 12px;
                  padding: 24px 16px; text-align: center;
                  color: #666; font-size: 14px;
                  cursor: pointer; transition: all .15s ease;
                  -webkit-tap-highlight-color: transparent;
                }
                .drop:active, .drop.hot { border-color: #007AFF; background: #EAF2FF; color: #007AFF; }
                .btn {
                  display: block; width: 100%;
                  background: #007AFF; color: #fff;
                  border: none; border-radius: 10px;
                  padding: 13px 20px; font-size: 15px; font-weight: 600;
                  cursor: pointer; text-decoration: none; text-align: center;
                  margin-top: 14px;
                  -webkit-tap-highlight-color: transparent;
                }
                .btn:disabled { opacity: .45; cursor: default; }
                .btn.ghost { background: #fff; color: #007AFF; border: 1px solid #007AFF; }
                .status { margin-top: 12px; font-size: 13px; color: #444; min-height: 18px; }
                #file { position: absolute; left: -9999px; opacity: 0; }
                .ver { color: #9CA3AF; font-size: 12px; margin-top: 8px; }
              </style>
            </head>
            <body>
              <div class="card">
                <h1>📦 导入课程</h1>
                <p class="sub">选一个 <code>.coursebox.zip</code> 上传到手机。</p>
                <input id="file" type="file" accept=".zip,.coursebox.zip,application/zip">
                <label class="drop" id="drop" for="file">
                  <span id="dropText">点击选择或拖拽 zip 文件</span>
                </label>
                <button class="btn" id="go" disabled>上传</button>
                <div class="status" id="status"></div>
              </div>

              <div class="card">
                <h2>📲 下载本机的 App</h2>
                <p class="sub">没有装课程盒子？点这里下载并安装本机当前版本（v$ver）。</p>
                <a class="btn" href="/apk" download>下载 APK</a>
                <p class="ver">需要打开"未知来源应用"安装权限。</p>
              </div>

              <script>
                (function() {
                  var drop = document.getElementById('drop');
                  var fileEl = document.getElementById('file');
                  var go = document.getElementById('go');
                  var status = document.getElementById('status');
                  var dropText = document.getElementById('dropText');
                  var selected = null;

                  function pick(f) {
                    if (!f) return;
                    selected = f;
                    dropText.textContent = f.name + ' · ' + (f.size / 1024 / 1024).toFixed(1) + ' MB';
                    go.disabled = false;
                  }

                  fileEl.addEventListener('change', function(e) { pick(e.target.files[0]); });
                  ['dragenter','dragover'].forEach(function(t) {
                    drop.addEventListener(t, function(e) { e.preventDefault(); drop.classList.add('hot'); });
                  });
                  ['dragleave','drop'].forEach(function(t) {
                    drop.addEventListener(t, function(e) { e.preventDefault(); drop.classList.remove('hot'); });
                  });
                  drop.addEventListener('drop', function(e) {
                    e.preventDefault();
                    drop.classList.remove('hot');
                    if (e.dataTransfer && e.dataTransfer.files.length) pick(e.dataTransfer.files[0]);
                  });

                  go.addEventListener('click', function() {
                    if (!selected) return;
                    go.disabled = true; status.textContent = '上传中…';
                    var fd = new FormData();
                    fd.append('file', selected, selected.name);
                    fetch('/upload', { method: 'POST', body: fd })
                      .then(function(r) { return r.text().then(function(t) { return { ok: r.ok, t: t }; }); })
                      .then(function(x) {
                        status.textContent = x.ok ? ('✓ ' + x.t) : ('上传失败：' + x.t);
                        if (x.ok) {
                          selected = null;
                          dropText.textContent = '点击选择或拖拽 zip 文件';
                        } else {
                          go.disabled = false;
                        }
                      })
                      .catch(function(e) { status.textContent = '上传失败：' + e.message; go.disabled = false; });
                  });
                })();
              </script>
            </body>
            </html>
        """.trimIndent()
        return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
    }

    private fun handleMultipart(session: IHTTPSession): Response {
        return try {
            val files = HashMap<String, String>()
            session.parseBody(files)
            val tmpPath = files["file"] ?: return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/plain", "missing file part",
            )
            val originalName = session.parameters["file"]?.firstOrNull() ?: "import.zip"
            ingest(File(tmpPath), originalName)
            newFixedLengthResponse(Response.Status.OK, "text/plain", "已接收，正在导入…")
        } catch (e: Throwable) {
            Log.w(TAG, "multipart fail", e)
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
        }
    }

    private fun handleRaw(session: IHTTPSession): Response {
        return try {
            val out = File(ctx.cacheDir, "lan-import-${System.currentTimeMillis()}.zip")
            session.inputStream.use { input ->
                out.outputStream().use { input.copyTo(it) }
            }
            ingest(out, out.name)
            newFixedLengthResponse(Response.Status.OK, "text/plain", "ok")
        } catch (e: Throwable) {
            newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", e.message ?: "error")
        }
    }

    private fun ingest(src: File, label: String) {
        onProgress("收到 $label，正在导入…")
        scope.launch {
            runCatching { library.importLocalFile(src, sourceLabel = "lan://$label") }
                .onSuccess { res ->
                    val titles = res.packages.joinToString { it.title }
                    onProgress("✓ 已导入：$titles（${res.addedObjects} 个对象）")
                }
                .onFailure { onProgress("导入失败：${it.message}") }
            runCatching { src.delete() }
        }
    }

    override fun stop() {
        super.stop()
        runCatching { localSend.stop() }
        runCatching { scope.coroutineContext.cancel() }
    }

    companion object {
        /** First non-loopback IPv4 of any "up" interface — typically wlan0. */
        fun localIpv4(): String? = runCatching {
            NetworkInterface.getNetworkInterfaces().toList()
                .filter { it.isUp && !it.isLoopback }
                .flatMap { it.inetAddresses.toList() }
                .filterIsInstance<Inet4Address>()
                .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
                ?.hostAddress
        }.getOrNull()

        fun url(): String? = localIpv4()?.let { "http://$it:$SERVER_PORT" }

        /** Render a `text` payload (the upload URL) as a square QR Bitmap. */
        fun qrBitmap(text: String, sizePx: Int = 512): Bitmap {
            val hints = mapOf(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M,
                EncodeHintType.MARGIN to 1,
            )
            val matrix = QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, sizePx, sizePx, hints)
            val w = matrix.width
            val h = matrix.height
            val pixels = IntArray(w * h)
            for (y in 0 until h) for (x in 0 until w) {
                pixels[y * w + x] = if (matrix.get(x, y)) Color.BLACK else Color.WHITE
            }
            return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                setPixels(pixels, 0, w, 0, 0, w, h)
            }
        }
    }
}

// Bridges runCatching cancel call above to the Job underlying the scope.
private fun kotlin.coroutines.CoroutineContext.cancel() {
    this[kotlinx.coroutines.Job]?.cancel()
}
