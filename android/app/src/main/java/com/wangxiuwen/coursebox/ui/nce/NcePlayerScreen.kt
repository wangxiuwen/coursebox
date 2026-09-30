package com.wangxiuwen.coursebox.ui.nce

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.VerticalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.snapshotFlow
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
import com.wangxiuwen.coursebox.ui.SlimSlider
import com.wangxiuwen.coursebox.ui.fmtTime
import com.wangxiuwen.coursebox.ui.theme.CourseTone
import com.wangxiuwen.coursebox.ui.theme.toneFor
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch

private val ScreenBlack = Color(0xFF000000)
private val OnDark = Color.White
private val OnDarkDim = Color(0xCCFFFFFF)
private val OnDarkFaint = Color(0x66FFFFFF)
// Player chrome sits on a deep-blue gradient; the global AccentBlue
// would disappear against tone.gradMid, so use a warm amber accent here
// for high-contrast highlights (tab underline, word pron, etc).
private val PlayerAccent = Color(0xFFFBBF24)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NcePlayerScreen(
    library: CourseLibrary,
    courseId: String,
    lessonId: String,
    nav: NavHostController,
) {
    val scope = rememberCoroutineScope()
    val vm = remember { CourseboxApp.playerVm }
    var showDrill by rememberSaveable { mutableStateOf(false) }

    var ready by remember { mutableStateOf(false) }
    LaunchedEffect(courseId, lessonId) {
        scope.launch {
            val lessons = loadNceLessons(library, courseId)
            vm.load(courseId, lessons, library, lessonId)
            ready = true
        }
    }

    // Tone derives from the owning course so the player chrome matches
    // the library card's hue — purple 900句 stays purple in the player,
    // red NCE-3 stays red, etc.
    val pkg = remember(courseId) { library.packageById(courseId) }
    val tone = toneFor(pkg?.type ?: "nce", pkg?.id ?: courseId)
    val lesson = vm.current ?: vm.playlist.firstOrNull()
    val bgGradient = if (vm.showBack)
        Brush.verticalGradient(listOf(tone.gradMid, tone.gradEnd, ScreenBlack))
    else
        Brush.verticalGradient(listOf(tone.gradMid, ScreenBlack, ScreenBlack))

    Box(modifier = Modifier.fillMaxSize().background(bgGradient)) {
        if (!ready) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = OnDark)
            }
            return@Box
        }
        if (lesson == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("课程数据缺失，无法播放", color = OnDarkDim)
            }
            return@Box
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Chrome
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }, modifier = Modifier.size(40.dp)) {
                    Icon(Icons.Default.KeyboardArrowDown, contentDescription = "收起", tint = OnDark)
                }
                Spacer(Modifier.weight(1f))
                Text(
                    "NEW CONCEPT ENGLISH · BOOK 02",
                    color = OnDarkDim,
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.labelMedium,
                )
                Spacer(Modifier.weight(1f))
                Spacer(Modifier.size(40.dp))
            }

            if (!vm.showBack) {
                PlayerFront(vm, lesson, tone, onOpenDrill = { showDrill = true })
            } else {
                PlayerBackLyrics(vm, lesson, tone)
            }
        }

        // Full-screen per-sentence drill overlays everything, player
        // chrome included. Leaving it resumes playback after the drilled
        // sentence (same semantics the old bottom sheet had).
        if (showDrill) {
            SentenceDrillScreen(
                vm = vm,
                onDismiss = {
                    vm.finishSentencePracticeAndContinue()
                    showDrill = false
                },
            )
        }
    }
}

