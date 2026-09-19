package tech.zseven.rish.runtime

import android.system.Os
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.Normalizer
import java.util.Locale
import java.util.UUID

/**
 * Android's workspace registry: app-private roots, their authorities, and the
 * grants they imply.
 *
 * **Scope, stated plainly.** Three origins exist here: `rish_created` and
 * `imported` — app-private directories — and `granted_folder`, a Storage
 * Access Framework tree the person granted. A granted folder's "bookmark" is
 * its tree URI: the same shape iOS seals a security-scoped bookmark into,
 * with the URI's bytes where the bookmark's bytes go, so the shared rules
 * validate both platforms' grants without knowing which one they are reading.
 * Legacy iOS projects remain unreachable and are rejected, not stubbed.
 * There is no rebinding yet: every record here is at `binding_revision` 1.
 *
 * **What is written is what iOS writes.** The record, the authority and the
 * fingerprint are the same JSON shapes, validated by the same core rules
 * ([RishAgentCoreNative.workspaceRecord], `workspaceAuthority`,
 * `workspaceFingerprint`), so growing into rebinding later is new code over
 * the same bytes rather than a migration.
 *
 * **Folding is this host's own.** iOS folds with Foundation under
 * `en_US_POSIX`; here it is NFD, combining marks dropped, lowercased in the
 * root locale. The two do not always agree, and they do not have to: a folded
 * name is never stored, only compared against other names on the same device.
 * What *is* stored — the record, the authority, the fingerprint — goes through
 * the shared rules.
 */
