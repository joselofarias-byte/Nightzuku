package com.joselofarias.oppoassist.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI

class JsonTest {
    @Test
    fun roundTrip() {
        val value = jobj(
            "op" to jstr("ping"),
            "ok" to jbool(true),
            "n" to jnum(7),
            "empty" to JsonValue.Null,
            "items" to jarr(listOf(jstr("código \"1\"\n"))),
        )
        val again = JsonCodec.parse(JsonCodec.stringify(value))
        assertEquals(value, again)
    }

    @Test
    fun rejectsTrailingJunk() {
        var failed = false
        try {
            JsonCodec.parse("""{"a":1}{"b":2}""")
        } catch (_: JsonException) {
            failed = true
        }
        assertTrue(failed)
    }
}

class NetPolicyTest {
    @Test
    fun hostCases() {
        val file = File("../shared/host-cases.list")
        assertTrue(file.isFile)
        file.readLines().forEach { line ->
            val text = line.substringBefore('#').trim()
            if (text.isEmpty()) return@forEach
            val (host, expect) = text.split(" ")
            assertEquals(host, expect == "allow", TailscaleAddresses.isTailscaleLiteral(host))
        }
    }

    @Test
    fun choosesTailscaleInterface() {
        val lan = InetAddress.getByName("192.168.1.9")
        val ts = InetAddress.getByName("100.80.1.2")
        val chosen = TailscaleBind.choose(
            listOf(
                LocalNic("wlan0", true, listOf(lan)),
                LocalNic("tailscale0", true, listOf(ts)),
            ),
        )
        assertEquals(ts, chosen)
    }

    @Test
    fun ignoresDownAndLoopback() {
        val ts = InetAddress.getByName("100.80.1.2")
        val chosen = TailscaleBind.choose(
            listOf(
                LocalNic("tailscale0", false, listOf(ts)),
                LocalNic("lo", true, listOf(InetAddress.getByName("127.0.0.1"))),
            ),
        )
        assertNull(chosen)
    }

    @Test
    fun tokenIsFixedLengthCompare() {
        val token = "ab".repeat(32)
        assertTrue(TokenAuth.matches(token, token))
        assertFalse(TokenAuth.matches(token, token.dropLast(1) + "c"))
        assertFalse(TokenAuth.matches(token, "short"))
    }
}

class SmsPolicyTest {
    @Test
    fun forwardsOneTimeCodesWithALabelAndDropsRcs() {
        assertEquals(SmsDecision.FORWARDED, SmsPolicy.decide(Channel.SMS))
        assertEquals(SmsDecision.DROPPED_RCS, SmsPolicy.decide(Channel.RCS))
        assertTrue(SensitiveSms.looksLikeOneTimeCode("Tu codigo es 847291"))
        assertTrue(SensitiveSms.looksLikeOneTimeCode("847291"))
        assertFalse(SensitiveSms.looksLikeOneTimeCode("El médico te espera a las 15"))
    }

    @Test
    fun oneTimeCodeIsQueuedImmediatelyWithTheFlag() {
        val dir = File("build/sms-test").apply { deleteRecursively(); mkdirs() }
        val now = 1_700_000_000_000L
        val queue = FileSmsQueue(dir)
        var serial = 0
        val ingress = SmsIngress(queue, { "code${serial++}" }, { now })
        val secret = "847291"
        val result = ingress.accept(Channel.SMS, "+59899000000", "Su codigo de seguridad es $secret")
        assertEquals(SmsDecision.FORWARDED, result.decision)
        val forwarded = JsonCodec.stringify(jarr(queue.forwardList().map { it.toForwardJson() }))
        assertTrue(forwarded.contains(secret))
        assertTrue(forwarded.contains("\"otp\":true"))
        val plain = ingress.accept(Channel.SMS, "+59899000000", "El médico te espera a las 15")
        assertEquals(SmsDecision.FORWARDED, plain.decision)
        assertTrue(queue.forwardList().any { !it.otp && it.body.contains("médico") })
        val dropped = ingress.accept(Channel.RCS, "+59899000000", "codigo $secret")
        assertEquals(SmsDecision.DROPPED_RCS, dropped.decision)
        assertEquals(2, queue.forwardList().size)
    }