@Composable
private fun ColumnScope.PlayerFront(
    vm: NcePlayerVm,
    lesson: NceLesson,
    tone: CourseTone,
    onOpenDrill: () -> Unit,
) {
    // In landscape, fillMaxWidth + aspectRatio blows the media box past the
    // screen height (a 16:9 video on a 2400x1080 phone wants 1332px tall),
    // wiping the controls. Bind height to a fraction of the screen instead
    // and let aspectRatio drive the width, centred horizontally.
    val cfg = androidx.compose.ui.platform.LocalConfiguration.current
    val isLandscape = cfg.screenWidthDp > cfg.screenHeightDp

    if (vm.hasVideo) {
        // Video face: SurfaceView wrapped in a black rounded shell.
        // Bound to the ExoPlayer via VM; detached on disposal.
        val aspect = vm.videoAspect.coerceAtLeast(0.6f)
        val mediaModifier = if (isLandscape) {
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxHeight(0.42f)
                .aspectRatio(aspect)
                .align(Alignment.CenterHorizontally)
        } else {
            Modifier
                .padding(horizontal = 16.dp)
                .fillMaxWidth()
                .aspectRatio(aspect)
        }
        Box(
            modifier = mediaModifier
                // Visible border so the black SurfaceView doesn't blend
                // into the page's ScreenBlack gradient stop (especially in
                // landscape, where the media box sits in the lower half
                // of the screen where the bg has faded to pure black).
                .border(
                    width = 1.dp,
                    color = Color.White.copy(alpha = 0.18f),
                    shape = RoundedCornerShape(14.dp),
                )
                .clip(RoundedCornerShape(14.dp))
                .background(Color.Black)
                // The whole video face doubles as the pause/play target —
                // the learner should never have to hunt for the small
                // round button mid-lesson.
                .clickable { vm.togglePlayPause() },
        ) {
            androidx.compose.ui.viewinterop.AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { c ->
                    android.view.SurfaceView(c).also { sv -> vm.attachVideoSurfaceView(sv) }
                },
            )
            DisposableEffect(Unit) {
                onDispose { /* surface auto-released with SurfaceView */ }
            }
        }
    } else if (isLandscape) {
        CoverFace(
            lesson = lesson,
            tone = tone,
            onClick = { vm.togglePlayPause() },
            modifier = Modifier
                .padding(horizontal = 32.dp)
                .fillMaxHeight(0.42f)
                .aspectRatio(1f)
                .align(Alignment.CenterHorizontally),
        )
    } else {
        // Portrait: the old fillMaxWidth + aspectRatio(1f) cover alone
        // overflowed the M5's short display, pushing the transport controls
        // half off screen — the play button looked "squashed" and was
        // barely tappable. Bind the cover to whatever height is left after
        // the fixed controls, as a centred square; it can shrink but the
        // controls can never be pushed out.
        BoxWithConstraints(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .padding(horizontal = 24.dp),
            contentAlignment = Alignment.Center,
        ) {
            val side = minOf(maxWidth, maxHeight)
            CoverFace(
                lesson = lesson,
                tone = tone,
                onClick = { vm.togglePlayPause() },
                modifier = Modifier.size(side),
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                lesson.titleEn.ifBlank { lesson.numberLabel },
                color = OnDark,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (lesson.titleCn.isNotBlank()) {
                Text(
                    "${lesson.titleCn} · 第 ${lesson.lesson} 课",
                    color = OnDarkDim,
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }

    // Current-transcript glance card. The learner's "what was that
    // sentence?" moment happens on this face — show the line being read
    // right here, and let a tap open the full lyrics page. Replaces the
    // old bottom flip pill (and gives video lessons a lyrics entry too).
    val curLine = currentLineText(vm, lesson)
    val curEn = curLine?.first.orEmpty().trim()
    val curCn = curLine?.second.orEmpty().trim()
    val curMain = when {
        curEn.isNotBlank() -> curEn
        curCn.isNotBlank() -> curCn
        else -> "课文 / 单词"
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp)
            .padding(top = 8.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(Color(0x26FFFFFF))
            .clickable { vm.setFlip(true) }
            .padding(horizontal = 14.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                curMain,
                color = OnDark,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (curEn.isNotBlank() && curCn.isNotBlank()) {
                Text(
                    curCn,
                    color = OnDarkDim,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Spacer(Modifier.width(10.dp))
        Icon(
            Icons.AutoMirrored.Filled.MenuBook,
            contentDescription = "查看课文全文",
            tint = OnDarkFaint,
            modifier = Modifier.size(18.dp),
        )
    }

    Spacer(Modifier.height(if (isLandscape) 6.dp else 10.dp))
    SliderRow(vm)
    SentenceTransportRow(vm, onOpenDrill)
    TransportRow(vm)
}

/** Audio-lesson cover: gradient square with the book watermark and the
 *  lesson number. Doubles as a giant play/pause target. All type inside
 *  scales with the square's side — the flexible portrait layout can
 *  shrink the cover well below its natural size, and fixed sp sizes
 *  would clip ("LESSON" wrapped, the number pushed out). */
@Composable
private fun CoverFace(lesson: NceLesson, tone: CourseTone, onClick: () -> Unit, modifier: Modifier) {
    BoxWithConstraints(
        modifier = modifier
            .clickable(onClick = onClick)
            .clip(RoundedCornerShape(14.dp))
            .background(tone.gradient),
    ) {
        val side = maxWidth
        Text(
            text = if (lesson.book in 1..4) "B${lesson.book}" else lesson.bookLabel,
            color = Color.White.copy(alpha = 0.18f),
            fontSize = (side.value * 0.85f).sp,
            fontWeight = FontWeight.Black,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .offset(x = side * 0.04f, y = side * 0.13f),
        )
        Column(modifier = Modifier.fillMaxSize().padding((side.value * 0.09f).dp)) {
            Text(
                "LESSON",
                color = OnDark.copy(alpha = 0.9f),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.weight(1f))
            Text(
                lesson.lesson.toString().padStart(2, '0'),
                color = OnDark,
                fontSize = (side.value * 0.36f).sp,
                fontWeight = FontWeight.Black,
            )
        }
    }
}

@Composable
private fun SliderRow(vm: NcePlayerVm) {
    var dragging by remember { mutableStateOf(false) }
    var dragPos by remember { mutableLongStateOf(0L) }
    val effective = if (dragging) dragPos else vm.positionMs
    val duration = vm.durationMs.coerceAtLeast(0L)
    Column(modifier = Modifier.padding(horizontal = 32.dp)) {
        SlimSlider(
            value = effective.coerceAtMost(duration).toFloat(),
            valueRange = 0f..(if (duration > 0) duration.toFloat() else 1f),
            enabled = duration > 0,
            onValueChange = { dragging = true; dragPos = it.toLong() },
            onValueChangeFinished = { vm.seekTo(dragPos); dragging = false },
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(fmtTime(effective), color = OnDarkFaint, style = MaterialTheme.typography.labelSmall)
            Text(fmtTime(duration), color = OnDarkFaint, style = MaterialTheme.typography.labelSmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SentenceTransportRow(vm: NcePlayerVm, onOpenDrill: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                modifier = Modifier.weight(1.2f),
                onClick = onOpenDrill,
                enabled = vm.speechSegments.isNotEmpty(),
                contentPadding = PaddingValues(horizontal = 2.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = OnDark),
            ) { Text("逐句", style = MaterialTheme.typography.labelLarge) }
            TextButton(
                modifier = Modifier.weight(1.2f),
                onClick = vm::playPreviousSentence,
                contentPadding = PaddingValues(horizontal = 2.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = OnDark),
            ) { Text("上一句", style = MaterialTheme.typography.labelLarge) }
            TextButton(
                modifier = Modifier.weight(1.2f),
                onClick = vm::replayCurrentSentence,
                contentPadding = PaddingValues(horizontal = 2.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = PlayerAccent),
            ) { Text("重听", style = MaterialTheme.typography.labelLarge) }
            TextButton(
                modifier = Modifier.weight(1.2f),
                onClick = vm::playNextSentence,
                contentPadding = PaddingValues(horizontal = 2.dp),
                colors = ButtonDefaults.textButtonColors(contentColor = OnDark),
            ) { Text("下一句", style = MaterialTheme.typography.labelLarge) }
        }
        Text(
            when {
                vm.sentencePracticeMode == SentencePracticeMode.REPEAT_ONE ->
                    "正在循环第 ${vm.activeSentenceIndex + 1} 句"
                vm.sentencePracticeMode == SentencePracticeMode.SHADOWING &&
                    vm.shadowingPhase == ShadowingPhase.SPEAKING ->
                    "第 ${vm.activeSentenceIndex + 1} 句：现在跟读，稍后自动播放下一句"
                vm.sentencePracticeMode == SentencePracticeMode.SHADOWING ->
                    "第 ${vm.activeSentenceIndex + 1} 句：听原音"
                vm.sentenceAnalysisState == SentenceAnalysisState.ANALYZING ->
                    "正在离线分析语音…按钮暂按 8 秒跳转"
                vm.sentenceAnalysisState == SentenceAnalysisState.READY ->
                    "已识别 ${vm.speechSegments.size} 个语音片段"
                vm.sentenceAnalysisState == SentenceAnalysisState.FAILED ->
                    "未识别到语音，按钮按 8 秒跳转"
                else -> ""
            },
            color = OnDarkFaint,
            style = MaterialTheme.typography.labelSmall,
        )
    }
}

/**
 * Full-screen per-sentence drill, entered from the player's 句子 button.
 * Short-video mechanics: one sentence per screen, swipe up for the next,
 * swipe down for the previous. Everything on the page serves the current
 * sentence — oversized text (the learner's actual complaint about the
 * old bottom-sheet list), its translation, and the practice controls.
 * Arriving on a page selects that sentence for practice; leaving the
 * screen resumes normal playback after the drilled sentence.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SentenceDrillScreen(vm: NcePlayerVm, onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    val segments = vm.speechSegments
    val lesson = vm.current
    if (segments.isEmpty() || lesson == null) return
    // No remember(): a drill opened while durationMs/segments were still
    // settling would cache an empty mapping and never recompute.
    val segmentTexts = segmentLineTexts(segments, lesson.lines, vm.durationMs)
    // The recording often opens with an announcement before the text
    // begins (NCE audio has ~19s of "listen to the tape…"). Those VAD
    // chunks carry no transcript, so drop them from the drill — the
    // learner pages through actual sentences only.
    val drillSegments = segments.indices.filter { i ->
        val t = segmentTexts.getOrNull(i)
        t != null && (t.first.isNotBlank() || t.second.isNotBlank())
    }
    if (drillSegments.isEmpty()) {
        // Lesson audio without any transcript (THINK exercise tracks):
        // show why, instead of a black screen.
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(ScreenBlack)
                .statusBarsPadding()
                .navigationBarsPadding(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                "本课音频没有课文文本，不支持逐句模式",
                color = OnDarkDim,
                style = MaterialTheme.typography.bodyLarge,
            )
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onDismiss) {
                Text("返回", color = PlayerAccent, style = MaterialTheme.typography.titleMedium)
            }
        }
        return
    }
    fun pageToSegment(page: Int): Int = drillSegments[page.coerceIn(0, drillSegments.lastIndex)]
    val initialSegment = if (vm.activeSentenceIndex in segments.indices) {
        vm.activeSentenceIndex
    } else 0
    val pagerState = rememberPagerState(
        initialPage = drillSegments.indexOf(initialSegment).coerceAtLeast(0),
        pageCount = { drillSegments.size },
    )
    // Swiping drives the drill: landing on a page selects that sentence
    // (loop by default, shadowing if the toggle is on). The initial page
    // must not re-select on entry — hence drop(1).
    LaunchedEffect(pagerState, segments) {
        snapshotFlow { pagerState.currentPage }
            .drop(1)
            .collect { page ->
                if (page in drillSegments.indices) {
                    vm.selectSentenceForPractice(drillSegments[page])
                }
            }
    }
    val page = pagerState.currentPage
    val segmentIndex = pageToSegment(page)
    val drillingThisPage = vm.activeSentenceIndex == segmentIndex &&
        (vm.sentencePracticeMode == SentencePracticeMode.REPEAT_ONE ||
            vm.sentencePracticeMode == SentencePracticeMode.SHADOWING)
    val shadowingThisPage = drillingThisPage &&
        vm.sentencePracticeMode == SentencePracticeMode.SHADOWING

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(ScreenBlack)
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "第 ${page + 1} / ${drillSegments.size} 句",
                color = OnDarkDim,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = vm::reanalyzeCurrent) {
                Text("重新分析", color = PlayerAccent, style = MaterialTheme.typography.labelMedium)
            }
            IconButton(onClick = onDismiss, modifier = Modifier.size(44.dp)) {
                Icon(
                    Icons.Default.KeyboardArrowDown,
                    contentDescription = "退出逐句练习",
                    tint = OnDark,
                    modifier = Modifier.size(30.dp),
                )
            }
        }

        VerticalPager(
            state = pagerState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
        ) { pageIndex ->
            val segIdx = drillSegments[pageIndex]
            val text = segmentTexts.getOrNull(segIdx)
            val en = text?.first.orEmpty().trim()
            val cn = text?.second.orEmpty().trim()
            // Bigger text for shorter sentences; a merged 3-sentence
            // segment still has to fit without becoming tiny.
            val enSize = when {
                en.length > 90 -> 22.sp
                en.length > 45 -> 26.sp
                else -> 30.sp
            }
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                if (en.isNotBlank()) {
                    Text(
                        en,
                        color = if (pageIndex == page) OnDark else OnDarkDim,
                        fontSize = enSize,
                        fontWeight = FontWeight.Bold,
                        lineHeight = enSize * 1.45f,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                if (cn.isNotBlank()) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        cn,
                        color = OnDarkDim,
                        fontSize = 17.sp,
                        lineHeight = 26.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
                if (en.isBlank() && cn.isBlank()) {
                    Text(
                        "（这段没有对应课文文本）",
                        color = OnDarkFaint,
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                Spacer(Modifier.height(20.dp))
                Text(
                    fmtTime(segments[segIdx].startMs),
                    color = OnDarkFaint,
                    style = MaterialTheme.typography.labelMedium,
                )
            }
        }

        Text(
            when {
                shadowingThisPage && vm.shadowingPhase == ShadowingPhase.SPEAKING ->
                    "现在跟读，稍后自动重播这句"
                shadowingThisPage -> "听原音，跟着读"
                drillingThisPage -> "循环播放这句"
                else -> "上滑下一句 · 下滑上一句"
            },
            color = if (drillingThisPage) PlayerAccent else OnDarkFaint,
            style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.align(Alignment.CenterHorizontally),
        )
        Spacer(Modifier.height(6.dp))
        // Practice row: replay this sentence, toggle its loop, toggle
        // shadowing — all bound to the sentence on screen.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            DrillActionButton(
                label = "重听",
                onClick = vm::replayCurrentSentence,
                modifier = Modifier.weight(1f),
            )
            DrillActionButton(
                label = if (drillingThisPage && !shadowingThisPage) "循环中" else "循环",
                active = drillingThisPage && !shadowingThisPage,
                onClick = { vm.toggleRepeatSentence(segmentIndex) },
                modifier = Modifier.weight(1f),
            )
            DrillActionButton(
                label = if (shadowingThisPage) "跟读中" else "跟读",
                active = shadowingThisPage,
                onClick = { vm.setShadowingEnabled(!shadowingThisPage, segmentIndex) },
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(8.dp))
        // Transport row: page navigation plus the global play/pause.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(
                onClick = {
                    if (page > 0) scope.launch { pagerState.animateScrollToPage(page - 1) }
                },
                enabled = page > 0,
            ) {
                Icon(
                    Icons.Default.SkipPrevious,
                    contentDescription = "上一句",
                    tint = if (page > 0) OnDark else OnDarkFaint,
                    modifier = Modifier.size(44.dp),
                )
            }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(OnDark)
                    .clickable { vm.togglePlayPause() },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (vm.isPlaying) "暂停" else "播放",
                    tint = ScreenBlack,
                    modifier = Modifier.size(36.dp),
                )
            }
            IconButton(
                onClick = {
                    if (page < segments.lastIndex) {
                        scope.launch { pagerState.animateScrollToPage(page + 1) }
                    }
                },
                enabled = page < segments.lastIndex,
            ) {
                Icon(
                    Icons.Default.SkipNext,
                    contentDescription = "下一句",
                    tint = if (page < segments.lastIndex) OnDark else OnDarkFaint,
                    modifier = Modifier.size(44.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
    }
}

@Composable
private fun DrillActionButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    active: Boolean = false,
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(14.dp))
            .background(if (active) PlayerAccent.copy(alpha = 0.22f) else Color(0x1FFFFFFF))
            .clickable(onClick = onClick)
            .padding(vertical = 14.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = if (active) PlayerAccent else OnDark,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}


@Composable
private fun TransportRow(vm: NcePlayerVm) {
    val canPrev = vm.currentIndex > 0
    val canNext = vm.currentIndex < vm.playlist.lastIndex
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 32.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = vm::toggleLessonRepeat) {
            Icon(
                if (vm.repeatChoice == RepeatModeChoice.ONE) {
                    Icons.Default.RepeatOne
                } else {
                    Icons.Default.Repeat
                },
                contentDescription = if (vm.repeatChoice == RepeatModeChoice.ONE) {
                    "关闭整篇循环"
                } else {
                    "开启整篇循环"
                },
                tint = if (vm.repeatChoice == RepeatModeChoice.ONE) PlayerAccent else OnDarkDim,
                modifier = Modifier.size(28.dp),
            )
        }
        IconButton(onClick = { vm.playPrev() }, enabled = canPrev) {
            Icon(
                Icons.Default.SkipPrevious,
                contentDescription = "上一课",
                tint = if (canPrev) OnDark else OnDarkFaint,
                modifier = Modifier.size(48.dp),
            )
        }
        Box(
            modifier = Modifier
                .size(84.dp)
                .clip(CircleShape)
                .background(OnDark)
                .clickable { vm.togglePlayPause() },
            contentAlignment = Alignment.Center,
        ) {
            if (vm.isBuffering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(32.dp),
                    color = ScreenBlack,
                    strokeWidth = 2.5.dp,
                )
            } else {
                Icon(
                    if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (vm.isPlaying) "暂停" else "播放",
                    tint = ScreenBlack,
                    modifier = Modifier.size(40.dp),
                )
            }
        }
        IconButton(onClick = { vm.playNext() }, enabled = canNext) {
            Icon(
                Icons.Default.SkipNext,
                contentDescription = "下一课",
                tint = if (canNext) OnDark else OnDarkFaint,
                modifier = Modifier.size(48.dp),
            )
        }
        // Balances the whole-lesson repeat button so play stays centered.
        Spacer(Modifier.size(48.dp))
    }
}

@Composable
private fun ColumnScope.PlayerBackLyrics(vm: NcePlayerVm, lesson: NceLesson, tone: CourseTone) {
    val hasWords = lesson.sections.any { it.words.isNotEmpty() }
    val hasQuestion = lesson.question.isNotBlank()
    var selectedTab by rememberSaveable(lesson.id) { mutableStateOf("lines") }

    Column(modifier = Modifier.weight(1f).fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            LyricsTab("课文", selected = selectedTab == "lines") { selectedTab = "lines" }
            if (hasWords) LyricsTab("单词", selected = selectedTab == "words") { selectedTab = "words" }
            if (hasQuestion) LyricsTab("问题", selected = selectedTab == "question") { selectedTab = "question" }
        }
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 12.dp),
        ) {
            when (selectedTab) {
                "words" -> LyricsWordsContent(lesson)
                "question" -> LyricsQuestionContent(lesson)
                else -> LyricsLinesContent(vm, lesson)
            }
            Spacer(Modifier.height(120.dp))
        }
    }

    Surface(
        color = Color(0x66000000),
        modifier = Modifier.fillMaxWidth().clickable { vm.setFlip(false) },
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(tone.gradient),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    lesson.lesson.toString().padStart(2, '0'),
                    color = OnDark,
                    fontWeight = FontWeight.Black,
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    lesson.titleEn.ifBlank { lesson.numberLabel },
                    color = OnDark,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${fmtTime(vm.positionMs)} / ${fmtTime(vm.durationMs)}",
                    color = OnDarkFaint,
                    style = MaterialTheme.typography.labelSmall,
                )
            }
            IconButton(onClick = { vm.togglePlayPause() }) {
                Icon(
                    if (vm.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = null,
                    tint = OnDark,
                    modifier = Modifier.size(28.dp),
                )
            }
            IconButton(onClick = { vm.setFlip(false) }) {
                Icon(Icons.Default.Close, contentDescription = "关闭课文", tint = OnDarkDim)
            }
        }
    }
}

@Composable
internal fun LyricsTab(label: String, selected: Boolean, onClick: () -> Unit) {
    Column(
        modifier = Modifier.clickable(
            interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
            indication = null,
            onClick = onClick,
        ),
    ) {
        Text(
            label,
            color = if (selected) OnDark else OnDarkFaint,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
        if (selected) {
            Spacer(Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .height(2.dp)
                    .width(24.dp)
                    .background(PlayerAccent),
            )
        }
    }
}

// Paragraph-level transcript lines (one "line" of these packages is a
// whole multi-sentence paragraph) must be split into sentences before
// mapping onto VAD segments — otherwise every speech segment pulls in
// a full paragraph and reads like the whole text. Only applied when the
// package has no forced alignment; aligned lines are already
// sentence-grained with real timestamps.
private val EN_SENTENCE_SPLIT = Regex("(?<=[.!?])\\s+(?=[A-Z\"'‘“])")
private val CN_SENTENCE_SPLIT = Regex("(?<=[。！？!?])\\s*")

/** Split paragraph-sized [NceLine]s into sentence-sized ones. English
 *  and Chinese are split independently and paired by index ratio, so a
 *  stray unsplit quotation can't veto the whole split (the old
 *  all-or-nothing pairing kept full paragraphs whenever counts
 *  differed — and dialogue-heavy NCE texts trip the splitter at their
 *  quotes). An aligned line's time range is divided evenly among its
 *  sentences; unaligned lines stay -1 and fall back to the uniform
 *  spread downstream. */
internal fun expandToSentences(lines: List<NceLine>): List<NceLine> {
    val out = mutableListOf<NceLine>()
    for (line in lines) {
        val en = line.en.split(EN_SENTENCE_SPLIT).filter(String::isNotBlank)
        val cn = line.cn.split(CN_SENTENCE_SPLIT).filter(String::isNotBlank)
        if (en.size <= 1) {
            out.add(line)
            continue
        }
        val aligned = line.startMs >= 0
        val step = if (aligned && line.endMs > line.startMs) {
            (line.endMs - line.startMs).toFloat() / en.size
        } else 0f
        en.forEachIndexed { j, sentence ->
            val cnPart = if (cn.isEmpty()) "" else cn[
                (j.toFloat() / en.size * cn.size + 0.5f).toInt().coerceIn(0, cn.lastIndex)
            ]
            out.add(
                NceLine(
                    en = sentence.trim(),
                    cn = cnPart.trim(),
                    startMs = if (aligned) line.startMs + (step * j).toLong() else -1L,
                    endMs = if (aligned) line.startMs + (step * (j + 1)).toLong() else -1L,
                ),
            )
        }
    }
    return if (out.size > lines.size) out else lines
}

/**
 * Map every VAD speech segment to the transcript lines it overlaps, so
 * the drill view can show actual sentence text instead of "第 N 句" + a
 * time range. Forced-aligned packages use real line timestamps; the rest
 * spread the (sentence-split) lines uniformly across the audio duration.
 * Returns one (en, cn) pair per segment.
 */
internal fun segmentLineTexts(
    segments: List<SpeechSegment>,
    lines: List<NceLine>,
    durationMs: Long,
): List<Pair<String, String>> {
    if (segments.isEmpty() || lines.isEmpty() || durationMs <= 0L) return emptyList()
    // Sentence-split first (also dividing aligned line timestamps), then
    // decide alignment on the split result.
    val usable = expandToSentences(lines)
    val aligned = usable.any { it.startMs >= 0 }
    val n = usable.size
    fun lineRange(i: Int): Pair<Long, Long> = when {
        aligned -> {
            val start = usable[i].startMs.coerceAtLeast(0L)
            val end = usable[i].endMs.takeIf { it >= 0 }
                ?: usable.getOrNull(i + 1)?.startMs?.takeIf { it >= 0 }
                ?: durationMs
            start to end
        }
        else -> {
            (i.toFloat() / n * durationMs).toLong() to
                ((i + 1).toFloat() / n * durationMs).toLong()
        }
    }
    return segments.map { seg ->
        var en = ""
        var cn = ""
        usable.forEachIndexed { i, line ->
            val (lStart, lEnd) = lineRange(i)
            if (minOf(seg.endMs, lEnd) - maxOf(seg.startMs, lStart) > 0) {
                if (en.isNotEmpty()) en += "\n"
                en += line.en
                // Ratio pairing can hand adjacent sentences the same
                // Chinese line; showing it twice just reads as a glitch.
                if (line.cn.isNotBlank() && !cn.endsWith(line.cn)) {
                    if (cn.isNotEmpty()) cn += "\n"
                    cn += line.cn
                }
            }
        }
        en to cn
    }
}

/**
 * The transcript line to show on the front face right now: prefer the
 * VAD sentence the player sits in, falling back to the ratio/alignment
 * approximation the lyrics view uses while speech analysis is still
 * empty. Returns null when the lesson has no transcript at all.
 */
internal fun currentLineText(vm: NcePlayerVm, lesson: NceLesson): Pair<String, String>? {
    val lines = lesson.lines
    if (lines.isEmpty()) return null
    val texts = segmentLineTexts(vm.speechSegments, lines, vm.durationMs)
    val idx = vm.activeSentenceIndex
    if (idx in texts.indices) {
        val t = texts[idx]
        if (t.first.isNotBlank() || t.second.isNotBlank()) return t
    }
    val usable = expandToSentences(lines)
    var cur = 0
    if (usable.any { it.startMs >= 0 }) {
        usable.forEachIndexed { i, line -> if (line.startMs in 0..vm.positionMs) cur = i }
    } else if (vm.durationMs > 0) {
        cur = ((vm.positionMs.toFloat() / vm.durationMs) * usable.size).toInt()
    }
    val line = usable[cur.coerceIn(0, usable.lastIndex)]
    return line.en to line.cn
}

@Composable
internal fun LyricsLinesContent(vm: NcePlayerVm, lesson: NceLesson) {
    val lines = lesson.lines
    if (lines.isEmpty()) {
        Text("课文文本缺失", color = OnDarkFaint, style = MaterialTheme.typography.bodyMedium)
        return
    }
    // Prefer real per-line start_ms when the package was forced-aligned
    // (scripts/align_lessons.py). Otherwise fall back to a uniform-ratio
    // approximation against the audio duration. The aligned path picks the
    // *last* line whose startMs has been reached, so a line stays
    // highlighted until the next aligned line begins.
    val hasTimestamps = lines.any { it.startMs >= 0 }
    val approxCurrent = if (hasTimestamps) {
        val pos = vm.positionMs
        var idx = 0
        lines.forEachIndexed { i, line ->
            if (line.startMs in 0..pos) idx = i
        }
        idx.coerceIn(0, lines.lastIndex)
    } else if (vm.durationMs > 0) {
        ((vm.positionMs.toFloat() / vm.durationMs) * lines.size).toInt()
            .coerceIn(0, lines.lastIndex)
    } else 0
    lines.forEachIndexed { idx, line ->
        val isCurrent = idx == approxCurrent
        Column(modifier = Modifier.padding(vertical = if (isCurrent) 10.dp else 6.dp)) {
            Text(
                line.en,
                color = if (isCurrent) Color.White else OnDarkDim,
                style = if (isCurrent) MaterialTheme.typography.headlineSmall else MaterialTheme.typography.bodyLarge,
                fontWeight = if (isCurrent) FontWeight.ExtraBold else FontWeight.SemiBold,
                lineHeight = if (isCurrent) 32.sp else MaterialTheme.typography.bodyLarge.lineHeight,
            )
            if (line.cn.isNotBlank()) {
                Text(
                    line.cn,
                    color = if (isCurrent) Color.White.copy(alpha = 0.85f) else OnDarkDim,
                    style = if (isCurrent) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                )
            }
        }
    }
}

@Composable
internal fun LyricsWordsContent(lesson: NceLesson) {
    var rendered = false
    for (s in lesson.sections) {
        if (s.words.isEmpty()) continue
        rendered = true
        for (w in s.words) {
            Column(modifier = Modifier.padding(vertical = 5.dp)) {
                Text(
                    w.word,
                    color = OnDark,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Row {
                    if (w.pron.isNotBlank()) {
                        Text(
                            w.pron,
                            color = PlayerAccent.copy(alpha = 0.95f),
                            style = MaterialTheme.typography.labelMedium,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        listOf(w.pos, w.definition).filter { it.isNotBlank() }.joinToString(" "),
                        color = OnDarkDim,
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
    if (!rendered) {
        Text("本课暂无单词表", color = OnDarkFaint, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun LyricsQuestionContent(lesson: NceLesson) {
    if (lesson.question.isBlank()) {
        Text("本课暂无问题", color = OnDarkFaint, style = MaterialTheme.typography.bodyMedium)
        return
    }
    Text(
        lesson.question,
        color = OnDark,
        style = MaterialTheme.typography.bodyLarge,
        fontWeight = FontWeight.SemiBold,
    )
}
