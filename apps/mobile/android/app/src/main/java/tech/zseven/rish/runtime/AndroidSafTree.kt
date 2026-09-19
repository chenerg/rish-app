package tech.zseven.rish.runtime

import android.content.ContentResolver
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import java.io.IOException

/**
 * The Storage Access Framework mechanics under a granted workspace root.
 *
 * A granted folder is not a `File`: it is a tree URI whose authority is a
 * `DocumentsProvider`, reached through a `ContentResolver`, held by a
 * persistable permission the system may revoke. Everything a workspace tool
 * needs from such a root -- stat one node, list one directory, read a file,
 * write a file -- is here, and nothing else is: every *rule* about those
 * operations stays with the shared core, exactly as it does for owned roots.
 *
 * **One observation per listing.** A directory is observed with exactly one
 * `queryChildDocuments` pass returning every column the caller needs -- name,
 * kind, size, mtime, document id. The fingerprint a listing asserts and the
 * entries it reports both come from that one pass, so they cannot disagree
 * with each other, and a large SAF directory is not walked three times for
 * one `list_dir`.
 */
internal class AndroidSafAccess(private val resolver: ContentResolver) {

    /** One observed child or node: what SAF says about a document. */
    data class Node(
        val documentId: String,
        val name: String,
        val isDirectory: Boolean,
        val size: Long,
        val lastModified: Long,
    )

    private companion object {
        val COLUMNS = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        const val PERMISSIONS =
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
    }

    /** Takes the persistable read+write grant; false when the OEM refuses. */
    fun persistGrant(tree: Uri): Boolean = try {
        resolver.takePersistableUriPermission(tree, PERMISSIONS)
        true
    } catch (_: SecurityException) {
        false
    }

    /** Whether the persisted read+write grant for [tree] is still held. */
    fun grantHeld(tree: Uri): Boolean = try {
        resolver.persistedUriPermissions.any { permission ->
            permission.uri == tree && permission.isReadPermission && permission.isWritePermission
        }
    } catch (_: Exception) {
        false
    }

    /** The tree's own root node, or null when it no longer proves out. */
    fun treeRoot(tree: Uri): Node? = try {
        nodeFor(tree, DocumentsContract.getTreeDocumentId(tree))
    } catch (_: Exception) {
        null
    }

    /** One node by document id: a single-row query, or null when it is gone. */
    fun nodeFor(tree: Uri, documentId: String): Node? = try {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        resolver.query(uri, COLUMNS, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            readNode(cursor)
        }
    } catch (_: Exception) {
        null
    }

    /**
     * Every child of one directory, from one query. Null means the directory
     * could not be observed at all -- which is not the same as empty.
     */
    fun children(tree: Uri, parentDocumentId: String): List<Node>? = try {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentDocumentId)
        resolver.query(uri, COLUMNS, null, null, null)?.use { cursor ->
            val nodes = ArrayList<Node>(cursor.count)
            while (cursor.moveToNext()) nodes.add(readNode(cursor))
            nodes
        }
    } catch (_: Exception) {
        null
    }

    private fun readNode(cursor: android.database.Cursor): Node {
        val mime = cursor.getString(2) ?: ""
        return Node(
            documentId = cursor.getString(0) ?: "",
            name = cursor.getString(1) ?: "",
            isDirectory = mime == DocumentsContract.Document.MIME_TYPE_DIR,
            size = if (cursor.isNull(3)) 0L else cursor.getLong(3),
            lastModified = if (cursor.isNull(4)) 0L else cursor.getLong(4),
        )
    }

    /**
     * The node a relative path names, walked one level per component. Each
     * level is one child query; the path rule has already bounded the depth.
     * Returns null when any component is missing.
     */
    fun resolve(tree: Uri, components: List<String>): Node? {
        var current = treeRoot(tree) ?: return null
        for (component in components) {
            if (!current.isDirectory) return null
            val next = children(tree, current.documentId)?.firstOrNull { it.name == component }
                ?: return null
            current = next
        }
        return current
    }

    /** Reads at most [limit] bytes of one document. Null on any failure. */
    fun read(tree: Uri, documentId: String, limit: Int): ByteArray? = try {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        resolver.openInputStream(uri)?.use { stream ->
            val buffer = ByteArray(limit)
            var read = 0
            while (read < buffer.size) {
                val n = stream.read(buffer, read, buffer.size - read)
                if (n < 0) break
                read += n
            }
            buffer.copyOf(read)
        }
    } catch (_: Exception) {
        null
    }

    /** Creates one child document; null when the provider refuses. */
    fun createDocument(tree: Uri, parentDocumentId: String, name: String, directory: Boolean): String? = try {
        val parent = DocumentsContract.buildDocumentUriUsingTree(tree, parentDocumentId)
        DocumentsContract.createDocument(
            resolver, parent,
            if (directory) DocumentsContract.Document.MIME_TYPE_DIR else "application/octet-stream",
            name,
        )?.let { DocumentsContract.getDocumentId(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * Replaces one document's bytes. "wt" truncates before writing, so a
     * failure can leave a torn file -- SAF offers no rename-into-place. The
     * ledger's write precondition is what keeps that risk visible: a retry
     * re-reads the world rather than assuming either outcome.
     */
    fun write(tree: Uri, documentId: String, bytes: ByteArray): Boolean = try {
        val uri = DocumentsContract.buildDocumentUriUsingTree(tree, documentId)
        resolver.openOutputStream(uri, "wt")?.use { stream ->
            stream.write(bytes)
            stream.flush()
            true
        } ?: false
    } catch (_: IOException) {
        false
    } catch (_: Exception) {
        false
    }

    /** Opens one document for a bounded copy out; the caller owns the stream. */
    fun openForCopy(tree: Uri, documentId: String): java.io.InputStream? = try {
        resolver.openInputStream(DocumentsContract.buildDocumentUriUsingTree(tree, documentId))
    } catch (_: Exception) {
        null
    }
}
