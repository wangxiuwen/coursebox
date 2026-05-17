package com.wangxiuwen.coursebox

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.wangxiuwen.coursebox.core.CourseLibrary
import com.wangxiuwen.coursebox.ui.RootScreen
import com.wangxiuwen.coursebox.ui.theme.ParrotTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            ParrotTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BootGate()
                }
            }
        }
    }
}

/**
 * Loads the [CourseLibrary] once on launch — manifest parsing reads JSON
 * off disk, fine for IO dispatcher. Until it's ready we render a spinner so
 * the rest of the tree can treat the library as non-null.
 */
@Composable
private fun BootGate() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var library by remember { mutableStateOf<CourseLibrary?>(null) }

    LaunchedEffect(Unit) {
        scope.launch {
            library = CourseLibrary.get(ctx)
        }
    }

    val lib = library
    if (lib == null) {
        Scaffold { inner ->
            androidx.compose.foundation.layout.Box(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentAlignment = Alignment.Center,
            ) { CircularProgressIndicator() }
        }
    } else {
        RootScreen(library = lib)
    }
}
