package com.selfhost.remotesupport

import android.app.*
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import org.json.JSONObject

class CaptureForegroundService : Service() {

    companion object {
        const val EXTRA_SERVER_URL = "serverUrl"
        const val EXTRA_TURN_URL = "turnUrl"
        const val EXTRA_TURN_USERNAME = "turnUsername"
        const val EXTRA_TURN_CREDENTIAL = "turnCredential"
        const val EXTRA_PROJECTION_DATA = "projectionData"

        private const val CHANNEL_ID = "remote_support_session"
        private const val NOTIFICATION_ID = 42

        interface SessionListener {
            fun onCode(code: String)
            fun onStatus(text: String)
            fun onEnded()
        }
        var listener: SessionListener? = null
    }

    private var signalingClient: SignalingClient? = null
    private var webRtcManager: WebRtcManager? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, buildNotification())
        intent ?: return START_NOT_STICKY

        val serverUrl = intent.getStringExtra(EXTRA_SERVER_URL) ?: return stopSelfResult()
        val turnUrl = intent.getStringExtra(EXTRA_TURN_URL)
        val turnUsername = intent.getStringExtra(EXTRA_TURN_USERNAME)
        val turnCredential = intent.getStringExtra(EXTRA_TURN_CREDENTIAL)
        val projectionData = intent.getParcelableExtra<Intent>(EXTRA_PROJECTION_DATA)
            ?: return stopSelfResult()

        val wm = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)

        signalingClient = SignalingClient(
            serverUrl = serverUrl,
            onRegistered = { code ->
                listener?.onCode(code)
                listener?.onStatus("Waiting for the helper to enter this code...")
            },
            onControllerJoined = {
                listener?.onStatus("Connected! Setting up the video/control link...")
                webRtcManager = WebRtcManager(
                    context = applicationContext,
                    signaling = signalingClient!!,
                    turnUrl = turnUrl?.ifBlank { null },
                    turnUsername = turnUsername,
                    turnCredential = turnCredential,
                ).also {
                    it.start(projectionData, metrics.widthPixels, metrics.heightPixels, metrics.densityDpi)
                }
                listener?.onStatus("Session active.")
            },
            onSignal = { payload: JSONObject -> webRtcManager?.onRemoteSignal(payload) },
            onPeerLeft = {
                listener?.onStatus("Helper disconnected.")
                stopSelfResult()
            },
            onError = { message -> listener?.onStatus("Error: $message") },
        )
        signalingClient?.connect()

        return START_NOT_STICKY
    }

    private fun stopSelfResult(): Int {
        endSession()
        return START_NOT_STICKY
    }

    fun endSession() {
        webRtcManager?.stop()
        signalingClient?.endSession()
        listener?.onEnded()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        super.onDestroy()
        webRtcManager?.stop()
    }

    private fun buildNotification(): Notification {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID, "Remote support session", NotificationManager.IMPORTANCE_LOW,
            )
            getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        }
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Remote support session active")
            .setContentText("Someone you shared a pairing code with may be viewing/controlling this screen.")
            .setSmallIcon(android.R.drawable.ic_menu_view)
            .setOngoing(true)
            .build()
    }
}
