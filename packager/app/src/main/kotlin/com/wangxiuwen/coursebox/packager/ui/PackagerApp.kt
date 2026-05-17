package com.wangxiuwen.coursebox.packager.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.wangxiuwen.coursebox.packager.core.Packager
import com.wangxiuwen.coursebox.packager.core.PackagerProgress
import java.awt.FileDialog
import java.awt.Frame
import kotlinx.coroutines.launch
import java.io.File

private val courseTypes = listOf("audio_course", "nce", "chinese_poetry", "music")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PackagerApp() {
    val scope = rememberCoroutineScope()
    val courses = remember { mutableStateListOf(blankCourseDraft()) }
    var output by remember { mutableStateOf<File?>(null) }
    var building by remember { mutableStateOf(false) }
    var lastResult by remember { mutableStateOf<String?>(null) }
    var lastError by remember { mutableStateOf<String?>(null) }
    var progressText by remember { mutableStateOf<String?>(null) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = {
                Column {
                    Text("课程盒子 · 打包", fontWeight = FontWeight.SemiBold)
                    Text(
                        "把本地的音频 + lessons.json 打成 .coursebox.zip",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            actions = {
                IconButton(onClick = { courses += blankCourseDraft() }) {
                    Icon(Icons.Default.Folder, contentDescription = "Add course")
                }
            },
        )

        Row(modifier = Modifier.fillMaxSize()) {
            // Left: course list + edit form
            Column(
                modifier = Modifier.weight(1f).padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(courses, key = { it.id }) { draft ->
                        CourseDraftCard(
                            draft = draft,
                            removable = courses.size > 1,
                            onRemove = { courses.remove(draft) },
                            onChange = { updated ->
                                val idx = courses.indexOf(draft)
                                if (idx >= 0) courses[idx] = updated
                            },
                        )
                    }
                }
            }

            // Right: output panel
            Surface(
                modifier = Modifier.width(360.dp).fillMaxSize(),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text("输出 / Output", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surface,
                        shape = RoundedCornerShape(10.dp),
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(
                                output?.absolutePath ?: "(尚未选择输出文件)",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Spacer(Modifier.size(8.dp))
                            OutlinedButton(
                                onClick = { pickSaveFile()?.let { output = it } },
                            ) {
                                Icon(Icons.Default.Save, contentDescription = null)
                                Spacer(Modifier.size(6.dp))
                                Text("选择 .coursebox.zip 输出位置")
                            }
                        }
                    }

                    Button(
                        onClick = {
                            val out = output ?: return@Button
                            building = true
                            lastError = null
                            lastResult = null
                            scope.launch {
                                val specs = runCatching { courses.map { it.toSpec() } }
                                    .getOrElse {
                                        lastError = it.message ?: "课程定义不完整"
                                        building = false
                                        return@launch
                                    }
                                runCatching {
                                    Packager.build(
                                        courses = specs,
                                        outputZip = out,
                                        progress = { p ->
                                            progressText = when (p) {
                                                is PackagerProgress.CourseStart ->
                                                    "课程 ${p.index + 1}/${p.total}: ${p.title}"
                                                is PackagerProgress.FileHashed ->
                                                    "已处理 ${formatBytes(p.bytesProcessed)} · ${p.name}"
                                            }
                                        },
                                    )
                                }.onSuccess { result ->
                                    lastResult = "✓ 打包成功 · ${result.resourceCount} 资源 / ${formatBytes(result.totalBytes)} → ${result.outputZip.name}"
                                    progressText = null
                                }.onFailure {
                                    lastError = "打包失败：${it.message ?: it.javaClass.simpleName}"
                                    progressText = null
                                }
                                building = false
                            }
                        },
                        enabled = !building && output != null && courses.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        if (building) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                color = MaterialTheme.colorScheme.onPrimary,
                                strokeWidth = 2.dp,
                            )
                            Spacer(Modifier.size(8.dp))
                        } else {
                            Icon(Icons.Default.Build, contentDescription = null)
                            Spacer(Modifier.size(6.dp))
                        }
                        Text(if (building) "打包中…" else "打包成 .coursebox.zip")
                    }

                    progressText?.let {
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                    lastResult?.let {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(it, modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    lastError?.let {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                it,
                                modifier = Modifier.padding(12.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                            )
                        }
                    }
                }
            }
        }
    }
}

