package tech.zseven.rish.modules

import android.app.Activity
import android.content.Intent
import android.net.Uri
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.BaseActivityEventListener
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import com.facebook.react.bridge.ReadableMap
import org.json.JSONArray
import org.json.JSONObject
import tech.zseven.rish.RishUnavailable
import tech.zseven.rish.runtime.AndroidDebugLog
import tech.zseven.rish.runtime.AndroidRuntimeState
import tech.zseven.rish.runtime.AndroidWorkspaceRegistry
import tech.zseven.rish.runtime.RishAgentCoreNative
import tech.zseven.rish.runtime.RuntimeJson
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * LocalWorkspaces on Android.
 *
 * Mirrors the iOS registration in modules/rish/ios/Sources/LocalWorkspacesModule.mm
 * and the JS wrapper in apps/mobile/src/native/LocalWorkspaces.ts. Every rule
 * it needs already lives in the shared core and every mechanism already lives
 * in [AndroidWorkspaceRegistry]; this is the wire between them and the bridge,
 * and it decides nothing of its own.
 *
 * A person can make a workspace, grant one, or import one. The folder picker
 * is the Storage Access Framework's document-tree picker:
 *
 * - **Open in place** takes the persistable grant and binds the tree URI as a
 *   `granted_folder` workspace — resolved ever after through that URI, never
 *   through a POSIX path it does not have.
 * - **Import** copies the tree into an app-owned workspace, staged first so a
 *   half-finished copy is thrown away rather than published.
 * - A provider-managed location, an import-only request, or an OEM that
 *   refuses to persist the grant all settle as `requires_import`: the person
 *   is told the folder can be copied but not bound, which is the truth.
 *
 * One picker may be pending at a time. A newer `presentFolderPicker` settles
 * the older one as cancelled instead of refusing busy forever, and
 * `cancelPicker` settles it by operation id — the two fixes that ended the
 * sticky BUSY/UNAVAILABLE states the first device iteration hit.
 */
