/*
 * Adapted from mpv-android commit 3018d47 (MIT license).
 * The package and callback signatures are intentionally retained because the
 * pinned native libplayer JNI artifact resolves these exact names.
 */
package `is`.xyz.mpv

import android.content.Context
import android.view.Surface
import java.util.concurrent.CopyOnWriteArrayList

@Suppress("unused")
object MPVLib {
    init {
        System.loadLibrary("mpv")
        System.loadLibrary("player")
    }

    external fun create(appctx: Context)
    external fun init()
    external fun destroy()
    external fun attachSurface(surface: Surface)
    external fun detachSurface()
    external fun command(command: Array<out String>)
    external fun setOptionString(name: String, value: String): Int
    external fun getPropertyInt(property: String): Int?
    external fun setPropertyInt(property: String, value: Int)
    external fun getPropertyDouble(property: String): Double?
    external fun setPropertyDouble(property: String, value: Double)
    external fun getPropertyBoolean(property: String): Boolean?
    external fun setPropertyBoolean(property: String, value: Boolean)
    external fun getPropertyString(property: String): String?
    external fun setPropertyString(property: String, value: String)
    external fun observeProperty(property: String, format: Int)

    private val observers = CopyOnWriteArrayList<EventObserver>()
    private val logObservers = CopyOnWriteArrayList<LogObserver>()

    @JvmStatic fun addObserver(observer: EventObserver) { observers += observer }
    @JvmStatic fun removeObserver(observer: EventObserver) { observers -= observer }
    @JvmStatic fun addLogObserver(observer: LogObserver) { logObservers += observer }
    @JvmStatic fun removeLogObserver(observer: LogObserver) { logObservers -= observer }

    @JvmStatic fun eventProperty(property: String, value: Long) =
        observers.forEach { it.eventProperty(property, value) }
    @JvmStatic fun eventProperty(property: String, value: Boolean) =
        observers.forEach { it.eventProperty(property, value) }
    @JvmStatic fun eventProperty(property: String, value: Double) =
        observers.forEach { it.eventProperty(property, value) }
    @JvmStatic fun eventProperty(property: String, value: String) =
        observers.forEach { it.eventProperty(property, value) }
    @JvmStatic fun eventProperty(property: String) =
        observers.forEach { it.eventProperty(property) }
    @JvmStatic fun event(eventId: Int) = observers.forEach { it.event(eventId) }
    @JvmStatic fun logMessage(prefix: String, level: Int, text: String) =
        logObservers.forEach { it.logMessage(prefix, level, text) }

    interface EventObserver {
        fun eventProperty(property: String)
        fun eventProperty(property: String, value: Long)
        fun eventProperty(property: String, value: Boolean)
        fun eventProperty(property: String, value: String)
        fun eventProperty(property: String, value: Double)
        fun event(eventId: Int)
    }

    interface LogObserver {
        fun logMessage(prefix: String, level: Int, text: String)
    }

    object MpvFormat {
        const val NONE = 0
        const val STRING = 1
        const val FLAG = 3
        const val INT64 = 4
        const val DOUBLE = 5
    }

    object MpvEvent {
        const val SHUTDOWN = 1
        const val START_FILE = 6
        const val END_FILE = 7
        const val FILE_LOADED = 8
        const val VIDEO_RECONFIG = 17
        const val PLAYBACK_RESTART = 21
    }
}

