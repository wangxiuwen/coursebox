package com.wangxiuwen.coursebox.kiosk

import android.app.admin.DeviceAdminReceiver

/**
 * Device-admin entry point. Only needed so the app can be promoted to device
 * owner via `adb shell dpm set-device-owner`, which is what lets
 * [KioskController] pin the screen without the system confirmation dialog and
 * without a user-reachable escape gesture.
 */
class KioskAdminReceiver : DeviceAdminReceiver()
