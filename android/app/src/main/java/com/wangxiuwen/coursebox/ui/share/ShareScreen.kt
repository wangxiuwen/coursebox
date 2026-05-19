package com.wangxiuwen.coursebox.ui.share

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.wangxiuwen.coursebox.core.CourseLibrary
import com.wangxiuwen.coursebox.core.lan.CourseShareClient
import com.wangxiuwen.coursebox.ui.theme.AccentBlue
import kotlinx.coroutines.launch
import java.io.File

private val PaperBg = Color(0xFFF5F4F1)
private val InkSoft = Color(0xFF6B6B66)

/**
 * Sends a course package's .cx files to another coursebox instance over
 * LocalSend v2 (plain HTTP — the peer must have its 局域网导入 page open).
 *
 * Flow:
 *   1. user enters peer IP (or scans a QR — TODO)
 *   2. Probe button pings /api/localsend/v2/info to verify the receiver
 *   3. Send button does prepare-upload + per-file upload, streaming
 *      bytes through HttpURLConnection without buffering
 *
 * Multi-part .cx packs send every cxPaths entry; the receiver matches
 * partN filenames against its package's multipart_parts list and merges
 * them under the same course id.
 */
@Composable
fun ShareScreen(library: CourseLibrary, courseId: String, nav: NavHostController) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val pkg = remember(courseId) { library.packageById(courseId) }

    var host by remember { mutableStateOf("") }
    var probeStatus by remember { mutableStateOf<String?>(null) }
    var sending by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<String?>(null) }
    val progressByFile = remember { mutableStateMapOf<String, Pair<Long, Long>>() }

    val cxFiles = pkg?.cxPaths?.map { File(it) }?.filter { it.exists() }.orEmpty()
    val totalBytes = cxFiles.sumOf { it.length() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(PaperBg)
            .statusBarsPadding(),
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.Black)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "分享课程",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.Black,
                )
            }

            Column(
                modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Card(title = pkg?.title ?: "未知课程") {
                    Text(
                        "${cxFiles.size} 个文件 · ${fmtMb(totalBytes)} MB",
                        color = InkSoft,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    if (pkg?.multipartParts?.isNotEmpty() == true) {
                        Text(
                            "多分片 (${pkg.multipartParts.size} parts) — 接收方会按 part 累加",
                            color = InkSoft,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Card(title = "接收方 IP") {
                    OutlinedTextField(
                        value = host,
                        onValueChange = { host = it.trim() },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        placeholder = { Text("例如 192.168.0.122") },
                    )
                    Text(
                        "对方打开「局域网导入」页, 二维码下方就是 IP",
                        color = InkSoft,
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(
                            onClick = {
                                if (host.isBlank()) return@OutlinedButton
                                probeStatus = "测试中…"
                                scope.launch {
                                    val info = CourseShareClient.probe(host)
                                    probeStatus = info?.let {
                                        "✓ ${it.alias} (${it.deviceModel ?: it.deviceType.wire})"
                                    } ?: "✗ 连不上 / 对方没开局域网导入页"
                                }
                            },
                        ) { Text("测试连接") }

                        Button(
                            onClick = {
                                if (host.isBlank() || cxFiles.isEmpty() || sending) return@Button
                                sending = true
                                lastResult = null
                                progressByFile.clear()
                                val specs = cxFiles.map { CourseShareClient.FileSpec(source = it) }
                                scope.launch {
                                    val r = CourseShareClient.sendFiles(
                                        ctx = ctx,
                                        host = host,
                                        port = com.wangxiuwen.coursebox.core.lan.LocalSend.PORT,
                                        files = specs,
                                    ) { id, sent, total ->
                                        progressByFile[id] = sent to total
                                    }
                                    lastResult = when (r) {
                                        is CourseShareClient.Result.Ok -> "✓ 已发送 (session ${r.sessionId.take(8)}…)"
                                        is CourseShareClient.Result.Rejected -> "✗ 拒绝: ${r.message}"
                                        is CourseShareClient.Result.IoError -> "✗ 网络错: ${r.cause.message}"
                                    }
                                    sending = false
                                }
                            },
                            enabled = !sending && host.isNotBlank() && cxFiles.isNotEmpty(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                        ) { Text(if (sending) "发送中…" else "立即发送") }
                    }
                    probeStatus?.let {
                        Text(
                            it,
                            color = if (it.startsWith("✓")) Color(0xFF0A7A3F) else InkSoft,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                if (sending || progressByFile.isNotEmpty()) {
                    Card(title = "发送进度") {
                        val sentTotal = progressByFile.values.sumOf { it.first }
                        val pct = if (totalBytes > 0) (sentTotal * 100 / totalBytes).toInt() else 0
                        Text(
                            "$pct% · ${fmtMb(sentTotal)} / ${fmtMb(totalBytes)} MB",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color.Black,
                        )
                        LinearProgressIndicator(
                            progress = { sentTotal.toFloat() / totalBytes.coerceAtLeast(1L) },
                            modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                            color = AccentBlue,
                        )
                        cxFiles.forEach { f ->
                            val s = progressByFile.entries.firstOrNull { (id, _) ->
                                specsFor(cxFiles).any { it.source == f && it.id == id }
                            }?.value
                            Text(
                                "${f.name} · ${s?.first?.let { fmtMb(it) } ?: "0"} / ${fmtMb(f.length())} MB",
                                style = MaterialTheme.typography.labelSmall.copy(
                                    fontFamily = FontFamily.Monospace,
                                    fontSize = 11.sp,
                                ),
                                color = InkSoft,
                            )
                        }
                    }
                }

                lastResult?.let {
                    Card(title = "结果") {
                        Text(it, color = if (it.startsWith("✓")) Color(0xFF0A7A3F) else Color(0xFFC93B3B))
                    }
                }

                Spacer(Modifier.height(40.dp))
            }
        }
    }
}

// Stable spec list (id → file mapping fixed once per send), so the progress
// map can find which file produced which id.
@Composable
private fun specsFor(files: List<File>): List<CourseShareClient.FileSpec> = remember(files) {
    files.map { CourseShareClient.FileSpec(source = it) }
}

@Composable
private fun Card(title: String, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = Color.White,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 1.dp,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = Color.Black)
            content()
        }
    }
}

private fun fmtMb(b: Long): String = "%.1f".format(b / 1024.0 / 1024.0)
