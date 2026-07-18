package org.upscalerelay.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.session.MediaSession
import android.net.wifi.WifiManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

/**
 * Process-wide bridge between the Activity-scoped playback owner
 * (RelayViewModel) and the foreground media service / PiP actions. The
 * service and system surfaces read state here; controls call back into the
 * active ViewModel through [controls].
 */
object PlaybackBridge {
    /** What the media notification shows; position/seek live in PlaybackState. */
    data class Snapshot(
        val active: Boolean = false,
        val title: String = "",
        val playing: Boolean = false,
    )

    interface Controls {
        fun togglePlayPause()
        fun playbackSeekBy(seconds: Double)
        fun stopPlayback()
    }

    val snapshot = MutableStateFlow(Snapshot())

    @Volatile
    var controls: Controls? = null

    @Volatile
    var sessionToken: MediaSession.Token? = null
}

/**
 * Foreground media-playback service. It owns nothing but the notification:
 * playback itself stays with the Activity-scoped ViewModel, and the service
 * simply keeps the process foreground while a relay session is active in the
 * background and mirrors the MediaSession into a MediaStyle notification for
 * the shade and lock screen.
 */
class PlaybackService : Service() {
    private var scope: CoroutineScope? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Playback",
                NotificationManager.IMPORTANCE_LOW,
            ).apply { description = "Active relay playback controls" },
        )
        // Background streaming dies without these: Samsung's standby Wi-Fi
        // power-save drops the relay sockets within seconds of screen-off.
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "upscalerelay:playback")
            .apply { setReferenceCounted(false) }
        val wifi = applicationContext.getSystemService(WifiManager::class.java)
        @Suppress("DEPRECATION") // low-latency mode needs API 34 constants; this covers 29+.
        wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "upscalerelay:playback")
            .apply { setReferenceCounted(false) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            PlaybackBridge.controls?.stopPlayback()
            stopSelf()
            return START_NOT_STICKY
        }
        val snapshot = PlaybackBridge.snapshot.value
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(
                NOTIFICATION_ID,
                buildNotification(snapshot),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK,
            )
        } else {
            startForeground(NOTIFICATION_ID, buildNotification(snapshot))
        }
        wakeLock?.takeIf { !it.isHeld }?.acquire(WAKE_LOCK_LIMIT_MILLIS)
        wifiLock?.takeIf { !it.isHeld }?.acquire()
        if (scope == null) {
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { active ->
                active.launch {
                    // StateFlow already conflates equal snapshots.
                    PlaybackBridge.snapshot.collectLatest { current ->
                        if (!current.active) {
                            stopSelf()
                        } else {
                            getSystemService(NotificationManager::class.java)
                                .notify(NOTIFICATION_ID, buildNotification(current))
                        }
                    }
                }
            }
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        wakeLock?.takeIf { it.isHeld }?.release()
        wifiLock?.takeIf { it.isHeld }?.release()
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    private fun buildNotification(snapshot: PlaybackBridge.Snapshot): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val builder = Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher)
            .setContentTitle(snapshot.title.ifEmpty { "Upscale Relay" })
            .setContentText(if (snapshot.playing) "Playing" else "Paused")
            .setContentIntent(open)
            .setOngoing(snapshot.playing)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
        PlaybackBridge.sessionToken?.let { token ->
            builder.setStyle(Notification.MediaStyle().setMediaSession(token))
        }
        return builder.build()
    }

    companion object {
        private const val CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 41
        // Safety bound so an orphaned lock cannot drain the battery for more
        // than one feature film; normal teardown releases far earlier.
        private const val WAKE_LOCK_LIMIT_MILLIS = 4L * 60 * 60 * 1000
        const val ACTION_STOP = "org.upscalerelay.android.action.STOP_PLAYBACK"

        fun start(context: Context) {
            context.startForegroundService(Intent(context, PlaybackService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
        }
    }
}
