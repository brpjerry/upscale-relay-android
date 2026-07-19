package org.upscalerelay.demux

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract

data class LocalDocumentEntry(
    val uri: String,
    val name: String,
    val mimeType: String,
    val isDirectory: Boolean,
    val sizeBytes: Long?,
    val lastModifiedMillis: Long? = null,
)

/** Read-only SAF tree browsing without taking ownership of the persisted grant. */
object LocalDocumentBrowser {
    fun displayName(context: Context, uri: Uri): String =
        context.contentResolver.query(
            uri,
            arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        } ?: uri.lastPathSegment ?: "Local folder"

    fun children(context: Context, treeUri: Uri, directoryUri: Uri): List<LocalDocumentEntry> {
        val resolver = context.contentResolver
        val documentId = DocumentsContract.getDocumentId(directoryUri)
        val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, documentId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        return resolver.query(childrenUri, projection, null, null, null)?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val nameColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val mimeColumn = cursor.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val sizeColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_SIZE)
            val modifiedColumn = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            buildList {
                while (cursor.moveToNext()) {
                    val id = cursor.getString(idColumn)
                    val mime = cursor.getString(mimeColumn).orEmpty()
                    add(LocalDocumentEntry(
                        uri = DocumentsContract.buildDocumentUriUsingTree(treeUri, id).toString(),
                        name = cursor.getString(nameColumn) ?: id,
                        mimeType = mime,
                        isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
                        sizeBytes = if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                            cursor.getLong(sizeColumn)
                        } else null,
                        lastModifiedMillis = if (modifiedColumn >= 0 && !cursor.isNull(modifiedColumn)) {
                            cursor.getLong(modifiedColumn)
                        } else null,
                    ))
                }
            }
        }.orEmpty()
            // Hidden entries like .thumbnails are noise in a video browser.
            .filterNot { it.name.startsWith(".") }
            .sortedWith(
                compareByDescending<LocalDocumentEntry> { it.isDirectory }
                    .thenBy(String.CASE_INSENSITIVE_ORDER) { it.name },
            )
    }

    fun rootDocumentUri(treeUri: Uri): Uri = DocumentsContract.buildDocumentUriUsingTree(
        treeUri,
        DocumentsContract.getTreeDocumentId(treeUri),
    )
}