internal class AndroidWorkspaceRegistry(
    val root: File,
    /** SAF facts for granted folders; null on hosts that never grant one. */
    private val saf: AndroidSafAccess? = null,
) {
    companion object {
        /** The container that holds every owned workspace directory. */
        const val CONTAINER_NAME = "Rish Workspaces"
        private const val REGISTRY_NAME = "registry.json"
        private const val RECEIPTS_NAME = "receipts.json"
        private const val BINDINGS_DIR = "bindings"

        /**
         * The empty registry. Generation 0 with no records is what a fresh
         * install has, and it is a state, not an absence.
         */
        fun emptyRegistry(): JSONObject = JSONObject()
            .put("schema_version", 1).put("generation", 0)
            .put("records", JSONArray())

        /** The empty receipt store, which a fresh install has. */
        fun emptyReceipts(): JSONObject = JSONObject()
            .put("schema_version", 1).put("receipts", JSONArray())

        /**
         * This host's folding. Not Foundation's, and it does not claim to be.
         */
        fun folded(component: String): String =
            Normalizer.normalize(component, Normalizer.Form.NFD)
                .replace(Regex("\\p{Mn}+"), "")
                .lowercase(Locale.ROOT)

        /** Grapheme clusters, in order, for the truncation projection. */
        fun graphemes(text: String): JSONArray {
            val clusters = JSONArray()
            val iterator = java.text.BreakIterator.getCharacterInstance(Locale.ROOT)
            iterator.setText(text)
            var start = iterator.first()
            var end = iterator.next()
            while (end != java.text.BreakIterator.DONE) {
                clusters.put(text.substring(start, end))
                start = end
                end = iterator.next()
            }
            return clusters
        }

        private fun sha256(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-256").digest(bytes)
                .joinToString("") { "%02x".format(it) }
    }

    /** Why a workspace could not be created or opened. */
    class Refused(val code: String) : Exception(code)

    private val container = File(root, CONTAINER_NAME)
    private val registryFile = File(root, REGISTRY_NAME)
    private val receiptsFile = File(root, RECEIPTS_NAME)
    private val bindings = File(root, BINDINGS_DIR)

    private val lock = Any()

    /** The committed registry, or the empty one on a fresh install. */
    fun registry(): JSONObject = synchronized(lock) { loadRegistry() }

    private fun loadRegistry(): JSONObject {
        if (!registryFile.exists()) return emptyRegistry()
        val bytes = try {
            registryFile.readBytes()
        } catch (_: Exception) {
            throw Refused("E_WORKSPACE_PERSISTENCE")
        }
        // Before the parse: one complete value, bounded in depth and nodes, no
        // duplicate keys, no negative zero. A corrupt file costs a refusal
        // rather than an unbounded walk, and `JSONObject` would have taken the
        // *last* of two duplicate keys without saying so.
        if (!RishAgentCoreNative.workspaceJsonBounded(bytes)) {
            throw Refused("E_WORKSPACE_CORRUPT")
        }
        val parsed = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            // A registry that will not parse is corrupt. It is never silently
            // replaced with an empty one: that would lose every binding.
            throw Refused("E_WORKSPACE_CORRUPT")
        }
        // The whole shape is the shared rule's: the envelope, the capacity,
        // every record, the ascending order, and the uniqueness of the folded
        // directory names. Only the folding is this host's.
        val records = parsed.optJSONArray("records")
        val foldings = JSONArray()
        if (records != null) {
            for (index in 0 until records.length()) {
                val record = records.optJSONObject(index)
                val display = record?.opt("display_name")
                val directory = record?.opt("owned_directory_name")
                foldings.put(
                    JSONObject()
                        .put(
                            "display_name",
                            if (display is String) folded(display) else JSONObject.NULL,
                        )
                        .put(
                            "directory_name",
                            if (directory is String) folded(directory) else JSONObject.NULL,
                        ),
                )
            }
        }
        val reply = RishAgentCoreNative.workspaceRecord(
            JSONObject().put("op", "registry_shape").put("registry", parsed)
                .put("folded", foldings),
        )
        if (reply?.optBoolean("valid") != true) throw Refused("E_WORKSPACE_CORRUPT")
        return parsed
    }

    /**
     * Creates an app-private workspace and returns its record.
     *
     * The order is deliberate: the directory, then the authority, then the
     * registry. A crash after the authority leaves an orphan nothing points
     * at; a crash the other way round would leave a record whose root cannot
     * be proven, which is the failure that matters.
     */
    fun create(
        displayName: String,
        workspaceId: String = UUID.randomUUID().toString(),
        now: String = RuntimeJson.now(),
        operationId: String = UUID.randomUUID().toString(),
        origin: String = "rish_created",
    ): JSONObject = synchronized(lock) {
        if (!RishAgentCoreNative.available) throw Refused("E_WORKSPACE_UNAVAILABLE")
        if (!RuntimeJson.uuid(workspaceId)) throw Refused("E_WORKSPACE_INVALID")
        if (!RuntimeJson.uuid(operationId)) throw Refused("E_WORKSPACE_INVALID")
        if (origin != "rish_created" && origin != "imported") throw Refused("E_WORKSPACE_INVALID")
        // A retried operation is the one that already happened, not a second
        // one. Without this a crash between the directory and the registry
        // would leave the person with two workspaces where they asked for one.
        replayedRecord(operationId, displayName)?.let { return@synchronized it }
        val registry = loadRegistry()
        if (recordFor(registry, workspaceId) != null) throw Refused("E_WORKSPACE_CONFLICT")
        // A full registry refuses rather than dropping a binding somebody uses.
        val room = RishAgentCoreNative.workspaceRecord(
            JSONObject().put("op", "registry_has_room")
                .put("count", registry.getJSONArray("records").length()),
        )
        if (room?.optBoolean("has_room") != true) throw Refused("E_WORKSPACE_BUSY")
        if (!container.isDirectory && !container.mkdirs()) {
            throw Refused("E_WORKSPACE_PERSISTENCE")
        }
        val directoryName = allocateDirectoryName(displayName, registry)
        val record = JSONObject()
            .put("schema_version", 1)
            .put("workspace_id", workspaceId)
            .put("display_name", displayName)
            .put("origin", origin)
            .put("root_locator_kind", "documents_owned")
            .put("location_class", "rish_owned")
            .put("owned_directory_name", directoryName)
            .put("legacy_project_id", JSONObject.NULL)
            .put("binding_revision", 1)
            .put("created_at", now)
            .put("last_opened_at", now)
        if (!recordValid(record)) throw Refused("E_WORKSPACE_INVALID")

        val directory = File(container, directoryName)
        if (directory.exists()) throw Refused("E_WORKSPACE_CONFLICT")
        if (!directory.mkdir()) throw Refused("E_WORKSPACE_PERSISTENCE")

        val authority = sealAuthority(record, directory, now)
        writeJson(authorityFile(workspaceId, 1), authority)

        // Records are stored in ascending workspace id order, because the
        // registry's canonical JSON is what a journal's
        // `previous_registry_sha256` is taken over: the same records in a
        // different order digest differently. Appending would have written a
        // registry this device could no longer read.
        val records = insertedInOrder(registry.getJSONArray("records"), record)
        val generation = registry.getInt("generation") + 1
        val published = JSONObject().put("schema_version", 1)
            .put("generation", generation).put("records", records)
        writeJson(registryFile, published)
        // The receipt is written last, so a crash before it leaves an
        // unreceipted workspace rather than a receipt for one that is not
        // there. A retry then finds no receipt and refuses on the directory
        // that already exists, which is a visible failure instead of a silent
        // second workspace.
        writeReceipt(
            operationId, record, generation, published, displayName, now,
            if (origin == "imported") "import" else "create",
        )
        record
    }

    /**
     * Binds a granted SAF tree in place and returns its record.
     *
     * The "bookmark" is the tree URI's own bytes: the shared bookmark and
     * granted authority shapes are validated over them exactly as iOS
     * validates a security-scoped bookmark, so a grant written here reads as
     * a grant everywhere. The volume and resource identities are the URI's
     * authority and its tree document id; device and inode are "0" because
     * SAF exposes neither, and the rule only asks that they be stated.
     *
     * Order: bookmark authority, granted authority, registry, receipt — a
     * crash leaves orphan authorities nothing points at, never a record
     * whose grant cannot be proven.
     */
    fun bindGrantedFolder(
        displayName: String,
        treeUri: String,
        treeDocumentId: String,
        workspaceId: String = UUID.randomUUID().toString(),
        now: String = RuntimeJson.now(),
        operationId: String = UUID.randomUUID().toString(),
    ): JSONObject = synchronized(lock) {
        if (!RishAgentCoreNative.available) throw Refused("E_WORKSPACE_UNAVAILABLE")
        if (!RuntimeJson.uuid(workspaceId)) throw Refused("E_WORKSPACE_INVALID")
        if (!RuntimeJson.uuid(operationId)) throw Refused("E_WORKSPACE_INVALID")
        if (treeUri.isEmpty() || treeDocumentId.isEmpty()) throw Refused("E_WORKSPACE_INVALID")
        replayedRecord(operationId, displayName)?.let { return@synchronized it }
        val registry = loadRegistry()
        if (recordFor(registry, workspaceId) != null) throw Refused("E_WORKSPACE_CONFLICT")
        val room = RishAgentCoreNative.workspaceRecord(
            JSONObject().put("op", "registry_has_room")
                .put("count", registry.getJSONArray("records").length()),
        )
        if (room?.optBoolean("has_room") != true) throw Refused("E_WORKSPACE_BUSY")

        val record = JSONObject()
            .put("schema_version", 1)
            .put("workspace_id", workspaceId)
            .put("display_name", displayName)
            .put("origin", "granted_folder")
            .put("root_locator_kind", "security_scoped")
            .put("location_class", "proven_local")
            .put("owned_directory_name", JSONObject.NULL)
            .put("legacy_project_id", JSONObject.NULL)
            .put("binding_revision", 1)
            .put("created_at", now)
            .put("last_opened_at", now)
        if (!recordValid(record)) throw Refused("E_WORKSPACE_INVALID")

        val bookmarkBytes = treeUri.toByteArray(Charsets.UTF_8)
        val bookmarkSha = sha256(bookmarkBytes)
        val bookmark = JSONObject()
            .put("schema_version", 1)
            .put("workspace_id", workspaceId)
            .put("binding_revision", 1)
            .put("bookmark_sha256", bookmarkSha)
            .put(
                "bookmark_bytes_base64",
                android.util.Base64.encodeToString(bookmarkBytes, android.util.Base64.NO_WRAP),
            )
            .put("recorded_at", now)
        val bookmarkValid = RishAgentCoreNative.workspaceAuthority(
            JSONObject().put("op", "bookmark").put("authority", bookmark)
                .put("record", record)
                .put("bookmark_bytes_sha256", bookmarkSha)
                .put("bookmark_bytes_length", bookmarkBytes.size),
        )
        if (bookmarkValid?.optBoolean("valid") != true) throw Refused("E_WORKSPACE_PERSISTENCE")

        val base = JSONObject()
            .put("schema_version", 1)
            .put("workspace_id", workspaceId)
            .put("binding_revision", 1)
            .put(
                "volume_identifier_sha256",
                sha256(treeUri.toUri().authorityOrEmpty().toByteArray(Charsets.UTF_8)),
            )
            .put("resource_identifier_sha256", sha256(treeDocumentId.toByteArray(Charsets.UTF_8)))
            .put("device_id", "0")
            .put("inode_id", "0")
            .put("bookmark_sha256", bookmarkSha)
            .put("classified_at", now)
        val fingerprint = RishAgentCoreNative.workspaceFingerprint(
            JSONObject().put("op", "seal").put("record", record).put("authority", base),
        )?.optString("fingerprint").takeUnless { it.isNullOrEmpty() }
            ?: throw Refused("E_WORKSPACE_PERSISTENCE")
        val granted = JSONObject(base.toString()).put("root_fingerprint_sha256", fingerprint)
        val grantedValid = RishAgentCoreNative.workspaceAuthority(
            JSONObject().put("op", "granted").put("authority", granted)
                .put("record", record).put("bookmark_authority", bookmark),
        )
        if (grantedValid?.optBoolean("valid") != true) throw Refused("E_WORKSPACE_PERSISTENCE")

        writeJson(bookmarkFile(workspaceId, 1), bookmark)
        writeJson(grantedFile(workspaceId, 1), granted)

        val records = insertedInOrder(registry.getJSONArray("records"), record)
        val generation = registry.getInt("generation") + 1
        val published = JSONObject().put("schema_version", 1)
            .put("generation", generation).put("records", records)
        writeJson(registryFile, published)
        writeReceipt(operationId, record, generation, published, displayName, now, "create")
        AndroidDebugLog.log("workspace_grant", "bound_in_place", workspaceId)
        record
    }

    /** `authority` of a URI string without pulling android.net.Uri apart here. */
    private fun String.toUri(): android.net.Uri = android.net.Uri.parse(this)
    private fun android.net.Uri.authorityOrEmpty(): String = authority ?: ""

    /**
     * The records the registry holds that are still provable: the record shape
     * is valid, its authority is the one the record implies, and the directory
     * is still the one the authority was sealed over. Anything else is left
     * out rather than repaired — repair is a rebind, and there is none yet.
     */
    fun list(): List<JSONObject> = synchronized(lock) {
        val records = loadRegistry().getJSONArray("records")
        (0 until records.length()).mapNotNull { index ->
            val record = records.optJSONObject(index) ?: return@mapNotNull null
            if (!recordValid(record)) return@mapNotNull null
            // A granted folder whose grant broke is still a record the person
            // holds: it is listed with its honest status (revoked or
            // unavailable) rather than silently vanishing, because vanishing
            // is indistinguishable from never having granted it.
            if (record.optString("origin") == "granted_folder") return@mapNotNull record
            if (authorityFor(record) == null) return@mapNotNull null
            record
        }
    }

    /**
     * The root directory of a provable workspace, or null. Proving it means
     * re-reading the authority and re-stating the directory: a folder that was
     * replaced since the authority was written is not that workspace's root,
     * however matching its name.
     */
    fun rootFor(workspaceId: String): File? = synchronized(lock) {
        val record = recordFor(loadRegistry(), workspaceId) ?: return null
        // A granted folder has no POSIX path to hand out: its root is a tree
        // URI, resolved through [grantedTreeFor], never a File.
        if (record.opt("owned_directory_name") !is String) return null
        if (!recordValid(record)) return null
        if (authorityFor(record) == null) return null
        File(container, record.getString("owned_directory_name"))
    }

    /**
     * The tree URI of a provable granted workspace, or null. Proving it means
     * re-validating both authorities, re-checking the persisted grant and
     * re-stating the tree's root document — the SAF equivalent of re-stating
     * an owned directory's inode.
     */
    fun grantedTreeFor(workspaceId: String): android.net.Uri? = synchronized(lock) {
        val record = recordFor(loadRegistry(), workspaceId) ?: return null
        if (record.optString("origin") != "granted_folder") return null
        if (!recordValid(record)) return null
        if (grantedStatus(record) != "ok") return null
        treeUriOf(record)
    }

    /**
     * The receipt store, or the empty one. A store the shared rule refuses is
     * corrupt: it is never replaced with an empty one, because that would let
     * every operation in it run a second time.
     */
    fun receipts(): JSONObject = synchronized(lock) { loadReceipts() }

    private fun loadReceipts(): JSONObject {
        if (!receiptsFile.exists()) return emptyReceipts()
        val bytes = try {
            receiptsFile.readBytes()
        } catch (_: Exception) {
            throw Refused("E_WORKSPACE_PERSISTENCE")
        }
        if (!RishAgentCoreNative.workspaceJsonBounded(bytes)) {
            throw Refused("E_WORKSPACE_CORRUPT")
        }
        val parsed = try {
            JSONObject(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            throw Refused("E_WORKSPACE_CORRUPT")
        }
        val reply = RishAgentCoreNative.workspaceReceipt(
            JSONObject().put("op", "store_shape").put("envelope", parsed),
        )
        if (reply?.optBoolean("valid") != true) throw Refused("E_WORKSPACE_CORRUPT")
        return parsed
    }

    /** What a caller is shown of an operation, or null if it never happened. */
    fun queryOperation(operationId: String): JSONObject? = synchronized(lock) {
        val receipt = receiptFor(loadReceipts(), operationId) ?: return null
        RishAgentCoreNative.workspaceReceipt(
            JSONObject().put("op", "public_receipt").put("receipt", receipt),
        )?.optJSONObject("receipt")
    }

    private fun receiptFor(store: JSONObject, operationId: String): JSONObject? {
        val receipts = store.getJSONArray("receipts")
        for (index in 0 until receipts.length()) {
            val receipt = receipts.optJSONObject(index) ?: continue
            if (receipt.optString("operation_id") == operationId) return receipt
        }
        return null
    }

    /**
     * The record a receipt names, when this operation already ran *and* the
     * request was the same one. A receipt whose request digest disagrees is a
     * different operation reusing an id, and it is refused rather than
     * answered with somebody else's workspace.
     */
    private fun replayedRecord(operationId: String, displayName: String): JSONObject? {
        val receipt = receiptFor(loadReceipts(), operationId) ?: return null
        val expected = RishAgentCoreNative.workspaceJournal(
            JSONObject().put("op", "create_request_sha256")
                .put("display_name", displayName),
        )?.optString("digest")
        if (expected.isNullOrEmpty() ||
            receipt.optString("request_sha256") != expected
        ) {
            throw Refused("E_WORKSPACE_CONFLICT")
        }
        val record = recordFor(loadRegistry(), receipt.optString("workspace_id"))
            ?: throw Refused("E_WORKSPACE_PERSISTENCE")
        return record
    }

    private fun writeReceipt(
        operationId: String,
        record: JSONObject,
        generation: Int,
        published: JSONObject,
        displayName: String,
        now: String,
        operation: String = "create",
    ) {
        val store = loadReceipts()
        val digest = RishAgentCoreNative.workspaceJournal(
            JSONObject().put("op", "create_request_sha256")
                .put("display_name", displayName),
        )?.optString("digest")
        if (digest.isNullOrEmpty()) throw Refused("E_WORKSPACE_PERSISTENCE")
        val canonical = RishAgentCoreNative.canonical(published.toString())
            ?: throw Refused("E_WORKSPACE_PERSISTENCE")
        val receipt = JSONObject()
            .put("schema_version", 1)
            .put("operation_id", operationId)
            .put("workspace_id", record.getString("workspace_id"))
            .put("operation", operation)
            .put("binding_revision", record.getInt("binding_revision"))
            // The generation and digest of the registry this committed
            // *against*, so a receipt describes one state of the store.
            .put("registry_generation", generation)
            .put("registry_sha256", sha256(canonical.toByteArray(Charsets.UTF_8)))
            .put("request_sha256", digest)
            .put("outcome", "committed")
            .put("committed_at", now)
        val reply = RishAgentCoreNative.workspaceReceipt(
            JSONObject().put("op", "receipt_shape").put("receipt", receipt),
        )
        if (reply?.optBoolean("valid") != true) throw Refused("E_WORKSPACE_PERSISTENCE")
        val room = RishAgentCoreNative.workspaceReceipt(
            JSONObject().put("op", "has_room")
                .put("count", store.getJSONArray("receipts").length()),
        )
        if (room?.optBoolean("has_room") != true) throw Refused("E_WORKSPACE_BUSY")
        val receipts = store.getJSONArray("receipts")
        receipts.put(receipt)
        writeJson(
            receiptsFile,
            JSONObject().put("schema_version", 1).put("receipts", receipts),
        )
    }

    /**
     * The root fingerprint of a provable workspace, or null. It is read from
     * the authority rather than recomputed: the authority is the thing that
     * was verified, and it is only handed back while it still proves the
     * directory on disk.
     */
    fun fingerprintFor(workspaceId: String): String? = synchronized(lock) {
        val record = recordFor(loadRegistry(), workspaceId) ?: return null
        if (!recordValid(record)) return null
        val authority = authorityFor(record) ?: return null
        authority.optString("root_fingerprint_sha256").takeIf { it.length == 64 }
    }

    // --- granted folders ----------------------------------------------------

    /** The decoded tree URI a granted record's bookmark carries, or null. */
    private fun treeUriOf(record: JSONObject): android.net.Uri? {
        val workspaceId = record.optString("workspace_id")
        val revision = record.opt("binding_revision") as? Int ?: return null
        val bookmark = readJson(bookmarkFile(workspaceId, revision)) ?: return null
        val bytes = decodedBookmark(bookmark) ?: return null
        return try {
            android.net.Uri.parse(String(bytes, Charsets.UTF_8))
        } catch (_: Exception) {
            null
        }
    }

    private fun decodedBookmark(bookmark: JSONObject): ByteArray? = try {
        android.util.Base64.decode(
            bookmark.optString("bookmark_bytes_base64"),
            android.util.Base64.NO_WRAP,
        )
    } catch (_: Exception) {
        null
    }

    /**
     * The health of one granted binding, spelled in the descriptor's closed
     * status set: `ok` when both authorities validate and the grant and the
     * tree root still prove out, `revoked` when the system no longer holds the
     * persisted permission, `unavailable` for everything else — a corrupt
     * authority, a tree whose root document is gone, a host with no SAF.
     */
    private fun grantedStatus(record: JSONObject): String {
        val granted = grantedAuthorityFor(record) ?: return "unavailable"
        if (granted.optString("root_fingerprint_sha256").length != 64) return "unavailable"
        val access = saf ?: return "unavailable"
        val tree = treeUriOf(record) ?: return "unavailable"
        if (!access.grantHeld(tree)) return "revoked"
        val rootNode = access.treeRoot(tree) ?: return "unavailable"
        return if (rootNode.isDirectory) "ok" else "unavailable"
    }

    /**
     * Both granted authorities, validated by the shared rules, or null. This
     * checks the *records*; whether the grant is still held is the status's
     * question, so a revoked grant still has a valid authority.
     */
    private fun grantedAuthorityFor(record: JSONObject): JSONObject? {
        val workspaceId = record.optString("workspace_id")
        val revision = record.opt("binding_revision") as? Int ?: return null
        val bookmark = readJson(bookmarkFile(workspaceId, revision)) ?: return null
        val bytes = decodedBookmark(bookmark) ?: return null
        val bookmarkReply = RishAgentCoreNative.workspaceAuthority(
            JSONObject().put("op", "bookmark").put("authority", bookmark)
                .put("record", record)
                .put("bookmark_bytes_sha256", sha256(bytes))
                .put("bookmark_bytes_length", bytes.size),
        ) ?: return null
        if (!bookmarkReply.optBoolean("valid")) return null
        val granted = readJson(grantedFile(workspaceId, revision)) ?: return null
        val grantedReply = RishAgentCoreNative.workspaceAuthority(
            JSONObject().put("op", "granted").put("authority", granted)
                .put("record", record).put("bookmark_authority", bookmark),
        ) ?: return null
        if (!grantedReply.optBoolean("valid")) return null
        return granted
    }

    /**
     * What an agent may do with this workspace, as the shared rule states it.
     * A root that cannot be proven grants nothing — not "read only", nothing.
     */
    fun descriptor(workspaceId: String): JSONObject? = synchronized(lock) {
        val record = recordFor(loadRegistry(), workspaceId) ?: return null
        if (!recordValid(record)) return null
        // Deriving the status is this host's job — it stats a directory or
        // re-checks a grant. What the status *means* is the rule's, so the
        // grants are asked for.
        val status = if (record.optString("origin") == "granted_folder") {
            grantedStatus(record)
        } else if (authorityFor(record) != null) {
            "ok"
        } else {
            "root_changed"
        }
        val grants = RishAgentCoreNative.workspaceGrants(
            JSONObject().put("op", "operational_grants")
                .put("locator_kind", record.optString("root_locator_kind"))
                .put("status", status),
        )?.optJSONArray("grants") ?: return null
        val reply = RishAgentCoreNative.workspaceGrants(
            JSONObject().put("op", "descriptor").put("record", record)
                .put("status", status).put("grants", grants),
        ) ?: return null
        reply.optJSONObject("descriptor")
    }

    // --- rules, asked rather than answered -------------------------------

    private fun recordValid(record: JSONObject): Boolean {
        val display = record.opt("display_name")
        val directory = record.opt("owned_directory_name")
        val reply = RishAgentCoreNative.workspaceRecord(
            JSONObject().put("op", "record_shape").put("record", record)
                .put(
                    "folded_display_name",
                    if (display is String) folded(display) else JSONObject.NULL,
                )
                .put(
                    "folded_directory_name",
                    if (directory is String) folded(directory) else JSONObject.NULL,
                ),
        ) ?: return false
        return reply.optBoolean("valid")
    }

    /**
     * The authority for a record, if it is still the record's own and still
     * describes the directory on disk. Everything about *what makes it valid*
     * is the core's; what is on disk is this host's.
     */
    private fun authorityFor(record: JSONObject): JSONObject? {
        if (record.optString("origin") == "granted_folder") {
            return if (grantedStatus(record) == "ok") grantedAuthorityFor(record) else null
        }
        val workspaceId = record.optString("workspace_id")
        val revision = record.opt("binding_revision") as? Int ?: return null
        val file = authorityFile(workspaceId, revision)
        val authority = readJson(file) ?: return null
        val reply = RishAgentCoreNative.workspaceAuthority(
            JSONObject().put("op", "owned").put("authority", authority)
                .put("record", record),
        ) ?: return null
        if (!reply.optBoolean("valid")) return null
        // The authority is internally sound; now it has to still be *this*
        // directory. Device and inode are what the authority was sealed over.
        val directory = File(container, record.optString("owned_directory_name"))
        val identity = identityOf(directory) ?: return null
        if (authority.optString("device_id") != identity.first ||
            authority.optString("inode_id") != identity.second
        ) {
            return null
        }
        return authority
    }

    private fun sealAuthority(record: JSONObject, directory: File, now: String): JSONObject {
        val identity = identityOf(directory) ?: throw Refused("E_WORKSPACE_PERSISTENCE")
        val base = JSONObject()
            .put("schema_version", 1)
            .put("workspace_id", record.getString("workspace_id"))
            .put("binding_revision", record.getInt("binding_revision"))
            .put("device_id", identity.first)
            .put("inode_id", identity.second)
            .put(
                "directory_name_sha256",
                sha256(record.getString("owned_directory_name").toByteArray(Charsets.UTF_8)),
            )
            .put("recorded_at", now)
        val input = RishAgentCoreNative.workspaceFingerprint(
            JSONObject().put("op", "fingerprint_input").put("record", record)
                .put("authority", base),
        )?.optJSONObject("input") ?: throw Refused("E_WORKSPACE_PERSISTENCE")
        val fingerprint = RishAgentCoreNative.workspaceFingerprint(
            JSONObject().put("op", "fingerprint").put("input", input),
        )?.optString("fingerprint").takeUnless { it.isNullOrEmpty() }
            ?: throw Refused("E_WORKSPACE_PERSISTENCE")
        val authority = JSONObject(base.toString())
            .put("root_fingerprint_sha256", fingerprint)
        // A sealed authority that the rule would not accept is a bug here, not
        // a state to write: refuse rather than persist something unreadable.
        val reply = RishAgentCoreNative.workspaceAuthority(
            JSONObject().put("op", "owned").put("authority", authority)
                .put("record", record),
        )
        if (reply?.optBoolean("valid") != true) throw Refused("E_WORKSPACE_PERSISTENCE")
        return authority
    }

    /**
     * The first ordinal whose name nothing has taken. The loop is here because
     * only this host can fold; each name is the core's.
     */
    private fun allocateDirectoryName(displayName: String, registry: JSONObject): String {
        val occupied = HashSet<String>()
        val records = registry.getJSONArray("records")
        for (index in 0 until records.length()) {
            val name = records.optJSONObject(index)?.opt("owned_directory_name")
            if (name is String) occupied.add(folded(name))
        }
        container.list()?.forEach { occupied.add(folded(it)) }
        val clusters = graphemes(displayName)
        var ordinal = 0L
        var candidate = displayName
        while (occupied.contains(folded(candidate))) {
            ordinal += 1
            candidate = RishAgentCoreNative.workspaceDirectoryName(
                JSONObject().put("op", "candidate").put("graphemes", clusters)
                    .put("ordinal", ordinal),
            )?.optString("candidate").takeUnless { it.isNullOrEmpty() }
                ?: throw Refused("E_WORKSPACE_INVALID")
            if (!internalComponent(candidate)) throw Refused("E_WORKSPACE_INVALID")
        }
        return candidate
    }

    private fun internalComponent(value: String): Boolean {
        val reply = RishAgentCoreNative.workspaceDirectoryName(
            JSONObject().put("op", "internal_component").put("value", value),
        ) ?: return false
        return reply.optBoolean("valid")
    }

    // --- host facts -------------------------------------------------------

    /** `st_dev` and `st_ino` as the decimal strings the authority carries. */
    private fun identityOf(directory: File): Pair<String, String>? = try {
        val stat = Os.stat(directory.absolutePath)
        if (stat.st_dev < 0 || stat.st_ino < 0) null
        else Pair(stat.st_dev.toString(), stat.st_ino.toString())
    } catch (_: Exception) {
        null
    }

    private fun authorityFile(workspaceId: String, revision: Int): File =
        File(bindings, "owned-$workspaceId-r$revision.json")

    private fun bookmarkFile(workspaceId: String, revision: Int): File =
        File(bindings, "bookmark-$workspaceId-r$revision.json")

    private fun grantedFile(workspaceId: String, revision: Int): File =
        File(bindings, "granted-$workspaceId-r$revision.json")

    private fun readJson(file: File): JSONObject? = try {
        if (file.isFile) JSONObject(file.readText()) else null
    } catch (_: Exception) {
        null
    }

    /**
     * Write, flush, rename. The rename is what makes the new bytes visible, so
     * a crash shows either the old object or the new one and never half of
     * either.
     */
    private fun writeJson(file: File, value: JSONObject) {
        val parent = file.parentFile ?: throw Refused("E_WORKSPACE_PERSISTENCE")
        if (!parent.isDirectory && !parent.mkdirs()) throw Refused("E_WORKSPACE_PERSISTENCE")
        val temporary = File(parent, file.name + ".partial")
        try {
            FileOutputStream(temporary).use { stream ->
                stream.write(value.toString().toByteArray(Charsets.UTF_8))
                stream.fd.sync()
            }
            if (!temporary.renameTo(file)) throw Refused("E_WORKSPACE_PERSISTENCE")
        } catch (refused: Refused) {
            temporary.delete()
            throw refused
        } catch (_: Exception) {
            temporary.delete()
            throw Refused("E_WORKSPACE_PERSISTENCE")
        }
    }

    /** The records with [record] in its sorted place. */
    private fun insertedInOrder(records: JSONArray, record: JSONObject): JSONArray {
        val id = record.getString("workspace_id")
        val ordered = JSONArray()
        var inserted = false
        for (index in 0 until records.length()) {
            val existing = records.getJSONObject(index)
            if (!inserted && existing.optString("workspace_id") > id) {
                ordered.put(record)
                inserted = true
            }
            ordered.put(existing)
        }
        if (!inserted) ordered.put(record)
        return ordered
    }

    private fun recordFor(registry: JSONObject, workspaceId: String): JSONObject? {
        val records = registry.getJSONArray("records")
        for (index in 0 until records.length()) {
            val record = records.optJSONObject(index) ?: continue
            if (record.optString("workspace_id") == workspaceId) return record
        }
        return null
    }
}
