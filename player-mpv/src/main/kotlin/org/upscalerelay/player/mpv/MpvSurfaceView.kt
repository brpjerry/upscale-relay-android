package org.upscalerelay.player.mpv

import android.content.Context
import android.util.AttributeSet
import android.view.SurfaceHolder
import android.view.SurfaceView

class MpvSurfaceView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : SurfaceView(context, attrs), SurfaceHolder.Callback {
    var engine: MpvPlayerEngine? = null
        set(value) {
            if (field === value) return
            if (holder.surface.isValid) field?.detachSurface(holder.surface)
            field = value
            if (holder.surface.isValid && width > 0 && height > 0) {
                value?.attachSurface(holder.surface, width, height)
            }
        }

    init {
        holder.addCallback(this)
        keepScreenOn = true
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        engine?.attachSurface(holder.surface, width.coerceAtLeast(1), height.coerceAtLeast(1))
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        engine?.resize(width, height)
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        engine?.detachSurface(holder.surface)
    }

    override fun onDetachedFromWindow() {
        if (holder.surface.isValid) engine?.detachSurface(holder.surface)
        super.onDetachedFromWindow()
    }
}

