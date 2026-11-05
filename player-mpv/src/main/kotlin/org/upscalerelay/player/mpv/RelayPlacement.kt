package org.upscalerelay.player.mpv

import kotlin.math.roundToInt

/**
 * How the HDR relay's layer shows part of mpv's frame in its view: the crop
 * [left], [top], [right], [bottom] in the relay's pixels, scaled by [scale],
 * with the relay's origin at ([x], [y]) in the view.
 */
internal data class RelayPlacement(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val scale: Float,
    val x: Float,
    val y: Float,
)

/**
 * Where a relay of [relayWidth]x[relayHeight] goes in a view of
 * [viewWidth]x[viewHeight] it was not made for (PiP, a resize in the middle of
 * an epoch). mpv drew the picture at [picture] (left, top, right, bottom;
 * null when it does not know yet: the whole relay) and its own bars around
 * it, for the relay's aspect.
 *
 * The layer shows the smallest centred part of the relay that holds the whole
 * picture and has the view's aspect, scaled to fit. In "fit" framing the PiP
 * window takes the picture's aspect, so that is the picture alone, filling
 * the window as mpv would at the window's size. Scaling the whole relay would
 * leave it smaller, with bars on every side and strips the layer does not
 * cover. Where the relay cannot reach the view's aspect, the strips that
 * remain are the view's own.
 */
internal fun relayPlacement(
    relayWidth: Int,
    relayHeight: Int,
    picture: IntArray?,
    viewWidth: Int,
    viewHeight: Int,
): RelayPlacement {
    val pictureLeft = picture?.getOrNull(0)?.coerceIn(0, relayWidth) ?: 0
    val pictureTop = picture?.getOrNull(1)?.coerceIn(0, relayHeight) ?: 0
    val pictureRight = picture?.getOrNull(2)?.coerceIn(0, relayWidth) ?: relayWidth
    val pictureBottom = picture?.getOrNull(3)?.coerceIn(0, relayHeight) ?: relayHeight
    val known = pictureRight > pictureLeft && pictureBottom > pictureTop
    val left0 = if (known) pictureLeft else 0
    val top0 = if (known) pictureTop else 0
    val pictureWidth = (if (known) pictureRight else relayWidth) - left0
    val pictureHeight = (if (known) pictureBottom else relayHeight) - top0

    // Widen the picture to the view's aspect, within the relay.
    val aspect = viewWidth.toDouble() / viewHeight
    var cropWidth = pictureWidth.toDouble()
    var cropHeight = pictureHeight.toDouble()
    if (cropWidth / cropHeight < aspect) cropWidth = cropHeight * aspect else cropHeight = cropWidth / aspect
    val width = cropWidth.roundToInt().coerceIn(1, relayWidth)
    val height = cropHeight.roundToInt().coerceIn(1, relayHeight)
    val left = (left0 + pictureWidth / 2.0 - width / 2.0).roundToInt().coerceIn(0, relayWidth - width)
    val top = (top0 + pictureHeight / 2.0 - height / 2.0).roundToInt().coerceIn(0, relayHeight - height)

    val scale = minOf(viewWidth.toFloat() / width, viewHeight.toFloat() / height)
    return RelayPlacement(
        left = left,
        top = top,
        right = left + width,
        bottom = top + height,
        scale = scale,
        x = (viewWidth - width * scale) / 2 - left * scale,
        y = (viewHeight - height * scale) / 2 - top * scale,
    )
}
