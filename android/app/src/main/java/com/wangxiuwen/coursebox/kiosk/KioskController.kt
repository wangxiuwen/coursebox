package com.wangxiuwen.coursebox.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import android.view.WindowManager
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat

/**
 * Turns the player into a single-purpose appliance: full screen, no system
 * bars, no way back to the launcher.
 *
 * Two levels of lockdown, picked automatically:
 *
 *  - **Device owner** (`adb shell dpm set-device-owner
 *    com.wangxiuwen.coursebox/.kiosk.KioskAdminReceiver` on a freshly wiped
 *    device): lock task starts silently and cannot be dismissed by any
 *    gesture. Home and recents are dead.
 *  - **Plain install**: [Activity.startLockTask] falls back to screen pinning,
 *    which shows a one-time confirmation and can be escaped by holding
 *    back + overview. Still full screen, back is still swallowed.
 *
 * [ADMIN_TAP_COUNT] volume-down presses within [ADMIN_TAP_WINDOW_MS]
 * release the lock — the deliberate service hatch, otherwise the device needs
 * adb to become usable again.
 */
object KioskController {

    private const val TAG = "Kiosk"
    const val ADMIN_TAP_COUNT = 7
    const val ADMIN_TAP_WINDOW_MS = 3_000L
    private const val SETTINGS_PACKAGE = "com.android.settings"

    /**
     * Set once the user has stepped out of kiosk this session, so
     * [apply] stops pulling the bars back and re-locking on every focus
     * change. Cleared by a process restart, which is what makes
     * [exitLockTask] temporary and survivable.
     */
    @Volatile
    private var suspended = false

    private const val PREFS = "kiosk"
    private const val KEY_ROTATION_LOCKED = "rotation_locked"

    /**
     * Applied while we are device owner. Settings has to stay reachable so
     * the device can be moved to another wifi network, which means the
     * destructive corners of it need closing off. Debugging is deliberately
     * NOT restricted — adb is how the device gets serviced.
     */
    private val GUARD_RESTRICTIONS = listOf(
        UserManager.DISALLOW_FACTORY_RESET,
        UserManager.DISALLOW_SAFE_BOOT,
        UserManager.DISALLOW_ADD_USER,
        UserManager.DISALLOW_UNINSTALL_APPS,
        UserManager.DISALLOW_MODIFY_ACCOUNTS,
        UserManager.DISALLOW_CONFIG_CREDENTIALS,
    )

    private fun dpm(ctx: Context) =
        ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private fun admin(ctx: Context) =
        ComponentName(ctx.applicationContext, KioskAdminReceiver::class.java)

    fun isDeviceOwner(ctx: Context): Boolean =
        dpm(ctx).isDeviceOwnerApp(ctx.packageName)

