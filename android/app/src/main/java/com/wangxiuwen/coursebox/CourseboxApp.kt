package com.wangxiuwen.coursebox

import android.app.Application
import com.wangxiuwen.coursebox.ui.nce.NcePlayerVm

/**
 * Process-wide host for the playback view model. The player must outlive
 * Navigation transitions — when the user backs out of a player screen we
 * still want the mini player floating on top of every tab. Keeping the
 * VM on the Application is the cheapest way to get that.
 */
class CourseboxApp : Application() {
    val playerVm by lazy { NcePlayerVm(this) }

    override fun onCreate() {
        super.onCreate()
        instance = this
    }

    companion object {
        lateinit var instance: CourseboxApp
            private set

        /** Convenience accessor for composables. */
        val playerVm: NcePlayerVm get() = instance.playerVm
    }
}