    @Test
    fun rejectsPathTraversalIds() {
        val dir = File("build/sms-path").apply { deleteRecursively(); mkdirs() }
        val queue = FileSmsQueue(dir)
        queue.insertForward(SmsMessage("../x", Channel.SMS, "a", "b", 1, false))
        assertTrue(queue.forwardList().isEmpty())
        assertFalse(queue.ack("../x"))
    }
}

class AppPolicyTest {
    @Test
    fun protectedListBlocksAndroidAndTheAgent() {
        val listed = ProtectedPackages.parse(File("../shared/protected-packages.list").readText())
        assertTrue(listed.contains("com.joselofarias.oppoassist"))
        assertTrue(ProtectedPackages.uninstallBlocked("com.joselofarias.oppoassist", listed))
        assertTrue(ProtectedPackages.uninstallBlocked("com.android.bluetooth", listed))
        assertTrue(ProtectedPackages.uninstallBlocked("not a package", listed))
        assertFalse(ProtectedPackages.uninstallBlocked("com.example.juego", listed))
    }

    @Test
    fun baselineAndInstallSignals() {
        val added = AppBaseline.added(setOf("com.a"), setOf("com.a", "com.b"))
        assertEquals(listOf("com.b"), added)
        val flags = InstallSignals.attention("com.b", null, setOf("com.b"), false)
        assertTrue(InstallAttention.DISCOURAGED in flags)
        assertTrue(InstallAttention.UNKNOWN_INSTALLER in flags)
        assertTrue(InstallAttention.NEW_SINCE_BASELINE in flags)
    }

    @Test
    fun contactInput() {
        assertTrue(ContactInput.validName("Ana"))
        assertFalse(ContactInput.validName("Ana\n"))
        assertTrue(ContactInput.validPhone("+598 99 111 222"))
        assertFalse(ContactInput.validPhone("123;rm"))
        assertTrue(ContactInput.validEmail(""))
        assertTrue(ContactInput.validEmail("ana@example.com"))
    }
}

class AdbPortReportTest {
    @Test
    fun dropsLanAndPublicHostsButKeepsThePort() {
        val lan = JsonCodec.stringify(
            JsonValue.Obj(AdbPortReport.remoteFields(37123, "192.168.1.20", true)),
        )
        assertTrue(lan.contains("37123"))
        assertTrue(lan.contains("\"advertisedHostSuppressed\":true"))
        assertFalse(lan.contains("192.168"))
        val public = JsonCodec.stringify(
            JsonValue.Obj(AdbPortReport.remoteFields(5555, "8.8.8.8", true)),
        )
        assertFalse(public.contains("8.8.8.8"))
        val tailscale = JsonCodec.stringify(
            JsonValue.Obj(AdbPortReport.remoteFields(37123, "100.90.0.2", false)),
        )
        assertTrue(tailscale.contains("100.90.0.2"))
        val missing = AdbPortReport.remoteFields(0, null, null)
        assertFalse(missing.containsKey("port"))
        assertFalse(missing.values.any { it is JsonValue.Bool && it == JsonValue.Bool(true) && missing["demonstratedOnTargetDevice"] == JsonValue.Bool(true) })
        assertEquals(JsonValue.Bool(false), missing["demonstratedOnTargetDevice"])
    }
}

class CapabilityTest {
    @Test
    fun nothingIsMarkedDemonstratedAndLockscreenIsImpossible() {
        assertTrue(CapabilityMatrix.all.none { it.demonstratedOnTargetDevice })
        val lock = CapabilityMatrix.all.single { it.id == "lockscreen.bypass" }
        assertEquals("nadie", lock.availableFrom)
        assertTrue(CapabilityMatrix.all.single { it.id == "reboot" }.afterReboot == "no")
    }
}

