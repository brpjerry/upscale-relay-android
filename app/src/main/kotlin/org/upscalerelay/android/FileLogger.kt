package org.upscalerelay.android

import android.content.ContentValues
import android.content.Context
import android.provider.MediaStore
import android.util.Log
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/**
 * Opt-in diagnostic log in the user's public Documents folder
 * (`Documents/UpscaleRelay/upscale-relay-<start time>.log`), written through
 * MediaStore so no storage permission is needed. One file per enable/process
 * session; only the newest [KEEP_FILES] files are retained.
 *
 * Callers never block: lines go through a bounded queue to a writer thread,
 * which flushes whenever the queue drains so a native crash loses at most
 * the lines still queued.
 */
class FileLogger private constructor(
    private val stream: OutputStream,
    val displayName: String,
) {
    private val queue = LinkedBlockingQueue<String>(QUEUE_CAPACITY)

    @Volatile
    private var closed = false
    private val timestampFormat = SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US)

    private val worker = Thread({
        try {
            while (true) {
                val line = queue.poll(500, TimeUnit.MILLISECONDS)
                if (line == null) {
                    if (closed) break
                    continue
                }
                stream.write(line.toByteArray(Charsets.UTF_8))
                if (queue.isEmpty()) stream.flush()
            }
        } catch (_: InterruptedException) {
        } catch (error: Exception) {
            Log.w(TAG, "log writer stopped: ${error.message}")
        } finally {
            runCatching { stream.flush() }
            runCatching { stream.close() }
        }
    }, "relay-file-log").apply {
        isDaemon = true
        start()
    }

    fun log(level: Char, tag: String, message: String) {
        if (closed) return
        val timestamp = synchronized(timestampFormat) { timestampFormat.format(Date()) }
        // A full queue drops the line rather than stalling playback threads.
        queue.offer("$timestamp $level/$tag: $message\n")
    }

    /** Flushes what is queued and closes; used on disable and from crashes. */
    fun close(waitMillis: Long = 2_000) {
        if (closed) return
        closed = true
        runCatching { worker.join(waitMillis) }
    }

    companion object {
        private const val TAG = "RelayFileLog"
        private const val RELATIVE_PATH = "Documents/UpscaleRelay"
        private const val KEEP_FILES = 10
        private const val QUEUE_CAPACITY = 4096

        /** Creates the MediaStore-backed file; null when creation fails. */
        fun start(context: Context): FileLogger? {
            val resolver = context.applicationContext.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val name = "upscale-relay-" +
                SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date()) + ".log"
            return try {
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_PATH)
                }
                val uri = resolver.insert(collection, values) ?: return null
                val stream = resolver.openOutputStream(uri, "wa") ?: return null
                pruneOldLogs(context)
                FileLogger(stream, name)
            } catch (error: Exception) {
                Log.w(TAG, "unable to create log file: ${error.message}")
                null
            }
        }

        private fun pruneOldLogs(context: Context) {
            val resolver = context.applicationContext.contentResolver
            val collection = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            runCatching {
                val entries = mutableListOf<Pair<Long, String>>() // id to name
                resolver.query(
                    collection,
                    arrayOf(MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DISPLAY_NAME),
                    "${MediaStore.MediaColumns.RELATIVE_PATH} LIKE ? AND " +
                        "${MediaStore.MediaColumns.DISPLAY_NAME} LIKE ?",
                    arrayOf("$RELATIVE_PATH%", "upscale-relay-%.log"),
                    null,
                )?.use { cursor ->
                    while (cursor.moveToNext()) {
                        entries += cursor.getLong(0) to cursor.getString(1)
                    }
                }
                // Names embed the start time, so sorting them is chronological.
                entries.sortedByDescending { it.second }.drop(KEEP_FILES - 1).forEach { (id, _) ->
                    resolver.delete(android.content.ContentUris.withAppendedId(collection, id), null, null)
                }
            }
        }
    }
}

/**
 * Process-wide logging facade: always forwards to logcat, and mirrors into
 * the opt-in [FileLogger] when one is attached. Also owns the crash hook so
 * field crashes land in the file with a stack trace.
 */
object AppLog {
    @Volatile
    private var logger: FileLogger? = null

    @Volatile
    private var previousCrashHandler: Thread.UncaughtExceptionHandler? = null
    private var crashHandlerInstalled = false

    val active: Boolean get() = logger != null
    val currentFileName: String? get() = logger?.displayName

    @Synchronized
    fun attach(next: FileLogger?) {
        val old = logger
        logger = next
        old?.close()
        if (next != null) installCrashHandler()
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        logger?.log('D', tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        logger?.log('I', tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        logger?.log('W', tag, message)
    }

    fun e(tag: String, message: String, error: Throwable? = null) {
        Log.e(tag, message, error)
        logger?.log('E', tag, if (error != null) "$message: ${Log.getStackTraceString(error)}" else message)
    }

    /** File-only line for streams that already reach logcat elsewhere (mpv). */
    fun fileOnly(level: Char, tag: String, message: String) {
        logger?.log(level, tag, message)
    }

    @Synchronized
    private fun installCrashHandler() {
        if (crashHandlerInstalled) return
        crashHandlerInstalled = true
        previousCrashHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            logger?.log(
                'E',
                "CRASH",
                "uncaught on ${thread.name}: ${Log.getStackTraceString(throwable)}",
            )
            logger?.close()
            previousCrashHandler?.uncaughtException(thread, throwable)
        }
    }
}
