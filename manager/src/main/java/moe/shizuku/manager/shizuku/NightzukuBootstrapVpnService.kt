package moe.shizuku.manager.shizuku

import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import android.util.Log
import moe.shizuku.manager.AppConstants

/**
 * System-managed bootstrap for HONOR/MagicOS.
 *
 * This VPN intentionally installs no routes, so normal app traffic is not
 * redirected through Nightzuku. Its purpose is to give Android a system-owned
 * reason to start this package after reboot. Once started, it wakes NightDog.
 *
 * The feature is opt-in and configured through DevicePolicyManager with
 * lockdown disabled.
 */
class NightzukuBootstrapVpnService : VpnService() {

    private var tun: ParcelFileDescriptor? = null

    override fun onCreate() {
        super.onCreate()
        NightDogBootTrace.note(this, "vpn_bootstrap_create", "system started service")
        wakeNightDog("vpn_onCreate")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        NightDogBootTrace.note(
            this,
            "vpn_bootstrap_start",
            intent?.action ?: "no_action"
        )

        ensureInterface()
        wakeNightDog("vpn_onStartCommand")
        return START_STICKY
    }

    override fun onRevoke() {
        NightDogBootTrace.note(this, "vpn_bootstrap_revoke", "always-on revoked")
        closeInterface()
        super.onRevoke()
    }

    override fun onDestroy() {
        NightDogBootTrace.note(this, "vpn_bootstrap_destroy", "service destroyed")
        closeInterface()
        super.onDestroy()
    }

    private fun ensureInterface() {
        if (tun != null) return

        tun = runCatching {
            Builder()
                .setSession("Nightzuku bootstrap")
                .addAddress("198.18.0.1", 32)
                .setMtu(1280)
                .setBlocking(false)
                .establish()
        }.onFailure {
            Log.w(AppConstants.TAG, "Nightzuku bootstrap VPN establish failed", it)
            NightDogBootTrace.note(
                this,
                "vpn_bootstrap_establish_failed",
                "${it.javaClass.simpleName}:${it.message.orEmpty()}"
            )
        }.getOrNull()

        NightDogBootTrace.note(
            this,
            "vpn_bootstrap_established",
            if (tun != null) "no routes" else "null"
        )
    }

    private fun wakeNightDog(source: String) {
        if (!NightDogRecovery.isDesiredRunning(this)) {
            NightDogBootTrace.note(this, "vpn_bootstrap_skip", "desired_off")
            return
        }

        NightDogBootScheduler.schedule(this)

        runCatching {
            NightDogForegroundService.start(this)
        }.onSuccess {
            NightDogBootTrace.note(this, "vpn_bootstrap_fgs", source)
        }.onFailure {
            Log.w(AppConstants.TAG, "VPN bootstrap could not start NightDog", it)
            NightDogBootTrace.note(
                this,
                "vpn_bootstrap_fgs_failed",
                "${it.javaClass.simpleName}:${it.message.orEmpty()}"
            )
        }
    }

    private fun closeInterface() {
        runCatching { tun?.close() }
        tun = null
    }
}
