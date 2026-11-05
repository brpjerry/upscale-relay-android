package org.upscalerelay.player.mpv

import android.annotation.TargetApi
import android.hardware.DataSpace
import android.hardware.HardwareBuffer
import android.hardware.SyncFence
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.util.Log
import android.view.Surface
import android.view.SurfaceControl

/**
 * Presents mpv's PQ output as HDR (docs/HDR_OUTPUT.md, approach 3).
 *
 * mpv's Android GL context creates its EGL surface with no colour space, so
 * SurfaceFlinger shows whatever mpv renders as sRGB, and Java cannot retag
 * the `SurfaceView`'s own buffer layer. So mpv renders into this relay's
 * [ImageReader] instead (RGBA_1010102, default dataspace BT2020_PQ), and each
 * frame goes on to a child [SurfaceControl] of the `SurfaceView` with that
 * dataspace. Measured on the Tab S9 Ultra: an RGBA_1010102 BT2020_PQ overlay,
 * the panel in HDR, no drops, and no GPU cost over rendering PQ untagged.
 *
 * An owner like the loopback and font owners (CLAUDE.md): mpv must stop
 * rendering into [surface] before the reader goes. [hide] takes the layer off
 * screen at once; [close] releases the reader and the thread, and runs only
 * after mpv was detached and its command queue drained
 * (MpvPlayerEngine.afterPendingCommands).
 *
 * Everything that touches the reader or the layer runs on the relay's own
 * thread, so a retirement can never race a frame in flight. That includes
 * returning a frame SurfaceFlinger let go of: its release callback comes on
 * another thread and only hands the frame over.
 */
