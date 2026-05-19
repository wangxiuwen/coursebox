package com.wangxiuwen.coursebox.ui.tts

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.wangxiuwen.coursebox.ui.theme.AccentBlue
import java.util.Locale

private val PaperBg = Color(0xFFF5F4F1)
private val InkSoft = Color(0xFF6B6B66)

/**
 * Wraps Android's built-in TextToSpeech service. Free, on-device,
 * synthesizer-package-dependent quality (usually Google's Speech
 * Services on a modern phone — passable for previews, not studio audio).
 *
 * What this gives us today:
 *   - text-to-speech for zh/en (Chinese needs Google's zh-CN voice
 *     installed; we surface a status line so the user can install it
 *     from Settings → Languages & input → TTS if missing)
 *   - play queue (QUEUE_FLUSH on each speak so a rapid second tap
 *     doesn't pile-up)
 *   - utterance progress callbacks so the UI can disable the button
 *     while the engine is talking
 *
 * What it doesn't do: TTS-to-file → bundle into a coursebox.zip. That's
 * a future extension that uses synthesizeToFile.
 */
private class SystemTts(ctx: Context, private val onReady: (Boolean) -> Unit) {
    private val tts: TextToSpeech = TextToSpeech(ctx.applicationContext) { status ->
        onReady(status == TextToSpeech.SUCCESS)
    }
    var isSpeaking by mutableStateOf(false)
    var lastError by mutableStateOf<String?>(null)

    init {
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) { isSpeaking = true }
            override fun onDone(utteranceId: String?) { isSpeaking = false }
            override fun onError(utteranceId: String?, errorCode: Int) {
                isSpeaking = false
                lastError = "errorCode=$errorCode"
            }
            @Deprecated("legacy onError")
            override fun onError(utteranceId: String?) { isSpeaking = false }
        })
    }

    /** Try to set a sensible default voice for the language; returns whether
     *  the language is fully supported on this device. */
    fun setLanguage(lang: Locale): Int = tts.setLanguage(lang)

    fun voices(): List<Voice> =
        runCatching { tts.voices.orEmpty().toList() }.getOrDefault(emptyList())

    fun setVoice(v: Voice) { tts.voice = v }

    fun speak(text: String) {
        if (text.isBlank()) return
        lastError = null
        val id = "u-${System.currentTimeMillis()}"
        tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
    }

    fun stop() { tts.stop(); isSpeaking = false }
    fun shutdown() { runCatching { tts.stop() }; runCatching { tts.shutdown() } }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TtsScreen(nav: NavHostController) {
    val ctx = LocalContext.current
    var ready by remember { mutableStateOf(false) }
    var initFailed by remember { mutableStateOf(false) }
    val engine = remember {
        SystemTts(ctx) { ok -> ready = ok; if (!ok) initFailed = true }
    }
    DisposableEffect(Unit) {
        onDispose { engine.shutdown() }
    }

    var text by remember { mutableStateOf("你好，欢迎使用课程盒子。This is a TTS preview from Android's built-in engine.") }
    var lang by remember { mutableStateOf(Locale.SIMPLIFIED_CHINESE) }
    var langStatus by remember { mutableStateOf<String?>(null) }
    var voices by remember { mutableStateOf(emptyList<Voice>()) }
    var selectedVoiceName by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(ready, lang) {
        if (!ready) return@LaunchedEffect
        langStatus = when (engine.setLanguage(lang)) {
            TextToSpeech.LANG_AVAILABLE -> "${lang.displayName}: 可用 (基本)"
            TextToSpeech.LANG_COUNTRY_AVAILABLE -> "${lang.displayName}: 可用 (含地区)"
            TextToSpeech.LANG_COUNTRY_VAR_AVAILABLE -> "${lang.displayName}: 可用 (含变体)"
            TextToSpeech.LANG_MISSING_DATA -> "${lang.displayName}: 缺少数据 (设置→语言与输入→TTS 下载语音包)"
            TextToSpeech.LANG_NOT_SUPPORTED -> "${lang.displayName}: 不支持"
            else -> "${lang.displayName}: 未知"
        }
        voices = engine.voices().filter { v ->
            // Surface voices for the currently-selected language. We don't
            // hide unsupported ones globally — different engines (Google,
            // Samsung, Huawei) ship wildly different sets, so the user
            // might pick a near-locale voice intentionally.
            v.locale.language == lang.language
        }.sortedBy { it.name }
        selectedVoiceName = voices.firstOrNull()?.name
    }

    Box(modifier = Modifier.fillMaxSize().background(PaperBg).statusBarsPadding()) {
        Column(modifier = Modifier.fillMaxSize()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 16.dp, top = 18.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { nav.popBackStack() }) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回", tint = Color.Black)
                }
                Spacer(Modifier.width(4.dp))
                Text("文本朗读 · 系统 TTS", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusCard(ready, initFailed, langStatus)

                Card(title = "语言") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        LangPill("中文", Locale.SIMPLIFIED_CHINESE, lang) { lang = it }
                        LangPill("English", Locale.US, lang) { lang = it }
                    }
                }

                if (voices.size > 1) {
                    Card(title = "音色 (${voices.size} 个可选)") {
                        voices.forEach { v ->
                            val sel = v.name == selectedVoiceName
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        selectedVoiceName = v.name
                                        engine.setVoice(v)
                                    }
                                    .padding(vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = sel, onClick = {
                                    selectedVoiceName = v.name
                                    engine.setVoice(v)
                                })
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(v.name, style = MaterialTheme.typography.bodyMedium)
                                    val tags = buildList {
                                        if (v.isNetworkConnectionRequired) add("需联网")
                                        if (v.features?.contains("notInstalled") == true) add("未安装")
                                        add(v.locale.toLanguageTag())
                                    }
                                    Text(tags.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = InkSoft)
                                }
                            }
                        }
                    }
                }

                Card(title = "文本") {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 3,
                        maxLines = 8,
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { engine.speak(text) },
                        enabled = ready && !engine.isSpeaking,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(containerColor = AccentBlue),
                    ) { Text(if (engine.isSpeaking) "朗读中…" else "朗读") }
                    OutlinedButton(
                        onClick = { engine.stop() },
                        enabled = engine.isSpeaking,
                        modifier = Modifier.weight(1f),
                    ) { Text("停止") }
                }

                engine.lastError?.let { e ->
                    Card(title = "错误") {
                        Text(e, style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 12.sp))
                    }
                }

                Spacer(Modifier.height(80.dp))
            }
        }
    }
}

@Composable
private fun StatusCard(ready: Boolean, failed: Boolean, langStatus: String?) {
    Card(title = "引擎状态") {
        Text(
            when {
                failed -> "初始化失败 · 设备未装 TTS 引擎"
                !ready -> "正在初始化…"
                else -> "就绪 · 系统级 TTS"
            },
            style = MaterialTheme.typography.bodyMedium,
            color = if (ready && !failed) Color(0xFF166534) else InkSoft,
        )
        langStatus?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = InkSoft)
        }
    }
}

@Composable
private fun LangPill(label: String, value: Locale, current: Locale, onPick: (Locale) -> Unit) {
    val sel = value.language == current.language
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(if (sel) AccentBlue else Color(0x14000000))
            .clickable { onPick(value) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
    ) {
        Text(label, color = if (sel) Color.White else Color.Black, style = MaterialTheme.typography.labelLarge)
    }
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
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            content()
        }
    }
}
