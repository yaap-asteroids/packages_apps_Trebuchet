/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.uioverrides

import android.app.Activity
import android.view.MotionEvent
import com.android.launcher3.Launcher
import com.android.systemui.plugins.shared.LauncherOverlayManager
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayCallbacks
import com.android.systemui.plugins.shared.LauncherOverlayManager.LauncherOverlayTouchProxy
import java.io.PrintWriter

/**
 * Connects the launcher to the -1 screen provider: forwards the activity lifecycle and the
 * workspace's overscroll to [FeedOverlayClient], and hands the provider's scroll back to the
 * workspace once a provider is attached.
 */
class FeedOverlayManager(private val launcher: Launcher) :
    LauncherOverlayManager, LauncherOverlayTouchProxy, FeedOverlayClient.Listener {

    private val client = FeedOverlayClient(launcher, this)
    private var overlayCallbacks: LauncherOverlayCallbacks? = null
    private var attached = false

    init {
        // A manager made by Launcher#recreateOverlay joins an activity that is already running.
        if (launcher.isStarted) client.onStart()
        if (launcher.hasBeenResumed()) client.onResume()
    }

    // ---- LauncherOverlayManager --------------------------------------------

    override fun onDeviceProfileChanged() = client.redraw()

    override fun onAttachedToWindow() = client.onAttachedToWindow()

    override fun onDetachedFromWindow() = client.onDetachedFromWindow()

    override fun openOverlay() = client.openOverlay(animate = true)

    override fun hideOverlay(duration: Int) = client.closeOverlay(duration)

    override fun onActivityStarted(activity: Activity) = client.onStart()

    override fun onActivityResumed(activity: Activity) = client.onResume()

    override fun onActivityPaused(activity: Activity) = client.onPause()

    override fun onActivityStopped(activity: Activity) = client.onStop()

    override fun onActivityDestroyed(activity: Activity) = client.onDestroy()

    override fun dump(prefix: String, w: PrintWriter) = client.dump(prefix, w)

    // ---- LauncherOverlayTouchProxy -----------------------------------------

    override fun onFlingVelocity(velocity: Float) {}

    override fun onOverlayMotionEvent(ev: MotionEvent, scrollProgress: Float) {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> client.startScroll()
            MotionEvent.ACTION_MOVE -> client.setScroll(scrollProgress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> client.endScroll()
        }
    }

    override fun setOverlayCallbacks(callbacks: LauncherOverlayCallbacks?) {
        overlayCallbacks = callbacks
    }

    // ---- FeedOverlayClient.Listener ----------------------------------------

    override fun onOverlayScrollChanged(progress: Float) {
        overlayCallbacks?.onOverlayScrollChanged(progress)
    }

    override fun onOverlayAttachedChanged(attached: Boolean) {
        if (this.attached == attached) return
        this.attached = attached
        launcher.setLauncherOverlay(if (attached) this else null)
    }
}
