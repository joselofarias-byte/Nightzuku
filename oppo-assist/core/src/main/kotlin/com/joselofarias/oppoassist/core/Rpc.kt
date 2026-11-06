package com.joselofarias.oppoassist.core

fun interface RpcHandler {
    fun handle(op: String, fields: Map<String, JsonValue>): RpcResult
}

data class RpcResult(
    val ok: Boolean,
    val error: String? = null,
    val data: Map<String, JsonValue> = emptyMap(),
) {
    fun toJson(): JsonValue = jobj(
        "ok" to jbool(ok),
        "error" to (error?.let { jstr(it) } ?: JsonValue.Null),
        "data" to JsonValue.Obj(data),
    )
}

object RemoteOps {
    val denied: Set<String> = setOf(
        "sms.approve",
        "sms.deny",
        "reboot",
        "lockscreen.dismiss",
        "settings.time.set",
        "adb.tcpip",
        "adb.root",
    )
}

class GuardedHandler(private val delegate: RpcHandler) : RpcHandler {
    override fun handle(op: String, fields: Map<String, JsonValue>): RpcResult {
        if (op in RemoteOps.denied) {
            return RpcResult(ok = false, error = "operacion_no_permitida")
        }
        return delegate.handle(op, fields)
    }
}
