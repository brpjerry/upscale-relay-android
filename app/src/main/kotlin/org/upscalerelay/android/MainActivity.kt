package org.upscalerelay.android

import android.Manifest
import android.app.PendingIntent
import android.app.PictureInPictureParams
import android.app.RemoteAction
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.graphics.drawable.Icon
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import android.view.KeyEvent
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val viewModel: RelayViewModel by viewModels()
    private var inPictureInPicture by mutableStateOf(false)

    private val pipActionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == ACTION_PIP_PLAY_PAUSE) {
                PlaybackBridge.controls?.togglePlayPause()
            }
        }
    }

    // A press on the player's top bar that the tablet cancels as a palm while
    // the finger lifts is handed on as the press it was (PalmCancelRescue).
    private val palmRescue by lazy {
        val density = resources.displayMetrics.density
        PalmCancelRescue(stripPx = PALM_STRIP_DP * density, driftPx = PALM_DRIFT_DP * density)
    }
    private val mainHandler = Handler(Looper.getMainLooper())
    private var heldCancel: MotionEvent? = null
    private val deliverHeldCancel = Runnable {
        heldCancel?.let { held ->
            heldCancel = null
            palmRescue.flushed()
            AppLog.d(TAG, "left a touch the system cancelled as a palm alone: nothing followed its cancel")
            deliverTouch(held)
            held.recycle()
        }
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val kind = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> PalmCancelRescue.Kind.DOWN
            MotionEvent.ACTION_MOVE -> PalmCancelRescue.Kind.MOVE
            MotionEvent.ACTION_UP -> PalmCancelRescue.Kind.UP
            MotionEvent.ACTION_CANCEL -> PalmCancelRescue.Kind.CANCEL
            else -> PalmCancelRescue.Kind.OTHER
        }
        val verdict = palmRescue.onEvent(
            kind = kind,
            eventTime = event.eventTime,
            x = event.x,
            y = event.y,
            pointerCount = event.pointerCount,
            systemCanceled = event.flags and FLAG_CANCELED != 0,
            armed = viewModel.ui.value.playingPath != null && !inPictureInPicture,
        )
        palmRescue.refusal?.let { AppLog.d(TAG, "left a touch the system cancelled as a palm alone: $it") }
        return when (verdict) {
            PalmCancelRescue.Verdict.PASS -> super.dispatchTouchEvent(event)
            PalmCancelRescue.Verdict.HOLD -> {
                heldCancel = MotionEvent.obtain(event)
                // In wall time and generous: the up is judged by its own
                // event time, and a busy main thread may deliver it late.
                mainHandler.postDelayed(deliverHeldCancel, PalmCancelRescue.LIFT_WINDOW_MILLIS * 2)
                true
            }
            PalmCancelRescue.Verdict.RESCUE -> {
                AppLog.d(TAG, "delivered a press the system cancelled as a palm, at y=${event.y.toInt()}")
                mainHandler.removeCallbacks(deliverHeldCancel)
                heldCancel?.recycle()
                heldCancel = null
                super.dispatchTouchEvent(event)
            }
            PalmCancelRescue.Verdict.FLUSH -> {
                mainHandler.removeCallbacks(deliverHeldCancel)
                heldCancel?.let { held ->
                    heldCancel = null
                    super.dispatchTouchEvent(held)
                    held.recycle()
                }
                super.dispatchTouchEvent(event)
            }
        }
    }

    private fun deliverTouch(event: MotionEvent) {
        super.dispatchTouchEvent(event)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 0)
        }
        ContextCompat.registerReceiver(
            this,
            pipActionReceiver,
            IntentFilter(ACTION_PIP_PLAY_PAUSE),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        setContent { RelayApp(viewModel, inPictureInPicture) }
        lifecycleScope.launch {
            viewModel.ui.collectLatest { state ->
                runCatching { setPictureInPictureParams(pictureInPictureParams(state)) }
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(deliverHeldCancel)
        heldCancel?.recycle()
        heldCancel = null
        runCatching { unregisterReceiver(pipActionReceiver) }
        super.onDestroy()
    }

    private fun pictureInPictureParams(state: RelayUiState): PictureInPictureParams {
        val videoActive = state.playingPath != null && state.error == null &&
            state.reconnecting == null
        val width = state.session?.downlinkWidth ?: 16
        val height = state.session?.downlinkHeight ?: 9
        val ratio = if (width > 0 && height > 0) Rational(width, height) else Rational(16, 9)
        val clamped = when {
            ratio.toFloat() > 2.35f -> Rational(235, 100)
            ratio.toFloat() < 0.45f -> Rational(45, 100)
            else -> ratio
        }
        val playPause = RemoteAction(
            Icon.createWithResource(
                this,
                if (state.paused) R.drawable.ic_pip_play else R.drawable.ic_pip_pause,
            ),
            if (state.paused) "Play" else "Pause",
            "Toggle playback",
            PendingIntent.getBroadcast(
                this,
                1,
                Intent(ACTION_PIP_PLAY_PAUSE).setPackage(packageName),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        val builder = PictureInPictureParams.Builder()
            .setAspectRatio(clamped)
            .setActions(if (videoActive) listOf(playPause) else emptyList())
        // The player Surface fills the window. Supplying its visible bounds
        // lets Android animate from the actual player into the PiP window.
        val sourceRect = Rect()
        if (videoActive && window.decorView.getGlobalVisibleRect(sourceRect)) {
            builder.setSourceRectHint(sourceRect)
        }
        if (Build.VERSION.SDK_INT >= 31) {
            builder.setAutoEnterEnabled(videoActive && !state.paused)
        }
        return builder.build()
    }

    @Deprecated("Deprecated in Java")
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Pre-S has no auto-enter; S+ uses the params flag instead.
        val state = viewModel.ui.value
        if (Build.VERSION.SDK_INT < 31 && state.playingPath != null && !state.paused &&
            state.error == null && state.reconnecting == null
        ) {
            runCatching { enterPictureInPictureMode(pictureInPictureParams(state)) }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        val state = viewModel.ui.value
        if (state.playingPath != null && event?.isCtrlPressed != true &&
            event?.isAltPressed != true && event?.isMetaPressed != true
        ) {
            when (keyCode) {
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K -> {
                    // A held key emits repeated DOWN events. One physical
                    // press must change pause intent only once.
                    if ((event?.repeatCount ?: 0) == 0) viewModel.togglePaused()
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_J -> {
                    viewModel.skip(-1)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_L -> {
                    viewModel.skip(1)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val TAG = "RelayAndroid"
        private const val ACTION_PIP_PLAY_PAUSE = "org.upscalerelay.android.action.PIP_PLAY_PAUSE"

        /** MotionEvent.FLAG_CANCELED, which is public only from API 33. */
        private const val FLAG_CANCELED = 0x20

        // The tablet flagged presses down to 49dp from the top edge and none
        // from 52dp on; the top bar's buttons end at 60dp. The presses it
        // flagged had moved 10 px at most.
        private const val PALM_STRIP_DP = 64
        private const val PALM_DRIFT_DP = 24
    }
}
