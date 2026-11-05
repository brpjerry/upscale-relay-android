package org.upscalerelay.player.mpv

import android.annotation.TargetApi
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
 * this view owns. Which of the two mpv gets follows the output its engine's
 * load asks for ([MpvPlayerEngine.requestedOutput]). Everything here runs on
 * the main thread.
 */
class MpvSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    /**
     * Null when the view is let go (the player leaves composition): the
     * engine outlives it in the ViewModel and must not keep it, or its
     * Activity, through [MpvPlayerEngine.outputRequestListener].
     */
    var engine: MpvPlayerEngine? = null
        set(value) {
            if (field === value) return
            field?.let { previous ->
                // A newer view may already have taken the engine over.
                if (previous.outputRequestListener === requestOutput) previous.outputRequestListener = null
                unbind(previous)
            }
            field = value
            value?.outputRequestListener = requestOutput
            value?.resetHdrAvailability()
            bind()
        }

    private val requestOutput: () -> Unit = { bind() }

    private var relay: HdrSurfaceRelay? = null

    /**
     * No relay could be made for this Surface: PQ loads play tone-mapped on
     * the SDR surface (or on a relay that already exists) until the Surface
     * is replaced. Trying again on every resize would swap mpv's VO in the
     * middle of an epoch whenever one succeeded.
     */
    private var relayUnavailable = false

    /** The Surface the engine was last given: this view's own, or the relay's. */
    private var given: Surface? = null
    private var surfaceWidth = 0
    private var surfaceHeight = 0

    init {
        holder.addCallback(this)
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        // surfaceChanged always follows, with the Surface's real size. A relay
        // made here from the view's size (unknown before layout) would be
        // kept for the whole epoch.
        surfaceWidth = 0
        surfaceHeight = 0
        engine?.resetHdrAvailability()
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
            val old = relay
            // While an epoch plays, a relay keeps its size: a resize (PiP)
            // scales its layer rather than giving mpv a new VO in the middle
            // of the epoch. A load waiting for its Surface gets a new relay
            // when the old one is smaller than the view, as one made in PiP
            // is once the window grows again. A larger one is kept: scaled
            // down into PiP, it is still the whole picture.
            val next = old?.takeIf { it.coversView() || !engine.awaitsSurface() } ?: newRelay() ?: old
            if (next != null) {
                engine.attachSurface(next.surface, next.width, next.height, wanted, coversView = next.coversView())
                next.fitTo(surfaceWidth, surfaceHeight, engine.pictureBounds())
                given = next.surface
                relay = next
                // Attaching the new relay detached mpv from the old one.
                if (old != null && old !== next) retire(old, engine)
                return
            }
        }
        engine.attachSurface(holder.surface, surfaceWidth, surfaceHeight, VideoOutput.Sdr)
        given = holder.surface
        relay?.let { retire(it, engine) }
        relay = null
    }

    /** A relay at this view's size, or null if none can be made for this Surface. */
    @TargetApi(33)
    private fun newRelay(): HdrSurfaceRelay? {
        if (relayUnavailable) return null
        return try {
            HdrSurfaceRelay.create(surfaceControl, surfaceWidth, surfaceHeight)
        } catch (error: RuntimeException) {
            Log.w(TAG, "HDR output unavailable, playing tone-mapped: $error")
            relayUnavailable = true
            null
        }
    }

    private fun HdrSurfaceRelay.coversView() = width >= surfaceWidth && height >= surfaceHeight

    private fun unbind(engine: MpvPlayerEngine) {
        given?.let(engine::detachSurface)
        given = null
        relay?.let { retire(it, engine) }
        relay = null
        relayUnavailable = false
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
