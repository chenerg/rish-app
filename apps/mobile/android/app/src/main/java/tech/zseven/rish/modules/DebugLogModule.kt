package tech.zseven.rish.modules

import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.ReactContextBaseJavaModule
import com.facebook.react.bridge.ReactMethod
import tech.zseven.rish.runtime.AndroidDebugLog

/**
 * DebugLog on Android: export the in-app step log without a cable.
 *
 * The buffer is [AndroidDebugLog]'s; this module only hands its text to
 * JavaScript so the person can share it. Nothing here writes to the log --
 * the log records what native code did, and JavaScript is not native code.
 */
class DebugLogModule(reactContext: ReactApplicationContext) :
    ReactContextBaseJavaModule(reactContext) {

    override fun getName(): String = "DebugLog"

    override fun getConstants(): MutableMap<String, Any> = mutableMapOf("implemented" to true)

    @ReactMethod
    fun export(promise: Promise) {
        try {
            val map = Arguments.createMap()
            map.putInt("schema_version", 1)
            map.putString("text", AndroidDebugLog.export())
            promise.resolve(map)
        } catch (failure: Exception) {
            promise.reject("E_DEBUG_LOG", "Debug log could not be exported")
        }
    }

    @ReactMethod
    fun clear(promise: Promise) {
        try {
            AndroidDebugLog.clear()
            val map = Arguments.createMap()
            map.putInt("schema_version", 1)
            map.putString("status", "cleared")
            promise.resolve(map)
        } catch (failure: Exception) {
            promise.reject("E_DEBUG_LOG", "Debug log could not be cleared")
        }
    }
}
