package moe.shizuku.manager.shell

import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Parcel
import moe.shizuku.manager.utils.Logger.LOGGER
import rikka.shizuku.Shizuku

object ShellBinderRequestHandler {

    private val RETRY_DELAYS_MS = longArrayOf(0L, 200L, 600L, 1500L)

    fun handleRequest(context: Context, intent: Intent): Boolean {
        if (intent.action != "rikka.shizuku.intent.action.REQUEST_BINDER") {
            return false
        }

        val binder = intent.getBundleExtra("data")?.getBinder("binder") ?: return false
        val shizukuBinder = try {
            Shizuku.getBinder()
        } catch (e: Throwable) {
            LOGGER.w(e, "Binder not received or Shizuku service not running")
            return false
        }
        if (shizukuBinder == null) {
            LOGGER.w("Binder not received or Shizuku service not running")
            return false
        }

        var lastFailure: Throwable? = null
        for (delayMs in RETRY_DELAYS_MS) {
            if (delayMs > 0L) {
                try {
                    Thread.sleep(delayMs)
                } catch (e: InterruptedException) {
                    Thread.currentThread().interrupt()
                    LOGGER.w(e, "Binder delivery retry interrupted")
                    return false
                }
            }

            val data = Parcel.obtain()
            try {
                data.writeStrongBinder(shizukuBinder)
                data.writeString(context.applicationInfo.sourceDir)
                if (binder.transact(
                        IBinder.FIRST_CALL_TRANSACTION,
                        data,
                        null,
                        IBinder.FLAG_ONEWAY
                    )
                ) {
                    return true
                }
                LOGGER.w("Binder delivery transact returned false after ${delayMs}ms delay")
            } catch (e: Throwable) {
                lastFailure = e
                LOGGER.w(e, "Binder delivery attempt failed after ${delayMs}ms delay")
            } finally {
                data.recycle()
            }
        }

        if (lastFailure != null) {
            LOGGER.w(lastFailure, "Binder delivery failed after all retries")
        } else {
            LOGGER.w("Binder delivery failed after all retries")
        }
        return false
    }
}