@TargetApi(33) // callers check: MpvSurfaceView relays only from API 33
internal class HdrSurfaceRelay private constructor(
    val width: Int,
    val height: Int,
    private val reader: ImageReader,
    private val layer: SurfaceControl,
    private val thread: HandlerThread,
) {
    private val handler = Handler(thread.looper)

    /** Where mpv renders. Valid until [close]. */
    val surface: Surface = reader.surface

    // Relay thread only.
    private var hidden = false
    private var relayed = 0L
    private var superseded = 0L
    private var failed = 0L

    /**
     * Shows the picture in a view of [viewWidth]x[viewHeight] without asking
     * mpv for a new size, which would take a new VO in the middle of an
     * epoch: scaled to fit and centred. Entering PiP keeps the aspect ratio,
     * so the picture just shrinks. Any thread.
     */
    fun fitTo(viewWidth: Int, viewHeight: Int) {
        val scale = minOf(viewWidth.toFloat() / width, viewHeight.toFloat() / height)
        val x = (viewWidth - width * scale) / 2
        val y = (viewHeight - height * scale) / 2
        handler.post {
            if (hidden) return@post
            runCatching {
                SurfaceControl.Transaction().setScale(layer, scale, scale).setPosition(layer, x, y).apply()
            }.onFailure { Log.w(TAG, "HDR layer could not be fitted: $it") }
        }
    }

    /** Takes the picture off screen; frames still queued are dropped. Any thread. */
    fun hide() {
        handler.post {
            if (hidden) return@post
            hidden = true
            runCatching {
                SurfaceControl.Transaction().reparent(layer, null).apply()
                layer.release()
            }.onFailure { Log.w(TAG, "HDR layer release failed: $it") }
            Log.i(TAG, "HDR relay ${width}x$height hidden: relayed=$relayed superseded=$superseded failed=$failed")
        }
    }

    /**
     * Releases the reader and its thread. Only once mpv renders into [surface]
     * no more: detached from it, with its command queue drained. Any thread.
     */
    fun close() {
        hide()
        handler.post {
            reader.close()
            thread.quitSafely()
        }
    }

    /**
     * Presents the newest frame mpv has queued. Older ones still waiting are
     * passed over, so a late callback can never leave the picture a frame
     * behind the audio for good.
     */
    private fun relayLatest() {
        if (hidden) return
        var latest: Image? = null
        while (true) {
            // Throws once maxImages are out: what is left waits for the next
            // callback, or for the release below, which looks again.
            val next = try {
                reader.acquireNextImage()
            } catch (_: IllegalStateException) {
                null
            } ?: break
            latest?.let {
                it.close()
                superseded += 1
            }
            latest = next
        }
        latest?.let(::present)
    }

    private fun present(image: Image) {
        val buffer = image.hardwareBuffer
        if (buffer == null) {
            image.close()
            failed += 1
            return
        }
        try {
            image.fence.use { fence ->
                SurfaceControl.Transaction()
                    .setBuffer(layer, buffer, fence) { release ->
                        // Once the relay has quit (close), its reader is
                        // closed and nothing presents any more.
                        if (!handler.post { recycle(image, buffer, release, relayNext = true) }) {
                            recycle(image, buffer, release, relayNext = false)
                        }
                    }
                    .setDataSpace(layer, DataSpace.DATASPACE_BT2020_PQ)
                    .apply()
            }
            relayed += 1
            if (relayed == 1L) Log.i(TAG, "HDR relay ${width}x$height: first frame on screen")
        } catch (error: RuntimeException) {
            buffer.close()
            image.close()
            if (failed++ == 0L) Log.w(TAG, "HDR relay could not present a frame: $error")
        }
    }

    /**
     * Gives a frame SurfaceFlinger let go of back to mpv. The compositor may
     * read the buffer until its release fence signals, so this waits for that
     * however long it takes: a buffer returned early is one mpv may draw the
     * next frame into while it is still on screen.
     */
    private fun recycle(image: Image, buffer: HardwareBuffer, release: SyncFence, relayNext: Boolean) {
        release.use { it.awaitForever() }
        buffer.close()
        image.close()
        if (relayNext) relayLatest()
    }

    companion object {
        private const val TAG = "RelayMpv"

        /** mpv holds about two, the compositor two: five leaves one to spare. */
        private const val MAX_IMAGES = 5

        /** A relay of [width]x[height] under [parent], the `SurfaceView`'s layer. Main thread. */
        fun create(parent: SurfaceControl, width: Int, height: Int): HdrSurfaceRelay {
            val thread = HandlerThread("relay-hdr-output").apply { start() }
            var reader: ImageReader? = null
            var layer: SurfaceControl? = null
            try {
                // Format and dataspace as the queue's defaults. (setImageFormat
                // would reset the hardware format to RGBA_8888 once a dataspace
                // is set: mpv's EGL config still asks for 10 bits, but the
                // reader should say what it holds.)
                reader = ImageReader.Builder(width, height)
                    .setDefaultHardwareBufferFormat(HardwareBuffer.RGBA_1010102)
                    // EGL queues mpv's buffers with no dataspace; the queue
                    // gives them the consumer's default instead.
                    .setDefaultDataSpace(DataSpace.DATASPACE_BT2020_PQ)
                    .setMaxImages(MAX_IMAGES)
                    .setUsage(
                        HardwareBuffer.USAGE_GPU_COLOR_OUTPUT or HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or
                            HardwareBuffer.USAGE_COMPOSER_OVERLAY,
                    )
                    .build()
                layer = SurfaceControl.Builder()
                    .setName("relay-hdr-video")
                    .setParent(parent)
                    .setBufferSize(width, height)
                    // mpv fills every pixel; nothing beneath may show through.
                    .setOpaque(true)
                    .build()
                SurfaceControl.Transaction()
                    .setLayer(layer, 1)
                    .setDataSpace(layer, DataSpace.DATASPACE_BT2020_PQ)
                    .setVisibility(layer, true)
                    .apply()
                val relay = HdrSurfaceRelay(width, height, reader, layer, thread)
                reader.setOnImageAvailableListener({ relay.relayLatest() }, relay.handler)
                Log.i(TAG, "HDR relay ${width}x$height started")
                return relay
            } catch (error: Throwable) {
                runCatching { layer?.release() }
                runCatching { reader?.close() }
                thread.quitSafely()
                throw error
            }
        }
    }
}
