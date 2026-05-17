package com.wangxiuwen.coursebox.ui.learning

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.wangxiuwen.coursebox.CourseboxApp
import com.wangxiuwen.coursebox.core.CourseLibrary
import com.wangxiuwen.coursebox.core.CoursePackageRecord
import com.wangxiuwen.coursebox.ui.theme.toneFor
import java.time.LocalTime

private val PaperBg = Color(0xFFF5F1EA)
private val Ink = Color(0xFF2A2520)
private val Soft = Color(0xFF8E837A)
private val Faint = Color(0xFFB6A89A)
private val Stripe = Color(0xFFE6DCD0)
private val Warm = Color(0xFFD97706)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LearningTab(
    library: CourseLibrary,
    nav: NavHostController,
    onJumpToLibrary: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by library.stateFlow
    val learning = remember(state) {
        state.packages.filter { it.id in state.learning }
    }
    // Surface the currently playing course if any, even if user hasn't 'starred' it.
    val playerVm = CourseboxApp.playerVm
    val activeId = playerVm.currentPackageId

    Box(modifier = modifier.fillMaxSize().background(PaperBg).statusBarsPadding()) {
        if (learning.isEmpty() && activeId == null) {
            EmptyBookshelf(onJump = onJumpToLibrary)
            return@Box
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 16.dp, bottom = 130.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            item { Header() }
            // Active "now reading" card — drives engagement, sits above the shelf.
            val active = activeId?.let { id -> state.packages.firstOrNull { it.id == id } }
            if (active != null) {
                item { NowReadingCard(active, onOpen = { openCourse(active, nav) }) }
            }
            item { ShelvesHeader(count = learning.size) }
            if (learning.isNotEmpty()) {
                item { ShelfRow(learning, onOpen = { openCourse(it, nav) }) }
            }
        }
    }
}

@Composable
private fun Header() {
    val hour = remember { LocalTime.now().hour }
    val greeting = when (hour) {
        in 5..10 -> "早上好"
        in 11..13 -> "中午好"
        in 14..17 -> "下午好"
        in 18..22 -> "晚上好"
        else -> "夜深了"
    }
    Column {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("课程盒子", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            Spacer(Modifier.weight(1f))
            Icon(Icons.Default.Search, contentDescription = null, tint = Ink)
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "$greeting，今天读点什么？",
            color = Soft,
            style = MaterialTheme.typography.bodyMedium,
        )
    }
}

@Composable
private fun NowReadingCard(pkg: CoursePackageRecord, onOpen: () -> Unit) {
    val tone = toneFor(pkg.type)
    val playerVm = CourseboxApp.playerVm
    val cur = playerVm.current
    val progress = if (playerVm.durationMs > 0)
        (playerVm.positionMs.toFloat() / playerVm.durationMs).coerceIn(0f, 1f)
    else 0f

    Surface(
        modifier = Modifier.fillMaxWidth().clickable { onOpen() },
        shape = RoundedCornerShape(16.dp),
        color = Color.White,
        shadowElevation = 1.dp,
    ) {
        Row(modifier = Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            // mini book spine
            Box(
                modifier = Modifier
                    .size(56.dp, 72.dp)
                    .clip(RoundedCornerShape(2.dp, 6.dp, 6.dp, 2.dp))
                    .background(tone.gradient),
                contentAlignment = Alignment.BottomStart,
            ) {
                Text(
                    tone.short,
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.ExtraBold,
                    modifier = Modifier.padding(start = 10.dp, bottom = 6.dp),
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .fillMaxHeight()
                        .width(6.dp)
                        .background(Color.Black.copy(alpha = 0.22f)),
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "在读 · " + tone.label,
                    style = MaterialTheme.typography.labelMedium,
                    color = Soft,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    cur?.titleEn?.ifBlank { cur.numberLabel } ?: pkg.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Ink,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(8.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Stripe),
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceAtLeast(0.04f))
                            .fillMaxHeight()
                            .background(
                                Brush.horizontalGradient(listOf(Color(0xFFD97706), Color(0xFFF59E0B))),
                            ),
                    )
                }
                Spacer(Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    val l = cur?.lesson
                    val sub = if (l != null) "第 $l 课" else "${pkg.lessonIndex.size} 课"
                    Text(sub, style = MaterialTheme.typography.labelSmall, color = Faint)
                    Text("${(progress * 100).toInt()}%", style = MaterialTheme.typography.labelSmall, color = Faint)
                }
            }
        }
    }
}

@Composable
private fun ShelvesHeader(count: Int) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text("我的书架", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(
            if (count > 0) "已加学 $count" else "去资源库加入更多 →",
            style = MaterialTheme.typography.labelMedium,
            color = Warm,
        )
    }
}

@Composable
private fun ShelfRow(items: List<CoursePackageRecord>, onOpen: (CoursePackageRecord) -> Unit) {
    Column {
        // Render in rows of 3 with the wooden-shelf rule under each row.
        items.chunked(3).forEach { row ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                row.forEach { pkg -> BookSpine(pkg, modifier = Modifier.weight(1f), onClick = { onOpen(pkg) }) }
                repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
            }
            Spacer(Modifier.height(6.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .background(Stripe),
            )
            Spacer(Modifier.height(14.dp))
        }
    }
}

@Composable
private fun BookSpine(pkg: CoursePackageRecord, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val tone = toneFor(pkg.type)
    Column(modifier = modifier.clickable { onClick() }) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(3f / 4f)
                .clip(RoundedCornerShape(2.dp, 8.dp, 8.dp, 2.dp))
                .background(tone.gradient),
        ) {
            // Dark gradient on the spine left edge.
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(7.dp)
                    .background(
                        Brush.horizontalGradient(
                            listOf(Color.Black.copy(alpha = 0.4f), Color.Transparent),
                        ),
                    ),
            )
            Text(
                tone.short,
                color = Color.White.copy(alpha = 0.15f),
                fontSize = 140.sp,
                fontWeight = FontWeight.Black,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = 18.dp, y = 30.dp),
            )
            Text(
                pkg.title.lineFirst(),
                color = Color.White,
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.labelLarge,
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .padding(start = 12.dp, end = 6.dp, bottom = 10.dp),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Text(
            pkg.title,
            color = Ink,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            "${pkg.lessonIndex.size} 课",
            color = Faint,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

@Composable
private fun EmptyBookshelf(onJump: () -> Unit) {
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
                Text("📚", fontSize = 36.sp)
                Spacer(Modifier.height(10.dp))
                Text(
                    "书架空空如也",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "去资源库挑一个课程开始学习",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Soft,
                )
                Spacer(Modifier.height(14.dp))
                Button(
                    onClick = onJump,
                    colors = ButtonDefaults.buttonColors(containerColor = Warm),
                ) {
                    Icon(Icons.Default.PlayArrow, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("去资源库")
                }
            }
        }
    }
}

private fun String.lineFirst(): String = trim().split(' ').firstOrNull() ?: this

private fun openCourse(pkg: CoursePackageRecord, nav: NavHostController) {
    when {
        pkg.type == "nce" || pkg.id.startsWith("nce") -> nav.navigate("nce/${pkg.id}")
        pkg.type == "chinese_poetry" -> nav.navigate("chinese/${pkg.id}")
        pkg.type == "music" -> nav.navigate("music/${pkg.id}")
    }
}
