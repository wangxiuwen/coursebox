package com.wangxiuwen.coursebox.kiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
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
        if (isLockTaskActive(activity)) return
        if (isDeviceOwner(activity)) {
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

    fun isLockTaskActive(ctx: Context): Boolean {
        val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        return am.lockTaskModeState != ActivityManager.LOCK_TASK_MODE_NONE
    }

    /**
     * Service hatch: drop out of kiosk so the device can be used normally.
     *
     * Also gives up device ownership, because that is the only way back —
     * `adb shell dpm remove-active-admin` refuses to touch a non-test admin,
     * so a device owner that never clears itself leaves the app permanently
     * unremovable. Re-arm later with `dpm set-device-owner`, which needs a
     * device with no accounts added.
     */
    fun release(activity: Activity) {
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
        WindowInsetsControllerCompat(activity.window, activity.window.decorView)
            .show(WindowInsetsCompat.Type.systemBars())
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
            // Home and recents stay off — those are the ways out. Global
            // actions stay on so the long-press power menu can still shut the
            // device down, and system info so the clock and the wifi/battery
            // icons are readable when a panel pulls the status bar up.
            runCatching {
                dpm.setLockTaskFeatures(
                    admin,
                    DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                        DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO,
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
