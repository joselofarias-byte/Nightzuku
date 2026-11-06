package com.joselofarias.oppoassist.sms

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.joselofarias.oppoassist.OppoAssistApp

class SmsRelayReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECEIVE_SMS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        val parts = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (parts.isEmpty()) return
        val body = parts.joinToString("") { it.displayMessageBody ?: "" }
        val from = parts.firstOrNull()?.displayOriginatingAddress ?: ""
        val at = parts.firstOrNull()?.timestampMillis ?: System.currentTimeMillis()
        OppoAssistApp.from(context).acceptTraditionalSms(from, body, at)
    }
}

object InboxWatcher {
    fun poll(app: OppoAssistApp) {
        if (ContextCompat.checkSelfPermission(app, Manifest.permission.READ_SMS)
            != android.content.pm.PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (!app.prefs.contains(KEY_LAST)) {
            app.prefs.edit().putLong(KEY_LAST, System.currentTimeMillis()).apply()
            return
        }
        val last = app.prefs.getLong(KEY_LAST, 0L)
        var max = last
        app.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY, Telephony.Sms.DATE),
            "${Telephony.Sms.DATE}>?",
            arrayOf(last.toString()),
            "${Telephony.Sms.DATE} ASC",
        )?.use { cursor ->
            val fromIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.ADDRESS)
            val bodyIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.BODY)
            val dateIdx = cursor.getColumnIndexOrThrow(Telephony.Sms.DATE)
            while (cursor.moveToNext()) {
                val at = cursor.getLong(dateIdx)
                if (at > max) max = at
                app.acceptTraditionalSms(
                    cursor.getString(fromIdx) ?: "",
                    cursor.getString(bodyIdx) ?: "",
                    at,
                )
            }
        }
        if (max > last) app.prefs.edit().putLong(KEY_LAST, max).apply()
    }

    private const val KEY_LAST = "sms_last_date"
}
