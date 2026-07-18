package org.upscalerelay.android

import org.upscalerelay.protocol.LibraryNode

internal fun LibraryNode.findPath(path: String): LibraryNode? {
    if (this.path == path) return this
    if (type != LibraryNode.Type.DIRECTORY) return null
    return children.firstNotNullOfOrNull { it.findPath(path) }
}
