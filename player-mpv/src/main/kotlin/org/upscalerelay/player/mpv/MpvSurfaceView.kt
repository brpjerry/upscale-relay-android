package org.upscalerelay.player.mpv

import android.content.Context
import android.os.Build
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceHolder
import android.view.SurfaceView

/**
 * The player's surface. mpv draws an SDR picture straight into this view's
 * own Surface; a PQ one goes through an [HdrSurfaceRelay] (API 33+), which
 * this view owns at its Surface's size. Which of the two mpv gets follows the
 * output its engine's load asks for ([MpvPlayerEngine.requestedOutput]).
 * Everything here runs on the main thread.
 */
class MpvSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    var engine: MpvPlayerEngine? = null
        set(value) {
            if (field === value) return
            field?.let { previous ->
                previous.outputRequestListener = null
                unbind(previous)
            }
            field = value
            value?.outputRequestListener = ::bind
            bind()
        }

    private var relay: HdrSurfaceRelay? = null

    /** The Surface the engine was last given: this view's own, or the relay's. */
    private var given: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    init {
        holder.addCallback(this)
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceWidth = width.coerceAtLeast(1)
        surfaceHeight = height.coerceAtLeast(1)
        bind()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        surfaceWidth = width
        surfaceHeight = height
        bind()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        engine?.let(::unbind)
    }

    override fun onDetachedFromWindow() {
        engine?.let(::unbind)
        super.onDetachedFromWindow()
    }

    /**
     * Gives the engine the Surface for the output it asks for, at this view's
     * size. A relay that cannot be made leaves mpv on the SDR surface, where
     * it tone-maps.
     */
    private fun bind() {
        val engine = engine ?: return
        if (!holder.surface.isValid || surfaceWidth <= 0 || surfaceHeight <= 0) return
        val wanted = engine.requestedOutput()
        if (wanted is VideoOutput.Pq && Build.VERSION.SDK_INT >= 33) {
            // A relay keeps the size it was made at, for as long as this
            // Surface lives: a resize (PiP) scales its layer rather than
            // giving mpv a new VO in the middle of an epoch.
            val next = relay ?: try {
                HdrSurfaceRelay.create(surfaceControl, surfaceWidth, surfaceHeight)
            } catch (error: RuntimeException) {
                Log.w(TAG, "HDR output unavailable, playing tone-mapped: $error")
                null
            }
            if (next != null) {
                engine.attachSurface(next.surface, next.width, next.height, wanted)
                next.fitTo(surfaceWidth, surfaceHeight)
                given = next.surface
                relay = next
                return
            }
        }
        engine.attachSurface(holder.surface, surfaceWidth, surfaceHeight, VideoOutput.Sdr)
        given = holder.surface
        relay?.let { retire(it, engine) }
        relay = null
    }

    private fun unbind(engine: MpvPlayerEngine) {
        given?.let(engine::detachSurface)
        given = null
        relay?.let { retire(it, engine) }
        relay = null
    }

    /** mpv is already off [old]: hide it now, close it once mpv's queued commands have run. */
    private fun retire(old: HdrSurfaceRelay, engine: MpvPlayerEngine) {
        old.hide()
        engine.afterPendingCommands(old::close)
    }

    private companion object {
        const val TAG = "RelayMpv"
    }
}
