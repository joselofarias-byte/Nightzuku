package com.joselofarias.oppoassist

import android.app.Application
import android.content.Context
import com.joselofarias.oppoassist.core.Channel
import com.joselofarias.oppoassist.core.FileSmsQueue
import com.joselofarias.oppoassist.core.ProtectedPackages
import com.joselofarias.oppoassist.core.SmsIngress
import com.joselofarias.oppoassist.core.safeSmsId
import java.io.File
import java.security.MessageDigest
import java.security.SecureRandom

class OppoAssistApp : Application() {
    lateinit var token: String
        private set
    lateinit var sms: FileSmsQueue
        private set
    lateinit var ingress: SmsIngress
        private set
    lateinit var protectedPackages: Set<String>
        private set

    val prefs by lazy { getSharedPreferences(PREFS, MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        token = TokenStore.loadOrCreate(this)
        sms = FileSmsQueue(File(filesDir, "sms"))
        ingress = SmsIngress(
            queue = sms,
            ids = { newSmsId() },
            clock = { System.currentTimeMillis() },
        )
        protectedPackages = assets.open("protected-packages.list").bufferedReader().use {
            ProtectedPackages.parse(it.readText())
        }
    }

    fun acceptTraditionalSms(from: String, body: String, atMs: Long) {
        val key = fingerprint(from, body, atMs)
        if (!SmsDedup.fresh(File(filesDir, "sms-seen.txt"), key)) return
        ingress.accept(Channel.SMS, from, body)
    }

    private fun newSmsId(): String {
        val raw = java.util.UUID.randomUUID().toString()
        return safeSmsId(raw) ?: "sms"
    }

    companion object {
        const val PREFS = "oppo_assist"
        const val KEY_BASELINE = "baseline"
        const val KEY_STATUS = "status_line"
        const val KEY_TOKEN = "token"

        fun from(context: Context): OppoAssistApp = context.applicationContext as OppoAssistApp

        fun fingerprint(from: String, body: String, atMs: Long): String {
            val minute = atMs / 60_000L
            val digest = MessageDigest.getInstance("SHA-256")
                .digest("$from\n$body".toByteArray())
                .joinToString("") { "%02x".format(it) }
                .take(16)
            return "$minute-$digest"
        }
    }
}

object TokenStore {
    fun loadOrCreate(context: Context): String {
        val prefs = context.getSharedPreferences(OppoAssistApp.PREFS, Context.MODE_PRIVATE)
        val existing = prefs.getString(OppoAssistApp.KEY_TOKEN, null)
        if (existing != null && existing.length >= 32) return existing
        val bytes = ByteArray(32)
        SecureRandom().nextBytes(bytes)
        val token = bytes.joinToString("") { "%02x".format(it) }
        prefs.edit().putString(OppoAssistApp.KEY_TOKEN, token).apply()
        return token
    }
}

object SmsDedup {
    @Synchronized
    fun fresh(file: File, id: String): Boolean {
        val lines = if (file.exists()) file.readLines().toMutableList() else mutableListOf()
        if (id in lines) return false
        lines += id
        val kept = if (lines.size > 100) lines.takeLast(100) else lines
        file.writeText(kept.joinToString("\n"))
        return true
    }
}
