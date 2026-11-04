package org.upscalerelay.player.mpv

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.DataSpace
import android.hardware.HardwareBuffer
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.AttributeSet
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl
import android.view.SurfaceHolder
import android.view.SurfaceView
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

class MpvSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    var engine: MpvPlayerEngine? = null
        set(value) {
            if (field === value) return
            if (holder.surface.isValid) field?.detachSurface(mpvSurface(holder.surface))
            field = value
            if (holder.surface.isValid && width > 0 && height > 0) {
                value?.attachSurface(mpvSurface(holder.surface), width, height)
            }
        }

    init {
        holder.addCallback(this)
        keepScreenOn = true
        SpikeConfig.reload(context)
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        val w = width.coerceAtLeast(1)
        val h = height.coerceAtLeast(1)
        when (SpikeConfig.values["spike.tag"]) {
            "relay" -> startRelay(w, h)
            "sc" -> tagSurfaceControl()
        }
        engine?.attachSurface(mpvSurface(holder.surface), w, h)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        if (relay == null) engine?.resize(width, height)
        if (SpikeConfig.values["spike.tag"] == "sc") tagSurfaceControl()
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        engine?.detachSurface(mpvSurface(holder.surface))
        stopRelay()
    }

    override fun onDetachedFromWindow() {
        if (holder.surface.isValid) engine?.detachSurface(mpvSurface(holder.surface))
        stopRelay()
        super.onDetachedFromWindow()
    }

    // ---- HDR output spike (prototype branch only) -------------------------

    private val spikeDataSpace: Int
        get() = SpikeConfig.values["spike.dataspace"]?.toIntOrNull() ?: DataSpace.DATASPACE_BT2020_PQ

    /** Approach 3a: tag the SurfaceView's own layer; BLAST may overwrite it per buffer. */
    private fun tagSurfaceControl() {
        val control = surfaceControl ?: return
        SurfaceControl.Transaction().setDataSpace(control, spikeDataSpace).apply()
        postDelayed({
            surfaceControl?.let { SurfaceControl.Transaction().setDataSpace(it, spikeDataSpace).apply() }
            Log.i(TAG, "re-tagged SurfaceView layer dataspace=$spikeDataSpace")
        }, 15_000)
        Log.i(TAG, "tagged SurfaceView layer dataspace=$spikeDataSpace")
    }

    private class Relay(val reader: ImageReader, val child: SurfaceControl, val thread: HandlerThread)

    private var relay: Relay? = null
    private val relayed = AtomicLong()
    private val skipped = AtomicLong()

    private fun mpvSurface(own: Surface): Surface = relay?.reader?.surface ?: own

    /**
     * Approach 3b: mpv renders into an ImageReader whose default dataspace is
     * PQ (EGL queues UNKNOWN, which the BufferQueue replaces with the
     * consumer's default), and each buffer goes to a child SurfaceControl
     * with its dataspace.
     */
    private fun startRelay(w: Int, h: Int) {
        stopRelay()
        val parent = surfaceControl ?: return
        val format = SpikeConfig.values["spike.relayformat"]?.toIntOrNull() ?: PixelFormat.RGBA_1010102
        val thread = HandlerThread("spike-relay").apply { start() }
        val reader = ImageReader.Builder(w, h)
            .setImageFormat(format)
            .setMaxImages(5)
            .setUsage(
                HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                    HardwareBuffer.USAGE_COMPOSER_OVERLAY,
            )
            .setDefaultDataSpace(spikeDataSpace)
            .build()
        val child = SurfaceControl.Builder().setName("spike-hdr-relay").setParent(parent).setBufferSize(w, h).build()
        val init = SurfaceControl.Transaction().setVisibility(child, true).setLayer(child, 1)
        SpikeConfig.values["spike.headroom"]?.toFloatOrNull()?.let { init.setDesiredHdrHeadroom(child, it) }
        init.apply()
        reader.setOnImageAvailableListener({ source ->
            val image = try {
                source.acquireNextImage()
            } catch (error: IllegalStateException) {
                skipped.incrementAndGet()
                null
            } ?: return@setOnImageAvailableListener
            val buffer = image.hardwareBuffer
            if (buffer == null) {
                image.close()
                return@setOnImageAvailableListener
            }
            val dataSpace = image.dataSpace
            SurfaceControl.Transaction()
                .setBuffer(child, buffer, image.fence) { release ->
                    release.await(Duration.ofMillis(200))
                    image.close()
                    buffer.close()
                }
                .setDataSpace(child, dataSpace)
                .apply()
            val count = relayed.incrementAndGet()
            if (count == 1L || count % 240 == 0L) {
                Log.i(TAG, "relayed=$count skipped=${skipped.get()} dataspace=$dataSpace format=${image.format}")
            }
        }, Handler(thread.looper))
        relay = Relay(reader, child, thread)
        Log.i(TAG, "relay started ${w}x$h format=$format dataspace=$spikeDataSpace")
    }

    private fun stopRelay() {
        val current = relay ?: return
        relay = null
        SurfaceControl.Transaction().reparent(current.child, null).apply()
        current.child.release()
        current.reader.close()
        current.thread.quitSafely()
    }

    private companion object {
        const val TAG = "RelaySpike"
    }
}
