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
import android.os.Build
import android.os.Bundle
import android.util.Rational
import android.view.KeyEvent
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
        if (state.playingPath != null) {
            when (keyCode) {
                KeyEvent.KEYCODE_SPACE, KeyEvent.KEYCODE_K -> {
                    runCatching { viewModel.togglePaused() }
                    return true
                }
                KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_J -> {
                    viewModel.seekRelative(-10.0)
                    return true
                }
                KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_L -> {
                    viewModel.seekRelative(10.0)
                    return true
                }
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    companion object {
        private const val ACTION_PIP_PLAY_PAUSE = "org.upscalerelay.android.action.PIP_PLAY_PAUSE"
    }
}