class LocalWorkspacesModule(private val react: ReactApplicationContext) :
    ReactContextBaseJavaModule(react) {

    private val runtime by lazy { AndroidRuntimeState.get(react) }
    private val registry: AndroidWorkspaceRegistry get() = runtime.workspaces

    /** The one picker that may be waiting on the system UI. */
    private class PendingPicker(val operationId: String, val mode: String, val promise: Promise)

    /** A folder the person chose that still awaits import or cancellation. */
    private class Selection(
        val uri: Uri,
        val displayName: String,
        val expiresAt: Long,
    )

    private val pickerLock = Any()
    private var pendingPicker: PendingPicker? = null
    private val selections = ConcurrentHashMap<String, Selection>()

    init {
        react.addActivityEventListener(object : BaseActivityEventListener() {
            override fun onActivityResult(
                activity: Activity?,
                requestCode: Int,
                resultCode: Int,
                data: Intent?,
            ) {
                if (requestCode != PICKER_REQUEST) return
                val picker = synchronized(pickerLock) {
                    pendingPicker.also { pendingPicker = null }
                } ?: return
                val uri = if (resultCode == Activity.RESULT_OK) data?.data else null
                // The classification below queries the provider; that is not
                // main-thread work.
                runtime.io.execute { settlePicker(picker, uri) }
            }
        })
    }

    /**
     * Without the core there are no rules to ask, so there is nothing this
     * module could answer honestly. That is the same question LocalGuest asks
     * about its runtime, and the same answer.
     */
    override fun getConstants(): MutableMap<String, Any> =
        mutableMapOf("implemented" to RishAgentCoreNative.available)

    override fun getName(): String = "LocalWorkspaces"

    private fun resolve(promise: Promise, value: JSONObject) =
        promise.resolve(Arguments.makeNativeMap(RuntimeJson.map(value)))

    /**
     * A refusal carries the workspace code JS branches on and no detail: a
     * message could name a directory, and a path is not JavaScript's to see.
     */
    private fun reject(promise: Promise, error: Throwable) {
        val code = (error as? AndroidWorkspaceRegistry.Refused)?.code ?: "E_WORKSPACE_UNAVAILABLE"
        promise.reject(code, code)
    }

    private fun work(promise: Promise, action: () -> JSONObject) {
        runtime.io.execute {
            try {
                if (!RishAgentCoreNative.available) throw AndroidWorkspaceRegistry.Refused(UNAVAILABLE)
                resolve(promise, action())
            } catch (error: Throwable) {
                reject(promise, error)
            }
        }
    }

    /** `exactKeys` on the way in: an unexpected key is a different request. */
    private fun request(value: ReadableMap?, vararg keys: String): JSONObject {
        val map = RuntimeJson.fromBridgeMap(
            (value ?: throw AndroidWorkspaceRegistry.Refused(INVALID)).toHashMap(),
        )
        val present = map.keys().asSequence().toSet()
        if (present != keys.toSet()) throw AndroidWorkspaceRegistry.Refused(INVALID)
        RuntimeJson.checkVersion(map, 1)
        return map
    }

    private fun text(request: JSONObject, key: String): String {
        val value = request.opt(key)
        if (value !is String || value.isEmpty()) throw AndroidWorkspaceRegistry.Refused(INVALID)
        return value
    }

    private fun descriptorOrRefuse(workspaceId: String): JSONObject =
        registry.descriptor(workspaceId) ?: throw AndroidWorkspaceRegistry.Refused(UNAVAILABLE)

    @ReactMethod
    fun list(promise: Promise) = work(promise) {
        val workspaces = JSONArray()
        // `list` already leaves out every record it cannot still prove, so a
        // descriptor missing here is a record that stopped being provable
        // between the two reads rather than one to report as broken.
        for (record in registry.list()) {
            val id = record.optString("workspace_id")
            registry.descriptor(id)?.let { workspaces.put(it) }
        }
        JSONObject().put("schema_version", 1).put("workspaces", workspaces)
    }

    @ReactMethod
    fun create(request: ReadableMap?, promise: Promise) = work(promise) {
        val fields = request(request, "schema_version", "display_name", "operation_id")
        val record = registry.create(
            displayName = text(fields, "display_name"),
            operationId = text(fields, "operation_id"),
        )
        descriptorOrRefuse(record.getString("workspace_id"))
    }

    /**
     * An owned workspace is always reachable directly: there is no grant to
     * have expired, because the directory is the app's own. A revision the
     * caller did not expect, or a capability this binding does not carry, is
     * refused rather than answered with a descriptor that would mislead.
     */
    @ReactMethod
    fun resolve(request: ReadableMap?, promise: Promise) = work(promise) {
        val fields = request(
            request,
            "schema_version",
            "workspace_id",
            "expected_binding_revision",
            "required_capabilities",
        )
        val descriptor = descriptorOrRefuse(text(fields, "workspace_id"))
        val expected = fields.opt("expected_binding_revision")
        if (expected != null && expected != JSONObject.NULL) {
            if (expected !is Int) throw AndroidWorkspaceRegistry.Refused(INVALID)
            if (expected != descriptor.optInt("binding_revision")) {
                throw AndroidWorkspaceRegistry.Refused(STALE)
            }
        }
        val required = fields.opt("required_capabilities") as? JSONArray
            ?: throw AndroidWorkspaceRegistry.Refused(INVALID)
        val capabilities = descriptor.optJSONObject("capabilities")
            ?: throw AndroidWorkspaceRegistry.Refused(UNAVAILABLE)
        for (index in 0 until required.length()) {
            val capability = required.opt(index)
            if (capability !is String) throw AndroidWorkspaceRegistry.Refused(INVALID)
            if (!capabilities.optBoolean(capability)) {
                throw AndroidWorkspaceRegistry.Refused(CAPABILITY)
            }
        }
        JSONObject().put("schema_version", 1).put("disposition", "direct")
            .put("workspace", descriptor)
    }

    @ReactMethod
    fun queryOperation(request: ReadableMap?, promise: Promise) = work(promise) {
        val fields = request(request, "schema_version", "operation_id")
        registry.queryOperation(text(fields, "operation_id"))
            ?: throw AndroidWorkspaceRegistry.Refused(NOT_FOUND)
    }

    // --- the folder picker --------------------------------------------------

    @ReactMethod
    fun presentFolderPicker(request: ReadableMap?, promise: Promise) {
        val fields = try {
            request(request, "schema_version", "operation_id", "mode")
        } catch (error: Throwable) {
            return reject(promise, error)
        }
        val operationId: String
        val mode: String
        try {
            operationId = text(fields, "operation_id")
            mode = text(fields, "mode")
            if (!RuntimeJson.uuid(operationId)) throw AndroidWorkspaceRegistry.Refused(INVALID)
            if (mode != "grant_or_import" && mode != "import_only") {
                throw AndroidWorkspaceRegistry.Refused(INVALID)
            }
        } catch (error: Throwable) {
            return reject(promise, error)
        }
        if (!RishAgentCoreNative.available) {
            return promise.reject(UNAVAILABLE, UNAVAILABLE)
        }
        val activity = react.currentActivity
        if (activity == null) {
            // No activity means no system UI to show; saying so is the fix
            // for the old sticky UNAVAILABLE, which refused before looking.
            AndroidDebugLog.log("saf_picker", "no_activity", operationId)
            return promise.reject(UNAVAILABLE, UNAVAILABLE)
        }
        // A newer picker settles the older one as cancelled: JavaScript has
        // already invalidated it, and holding BUSY forever was the bug.
        val superseded = synchronized(pickerLock) {
            val previous = pendingPicker
            pendingPicker = PendingPicker(operationId, mode, promise)
            previous
        }
        superseded?.let {
            AndroidDebugLog.log("saf_picker", "superseded", it.operationId)
            resolve(it.promise, cancelledResult())
        }
        AndroidDebugLog.log("saf_picker", "presenting", "$operationId $mode")
        try {
            val intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE)
                .addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
            activity.startActivityForResult(intent, PICKER_REQUEST)
        } catch (_: Exception) {
            val current = synchronized(pickerLock) {
                pendingPicker.takeIf { it?.operationId == operationId }
                    .also { if (it != null) pendingPicker = null }
            }
            AndroidDebugLog.log("saf_picker", "present_failed", operationId)
            current?.promise?.reject(UNAVAILABLE, UNAVAILABLE)
        }
    }

    /** What became of the picker the system UI answered. Runs off-main. */
    private fun settlePicker(picker: PendingPicker, uri: Uri?) {
        try {
            if (uri == null) {
                AndroidDebugLog.log("saf_picker", "cancelled", picker.operationId)
                return resolve(picker.promise, cancelledResult())
            }
            val access = runtime.safAccess
            val persisted = access.persistGrant(uri)
            AndroidDebugLog.log(
                "saf_picker",
                if (persisted) "grant_persisted" else "grant_not_persistable",
                picker.operationId,
            )
            val rootNode = access.treeRoot(uri)
            val displayName = displayNameFor(rootNode?.name, uri)
            val providerManaged = uri.authority != LOCAL_DOCUMENTS_AUTHORITY
            if (picker.mode == "grant_or_import" && persisted && !providerManaged && rootNode != null) {
                try {
                    val record = registry.bindGrantedFolder(
                        displayName = displayName,
                        treeUri = uri.toString(),
                        treeDocumentId = rootNode.documentId,
                        operationId = picker.operationId,
                    )
                    val descriptor = descriptorOrRefuse(record.getString("workspace_id"))
                    AndroidDebugLog.log("saf_picker", "selected_in_place", picker.operationId)
                    return resolve(
                        picker.promise,
                        JSONObject().put("schema_version", 1).put("status", "selected")
                            .put("workspace", descriptor),
                    )
                } catch (error: Throwable) {
                    // A folder that cannot be bound can still be copied; the
                    // person is offered the truth rather than a dead end.
                    AndroidDebugLog.log(
                        "saf_picker", "bind_failed",
                        (error as? AndroidWorkspaceRegistry.Refused)?.code ?: "unexpected",
                    )
                }
            }
            val selectionId = UUID.randomUUID().toString()
            selections[selectionId] = Selection(
                uri = uri,
                displayName = displayName,
                expiresAt = System.currentTimeMillis() + SELECTION_TTL_MS,
            )
            AndroidDebugLog.log("saf_picker", "requires_import", picker.operationId)
            resolve(
                picker.promise,
                JSONObject().put("schema_version", 1).put("status", "requires_import")
                    .put("selection_id", selectionId)
                    .put("display_name", displayName)
                    .put(
                        "location_class",
                        if (providerManaged) "provider_managed" else "unknown",
                    ),
            )
        } catch (error: Throwable) {
            reject(picker.promise, error)
        }
    }

    @ReactMethod
    fun importSelection(request: ReadableMap?, promise: Promise) {
        runtime.io.execute {
            try {
                if (!RishAgentCoreNative.available) throw AndroidWorkspaceRegistry.Refused(UNAVAILABLE)
                val fields = request(request, "schema_version", "selection_id", "operation_id")
                val selectionId = text(fields, "selection_id")
                val operationId = text(fields, "operation_id")
                if (!RuntimeJson.uuid(selectionId) || !RuntimeJson.uuid(operationId)) {
                    throw AndroidWorkspaceRegistry.Refused(INVALID)
                }
                val selection = selections[selectionId]
                if (selection == null || selection.expiresAt < System.currentTimeMillis()) {
                    selections.remove(selectionId)
                    // The one honest replay: an import that already committed
                    // answers with the workspace it made.
                    val replayed = replayedImport(operationId)
                        ?: throw AndroidWorkspaceRegistry.Refused(STALE)
                    return@execute resolve(promise, replayed)
                }
                AndroidDebugLog.log("saf_import", "copy_started", selectionId)
                val staging = File(
                    registry.root,
                    ".rish-import-$selectionId",
                )
                try {
                    copyTree(selection.uri, staging)
                    val record = registry.create(
                        displayName = selection.displayName,
                        operationId = operationId,
                        origin = "imported",
                    )
                    val target = registry.rootFor(record.getString("workspace_id"))
                        ?: throw AndroidWorkspaceRegistry.Refused(PERSISTENCE)
                    moveChildren(staging, target)
                    selections.remove(selectionId)
                    AndroidDebugLog.log("saf_import", "committed", record.getString("workspace_id"))
                    resolve(promise, descriptorOrRefuse(record.getString("workspace_id")))
                } finally {
                    staging.deleteRecursively()
                }
            } catch (error: Throwable) {
                AndroidDebugLog.log(
                    "saf_import", "failed",
                    (error as? AndroidWorkspaceRegistry.Refused)?.code ?: "unexpected",
                )
                reject(promise, error)
            }
        }
    }

    @ReactMethod
    fun cancelSelection(request: ReadableMap?, promise: Promise) {
        try {
            val fields = request(request, "schema_version", "selection_id")
            val selectionId = text(fields, "selection_id")
            val removed = selections.remove(selectionId) != null
            AndroidDebugLog.log(
                "saf_picker",
                if (removed) "selection_cancelled" else "selection_already_settled",
                selectionId,
            )
            resolve(
                promise,
                JSONObject().put("schema_version", 1)
                    .put("status", if (removed) "cancelled" else "already_settled"),
            )
        } catch (error: Throwable) {
            reject(promise, error)
        }
    }

    @ReactMethod
    fun cancelPicker(request: ReadableMap?, promise: Promise) {
        try {
            val fields = request(request, "schema_version", "operation_id")
            val operationId = text(fields, "operation_id")
            val cancelled = synchronized(pickerLock) {
                val current = pendingPicker
                if (current?.operationId == operationId) {
                    pendingPicker = null
                    current
                } else {
                    null
                }
            }
            cancelled?.let { resolve(it.promise, cancelledResult()) }
            AndroidDebugLog.log(
                "saf_picker",
                if (cancelled != null) "picker_cancelled" else "picker_already_settled",
                operationId,
            )
            resolve(
                promise,
                JSONObject().put("schema_version", 1)
                    .put("status", if (cancelled != null) "cancelled" else "already_settled"),
            )
        } catch (error: Throwable) {
            reject(promise, error)
        }
    }

    private fun cancelledResult(): JSONObject =
        JSONObject().put("schema_version", 1).put("status", "cancelled")

    /** The already-committed import this operation id names, if any. */
    private fun replayedImport(operationId: String): JSONObject? {
        val receipt = registry.queryOperation(operationId) ?: return null
        if (receipt.optString("status") != "committed") return null
        val workspaceId = receipt.optJSONObject("receipt")?.optString("workspace_id")
            ?: return null
        return registry.descriptor(workspaceId)
    }

    /**
     * A display name the record rules accept, from whatever the provider
     * said the folder is called. Sanitising, not judging: forbidden
     * characters become dashes, and a name nothing survives of becomes
     * "Folder".
     */
    private fun displayNameFor(providerName: String?, uri: Uri): String {
        val raw = providerName?.takeIf { it.isNotBlank() }
            ?: uri.lastPathSegment?.substringAfterLast(':')?.substringAfterLast('/')
            ?: ""
        val normalized = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFC)
        val cleaned = buildString {
            for (character in normalized) {
                val forbidden = character == '/' || character == '\\' || character == ':' ||
                    character.isISOControl() ||
                    Character.getType(character) == Character.FORMAT.toInt()
                append(if (forbidden) '-' else character)
            }
        }.trim().trimStart('.')
        val bounded = StringBuilder()
        var bytes = 0
        for (character in cleaned) {
            val width = character.toString().toByteArray(Charsets.UTF_8).size
            if (bytes + width > 120) break
            bounded.append(character)
            bytes += width
        }
        var candidate = bounded.toString().trim()
        if (candidate.isEmpty()) candidate = "Folder"
        val folded = AndroidWorkspaceRegistry.folded(candidate)
        if (folded == "rish workspaces" || folded.startsWith(".rish-")) {
            candidate = "Folder $candidate".take(60)
        }
        return candidate
    }

    /**
     * Copies one granted tree into [into], bounded in entries, bytes and
     * depth so a pathological tree refuses instead of filling the device.
     */
    private fun copyTree(tree: Uri, into: File) {
        val access = runtime.safAccess
        val root = access.treeRoot(tree)
            ?: throw AndroidWorkspaceRegistry.Refused(STALE)
        if (!root.isDirectory) throw AndroidWorkspaceRegistry.Refused(STALE)
        if (into.exists()) into.deleteRecursively()
        if (!into.mkdirs()) throw AndroidWorkspaceRegistry.Refused(PERSISTENCE)
        var entries = 0
        var bytes = 0L
        fun walk(parentId: String, destination: File, depth: Int) {
            if (depth > MAX_IMPORT_DEPTH) throw AndroidWorkspaceRegistry.Refused(BUSY)
            val children = access.children(tree, parentId)
                ?: throw AndroidWorkspaceRegistry.Refused(PERSISTENCE)
            for (child in children) {
                val name = child.name
                if (name.isEmpty() || name == "." || name == ".." ||
                    name.contains('/') || name.contains('\u0000')
                ) {
                    continue
                }
                entries += 1
                if (entries > MAX_IMPORT_ENTRIES) throw AndroidWorkspaceRegistry.Refused(BUSY)
                val target = File(destination, name)
                if (child.isDirectory) {
                    if (!target.isDirectory && !target.mkdir()) {
                        throw AndroidWorkspaceRegistry.Refused(PERSISTENCE)
                    }
                    walk(child.documentId, target, depth + 1)
                } else {
                    bytes += child.size
                    if (bytes > MAX_IMPORT_BYTES) throw AndroidWorkspaceRegistry.Refused(BUSY)
                    val stream = access.openForCopy(tree, child.documentId)
                        ?: throw AndroidWorkspaceRegistry.Refused(PERSISTENCE)
                    stream.use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                }
            }
        }
        walk(root.documentId, into, 0)
    }

    /** Moves every staged child into the workspace directory by rename. */
    private fun moveChildren(staging: File, target: File) {
        val children = staging.listFiles() ?: return
        for (child in children) {
            if (!child.renameTo(File(target, child.name))) {
                throw AndroidWorkspaceRegistry.Refused(PERSISTENCE)
            }
        }
    }

    // --- not yet on Android ------------------------------------------------
    //
    // Rebinding a granted folder that lost its grant needs a compare between
    // the old tree and a newly picked one; until that lands these refuse,
    // because a picker that never appeared is not a cancelled picker.

    @ReactMethod
    fun presentRegrantPicker(request: ReadableMap?, promise: Promise) = refuseUnbuilt(promise)

    @ReactMethod
    fun completeRegrant(request: ReadableMap?, promise: Promise) = refuseUnbuilt(promise)

    @ReactMethod
    fun bootstrapLegacyProject(request: ReadableMap?, promise: Promise) = refuseUnbuilt(promise)

    // Forgetting a workspace and deleting its content are a clearance the core
    // already rules on; the host half -- removing a directory and proving it
    // is gone -- is not written, and a delete that reported success without
    // doing it would be the worst possible lie here.

    @ReactMethod
    fun forget(request: ReadableMap?, promise: Promise) = refuseUnbuilt(promise)

    @ReactMethod
    fun prepareDeleteOwnedContent(request: ReadableMap?, promise: Promise) = refuseUnbuilt(promise)

    @ReactMethod
    fun deleteOwnedContent(request: ReadableMap?, promise: Promise) = refuseUnbuilt(promise)

    private fun refuseUnbuilt(promise: Promise) =
        RishUnavailable.reject("LocalWorkspaces", UNAVAILABLE, promise)

    private companion object {
        const val UNAVAILABLE = "E_WORKSPACE_UNAVAILABLE"
        const val INVALID = "E_WORKSPACE_INVALID"
        const val STALE = "E_WORKSPACE_STALE"
        const val CAPABILITY = "E_WORKSPACE_CAPABILITY"
        const val NOT_FOUND = "E_WORKSPACE_NOT_FOUND"
        const val PERSISTENCE = "E_WORKSPACE_PERSISTENCE"
        const val BUSY = "E_WORKSPACE_BUSY"

        /** The request code the document-tree picker answers with. */
        const val PICKER_REQUEST = 0x5AF1

        /** The native picker keeps a selection alive for at most five minutes. */
        const val SELECTION_TTL_MS = 5L * 60 * 1000

        /** The system documents provider over local storage: bindable in place. */
        const val LOCAL_DOCUMENTS_AUTHORITY = "com.android.externalstorage.documents"

        const val MAX_IMPORT_ENTRIES = 20_000
        const val MAX_IMPORT_BYTES = 256L * 1024 * 1024
        const val MAX_IMPORT_DEPTH = 32
    }
}
