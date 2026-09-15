package com.wangxiuwen.coursebox

import android.os.Bundle
import android.view.KeyEvent
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
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
import com.wangxiuwen.coursebox.kiosk.KioskController
import com.wangxiuwen.coursebox.ui.RootScreen
import com.wangxiuwen.coursebox.ui.theme.ParrotTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    /** Timestamps of recent volume-down presses (the escape-hatch gesture). */
    private val adminTaps = ArrayDeque<Long>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        KioskController.applyOwnerPolicies(this)
        KioskController.applyRotationLock(this)
        KioskController.apply(this)

        // Kiosk: back must never fall through to finishing the activity, or
        // the user lands on the launcher.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (!KioskController.isActive()) {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        setContent {
            ParrotTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    BootGate()
                }
            }
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        // Dialogs, the volume HUD and transient swipes all bring the bars
        // back; re-hide whenever we own the window again.
        if (hasFocus) KioskController.apply(this)
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Last-resort bounce back, ONLY when lock task never took hold. While
        // lock task is running the system already blocks every exit, and
        // bouncing here would also slam the door on screens we open on
        // purpose — the wifi panel and the file picker used to import a
        // course both leave the activity and would be thrown straight out.
        if (KioskController.isActive() && !KioskController.isLockTaskActive(this)) {
            startActivity(intent)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        // Escape hatch lives on VOLUME_DOWN rather than a screen corner: every
        // corner of the player already has a control, and a dialog opening
        // mid-sequence would swallow the rest of the taps.
        if (KioskController.isActive() && keyCode == KeyEvent.KEYCODE_VOLUME_DOWN) {
            registerAdminTap()
        }
        return super.onKeyDown(keyCode, event)
    }

    /** [KioskController.ADMIN_TAP_COUNT] presses in a row leave kiosk. */
    private fun registerAdminTap() {
        val now = System.currentTimeMillis()
        while (adminTaps.isNotEmpty() &&
            now - adminTaps.first() > KioskController.ADMIN_TAP_WINDOW_MS
        ) {
            adminTaps.removeFirst()
        }
        adminTaps.addLast(now)
        if (adminTaps.size >= KioskController.ADMIN_TAP_COUNT) {
            adminTaps.clear()
            KioskController.release(this)
            Toast.makeText(this, R.string.kiosk_released, Toast.LENGTH_LONG).show()
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