class HttpServerTest {
    private val token = "c".repeat(64)

    @Test
    fun rejectsPublicAndWildcardBind() {
        val handler = RpcHandler { _, _ -> RpcResult(true) }
        assertThrows {
            HttpAgentServer(
                InetAddress.getByName("8.8.8.8"),
                0,
                token,
                BindPolicy.production(),
                handler,
            ).start()
        }
        assertThrows {
            HttpAgentServer(
                InetAddress.getByName("0.0.0.0"),
                0,
                token,
                BindPolicy { true },
                handler,
            ).start()
        }
    }

    @Test
    fun authAndOtpAreForwardedWithoutAnApproveOp() {
        val dir = File("build/http-sms").apply { deleteRecursively(); mkdirs() }
        val queue = FileSmsQueue(dir)
        val ingress = SmsIngress(queue, { "m1" }, { 10_000 })
        val secret = "847291"
        ingress.accept(Channel.SMS, "+59899000000", "codigo $secret")
        var approved = false
        val server = HttpAgentServer(
            InetAddress.getByName("127.0.0.1"),
            0,
            token,
            BindPolicy.loopbackForTests(),
            RpcHandler { op, _ ->
                when (op) {
                    "ping" -> RpcResult(true, data = mapOf("version" to jstr(AgentInfo.VERSION)))
                    "sms.recent" -> RpcResult(
                        true,
                        data = mapOf("messages" to jarr(queue.forwardList().map { it.toForwardJson() })),
                    )
                    "sms.approve" -> {
                        approved = true
                        RpcResult(true)
                    }
                    else -> RpcResult(false, "operacion_desconocida")
                }
            },
        )
        val port = server.start()
        try {
            val denied = post(port, "no", """{"op":"ping"}""")
            assertEquals(401, denied.first)
            val ping = post(port, token, """{"op":"ping"}""")
            assertEquals(200, ping.first)
            assertTrue(ping.second.contains("1.0.0"))
            val recent = post(port, token, """{"op":"sms.recent"}""")
            assertTrue(recent.second.contains(secret))
            assertTrue(recent.second.contains("\"otp\":true"))
            val approve = post(port, token, """{"op":"sms.approve"}""")
            assertTrue(approve.second.contains("operacion_no_permitida"))
            assertFalse(approved)
        } finally {
            server.stop()
        }
    }

    private fun post(port: Int, token: String, json: String): Pair<Int, String> {
        val conn = URI("http://127.0.0.1:$port/v1/rpc").toURL().openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 2000
        conn.readTimeout = 2000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        conn.outputStream.use { it.write(json.toByteArray()) }
        val code = conn.responseCode
        val stream = if (code >= 400) conn.errorStream else conn.inputStream
        val text = stream?.bufferedReader()?.readText().orEmpty()
        conn.disconnect()
        return code to text
    }

    private fun assertThrows(block: () -> Unit) {
        try {
            block()
        } catch (_: Exception) {
            return
        }
        throw AssertionError("se esperaba un error")
    }
}

class SafetySourceTest {
    @Test
    fun agentDoesNotListenWidelyOrBypassTheLock() {
        val sources = File("../agent/src/main").walkTopDown().filter { it.extension == "kt" }.toList()
        assertTrue(sources.isNotEmpty())
        val text = sources.joinToString("\n") { it.readText() }
        assertFalse(text.contains("0.0.0.0"))
        assertFalse(text.contains("ServerSocket"))
        assertFalse(text.contains("AccessibilityService"))
        assertFalse(text.contains("locksettings"))
        assertFalse(text.contains("DevicePolicyManager"))
        assertFalse(text.contains("ConsentActivity"))
        assertFalse(text.contains("set-device-owner"))
        assertTrue(text.contains("BindPolicy.production()"))
        val server = File("../core/src/main/kotlin/com/joselofarias/oppoassist/core/HttpAgentServer.kt").readText()
        assertTrue(server.contains("isAnyLocalAddress"))
        assertFalse(server.contains("0.0.0.0"))
    }
}