/** Mutable in-memory model for one course tab. */
internal data class CourseDraft(
    val id: String = "course-${System.currentTimeMillis()}",
    val title: String = "",
    val description: String = "",
    val type: String = "audio_course",
    val sourceDir: File? = null,
    val lessonsFile: File? = null,
) {
    fun toSpec(): Packager.CourseSpec {
        require(id.isNotBlank()) { "课程 id 不能为空" }
        require(title.isNotBlank()) { "课程标题不能为空" }
        val dir = sourceDir ?: error("尚未选择 '$title' 的资源目录")
        require(dir.isDirectory) { "资源目录无效：$dir" }
        return Packager.CourseSpec(
            id = id,
            title = title,
            description = description,
            type = type,
            sourceDir = dir,
            lessonsFile = lessonsFile,
        )
    }
}

internal fun blankCourseDraft() = CourseDraft()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseDraftCard(
    draft: CourseDraft,
    removable: Boolean,
    onRemove: () -> Unit,
    onChange: (CourseDraft) -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("课程信息", modifier = Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (removable) {
                    OutlinedButton(onClick = onRemove) { Text("移除课程") }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = draft.id,
                    onValueChange = { onChange(draft.copy(id = it.trim())) },
                    label = { Text("ID") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                )
                CourseTypeDropdown(
                    selected = draft.type,
                    onSelected = { onChange(draft.copy(type = it)) },
                    modifier = Modifier.weight(1f),
                )
            }
            OutlinedTextField(
                value = draft.title,
                onValueChange = { onChange(draft.copy(title = it)) },
                label = { Text("标题 / Title") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = draft.description,
                onValueChange = { onChange(draft.copy(description = it)) },
                label = { Text("描述 / Description") },
                modifier = Modifier.fillMaxWidth().heightIn(min = 60.dp),
                minLines = 2,
                maxLines = 4,
            )

            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("资源目录", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        draft.sourceDir?.absolutePath ?: "(尚未选择)",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    OutlinedButton(onClick = {
                        pickDirectory()?.let { dir ->
                            val lessons = File(dir, "lessons.json").takeIf { it.exists() }
                            onChange(draft.copy(sourceDir = dir, lessonsFile = lessons))
                        }
                    }) {
                        Icon(Icons.Default.FolderOpen, contentDescription = null)
                        Spacer(Modifier.size(6.dp))
                        Text("选择文件夹")
                    }
                }
            }

            draft.sourceDir?.let { dir ->
                val files = remember(dir) {
                    dir.walkTopDown().filter { it.isFile && !it.name.startsWith(".") }.toList()
                }
                AssistChip(onClick = {}, label = { Text("${files.size} 个文件") })
                Text(
                    "lessons.json " + if (draft.lessonsFile?.exists() == true) "✓" else "未发现 (会在打包时报错)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CourseTypeDropdown(
    selected: String,
    onSelected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier,
    ) {
        OutlinedTextField(
            value = selected,
            onValueChange = {},
            readOnly = true,
            label = { Text("Type") },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.menuAnchor().fillMaxWidth(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            courseTypes.forEach { t ->
                DropdownMenuItem(text = { Text(t) }, onClick = {
                    onSelected(t)
                    expanded = false
                })
            }
        }
    }
}

private fun pickDirectory(): File? {
    // Swing's native folder picker. On macOS the property below switches
    // FileDialog into directory-only mode; on Windows we still get a
    // file picker but with mode DIRECTORIES via JFileChooser fallback.
    System.setProperty("apple.awt.fileDialogForDirectories", "true")
    return try {
        val dialog = FileDialog(null as Frame?, "选择资源目录", FileDialog.LOAD)
        dialog.isVisible = true
        val dir = dialog.directory
        val file = dialog.file
        if (dir != null && file != null) File(dir, file) else dir?.let { File(it) }
    } finally {
        System.setProperty("apple.awt.fileDialogForDirectories", "false")
    }
}

private fun pickSaveFile(): File? {
    val dialog = FileDialog(null as Frame?, "保存为", FileDialog.SAVE)
    dialog.file = "course.coursebox.zip"
    dialog.isVisible = true
    val dir = dialog.directory ?: return null
    val file = dialog.file ?: return null
    val name = if (file.endsWith(".zip")) file else "$file.zip"
    return File(dir, name)
}

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "${bytes} B"
    val kb = bytes / 1024.0
    if (kb < 1024) return "%.1f KB".format(kb)
    val mb = kb / 1024.0
    if (mb < 1024) return "%.1f MB".format(mb)
    return "%.2f GB".format(mb / 1024.0)
}
