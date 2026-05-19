package com.wangxiuwen.coursebox.ui.library

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PushPin
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.foundation.Image
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.wangxiuwen.coursebox.BuildConfig
import com.wangxiuwen.coursebox.core.CourseLibrary
import com.wangxiuwen.coursebox.core.CoursePackageRecord
import com.wangxiuwen.coursebox.core.LanImportServer
import com.wangxiuwen.coursebox.core.UpdateAvailable
import com.wangxiuwen.coursebox.core.UpdateChecker
import com.wangxiuwen.coursebox.ui.theme.AccentBlue
import com.wangxiuwen.coursebox.ui.theme.toneFor
import kotlinx.coroutines.launch

private val PaperBg = Color(0xFFF5F4F1)
private val InkSoft = Color(0xFF6B6B66)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryTab(
    library: CourseLibrary,
    nav: NavHostController,
    modifier: Modifier = Modifier,
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val state by library.stateFlow

    var importing by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var menuOpen by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    var lanOpen by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    var checking by remember { mutableStateOf(false) }
    var updateFound by remember { mutableStateOf<UpdateAvailable?>(null) }
    var noUpdate by remember { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            importing = true
            status = "导入中…"
            runCatching { library.importZip(ctx, uri) }
                .onSuccess { res ->
                    val titles = res.packages.joinToString { it.title }
                    status = "已导入：$titles · 新增 ${res.addedObjects} 个对象"
                }
                .onFailure { e -> status = "导入失败：${e.message}" }
            importing = false
        }
    }

    Box(modifier = modifier.fillMaxSize().background(PaperBg).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 12.dp, top = 18.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "课程",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                )
                Spacer(Modifier.weight(1f))
                Box {
                    IconButton(onClick = { overflowOpen = true }) {
                        Icon(
                            Icons.Default.MoreVert,
                            contentDescription = "更多",
                            tint = Color.Black,
                            modifier = Modifier.size(26.dp),
                        )
                    }
                    DropdownMenu(
                        expanded = overflowOpen,
                        onDismissRequest = { overflowOpen = false },
                        containerColor = Color.White,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        DropdownMenuItem(
                            text = { Text("检查更新 · 当前 v${BuildConfig.VERSION_NAME}") },
                            colors = MenuDefaults.itemColors(
                                textColor = Color.Black,
                                leadingIconColor = Color.Black,
                            ),
                            onClick = {
                                overflowOpen = false
                                scope.launch {
                                    checking = true
                                    val result = runCatching {
                                        UpdateChecker.check(BuildConfig.VERSION_NAME)
                                    }.getOrNull()
                                    checking = false
                                    if (result != null) {
                                        updateFound = result
                                    } else {
                                        noUpdate = true
                                    }
                                }
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("MOSS-TTS · 设备端 (实验)") },
                            colors = MenuDefaults.itemColors(
                                textColor = Color.Black,
                                leadingIconColor = Color.Black,
                            ),
                            onClick = {
                                overflowOpen = false
                                nav.navigate("tts")
                            },
                        )
                    }
                }
                Box {
                    IconButton(onClick = { menuOpen = true }) {
                        if (importing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(22.dp),
                                strokeWidth = 2.5.dp,
                                color = Color.Black,
                            )
                        } else {
                            Icon(
                                Icons.Default.Add,
                                contentDescription = "添加",
                                tint = Color.Black,
                                modifier = Modifier.size(28.dp),
                            )
                        }
                    }
                    DropdownMenu(
                        expanded = menuOpen,
                        onDismissRequest = { menuOpen = false },
                        containerColor = Color.White,
                        shape = RoundedCornerShape(12.dp),
                    ) {
                        DropdownMenuItem(
                            text = { Text("从本地 zip 导入") },
                            leadingIcon = {
                                Icon(Icons.Default.FileDownload, null, tint = Color.Black)
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = Color.Black,
                                leadingIconColor = Color.Black,
                            ),
                            onClick = {
                                menuOpen = false
                                picker.launch(arrayOf("application/zip", "*/*"))
                            },
                        )
                        DropdownMenuItem(
                            text = { Text("局域网导入") },
                            leadingIcon = {
                                Icon(Icons.Default.Wifi, null, tint = Color.Black)
                            },
                            colors = MenuDefaults.itemColors(
                                textColor = Color.Black,
                                leadingIconColor = Color.Black,
                            ),
                            onClick = {
                                menuOpen = false
                                lanOpen = true
                            },
                        )
                    }
                }
            }
            if (checking) {
                Text(
                    "检查更新中…",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = InkSoft,
                    maxLines = 1,
                )
            }
            // Search field — always visible, same pattern as NceListScreen
            Surface(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                color = Color.White,
                shape = RoundedCornerShape(14.dp),
                shadowElevation = 1.dp,
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Default.Search, contentDescription = null, tint = InkSoft, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    androidx.compose.foundation.text.BasicTextField(
                        value = query,
                        onValueChange = { query = it },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Color.Black),
                        decorationBox = { inner ->
                            if (query.isEmpty()) {
                                Text("搜索课程名称", color = InkSoft, style = MaterialTheme.typography.bodyMedium)
                            }
                            inner()
                        },
                    )
                }
            }
            status?.let { txt ->
                Surface(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                    color = if (txt.startsWith("导入失败")) Color(0xFFFEE2E2) else Color(0xFFE6F4EA),
                    shape = RoundedCornerShape(10.dp),
                ) {
                    Text(
                        txt,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = if (txt.startsWith("导入失败")) Color(0xFF7F1D1D) else Color(0xFF166534),
                    )
                }
            }

            if (state.packages.isEmpty()) {
                EmptyState(onTapAdd = { menuOpen = true })
            } else {
                val sorted = remember(state.packages, state.pinned, query) {
                    val order = state.pinned.withIndex().associate { (i, id) -> id to i }
                    val needle = query.trim().lowercase()
                    state.packages
                        .asSequence()
                        .filter { needle.isBlank() || it.title.lowercase().contains(needle) }
                        .sortedBy { order[it.id] ?: Int.MAX_VALUE }
                        .toList()
                }
                // Adaptive so tablets (esp. landscape, ~1280dp) get more columns
                // instead of two huge half-screen cards. minSize=160dp keeps
                // phones at 2 columns and lets a 10" tablet land on 6-8.
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(
                        start = 12.dp, end = 12.dp, top = 6.dp, bottom = 130.dp,
                    ),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(sorted, key = { it.id }) { pkg ->
                        CourseGridCard(
                            pkg = pkg,
                            isPinned = pkg.id in state.pinned,
                            onClick = { openCourse(pkg, nav) },
                            onTogglePin = { scope.launch { library.togglePinned(pkg.id) } },
                        )
                    }
                }
            }
        }

        if (lanOpen) {
            LanImportDialog(
                ctx = ctx,
                library = library,
                onDismiss = { lanOpen = false },
            )
        }

        // Manual "检查更新" path — same split as the auto-launcher: kick off
        // a silent download then prompt to install once the file is on disk.
        updateFound?.let { u ->
            AlertDialog(
                onDismissRequest = { updateFound = null },
                containerColor = Color.White,
                title = { Text("发现新版本 v${u.latestVersion}") },
                text = {
                    Text(
                        if (downloading) "正在后台下载…完成后会提示安装。"
                        else "当前版本：v${u.currentVersion}\n\n" +
                            (u.release.body.take(280).ifBlank { "点击立即下载, 完成后再确认安装。" }),
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !downloading,
                        onClick = {
                            downloading = true
                            scope.launch {
                                runCatching { UpdateChecker.download(ctx, u.apkAsset) }
                                    .onSuccess { apk ->
                                        UpdateChecker.install(ctx, apk)
                                    }
                                downloading = false
                                updateFound = null
                            }
                        },
                    ) {
                        Text(if (downloading) "下载中…" else "立即下载", color = AccentBlue)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { updateFound = null }) {
                        Text("稍后", color = InkSoft)
                    }
                },
            )
        }

        if (noUpdate) {
            AlertDialog(
                onDismissRequest = { noUpdate = false },
                containerColor = Color.White,
                title = { Text("已是最新版本") },
                text = { Text("当前版本 v${BuildConfig.VERSION_NAME}") },
                confirmButton = {
                    TextButton(onClick = { noUpdate = false }) {
                        Text("好", color = AccentBlue)
                    }
                },
            )
        }
    }
}

