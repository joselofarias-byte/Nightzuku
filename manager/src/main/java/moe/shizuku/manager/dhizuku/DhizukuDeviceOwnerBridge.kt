package moe.shizuku.manager.dhizuku

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.IBinder
import android.os.UserManager
import android.provider.Settings
import com.rosan.dhizuku.api.Dhizuku
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Executes DevicePolicyManager calls through Dhizuku's binderWrapper.
 *
 * Unlike a Dhizuku UserService, this path does not need an auxiliary process
 * or lifecycle Binder protocol. The DevicePolicyManager object is created from
 * Dhizuku's own package context, its hidden mService is replaced with a proxy
 * backed by Dhizuku.binderWrapper(), and public DPM methods therefore reach
 * system_server under the Device Owner identity.
 */
object DhizukuDeviceOwnerBridge {

    private data class ElevatedDpm(
        val dpm: DevicePolicyManager,
        val admin: android.content.ComponentName
    )

    fun setAdbEnabled(context: Context, enabled: Boolean): Result<Unit> = runCatching {
        val elevated = elevatedDpm(context)

        if (enabled) {
            // If the Device Owner applied this restriction previously, clear it
            // before asking Android to enable debugging again.
            runCatching {
                elevated.dpm.clearUserRestriction(
                    elevated.admin,
                    UserManager.DISALLOW_DEBUGGING_FEATURES
                )
            }
        }

        elevated.dpm.setGlobalSetting(
            elevated.admin,
            Settings.Global.ADB_ENABLED,
            if (enabled) "1" else "0"
        )
    }

    fun setWirelessDebuggingEnabled(context: Context, enabled: Boolean): Result<Unit> = runCatching {
        val elevated = elevatedDpm(context)
        elevated.dpm.setGlobalSetting(
            elevated.admin,
            "adb_wifi_enabled",
            if (enabled) "1" else "0"
        )
    }

    fun setNightzukuAlwaysOnVpn(context: Context, enabled: Boolean): Result<Unit> = runCatching {
        val elevated = elevatedDpm(context)
        val packageName = if (enabled) context.applicationContext.packageName else null
        elevated.dpm.setAlwaysOnVpnPackage(
            elevated.admin,
            packageName,
            false
        )
    }

    fun getAlwaysOnVpnPackage(context: Context): Result<String?> = runCatching {
        val elevated = elevatedDpm(context)
        elevated.dpm.getAlwaysOnVpnPackage(elevated.admin)
    }

    private fun elevatedDpm(context: Context): ElevatedDpm {
        val app = context.applicationContext

        check(Dhizuku.init(app)) {
            "Dhizuku no está disponible o no está activo."
        }
        check(Dhizuku.isPermissionGranted()) {
            "Nightzuku no tiene permiso Dhizuku."
        }

        val owner = Dhizuku.getOwnerComponent()
            ?: error("Dhizuku no informó el componente Device Owner.")

        // DPM includes caller package/context information in several binder
        // calls. Build it from the actual Device Owner package, not Nightzuku.
        val ownerContext = app.createPackageContext(
            owner.packageName,
            Context.CONTEXT_IGNORE_SECURITY
        )
        val dpm = ownerContext.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as? DevicePolicyManager
            ?: error("DevicePolicyManager no disponible.")

        // Android 16 hides mService / IDevicePolicyManager. HiddenApiBypass is
        // already a project dependency; exempt only this framework namespace.
        HiddenApiBypass.addHiddenApiExemptions("Landroid/app/admin/")

        val serviceField = DevicePolicyManager::class.java.getDeclaredField("mService")
        serviceField.isAccessible = true

        val originalService = serviceField.get(dpm)
            ?: error("DevicePolicyManager.mService vacío.")

        val originalBinder = when (originalService) {
            is IBinder -> originalService
            else -> {
                val asBinder = originalService.javaClass.getMethod("asBinder")
                asBinder.isAccessible = true
                asBinder.invoke(originalService) as? IBinder
                    ?: error("No se pudo obtener el Binder de DevicePolicyManager.")
            }
        }

        val wrappedBinder = Dhizuku.binderWrapper(originalBinder)

        val stub = Class.forName("android.app.admin.IDevicePolicyManager\$Stub")
        val asInterface = stub.getDeclaredMethod("asInterface", IBinder::class.java)
        asInterface.isAccessible = true
        val elevatedService = asInterface.invoke(null, wrappedBinder)
            ?: error("No se pudo crear el proxy Device Owner.")

        serviceField.set(dpm, elevatedService)
        return ElevatedDpm(dpm, owner)
    }
}
