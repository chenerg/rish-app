package tech.zseven.rish.runtime

import org.json.JSONArray
import org.json.JSONObject

/**
 * The registry-v3 language-runtime tools, as far as Android can serve them.
 *
 * Mirrors the prepare half of modules/rish/ios/Sources/DSHAgentRuntimeToolExecutor.mm.
 * iOS keeps a package store, workspace snapshots and a VM; Android has none of
 * those yet. What it *can* answer honestly is the one read in the family:
 * `list_runtime_environments`, whose truthful reply on a device with no
 * package store is the empty listing.
 *
 * The other four are mutations over machinery this platform does not have.
 * They are refused **per call, at preparation**, with the runtime family's own
 * rejection vocabulary (`E_AGENT_CAPABILITY`) — the shape `tool_batch`'s
 * `prepare_finish` folds into a settled call the round carries onward. Before
 * this existed the probe reported a generic store error, which the core read
 * as "arguments do not match the schema" for arguments that matched perfectly,
 * and one runtime call cost the whole batch.
 *
 * Every contract asked here — the argument schema, the precondition shape,
 * the payload rules, the digests — is the shared core's, so a listing settled
 * on Android reads identically on iOS.
 */
internal class AndroidRuntimeToolExecutor(private val roots: AndroidAgentRootResolver) {

    /** A refusal carrying the agent's vocabulary rather than a message. */
    class Refused(val code: String) : Exception(code)

    /** Every tool of the family; only the listing is executable here. */
    val tools: List<String> = listOf(
        "list_runtime_environments",
        "install_runtime_environment",
        "run_program",
        "start_runtime_service",
        "stop_runtime_service",
    )

    /** The tools this platform can actually run. */
    val executable: List<String> = listOf("list_runtime_environments")

    private fun rejection(code: String, reason: String): JSONObject = JSONObject()
        .put("rejection", JSONObject().put("failure_code", code).put("reason", reason))

    /**
     * The probe outcome for one runtime call: `{"prepared": ...}` for a
     * listing this root may take, `{"rejection": ...}` for everything else.
     * The shape is exactly what `prepare_finish` reads for a runtime tool.
     */
    fun probe(name: String, arguments: JSONObject, root: JSONObject): JSONObject {
        if (name !in tools) return rejection(BAD_ARGUMENTS, SCHEMA_REASON)
        val valid = RishAgentCoreNative.wal(
            JSONObject().put("op", "runtime_arguments").put("value", arguments).put("name", name),
        )
        if (valid?.optBoolean("valid") != true) {
            return rejection(BAD_ARGUMENTS, SCHEMA_REASON)
        }
        if (name !in executable) {
            AndroidDebugLog.log("runtime_tool", "mutation_refused", name)
            return rejection("E_AGENT_CAPABILITY", "runtime_environments_unavailable_on_android")
        }
        if (!rootReadable(root)) {
            return rejection("E_AGENT_CAPABILITY", "runtime_root_unavailable")
        }
        val argumentsSha = argumentsSha256(name, arguments)
            ?: return rejection(BAD_ARGUMENTS, SCHEMA_REASON)
        val precondition = JSONObject()
            .put("schema_version", 1).put("kind", name)
            .put("arguments_sha256", argumentsSha)
            .put("snapshot_sha256", JSONObject.NULL)
            .put("environment_sha256", JSONObject.NULL)
        return JSONObject().put(
            "prepared",
            JSONObject().put("schema_version", 1)
                .put("precondition", precondition)
                .put("reserved_write_bytes", 0),
        )
    }

    /**
     * Runs the listing. There is no package store on this platform, so the
     * truthful listing is empty — a definite answer the model can act on,
     * not a failure it has to guess about.
     */
    fun execute(name: String, arguments: JSONObject, root: JSONObject): JSONObject {
        if (name !in executable) throw Refused(BAD_ARGUMENTS)
        if (!rootReadable(root)) throw Refused("E_AGENT_CONFLICT")
        val argumentsSha = argumentsSha256(name, arguments) ?: throw Refused(BAD_ARGUMENTS)
        val payload = JSONObject().put("schema_version", 1)
            .put("environments", JSONArray()).put("truncated", false)
        val feedback = RishAgentCoreNative.canonical(
            JSONObject().put("schema_version", 1).put("name", name)
                .put("outcome", "ok").put("payload", payload).toString(),
        ) ?: throw Refused("E_AGENT_PERSISTENCE")
        val facts = JSONObject().put("schema_version", 1).put("kind", name)
            .put("arguments_sha256", argumentsSha)
            .put("payload_sha256", RishAgentCoreNative.hash("runtime-tool-payload", payload))
        AndroidDebugLog.log("runtime_tool", "listed_environments", "empty")
        return JSONObject().put("schema_version", 1).put("status", "ok")
            .put("feedback", feedback).put("settled_facts", facts)
            .put("truncated", false).put("effect_may_have_occurred", false)
    }

    /** Whether the root resolves and carries the read the listing needs. */
    private fun rootReadable(root: JSONObject): Boolean {
        val resolved = roots.resolveAgentProjection(root) ?: return false
        val capabilities = resolved.optJSONArray("capabilities") ?: JSONArray()
        return (0 until capabilities.length()).any { capabilities.optString(it) == "file_read" }
    }

    private fun argumentsSha256(name: String, arguments: JSONObject): String? = try {
        RishAgentCoreNative.hash(
            "tool-arguments",
            JSONObject().put("name", name).put("arguments", arguments),
        )
    } catch (_: Exception) {
        null
    }

    private companion object {
        const val BAD_ARGUMENTS = "E_AGENT_BAD_ARGUMENTS"
        const val SCHEMA_REASON = "arguments_do_not_match_tool_schema"
    }
}
