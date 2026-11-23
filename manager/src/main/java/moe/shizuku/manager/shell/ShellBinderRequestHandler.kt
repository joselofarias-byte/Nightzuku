package moe.shizuku.manager.shell

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import moe.shizuku.manager.shizuku.NightDogTrace
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku

object ShellBinderRequestHandler {

    fun handleRequest(context: Context, intent: Intent): Boolean {
        if (intent.action != "rikka.shizuku.intent.action.REQUEST_BINDER") {
            return false
        }

        NightDogTrace.record(context, "RISH_REQUEST")
        val binder = intent.getBundleExtra("data")?.getBinder("binder") ?: run {
            NightDogTrace.record(context, "RISH_REPLY status=missing_callback")
            return false
        }
        val shizukuBinder = try {
            Shizuku.getBinder()?.takeIf { it.pingBinder() }
        } catch (e: Throwable) {
            NightDogTrace.record(context, "RISH_REPLY status=manager_binder_error")
            LOGGER.w(e, "Binder not received or Shizuku service not running")
            return false
        }
        if (shizukuBinder == null) {
            NightDogTrace.record(context, "RISH_REPLY status=no_manager_binder")
            LOGGER.w("Binder not received or Shizuku service not running")
            return false
        }

        val data = Parcel.obtain()
        return try {
            data.writeStrongBinder(shizukuBinder)
            data.writeString(context.applicationInfo.sourceDir)
            val accepted = binder.transact(1, data, null, IBinder.FLAG_ONEWAY)
            NightDogTrace.record(context, "RISH_REPLY status=sent accepted=$accepted")
            true
        } catch (e: Throwable) {
            NightDogTrace.record(context, "RISH_REPLY status=transaction_error")
            e.printStackTrace()
            false
        } finally {
            data.recycle()
        }
    }
}
