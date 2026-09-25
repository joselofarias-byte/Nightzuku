package moe.shizuku.manager.shell

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import moe.shizuku.manager.module.ModuleSettings
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku

object ShellBinderRequestHandler {

    fun handleRequest(context: Context, intent: Intent): Boolean {
        if (intent.action != "rikka.shizuku.intent.action.REQUEST_BINDER") {
            return false
        }

        val binder = intent.getBundleExtra("data")?.getBinder("binder") ?: return false
        if (intent.getBooleanExtra("tapi", false) && !ModuleSettings.isTapiEnabled()) {
            val emptyData = Parcel.obtain()
            return try {
                binder.transact(2, emptyData, null, IBinder.FLAG_ONEWAY)
                true
            } catch (_: Throwable) {
                false
            } finally {
                emptyData.recycle()
            }
        }
        val shizukuBinder = try {
            Shizuku.getBinder()
        } catch (e: Throwable) {
            LOGGER.w(e, "Binder not received or Shizuku service not running")
            null
        }
        if (shizukuBinder == null) {
            LOGGER.w("Binder not received or Shizuku service not running")
        }

        // Always reply. A missing server must not stall rish for the 5s timeout.
        val data = Parcel.obtain()
        return try {
            data.writeStrongBinder(shizukuBinder)
            data.writeString(context.applicationInfo.sourceDir)
            binder.transact(1, data, null, IBinder.FLAG_ONEWAY)
            shizukuBinder != null
        } catch (e: Throwable) {
            e.printStackTrace()
            false
        } finally {
            data.recycle()
        }
    }
}
