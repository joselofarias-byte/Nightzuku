package com.joselofarias.oppoassist.core

/**
 * Matriz que el agente puede devolver por la red.
 * demonstratedOnTargetDevice queda en false: esta corrida no usó el OPPO ni el HONOR.
 */
data class Capability(
    val id: String,
    val availableFrom: String,
    val afterReboot: String,
    val demonstratedOnTargetDevice: Boolean,
    val note: String,
)

object CapabilityMatrix {
    val all: List<Capability> = listOf(
        Capability(
            "screen.scrcpy",
            "adb_mientras_la_sesion_sigue",
            "no",
            false,
            "scrcpy usa el adbd ya autorizado. No se inicia si el bloqueo está activo.",
        ),
        Capability(
            "screen.screencap",
            "adb_mientras_la_sesion_sigue",
            "no",
            false,
            "Una captura no escribe en la pantalla. Puede mostrar el bloqueo o salir negra.",
        ),
        Capability(
            "input.touch",
            "adb_mientras_la_sesion_sigue",
            "no",
            false,
            "Se niega si el bloqueo está activo o si el estado no se puede leer.",
        ),
        Capability(
            "apps.open_close_uninstall",
            "adb_shell",
            "no",
            false,
            "Cerrar y desinstalar salen de la shell ADB. El agente solo puede pedir la confirmación del sistema.",
        ),
        Capability(
            "apps.inventory",
            "agente",
            "solicitado_no_demostrado",
            false,
            "El inventario usa PackageManager y sobrevive solo si el proceso del agente sigue vivo.",
        ),
        Capability(
            "reboot",
            "adb_reboot",
            "no",
            false,
            "adb reboot corta la sesión. No hay evidencia de que la depuración inalámbrica vuelva sola.",
        ),
        Capability(
            "settings.time",
            "adb_shell_si_el_uid_2000_puede",
            "no",
            false,
            "SET_TIME no está disponible para una app común. La shell puede fallar; el cliente exige relectura.",
        ),
        Capability(
            "contacts",
            "agente_con_permiso",
            "solicitado_no_demostrado",
            false,
            "Requiere READ_CONTACTS y WRITE_CONTACTS otorgados en persona.",
        ),
        Capability(
            "sms.forward",
            "agente_con_permiso",
            "solicitado_no_demostrado",
            false,
            "Solo SMS tradicionales. RCS se descarta. Un código de un solo uso espera un toque en el OPPO.",
        ),
        Capability(
            "diagnostics",
            "agente",
            "solicitado_no_demostrado",
            false,
            "Batería, red, almacenamiento y permisos por APIs normales.",
        ),
        Capability(
            "agent.listener",
            "tailscale",
            "solicitado_no_demostrado",
            false,
            "El socket se ata a una IP de Tailscale. No escucha en 0.0.0.0. Tras un reinicio depende de que Android deje vivir el servicio y de que Tailscale vuelva.",
        ),
        Capability(
            "lockscreen.bypass",
            "nadie",
            "no",
            false,
            "No implementado. No hay limpieza de credenciales ni ingreso de PIN.",
        ),
    )

    fun toJson(): JsonValue = jarr(all.map { item ->
        jobj(
            "id" to jstr(item.id),
            "availableFrom" to jstr(item.availableFrom),
            "afterReboot" to jstr(item.afterReboot),
            "demonstratedOnTargetDevice" to jbool(item.demonstratedOnTargetDevice),
            "note" to jstr(item.note),
        )
    })
}

object RecoveryPlan {
    val willAskAndroidToRestart = listOf(
        "El servicio en primer plano del agente, con START_STICKY.",
        "El socket, cuando vuelva una IP de Tailscale.",
        "El servicio otra vez después de BOOT_COMPLETED, si el sistema entrega el aviso.",
    )
    val willNotRestart = listOf(
        "adbd ni la depuración inalámbrica.",
        "El puerto de ADB, que además puede cambiar.",
        "La sesión de scrcpy.",
        "La autorización de depuración si se revocó.",
        "El desbloqueo de la pantalla.",
    )
}
