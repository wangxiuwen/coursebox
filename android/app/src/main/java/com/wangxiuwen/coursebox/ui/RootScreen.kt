package com.wangxiuwen.coursebox.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.wangxiuwen.coursebox.BuildConfig
import com.wangxiuwen.coursebox.core.CourseLibrary
import com.wangxiuwen.coursebox.core.UpdateAvailable
import com.wangxiuwen.coursebox.core.UpdateChecker
import com.wangxiuwen.coursebox.ui.chinese.ChineseLibraryScreen
import com.wangxiuwen.coursebox.ui.library.LibraryTab
import com.wangxiuwen.coursebox.ui.music.MusicFoundationScreen
import com.wangxiuwen.coursebox.ui.nce.NceListScreen
import com.wangxiuwen.coursebox.ui.nce.NcePlayerScreen
import com.wangxiuwen.coursebox.ui.theme.AccentBlue
import kotlinx.coroutines.launch

private object Routes {
    const val HOME = "home"
    const val NCE_LIST = "nce/{courseId}"
    const val NCE_PLAYER = "nce/{courseId}/player/{lessonId}"
    const val CHINESE = "chinese/{courseId}"
    const val MUSIC = "music/{courseId}"
}

/**
 * Single-screen root: 资源库 is the home, all course types drill in from there.
 * Mini player floats on top of every non-player screen.
 */
@Composable
fun RootScreen(library: CourseLibrary) {
    val nav = rememberNavController()
    val backStackEntry by nav.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val isOnPlayer = currentRoute?.startsWith("nce/") == true && currentRoute.contains("/player/")

    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var update by remember { mutableStateOf<UpdateAvailable?>(null) }
    var dismissed by rememberSaveable { mutableStateOf(false) }
    var downloading by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        update = UpdateChecker.check(BuildConfig.VERSION_NAME)
    }

    Box(modifier = Modifier.fillMaxSize()) {
        NavHost(navController = nav, startDestination = Routes.HOME) {
            composable(Routes.HOME) { LibraryTab(library, nav) }
            composable(Routes.NCE_LIST) { entry ->
                val courseId = entry.arguments?.getString("courseId").orEmpty()
                NceListScreen(library, courseId, nav)
            }
            composable(Routes.NCE_PLAYER) { entry ->
                val courseId = entry.arguments?.getString("courseId").orEmpty()
                val lessonId = entry.arguments?.getString("lessonId").orEmpty()
                NcePlayerScreen(library, courseId, lessonId, nav)
            }
            composable(Routes.CHINESE) { entry ->
                val courseId = entry.arguments?.getString("courseId").orEmpty()
                ChineseLibraryScreen(library, courseId, nav)
            }
            composable(Routes.MUSIC) { entry ->
                val courseId = entry.arguments?.getString("courseId").orEmpty()
                MusicFoundationScreen(library, courseId, nav)
            }
        }

        if (!isOnPlayer) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.BottomCenter,
            ) {
                MiniPlayer(
                    nav = nav,
                    modifier = Modifier.padding(bottom = 26.dp),
                )
            }
        }

        update?.takeIf { !dismissed }?.let { u ->
            AlertDialog(
                onDismissRequest = { dismissed = true },
                containerColor = Color.White,
                title = { Text("发现新版本 v${u.latestVersion}") },
                text = {
                    Text(
                        "当前版本：v${u.currentVersion}\n\n" +
                            (u.release.body.take(280).ifBlank { "点击下载并安装新版本。" })
                    )
                },
                confirmButton = {
                    TextButton(
                        enabled = !downloading,
                        onClick = {
                            downloading = true
                            scope.launch {
                                runCatching { UpdateChecker.downloadAndInstall(ctx, u.apkAsset) }
                                downloading = false
                                dismissed = true
                            }
                        },
                    ) {
                        Text(if (downloading) "下载中…" else "立即更新", color = AccentBlue)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { dismissed = true }) {
                        Text("稍后", color = Color(0xFF6B7280))
                    }
                },
            )
        }
    }
}

@Composable
internal fun currentRoute(nav: androidx.navigation.NavHostController): String? =
    nav.currentBackStackEntryAsState().value?.destination?.route
