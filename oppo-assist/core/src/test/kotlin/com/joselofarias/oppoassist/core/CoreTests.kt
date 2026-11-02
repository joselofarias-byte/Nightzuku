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
    fun holdsOneTimeCodesAndDropsRcs() {
        assertEquals(SmsDecision.HELD, SmsPolicy.decide(Channel.SMS, "Tu codigo es 847291", true))
        assertEquals(SmsDecision.HELD, SmsPolicy.decide(Channel.SMS, "847291", true))
        assertEquals(SmsDecision.FORWARDED, SmsPolicy.decide(Channel.SMS, "El médico te espera a las 15", true))
        assertEquals(SmsDecision.DROPPED_DISABLED, SmsPolicy.decide(Channel.SMS, "Hola", false))
        assertEquals(SmsDecision.DROPPED_RCS, SmsPolicy.decide(Channel.RCS, "Tu codigo es 847291", true))
    }

    @Test
    fun remoteViewHidesPendingCodeUntilLocalApproval() {
        val dir = File("build/sms-test").apply { deleteRecursively(); mkdirs() }
        var now = 1_700_000_000_000L
        val queue = FileSmsQueue(dir) { now }
        val ingress = SmsIngress(queue, { true }, { "code1" }, { now })
        val secret = "847291"
        val held = ingress.accept(Channel.SMS, "+59899000000", "Su codigo de seguridad es $secret")
        assertEquals(SmsDecision.HELD, held.decision)
        val pending = JsonCodec.stringify(jarr(queue.pendingMeta().map { it.toPendingMetaJson() }))
        assertFalse(pending.contains(secret))
        assertFalse(pending.contains("+59899000000"))
        assertTrue(queue.approve("code1", now))
        val forwarded = JsonCodec.stringify(jarr(queue.forwardList().map { it.toForwardJson() }))
        assertTrue(forwarded.contains(secret))
        assertTrue(queue.pendingMeta().isEmpty())
    }

    @Test
    fun expiredConsentCannotBeApproved() {
        val dir = File("build/sms-expire").apply { deleteRecursively(); mkdirs() }
        var now = 5_000L
        val queue = FileSmsQueue(dir) { now }
        val ingress = SmsIngress(queue, { true }, { "once" }, { now })
        ingress.accept(Channel.SMS, "+59899000000", "otp 445566")
        now += SmsPolicy.CONSENT_TTL_MS + 1
        assertFalse(queue.approve("once", now))
        assertTrue(queue.forwardList().isEmpty())
    }

    @Test
    fun rejectsPathTraversalIds() {
        val dir = File("build/sms-path").apply { deleteRecursively(); mkdirs() }
        val queue = FileSmsQueue(dir)
        queue.insertForward(SmsMessage("../x", Channel.SMS, "a", "b", 1, null))
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
    fun authOpsAndPendingSmsBodyStayOffTheWire() {
        val dir = File("build/http-sms").apply { deleteRecursively(); mkdirs() }
        val queue = FileSmsQueue(dir)
        val ingress = SmsIngress(queue, { true }, { "m1" }, { 10_000 })
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
                    "sms.pending" -> RpcResult(
                        true,
                        data = mapOf("pending" to jarr(queue.pendingMeta().map { it.toPendingMetaJson() })),
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
            val pending = post(port, token, """{"op":"sms.pending"}""")
            assertFalse(pending.second.contains(secret))
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
        assertTrue(text.contains("BindPolicy.production()"))
        val server = File("../core/src/main/kotlin/com/joselofarias/oppoassist/core/HttpAgentServer.kt").readText()
        assertTrue(server.contains("isAnyLocalAddress"))
        assertFalse(server.contains("0.0.0.0"))
    }
}