@Composable
private fun LanImportDialog(
    ctx: android.content.Context,
    library: CourseLibrary,
    onDismiss: () -> Unit,
) {
    var serverStatus by remember { mutableStateOf("启动服务器…") }
    var url by remember { mutableStateOf<String?>(null) }
    var qr by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    val server = remember {
        LanImportServer(ctx, library) { msg -> serverStatus = msg }
    }
    DisposableEffect(Unit) {
        runCatching {
            server.start(fi.iki.elonen.NanoHTTPD.SOCKET_READ_TIMEOUT, false)
            val u = LanImportServer.url()
            if (u != null) {
                url = u
                qr = LanImportServer.qrBitmap(u, 720)
                serverStatus = "等待上传…"
            } else {
                serverStatus = "未检测到 Wi-Fi，请检查网络"
            }
        }.onFailure { serverStatus = "启动失败：${it.message}" }
        onDispose { runCatching { server.stop() } }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Color.White,
        title = { Text("局域网导入") },
        text = {
            Column {
                Text(
                    "电脑/平板在浏览器打开下面的地址，选 .coursebox.zip 上传。",
                    color = InkSoft,
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(12.dp))
                qr?.let { bmp ->
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "二维码",
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f),
                    )
                }
                Spacer(Modifier.height(10.dp))
                Text(
                    url ?: "—",
                    color = Color.Black,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(8.dp))
                Text(serverStatus, style = MaterialTheme.typography.bodySmall, color = InkSoft)
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("关闭", color = AccentBlue) }
        },
    )
}

