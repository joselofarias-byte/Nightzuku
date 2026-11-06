package com.joselofarias.oppoassist.core

/**
 * Lo que el agente puede contar por Tailscale sobre el ADB del propio teléfono.
 * El puerto sale de la observación local. La IP anunciada por mDNS solo se
 * repite si ya es de Tailscale; una IP de LAN o pública no viaja.
 */
object AdbPortReport {
    fun remoteFields(
        port: Int?,
        advertisedHost: String?,
        wifiEnabled: Boolean?,
    ): Map<String, JsonValue> {
        val safePort = port?.takeIf { it in 1..65535 }
        val host = advertisedHost?.trim()?.takeIf { it.isNotEmpty() }
        val tailscaleHost = host?.takeIf { TailscaleAddresses.isTailscaleLiteral(it) }
        val fields = linkedMapOf<String, JsonValue>(
            "wifiEnabledKnown" to jbool(wifiEnabled != null),
            "wifiEnabled" to jbool(wifiEnabled == true),
            "portKnown" to jbool(safePort != null),
            "advertisedHostSuppressed" to jbool(host != null && tailscaleHost == null),
            "demonstratedOnTargetDevice" to jbool(false),
            "enabledBy" to jstr("nightzuku_si_cumple_condiciones"),
        )
        if (safePort != null) fields["port"] = jnum(safePort.toLong())
        if (tailscaleHost != null) fields["advertisedHost"] = jstr(tailscaleHost)
        return fields
    }
}
