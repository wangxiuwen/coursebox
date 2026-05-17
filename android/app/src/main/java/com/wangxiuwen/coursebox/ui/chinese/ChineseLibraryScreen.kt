package com.wangxiuwen.coursebox.ui.chinese

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.wangxiuwen.coursebox.core.CourseLibrary
import com.wangxiuwen.coursebox.ui.theme.AccentBlue
import kotlinx.coroutines.launch

private val PaperBg = Color(0xFFF5F4F1)
private val Ink = Color(0xFF111111)
private val InkSoft = Color(0xFF6B6B66)
private val PoetryBrown = Color(0xFFB45309)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChineseLibraryScreen(
    library: CourseLibrary,
    courseId: String,
    nav: NavHostController,
) {
    val scope = rememberCoroutineScope()
    var works by remember { mutableStateOf<List<ChineseWork>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var query by rememberSaveable { mutableStateOf("") }
    var selectedSource by rememberSaveable { mutableStateOf("all") }
    var detail by remember { mutableStateOf<ChineseWork?>(null) }

    LaunchedEffect(courseId) {
        scope.launch {
            works = loadChineseLibrary(library)
            loading = false
        }
    }

    val sourceCounts = remember(works) { works.groupingBy { it.sourceKey }.eachCount() }
    val filtered = remember(works, query, selectedSource) {
        val needle = query.trim().lowercase()
        works.asSequence()
            .filter { selectedSource == "all" || it.sourceKey == selectedSource }
            .filter { needle.isBlank() || it.searchableText.contains(needle) }
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
                    "古诗词与经典",
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
                            if (query.isEmpty()) {
                                Text("搜索标题 / 作者 / 章节 / 内容", color = InkSoft, style = MaterialTheme.typography.bodyMedium)
                            }
                            inner()
                        },
                    )
                }
            }

            // Source filter pills
            val scrollState = rememberScrollState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(scrollState)
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                SourcePill("全部 (${works.size})", selectedSource == "all") { selectedSource = "all" }
                chineseSources.forEach { src ->
                    val c = sourceCounts[src.key] ?: 0
                    SourcePill("${src.label} ($c)", selectedSource == src.key) {
                        selectedSource = src.key
                    }
                }
            }

            when {
                loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
                filtered.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(if (works.isEmpty()) "课程数据缺失" else "没有匹配的作品", color = InkSoft)
                }
                else -> LazyColumn(
                    contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 130.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(filtered, key = { it.id }) { w ->
                        WorkRow(w) { detail = w }
                    }
                }
            }
        }
    }

    detail?.let { w ->
        ModalBottomSheet(onDismissRequest = { detail = null }) {
            WorkDetail(w)
        }
    }
}

@Composable
private fun SourcePill(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(onClick = onClick),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            label,
            color = if (selected) Ink else InkSoft,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        if (selected) {
            Spacer(Modifier.height(3.dp))
            Box(
                modifier = Modifier
                    .height(3.dp)
                    .width(18.dp)
                    .clip(RoundedCornerShape(2.dp))
                    .background(AccentBlue),
            )
        }
    }
}

@Composable
private fun WorkRow(work: ChineseWork, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(14.dp),
        color = Color.White,
        shadowElevation = 1.dp,
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Box(
                modifier = Modifier
                    .size(46.dp, 56.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(PoetryBrown),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    work.typeLabel,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    work.title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = Ink,
                    maxLines = 2,
                    softWrap = true,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    work.subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = InkSoft,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (work.preview.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        work.preview,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Ink,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun WorkDetail(work: ChineseWork) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 18.dp),
    ) {
        Text(work.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(4.dp))
        Text(work.subtitle, style = MaterialTheme.typography.bodyMedium, color = InkSoft)
        if (work.tags.isNotEmpty()) {
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                work.tags.take(6).forEach { tag ->
                    Surface(
                        color = Color(0xFFF1ECE3),
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            tag,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            color = PoetryBrown,
                        )
                    }
                }
            }
        }
        Spacer(Modifier.height(16.dp))
        work.paragraphs.forEach { line ->
            Text(
                line,
                style = MaterialTheme.typography.bodyLarge,
                color = Ink,
                modifier = Modifier.padding(bottom = 10.dp),
            )
        }
        Spacer(Modifier.height(40.dp))
    }
}