@Composable
private fun CourseGridCard(
    pkg: CoursePackageRecord,
    isPinned: Boolean,
    onClick: () -> Unit,
    onTogglePin: () -> Unit,
) {
    val tone = toneFor(pkg.type)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 1.dp,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(3f / 4f)
                    .background(tone.gradient),
            ) {
                // Direct pin toggle on top-right of the cover. No long-press needed.
                IconButton(
                    onClick = onTogglePin,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(2.dp)
                        .size(36.dp),
                ) {
                    Icon(
                        Icons.Default.PushPin,
                        contentDescription = if (isPinned) "取消置顶" else "置顶",
                        tint = if (isPinned) Color.White else Color.White.copy(alpha = 0.55f),
                        modifier = Modifier.size(20.dp),
                    )
                }
                Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                    Surface(
                        color = Color.Black.copy(alpha = 0.32f),
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            "${pkg.lessonIndex.size} 课次",
                            color = Color.White,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                    Spacer(Modifier.weight(1f))
                    Text(
                        tone.label,
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
            }
            Column(
                modifier = Modifier
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .heightIn(min = 44.dp),
            ) {
                Text(
                    pkg.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 2,
                    minLines = 2,
                    softWrap = true,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(onTapAdd: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().padding(36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Surface(
            color = Color.White,
            shape = RoundedCornerShape(20.dp),
            shadowElevation = 1.dp,
        ) {
            Column(
                modifier = Modifier.padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("📦", fontSize = 36.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "还没有课程",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "右上角加号 → 选择本地 .coursebox.zip",
                    style = MaterialTheme.typography.bodyMedium,
                    color = InkSoft,
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onTapAdd,
                    colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                ) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("立即添加")
                }
            }
        }
    }
}

private fun openCourse(record: CoursePackageRecord, nav: NavHostController) {
    when (record.type) {
        "chinese_poetry" -> nav.navigate("chinese/${record.id}")
        "music" -> nav.navigate("music/${record.id}")
        // nce / video / mixed / audio_course / anything else → list+player.
        // NcePlayerScreen already routes to a SurfaceView when the lesson
        // has video_hash, so video packs flow through the same screen.
        else -> nav.navigate("nce/${record.id}")
    }
}
