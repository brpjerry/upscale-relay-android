package org.upscalerelay.android

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.upscalerelay.protocol.LibraryNode

/**
 * The server library exactly as it was last on screen: the open directory and
 * the chain of parents above it, tagged with the `host:port` it came from.
 *
 * It is kept on disk so a cold start with "Connect automatically" on can draw
 * the list the user left immediately and connect behind it, instead of
 * holding a "Connecting…" screen over nothing. A snapshot is only ever a
 * picture — every listing in it is replaced by the server's answer as soon as
 * the connection is up.
 */
data class LibrarySnapshot(
    val origin: String,
    val directory: LibraryNode,
    val stack: List<LibraryNode>,
)

object LibraryCacheCodec {
    const val VERSION = 1

    /** A legacy full-tree listing can be huge; past this it is not worth caching. */
    const val MAX_CHARS = 4 * 1024 * 1024

    /** Returns null when the snapshot is too large to be worth keeping. */
    fun encode(snapshot: LibrarySnapshot): String? {
        val text = buildJsonObject {
            put("version", VERSION)
            put("origin", snapshot.origin)
            put("directory", nodeJson(snapshot.directory))
            putJsonArray("stack") { snapshot.stack.forEach { add(nodeJson(it)) } }
        }.toString()
        return text.takeIf { it.length <= MAX_CHARS }
    }

    /** Returns null for anything that is not a snapshot this version wrote. */
    fun decode(text: String): LibrarySnapshot? = runCatching {
        val root = Json.parseToJsonElement(text).jsonObject
        if (root.getValue("version").jsonPrimitive.int != VERSION) return null
        val directory = LibraryNode.fromJson(root.getValue("directory").jsonObject)
        if (directory.type != LibraryNode.Type.DIRECTORY) return null
        LibrarySnapshot(
            origin = root.getValue("origin").jsonPrimitive.content,
            directory = directory,
            stack = root.getValue("stack").jsonArray.map { LibraryNode.fromJson(it.jsonObject) },
        )
    }.getOrNull()

    private fun nodeJson(node: LibraryNode): JsonObject = buildJsonObject {
        put("type", if (node.type == LibraryNode.Type.DIRECTORY) "directory" else "file")
        put("name", node.name)
        put("path", node.path)
        if (node.children.isNotEmpty()) {
            putJsonArray("children") { node.children.forEach { add(nodeJson(it)) } }
        }
    }
}

/**
 * A directory re-opened on a fresh connection, with current listings for the
 * whole Up chain. `cursorStack[i]` is the paging cursor of `stack[i]`.
 */
data class RestoredLibrary(
    val directory: LibraryNode,
    val stack: List<LibraryNode>,
    val cursorStack: List<String?>,
    val nextCursor: String?,
) {
    /**
     * The same view cut back to [path]. A connect that runs behind the list
     * leaves "Up" usable, so by the time the restore lands the user may be
     * standing in a parent of the directory it re-opened; putting them back
     * down would be the app navigating on its own.
     */
    fun truncatedTo(path: String): RestoredLibrary {
        val index = stack.indexOfFirst { it.path == path }
        if (index < 0) return this
        return RestoredLibrary(
            directory = stack[index],
            stack = stack.take(index),
            cursorStack = cursorStack.take(index),
            nextCursor = cursorStack.getOrNull(index),
        )
    }
}
