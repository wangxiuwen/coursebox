package com.wangxiuwen.coursebox.kiosk

import android.app.Activity
import android.content.Context

/**
 * No-op stand-in for the normal build.
 *
 * Same surface as the kiosk flavour's controller so shared UI and
 * [com.wangxiuwen.coursebox.MainActivity] compile against one API; here every
 * entry point does nothing and [isDeviceOwner] reports false, which is what
 * hides the kiosk menu items and lets back behave normally.
 */
object KioskController {

    // Kept so the shared escape-hatch code compiles; nothing consumes them
    // here because the gesture never fires.
    const val ADMIN_TAP_COUNT = 7
    const val ADMIN_TAP_WINDOW_MS = 3_000L

    fun isDeviceOwner(ctx: Context): Boolean = false

    /** Kiosk is never enforcing in this flavour. */
    fun isActive(): Boolean = false

    fun isLockTaskActive(ctx: Context): Boolean = false

    fun apply(activity: Activity) = Unit

    fun applyOwnerPolicies(ctx: Context) = Unit

    fun goFullScreen(activity: Activity) = Unit

    fun exitLockTask(activity: Activity) = Unit

    fun release(activity: Activity) = Unit

    fun openNetworkSettings(activity: Activity) = Unit

    fun isRotationLocked(ctx: Context): Boolean = false

    fun setRotationLocked(activity: Activity, locked: Boolean) = Unit

    fun applyRotationLock(activity: Activity) = Unit
}
