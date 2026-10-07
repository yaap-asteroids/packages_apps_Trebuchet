/*
 * SPDX-License-Identifier: Apache-2.0
 */

package com.android.launcher3.uioverrides

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PatternMatcher
import android.os.Process
import android.os.RemoteException
import android.util.Log
import android.view.WindowManager.LayoutParams
import com.android.launcher3.R
import com.google.android.libraries.launcherclient.ILauncherOverlay
import com.google.android.libraries.launcherclient.ILauncherOverlayCallback
import java.io.PrintWriter
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.min

/**
 * Launcher side of the -1 screen ("feed") protocol, [ILauncherOverlay].
 *
 * The provider is bound twice, as Google's own client does: once from the application context,
 * which survives the activity being recreated and carries the [ILauncherOverlay] binder, and
 * once from the activity while it is started, which only raises the provider's priority.
 *
 * All methods must be called on the main thread.
 */
class FeedOverlayClient(private val activity: Activity, private val listener: Listener) {

    interface Listener {
        /** The provider moved its panel; [progress] runs from 0 closed to 1 fully open. */
        fun onOverlayScrollChanged(progress: Float)

        /** Whether a provider is attached and the workspace should scroll into it. */
        fun onOverlayAttachedChanged(attached: Boolean)
    }

    private val overlayPackage = activity.getString(R.string.config_feedOverlayPackage)
    private val bindIntent =
        Intent(ACTION_OVERLAY)
            .setPackage(overlayPackage)
            .setData(
                Uri.parse("app://${activity.packageName}:${Process.myUid()}")
                    .buildUpon()
                    .appendQueryParameter("v", PROTOCOL_VERSION)
                    .appendQueryParameter("cv", CLIENT_VERSION)
                    .build()
            )

