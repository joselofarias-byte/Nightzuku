package com.joselofarias.oppoassist.core

object PackageNames {
    private val pattern = Regex("^[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+$")

    fun isValid(name: String): Boolean = name.length <= 200 && pattern.matches(name)
}

object ProtectedPackages {
    fun parse(text: String): Set<String> = text.lineSequence()
        .map { it.substringBefore('#').trim() }
        .filter { it.isNotEmpty() }
        .toSet()

    fun uninstallBlocked(packageName: String, listed: Set<String>): Boolean {
        if (!PackageNames.isValid(packageName)) return true
        if (packageName in listed) return true
        if (packageName == "android" || packageName.startsWith("com.android.")) return true
        return false
    }
}

object AppBaseline {
    fun added(baseline: Set<String>, current: Set<String>): List<String> =
        (current - baseline).filter { PackageNames.isValid(it) }.sorted()
}

enum class InstallAttention { NONE, DISCOURAGED, UNKNOWN_INSTALLER, NEW_SINCE_BASELINE }

object InstallSignals {
    fun attention(
        packageName: String,
        installer: String?,
        discouraged: Set<String>,
        inBaseline: Boolean,
    ): Set<InstallAttention> {
        val out = linkedSetOf<InstallAttention>()
        if (packageName in discouraged) out += InstallAttention.DISCOURAGED
        if (installer.isNullOrBlank()) out += InstallAttention.UNKNOWN_INSTALLER
        if (!inBaseline) out += InstallAttention.NEW_SINCE_BASELINE
        if (out.isEmpty()) out += InstallAttention.NONE
        return out
    }
}

object ContactInput {
    private val phone = Regex("^\\+?[0-9][0-9 \\-()]{2,19}$")
    private val email = Regex("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")

    fun validName(name: String): Boolean {
        if (name.any { it == '\n' || it == '\r' || it == '\u0000' }) return false
        val trimmed = name.trim()
        return trimmed.length in 1..80
    }

    fun validPhone(value: String): Boolean {
        val trimmed = value.trim()
        return trimmed.length in 3..20 && phone.matches(trimmed)
    }

    fun validEmail(value: String): Boolean {
        if (value.isEmpty()) return true
        return value.length <= 80 && email.matches(value)
    }
}
