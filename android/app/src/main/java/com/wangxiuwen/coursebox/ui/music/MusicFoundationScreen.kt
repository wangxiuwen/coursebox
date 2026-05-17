package com.wangxiuwen.coursebox.ui.music

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.OpenInNew
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.navigation.NavHostController
import androidx.core.content.FileProvider
import com.wangxiuwen.coursebox.core.CourseLibrary
import com.wangxiuwen.coursebox.ui.SlimSlider
import com.wangxiuwen.coursebox.ui.fmtTime
import com.wangxiuwen.coursebox.ui.theme.AccentBlue
import com.wangxiuwen.coursebox.ui.theme.toneFor
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

private val PaperBg = Color(0xFFF5F4F1)
private val Ink = Color(0xFF111111)
private val InkSoft = Color(0xFF6B6B66)

data class MusicAsset(
    val id: String,
    val title: String,
    val subtitle: String,
    val type: String,
    val grade: String,
    val subject: String,
    val groupLabel: String,
    val audioHash: String,
    val logicalPath: String,
) {
    fun resolve(library: CourseLibrary): String? =
        library.resolve(hash = audioHash.ifBlank { null }, logicalPath = logicalPath.ifBlank { null })
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MusicFoundationScreen(
    library: CourseLibrary,
    courseId: String,
    nav: NavHostController,
) {
    val ctx = LocalContext.current
    val player = remember { ExoPlayer.Builder(ctx).build() }
    val tone = toneFor("music")

    var assets by remember { mutableStateOf<List<MusicAsset>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var grade by rememberSaveable { mutableStateOf("all") }
    var subject by rememberSaveable { mutableStateOf("all") }
    var query by rememberSaveable { mutableStateOf("") }
    var currentId by remember { mutableStateOf<String?>(null) }
    var positionMs by remember { mutableLongStateOf(0L) }
    var durationMs by remember { mutableLongStateOf(0L) }
    var isPlaying by remember { mutableStateOf(false) }

    DisposableEffect(player) {
        val listener = object : Player.Listener {
            override fun onIsPlayingChanged(playing: Boolean) { isPlaying = playing }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_READY) durationMs = player.duration.coerceAtLeast(0L)
            }
        }
        player.addListener(listener)
        onDispose {
            player.removeListener(listener)
            player.release()
        }
    }
    LaunchedEffect(player) {
        while (true) {
            positionMs = player.currentPosition.coerceAtLeast(0L)
            durationMs = player.duration.coerceAtLeast(durationMs)
            delay(250)
        }
    }
    LaunchedEffect(courseId) {
        assets = loadMusicAssets(library, courseId)
        loading = false
    }

    val grades = remember(assets) { listOf("all") + assets.map { it.grade }.distinct().filter { it.isNotBlank() } }
    val subjects = remember(assets) { listOf("all") + assets.map { it.subject }.distinct().filter { it.isNotBlank() } }
    val visible = remember(assets, grade, subject, query) {
        val needle = query.trim().lowercase()
        assets.asSequence()
            .filter { grade == "all" || it.grade == grade }
            .filter { subject == "all" || it.subject == subject }
            .filter { needle.isBlank() || it.title.lowercase().contains(needle) || it.subtitle.lowercase().contains(needle) }
            .toList()
    }

    Box(modifier = Modifier.fillMaxSize().background(PaperBg).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Ink)
                }
                Spacer(Modifier.width(4.dp))
                Text(
                    "音乐基础",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Default.Search, contentDescription = null, tint = Ink, modifier = Modifier.size(24.dp))
            }

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
                        textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                        decorationBox = { inner ->
                            if (query.isEmpty()) Text("搜索歌曲 / 知识点", color = InkSoft, style = MaterialTheme.typography.bodyMedium)
                            inner()
                        },
                    )
                }
            }

            FilterStrip("年级", grades, grade) { grade = it }
            FilterStrip("专题", subjects, subject) { subject = it }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                visible.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (assets.isEmpty()) "课程数据缺失" else "没有匹配的内容", color = InkSoft)
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = if (currentId != null) 200.dp else 130.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(visible, key = { it.id }) { asset ->
                        AssetRow(asset, tone, playing = isPlaying && currentId == asset.id) {
                            val path = asset.resolve(library) ?: return@AssetRow
                            if (asset.type == "audio") {
                                if (currentId != asset.id) {
                                    currentId = asset.id
                                    player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
                                    player.prepare()
                                }
                                if (player.isPlaying) player.pause() else player.play()
                            } else if (asset.type == "pdf") {
                                openPdf(ctx, File(path))
                            }
                        }
                    }
                }
            }
        }

        currentId?.let { cur ->
            val asset = assets.firstOrNull { it.id == cur } ?: return@let
            NowPlayingCard(
                asset = asset,
                positionMs = positionMs,
                durationMs = durationMs,
                isPlaying = isPlaying,
                onSeek = { player.seekTo(it) },
                onToggle = { if (player.isPlaying) player.pause() else player.play() },
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 12.dp, start = 12.dp, end = 12.dp),
            )
        }
    }
}