    private val callback = OverlayCallback(this)
    private val activityConnection = ActivityConnection()
    private val packageReceiver =
        object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = reconnect()
        }

    private var apiVersion = loadApiVersion()
    private var overlay: ILauncherOverlay? = null
    private var windowParams: LayoutParams? = null
    private var activityState = 0
    private var serviceStatus = 0
    private var destroyed = false

    init {
        if (overlayPackage.isEmpty()) {
            destroyed = true
        } else {
            // Rebind when the provider is installed or updated.
            val filter =
                IntentFilter(Intent.ACTION_PACKAGE_ADDED).apply {
                    addDataScheme("package")
                    addDataSchemeSpecificPart(overlayPackage, PatternMatcher.PATTERN_LITERAL)
                }
            activity.registerReceiver(packageReceiver, filter, Context.RECEIVER_NOT_EXPORTED)

            SharedConnection.client = WeakReference(this)
            overlay = SharedConnection.overlay
            connect()
        }
    }

    // ---- lifecycle ---------------------------------------------------------

    fun onAttachedToWindow() {
        if (!destroyed) setWindowParams(activity.window.attributes)
    }

    fun onDetachedFromWindow() {
        if (!destroyed) setWindowParams(null)
    }

    fun onStart() {
        if (destroyed) return
        SharedConnection.setStopped(false)
        connect()
        activityState = activityState or STATE_STARTED
        sendActivityState()
    }

    fun onResume() {
        if (destroyed) return
        activityState = activityState or STATE_RESUMED
        sendActivityState()
    }

    fun onPause() {
        if (destroyed) return
        activityState = activityState and STATE_RESUMED.inv()
        sendActivityState()
    }

    fun onStop() {
        if (destroyed) return
        SharedConnection.setStopped(true)
        activityConnection.disconnect()
        activityState = activityState and STATE_STARTED.inv()
        sendActivityState()
    }

    fun onDestroy() {
        if (destroyed) return
        // The window is only detached after onDestroy, when this client no longer listens, so
        // let the provider know now. Across a configuration change it keeps the panel state.
        setWindowParams(null)
        destroyed = true
        activity.unregisterReceiver(packageReceiver)
        activityConnection.disconnect()
        if (SharedConnection.client?.get() === this) SharedConnection.client = null
    }

    /** Resends the window configuration, e.g. after the device profile changed. */
    fun redraw() {
        if (windowParams != null && apiVersion >= API_REDRAW) sendWindowAttached()
    }

    // ---- scroll and panel --------------------------------------------------

    fun startScroll() = callOverlay { it.startScroll() }

    fun setScroll(progress: Float) = callOverlay { it.onScroll(progress) }

    fun endScroll() = callOverlay { it.endScroll() }

    fun openOverlay(animate: Boolean) =
        callOverlay { it.openOverlay(if (animate) FLAG_ANIMATE else 0) }

    /** Closes the panel over [durationMs]; 0 closes it at once. */
    fun closeOverlay(durationMs: Int) {
        val flags =
            if (durationMs <= 0) 0 else FLAG_ANIMATE or (min(durationMs, MAX_DURATION_MS) shl 2)
        callOverlay { it.closeOverlay(flags) }
    }

    fun dump(prefix: String, writer: PrintWriter) {
        writer.println("${prefix}FeedOverlayClient: package=$overlayPackage api=$apiVersion")
        writer.println(
            "$prefix  connected=${overlay != null} attached=${(serviceStatus and 1) != 0}" +
                " activityState=$activityState destroyed=$destroyed"
        )
    }

    // ---- internals ---------------------------------------------------------

    private inline fun callOverlay(call: (ILauncherOverlay) -> Unit) {
        val remote = overlay ?: return
        try {
            call(remote)
        } catch (e: RemoteException) {
            // The provider died; onServiceDisconnected follows.
        }
    }

    private fun connect() {
        if (destroyed) return
        if (!SharedConnection.connect(activity.applicationContext, bindIntent) ||
            !activityConnection.connect()
        ) {
            setServiceStatus(0)
        }
    }

    private fun reconnect() {
        if (destroyed) return
        activityConnection.disconnect()
        SharedConnection.disconnect()
        apiVersion = loadApiVersion()
        if ((activityState and STATE_RESUMED) != 0) connect()
    }

    private fun setWindowParams(params: LayoutParams?) {
        if (windowParams === params) return
        windowParams = params
        if (params != null) {
            sendWindowAttached()
        } else {
            callOverlay { it.windowDetached(activity.isChangingConfigurations) }
        }
    }

    private fun sendWindowAttached() {
        val params = windowParams ?: return
        callOverlay {
            if (apiVersion < API_ATTACH_BUNDLE) {
                it.windowAttached(params, callback, CLIENT_OPTIONS)
            } else {
                val args =
                    Bundle().apply {
                        putParcelable("layout_params", params)
                        putParcelable("configuration", activity.resources.configuration)
                        putInt("client_options", CLIENT_OPTIONS)
                    }
                it.windowAttached2(args, callback)
            }
        }
        sendActivityState()
    }

    private fun sendActivityState() {
        if (windowParams == null) return
        callOverlay {
            when {
                apiVersion >= API_ACTIVITY_STATE -> it.setActivityState(activityState)
                (activityState and STATE_RESUMED) != 0 -> it.onResume()
                else -> it.onPause()
            }
        }
    }

    /** Called by [SharedConnection] when the provider connects (non-null) or goes away. */
    private fun setOverlay(remote: ILauncherOverlay?) {
        overlay = remote
        if (remote == null) {
            setServiceStatus(0)
        } else {
            sendWindowAttached()
        }
    }

    private fun setServiceStatus(status: Int) {
        if (serviceStatus == status) return
        serviceStatus = status
        listener.onOverlayAttachedChanged((status and STATUS_ATTACHED) != 0)
    }

    private fun onOverlayScrollChanged(progress: Float) {
        if ((serviceStatus and STATUS_ATTACHED) != 0) listener.onOverlayScrollChanged(progress)
    }

    private fun loadApiVersion(): Int {
        if (overlayPackage.isEmpty()) return 1
        val info =
            activity.packageManager.resolveService(bindIntent, PackageManager.GET_META_DATA)
        return info?.serviceInfo?.metaData?.getInt(META_API_VERSION, 1) ?: 1
    }

    /** Binding held by the activity while started, so the provider runs at its priority. */
    private inner class ActivityConnection : ServiceConnection {
        private var bound = false

        fun connect(): Boolean {
            if (!bound) {
                bound = bind(activity, bindIntent, this, Context.BIND_IMPORTANT)
            }
            return bound
        }

        fun disconnect() {
            if (bound) {
                activity.unbindService(this)
                bound = false
            }
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {}

        override fun onServiceDisconnected(name: ComponentName) {}
    }

    /** Process wide binding that carries the [ILauncherOverlay] across activity instances. */
    private object SharedConnection : ServiceConnection {
        var client: WeakReference<FeedOverlayClient>? = null
        var overlay: ILauncherOverlay? = null
            private set

        private var context: Context? = null
        private var bound = false
        private var stopped = false

        fun connect(appContext: Context, intent: Intent): Boolean {
            if (!bound) {
                context = appContext
                bound = bind(appContext, intent, this, Context.BIND_WAIVE_PRIORITY)
            }
            return bound
        }

        fun disconnect() {
            if (bound) {
                context?.unbindService(this)
                bound = false
            }
            if (overlay != null) {
                overlay = null
                client?.get()?.setOverlay(null)
            }
        }

        /** While the launcher is stopped there is no point retrying a provider that is gone. */
        fun setStopped(stopped: Boolean) {
            this.stopped = stopped
            if (stopped && overlay == null) disconnect()
        }

        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            overlay = ILauncherOverlay.Stub.asInterface(service)
            client?.get()?.setOverlay(overlay)
        }

        override fun onServiceDisconnected(name: ComponentName) {
            overlay = null
            client?.get()?.setOverlay(null)
            if (stopped) disconnect()
        }
    }

    /**
     * Receives the provider's callbacks on binder threads and forwards them to the main thread.
     * Holds the client weakly, as the provider can keep this binder alive past the activity.
     */
    private class OverlayCallback(client: FeedOverlayClient) : ILauncherOverlayCallback.Stub() {
        private val clientRef = WeakReference(client)
        private val mainHandler = Handler(Looper.getMainLooper())

        @Volatile private var latestProgress = 0f
        private val progressPosted = AtomicBoolean()

        override fun overlayScrollChanged(progress: Float) {
            // The panel reports every frame; only the newest position matters.
            latestProgress = progress
            if (progressPosted.compareAndSet(false, true)) {
                mainHandler.post {
                    progressPosted.set(false)
                    clientRef.get()?.onOverlayScrollChanged(latestProgress)
                }
            }
        }

        override fun overlayStatusChanged(status: Int) {
            mainHandler.post { clientRef.get()?.setServiceStatus(status) }
        }
    }

    private companion object {
        const val TAG = "FeedOverlayClient"

        const val ACTION_OVERLAY = "com.android.launcher3.WINDOW_OVERLAY"
        const val META_API_VERSION = "service.api.version"
        const val PROTOCOL_VERSION = "7"
        const val CLIENT_VERSION = "9"

        /** Provider api levels that added windowAttached2, setActivityState and redraw. */
        const val API_ATTACH_BUNDLE = 3
        const val API_ACTIVITY_STATE = 4
        const val API_REDRAW = 7

        /** Bit 0 enables the feed; the rest are what Google's launcher always sends. */
        const val CLIENT_OPTIONS = 0b1111

        const val STATE_STARTED = 1
        const val STATE_RESUMED = 2
        const val STATUS_ATTACHED = 1
        const val FLAG_ANIMATE = 1

        /** closeOverlay packs the duration above two flag bits, into 11 bits. */
        const val MAX_DURATION_MS = 2047

        fun bind(context: Context, intent: Intent, conn: ServiceConnection, flags: Int): Boolean =
            try {
                context.bindService(intent, conn, Context.BIND_AUTO_CREATE or flags)
            } catch (e: SecurityException) {
                Log.e(TAG, "Not allowed to bind the -1 screen provider", e)
                false
            }
    }
}
