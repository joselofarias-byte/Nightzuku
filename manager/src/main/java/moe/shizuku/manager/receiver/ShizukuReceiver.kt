package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import moe.shizuku.manager.shell.ShellBinderRequestHandler
import moe.shizuku.manager.shizuku.NightDogRecovery

class ShizukuReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if ("rikka.shizuku.intent.action.REQUEST_BINDER" == intent.action) {
            val handled = ShellBinderRequestHandler.handleRequest(context, intent)
            if (!handled && NightDogRecovery.isDesiredRunning(context)) {
                NightDogRecovery.onBinderRequestWhileDead(context)
            }
        }
    }
}