@Composable
private fun FilterStrip(label: String, values: List<String>, selected: String, onSelect: (String) -> Unit) {
    val scroll = rememberScrollState()
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(scroll).padding(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("$label", color = InkSoft, style = MaterialTheme.typography.labelMedium)
        values.forEach { v ->
            FilterPill(
                label = if (v == "all") "全部" else v,
                selected = v == selected,
                onClick = { onSelect(v) },
            )
        }
    }
}

@Composable
private fun FilterPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.clickable(onClick = onClick),
        shape = RoundedCornerShape(999.dp),
        color = if (selected) AccentBlue else Color.White,
        shadowElevation = if (selected) 0.dp else 1.dp,
    ) {
        Text(
            label,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
            color = if (selected) Color.White else InkSoft,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
        )
    }
}

@Composable
private fun AssetRow(
    asset: MusicAsset,
    tone: com.wangxiuwen.coursebox.ui.theme.CourseTone,
    playing: Boolean,
    onClick: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        shadowElevation = 1.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(tone.gradient),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    when (asset.type) {
                        "pdf" -> Icons.Default.PictureAsPdf
                        else -> if (playing) Icons.Default.Pause else Icons.Default.MusicNote
                    },
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(22.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    asset.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 2,
                    softWrap = true,
                    overflow = TextOverflow.Ellipsis,
                )
                if (asset.subtitle.isNotBlank()) {
                    Text(
                        asset.subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = InkSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            if (asset.type == "pdf") {
                Icon(Icons.Default.OpenInNew, contentDescription = null, tint = InkSoft, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun NowPlayingCard(
    asset: MusicAsset,
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    onSeek: (Long) -> Unit,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableLongStateOf(0L) }
    val pos = if (dragging) dragPos else positionMs
    val dur = durationMs.coerceAtLeast(1L)
    val tone = toneFor("music")
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp),
        color = Color.White,
        shadowElevation = 6.dp,
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(RoundedCornerShape(10.dp))
                        .background(tone.gradient),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Default.MusicNote, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(asset.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(asset.subtitle, style = MaterialTheme.typography.labelSmall, color = InkSoft, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.width(8.dp))
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(tone.gradMid)
                        .clickable { onToggle() },
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(
                        if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放",
                        tint = Color.White,
                        modifier = Modifier.size(20.dp),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            SlimSlider(
                value = pos.coerceAtMost(dur).toFloat(),
                valueRange = 0f..dur.toFloat(),
                onValueChange = { dragging = true; dragPos = it.toLong() },
                onValueChangeFinished = { onSeek(dragPos); dragging = false },
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(fmtTime(pos), style = MaterialTheme.typography.labelSmall, color = InkSoft)
                Text(fmtTime(durationMs), style = MaterialTheme.typography.labelSmall, color = InkSoft)
            }
        }
    }
}

private fun openPdf(ctx: android.content.Context, file: File) {
    val authority = "${ctx.packageName}.fileprovider"
    val uri = FileProvider.getUriForFile(ctx, authority, file)
    val intent = Intent(Intent.ACTION_VIEW).apply {
        setDataAndType(uri, "application/pdf")
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    ctx.startActivity(Intent.createChooser(intent, "打开 PDF"))
}

private suspend fun loadMusicAssets(library: CourseLibrary, courseId: String): List<MusicAsset> {
    val pkg = library.packageById(courseId) ?: return emptyList()
    val lessonsFile = File(pkg.lessonsManifestPath)
    val arr: JsonArray = if (lessonsFile.exists()) {
        runCatching {
            kotlinx.serialization.json.Json.parseToJsonElement(lessonsFile.readText()) as? JsonArray
        }.getOrNull() ?: return emptyList()
    } else {
        val raw = library.loadString("music/music_assets.json") ?: return emptyList()
        runCatching { kotlinx.serialization.json.Json.parseToJsonElement(raw) as? JsonArray }.getOrNull() ?: return emptyList()
    }

    return arr.mapNotNull { node ->
        val obj = node as? JsonObject ?: return@mapNotNull null
        val md = (obj["metadata"] as? JsonObject) ?: obj
        fun str(key: String) = (md[key] as? JsonPrimitive)?.contentOrNull
            ?: (obj[key] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val logical = (md["logical_path"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["path"] as? JsonPrimitive)?.contentOrNull.orEmpty()
        val title = (obj["title"] as? JsonPrimitive)?.contentOrNull
            ?: (obj["rawName"] as? JsonPrimitive)?.contentOrNull
            ?: logical.substringAfterLast('/')
        MusicAsset(
            id = (obj["id"] as? JsonPrimitive)?.contentOrNull ?: logical.ifBlank { title },
            title = title,
            subtitle = listOfNotNull(
                str("grade").takeIf { it.isNotBlank() },
                str("subject").takeIf { it.isNotBlank() },
                str("groupLabel").takeIf { it.isNotBlank() },
            ).joinToString(" · "),
            type = str("type").ifBlank { if (logical.endsWith(".pdf", true)) "pdf" else "audio" },
            grade = str("grade"),
            subject = str("subject"),
            groupLabel = str("groupLabel"),
            audioHash = (obj["audio_hash"] as? JsonPrimitive)?.contentOrNull.orEmpty(),
            logicalPath = logical,
        )
    }
}