    /**
     * Applies everything that must be re-applied every time the window regains
     * focus — the system restores bars after dialogs, volume HUD, etc.
     */
    fun apply(activity: Activity) {
        if (suspended) return
        goFullScreen(activity)
        activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            activity.setShowWhenLocked(true)
            activity.setTurnScreenOn(true)
        }
        startLockTask(activity)
    }

    /** Immersive-sticky: bars stay hidden, a swipe shows them transiently. */
    fun goFullScreen(activity: Activity) {
        WindowCompat.setDecorFitsSystemWindows(activity.window, false)
        WindowInsetsControllerCompat(activity.window, activity.window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun startLockTask(activity: Activity) {
        val owner = isDeviceOwner(activity)
        when (lockTaskState(activity)) {
            // Already as locked down as it gets.
            ActivityManager.LOCK_TASK_MODE_LOCKED -> return

            ActivityManager.LOCK_TASK_MODE_PINNED -> {
                // Pinning is the weaker mode a non-owner falls back to, and
                // it can be escaped by holding back + overview. If device
                // ownership was granted after we pinned — restored by hand,
                // say — leaving it alone means the box silently stays
                // escapable. Drop the pin so the restart below re-enters
                // properly; a non-owner has nothing better to switch to.
                if (!owner) return
                runCatching { activity.stopLockTask() }
                    .onFailure { Log.w(TAG, "unpin before re-lock failed", it) }
            }

            else -> Unit
        }
        if (owner) {
            // Whitelist ourselves first, else startLockTask still prompts.
            runCatching {
                dpm(activity).setLockTaskPackages(
                    admin(activity), arrayOf(activity.packageName, SETTINGS_PACKAGE),
                )
            }.onFailure { Log.w(TAG, "setLockTaskPackages failed", it) }
        }
        runCatching { activity.startLockTask() }
            .onFailure { Log.w(TAG, "startLockTask failed", it) }
    }

    private fun lockTaskState(ctx: Context): Int =
        (ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).lockTaskModeState

    /**
     * Rotation lock, in-app, because the quick-settings tile that normally
     * carries it is part of the panel lock task keeps shut. Pins the current
     * orientation; the choice survives restarts.
     */
    fun isRotationLocked(ctx: Context): Boolean =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ROTATION_LOCKED, false)

    fun setRotationLocked(activity: Activity, locked: Boolean) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ROTATION_LOCKED, locked).apply()
        applyRotationLock(activity)
    }

    fun applyRotationLock(activity: Activity) {
        activity.requestedOrientation = if (isRotationLocked(activity)) {
            ActivityInfo.SCREEN_ORIENTATION_LOCKED
        } else {
            ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    /** True while kiosk is meant to be enforcing; false after an exit. */
    fun isActive(): Boolean = !suspended

    fun isLockTaskActive(ctx: Context): Boolean =
        lockTaskState(ctx) != ActivityManager.LOCK_TASK_MODE_NONE

    /**
     * Service hatch: drop out of kiosk so the device can be used normally.
     *
     * Also gives up device ownership, because that is the only way back —
     * `adb shell dpm remove-active-admin` refuses to touch a non-test admin,
     * so a device owner that never clears itself leaves the app permanently
     * unremovable. Re-arm later with `dpm set-device-owner`, which needs a
     * device with no accounts added.
     */
    /**
     * Temporary way out, for the visible "exit" control: unpin and show the
     * bars, but stay device owner so the next launch locks down again. This
     * is the one an ordinary user should ever need.
     */
    fun exitLockTask(activity: Activity) {
        suspended = true
        // stopLockTask only releases the calling task, and this app can hold
        // several at once — it is both LAUNCHER and HOME, so the two launch
        // paths build separate tasks and each one entered lock task. Emptying
        // the whitelist ends all of them; startLockTask re-fills it.
        clearLockTaskWhitelist(activity)
        runCatching { activity.stopLockTask() }
            .onFailure { Log.w(TAG, "stopLockTask failed", it) }
        showSystemBars(activity)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    private fun clearLockTaskWhitelist(activity: Activity) {
        if (!isDeviceOwner(activity)) return
        runCatching { dpm(activity).setLockTaskPackages(admin(activity), emptyArray()) }
            .onFailure { Log.w(TAG, "clearing lock task packages failed", it) }
    }

    private fun showSystemBars(activity: Activity) {
        WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
    }

    fun release(activity: Activity) {
        suspended = true
        clearLockTaskWhitelist(activity)
        runCatching { activity.stopLockTask() }
            .onFailure { Log.w(TAG, "stopLockTask failed", it) }
        if (isDeviceOwner(activity)) {
            val dpm = dpm(activity)
            val admin = admin(activity)
            runCatching { dpm.setUninstallBlocked(admin, activity.packageName, false) }
                .onFailure { Log.w(TAG, "uninstall unblock failed", it) }
            for (restriction in GUARD_RESTRICTIONS) {
                runCatching { dpm.clearUserRestriction(admin, restriction) }
                    .onFailure { Log.w(TAG, "clear $restriction failed", it) }
            }
            @Suppress("DEPRECATION")
            runCatching { dpm.clearDeviceOwnerApp(activity.packageName) }
                .onFailure { Log.w(TAG, "clearDeviceOwnerApp failed", it) }
        }
        showSystemBars(activity)
        activity.window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }

    /**
     * Device owner extras that only stick while we own the device: keep the
     * screen awake while charging and suppress the "screen pinned" toast path.
     */
    fun applyOwnerPolicies(ctx: Context) {
        if (!isDeviceOwner(ctx)) return
        val dpm = dpm(ctx)
        val admin = admin(ctx)
        runCatching {
            dpm.setGlobalSetting(
                admin, Settings.Global.STAY_ON_WHILE_PLUGGED_IN,
                // AC | USB | wireless
                (BatteryPlugged.AC or BatteryPlugged.USB or BatteryPlugged.WIRELESS).toString(),
            )
        }.onFailure { Log.w(TAG, "stay-on policy failed", it) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            // Recents stays off. HOME is on only because the platform
            // rejects NOTIFICATIONS without it
            // ("Cannot use LOCK_TASK_FEATURE_NOTIFICATIONS without
            // LOCK_TASK_FEATURE_HOME") — and it costs nothing here, since
            // this app is the device's launcher, so HOME lands back on the
            // player. Notifications and global actions stay reachable
            // because a box you cannot read a notification on, or power
            // off, is broken rather than focused.
            runCatching {
                dpm.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                        DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO or
                        DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                        DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS,
                )
            }.onFailure { Log.w(TAG, "lock task features failed", it) }
        }
        // Settings is reachable (see [openNetworkSettings]), so fence off the
        // parts of it that would undo the kiosk or wipe the device.
        for (restriction in GUARD_RESTRICTIONS) {
            runCatching { dpm.addUserRestriction(admin, restriction) }
                .onFailure { Log.w(TAG, "restriction $restriction failed", it) }
        }
        runCatching { dpm.setUninstallBlocked(admin, ctx.packageName, true) }
            .onFailure { Log.w(TAG, "uninstall block failed", it) }
    }

    /**
     * Opens the system network panel — a floating sheet that only does wifi,
     * not the full Settings tree. Needed because a kiosk device that moves to
     * a new room has no other way to get back online.
     *
     * Works because [SETTINGS_PACKAGE] is on the lock-task whitelist; without
     * that, starting it from lock task fails silently.
     */
    fun openNetworkSettings(activity: Activity) {
        val intents = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(Intent(Settings.Panel.ACTION_INTERNET_CONNECTIVITY))
            }
            add(Intent(Settings.ACTION_WIFI_SETTINGS))
        }
        for (intent in intents) {
            val started = runCatching { activity.startActivity(intent); true }
                .onFailure { Log.w(TAG, "network settings failed: $intent", it) }
                .getOrDefault(false)
            if (started) return
        }
    }

    private object BatteryPlugged {
        const val AC = 1
        const val USB = 2
        const val WIRELESS = 4
    }
}
