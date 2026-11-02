package com.joselofarias.oppoassist.sms

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.joselofarias.oppoassist.OppoAssistApp
import com.joselofarias.oppoassist.R
import com.joselofarias.oppoassist.core.SmsDecision
import com.joselofarias.oppoassist.ui.ConsentActivity

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

object SmsAlerts {
    fun onIngress(context: Context, result: com.joselofarias.oppoassist.core.IngressResult) {
        if (result.decision != SmsDecision.HELD || result.id == null) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL,
            context.getString(R.string.channel_consent),
            NotificationManager.IMPORTANCE_HIGH,
        )
        manager.createNotificationChannel(channel)
        val open = PendingIntent.getActivity(
            context,
            result.id.hashCode(),
            Intent(context, ConsentActivity::class.java).putExtra(ConsentActivity.EXTRA_ID, result.id),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(context.getString(R.string.consent_title))
            .setContentText(context.getString(R.string.consent_notification))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        manager.notify(result.id.hashCode(), notification)
    }

    private const val CHANNEL = "consent"
}
