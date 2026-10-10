package org.upscalerelay.android

/**
 * Gives back a press that the tablet's palm filter cancelled as the finger
 * lifted.
 *
 * The Tab S9 Ultra's touch controller raises its palm flag on ordinary
 * fingertip presses in the top 9 mm of the screen in landscape, which is
 * where the player's top bar puts its buttons. Measured on 2026-10-09: 22 of
 * 44 presses on the lock, every one of them at y <= 85 px and none of the
 * seven at y >= 91 px. The flag comes 68 to 136 ms after the finger goes
 * down, on the last sample before it lifts. The system then delivers
 * ACTION_CANCEL marked FLAG_CANCELED and, 14 to 17 ms later, the ACTION_UP.
 * The button had lit up and nothing happened; the lock took several presses.
 *
 * So in that strip, in the player, a cancel like that is held back for a
 * moment. If the up follows at once the cancel was a press being lifted: it
 * is dropped and the up delivered, and whatever was pressed is clicked. A
 * palm that is really resting there keeps producing cancels, one per sample,
 * and the held one is delivered when the next arrives, so it goes on pressing
 * nothing. Anywhere else on the screen the tablet's verdict stands.
 *
 * This sits in MainActivity.dispatchTouchEvent because only there are the
 * flag and the trailing up visible; Compose sees a cancel and nothing more.
 */
internal class PalmCancelRescue(
    /** Height of the strip at the top of the window that is covered. */
    private val stripPx: Float,
    /** How far a press may wander from where it went down and still be one. */
    private val driftPx: Float,
) {
    enum class Kind { DOWN, MOVE, UP, CANCEL, OTHER }

    enum class Verdict {
        /** Deliver the event as it is. */
        PASS,

        /** Keep this cancel back until the next event shows what it was. */
        HOLD,

        /** The held cancel was a press being lifted: drop it, deliver this up. */
        RESCUE,

        /** The held cancel was a cancel: deliver it, then this event. */
        FLUSH,
    }

    private var armedAtDown = false
    private var strayed = false

    // One verdict a touch: a palm that stays sends a cancel for every sample.
    private var decided = false
    private var downTime = 0L
    private var downX = 0f
    private var downY = 0f
    private var heldAt: Long? = null

    /**
     * Why the event just given to [onEvent] left a touch the system had
     * cancelled as a palm cancelled, for the log; null when it did not.
     */
    var refusal: String? = null
        private set

    /**
     * [armed] is read when the finger goes down: whether the player is what
     * is on screen. [systemCanceled] is the event's FLAG_CANCELED.
     */
    fun onEvent(
        kind: Kind,
        eventTime: Long,
        x: Float,
        y: Float,
        pointerCount: Int,
        systemCanceled: Boolean,
        armed: Boolean,
    ): Verdict {
        refusal = null
        val held = heldAt
        if (held != null) {
            heldAt = null
            val lifted = kind == Kind.UP && pointerCount == 1 &&
                eventTime - held <= LIFT_WINDOW_MILLIS && near(x, y)
            if (lifted) return Verdict.RESCUE
            refusal = "no up followed its cancel ($kind after ${eventTime - held} ms)"
            return Verdict.FLUSH
        }
        when (kind) {
            Kind.DOWN -> {
                armedAtDown = armed
                strayed = false
                decided = false
                downTime = eventTime
                downX = x
                downY = y
            }
            Kind.MOVE -> if (pointerCount != 1 || !near(x, y)) strayed = true
            Kind.CANCEL -> if (systemCanceled && !decided) {
                decided = true
                val age = eventTime - downTime
                refusal = when {
                    !armedAtDown -> "the player is not up"
                    downY >= stripPx -> "below the top strip, at y=${downY.toInt()}"
                    strayed || pointerCount != 1 || !near(x, y) -> "it moved or was not alone"
                    age > PRESS_MILLIS -> "held for $age ms"
                    else -> null
                }
                if (refusal == null) {
                    heldAt = eventTime
                    return Verdict.HOLD
                }
            }
            Kind.UP -> Unit
            Kind.OTHER -> strayed = true
        }
        return Verdict.PASS
    }

    /** Nothing followed the held cancel in time, and it has been delivered. */
    fun flushed() {
        heldAt = null
    }

    private fun near(x: Float, y: Float): Boolean =
        kotlin.math.abs(x - downX) <= driftPx && kotlin.math.abs(y - downY) <= driftPx

    companion object {
        /** The longest a press may have lasted when its cancel arrives. */
        const val PRESS_MILLIS = 400L

        /** How soon after the cancel the up has to be, in event time. */
        const val LIFT_WINDOW_MILLIS = 50L
    }
}
