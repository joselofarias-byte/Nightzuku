package moe.shizuku.manager.adb

internal object AdbEndpointPolicy {
    fun isLocal(host: String, port: Int, localHosts: Set<String>): Boolean =
        port in 1..65535 && host in localHosts
}
