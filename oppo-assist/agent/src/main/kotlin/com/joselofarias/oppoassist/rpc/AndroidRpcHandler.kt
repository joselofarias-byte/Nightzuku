package com.joselofarias.oppoassist.rpc

import android.Manifest
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import android.os.StatFs
import androidx.core.content.ContextCompat
import com.joselofarias.oppoassist.OppoAssistApp
import com.joselofarias.oppoassist.core.AgentInfo
import com.joselofarias.oppoassist.core.AppBaseline
import com.joselofarias.oppoassist.core.CapabilityMatrix
import com.joselofarias.oppoassist.core.ContactInput
import com.joselofarias.oppoassist.core.InstallSignals
import com.joselofarias.oppoassist.core.JsonValue
import com.joselofarias.oppoassist.core.LocalNic
import com.joselofarias.oppoassist.core.PackageNames
import com.joselofarias.oppoassist.core.ProtectedPackages
import com.joselofarias.oppoassist.core.RecoveryPlan
import com.joselofarias.oppoassist.core.RpcHandler
import com.joselofarias.oppoassist.core.RpcResult
import com.joselofarias.oppoassist.core.TailscaleBind
import com.joselofarias.oppoassist.core.jarr
import com.joselofarias.oppoassist.core.jbool
import com.joselofarias.oppoassist.core.jnum
import com.joselofarias.oppoassist.core.jobj
import com.joselofarias.oppoassist.core.jstr
import com.joselofarias.oppoassist.core.toForwardJson
import com.joselofarias.oppoassist.core.toPendingMetaJson
import java.io.File
import java.net.NetworkInterface
import java.util.Collections

class AndroidRpcHandler(private val app: OppoAssistApp) : RpcHandler {
    override fun handle(op: String, fields: Map<String, JsonValue>): RpcResult {
        return try {
            when (op) {
                "ping" -> ok(
                    mapOf(
                        "version" to jstr(AgentInfo.VERSION),
                        "service" to jstr("oppo-assist"),
                    ),
                )
                "diagnostics" -> ok(diagnostics())
                "settings.capabilities" -> ok(mapOf("capabilities" to CapabilityMatrix.toJson()))
                "recovery.status" -> ok(
                    mapOf(
                        "willAsk" to jarr(RecoveryPlan.willAskAndroidToRestart.map { jstr(it) }),
                        "willNot" to jarr(RecoveryPlan.willNotRestart.map { jstr(it) }),
                    ),
                )
                "contacts.list" -> ContactsGateway.list(app)
                "contacts.upsert" -> ContactsGateway.upsert(app, fields)
                "contacts.delete" -> ContactsGateway.delete(app, fields)
                "apps.list" -> AppsGateway.list(app)
                "apps.open" -> AppsGateway.open(app, fields)
                "apps.details" -> AppsGateway.details(app, fields)
                "apps.uninstall" -> AppsGateway.uninstall(app, fields)
                "apps.close" -> RpcResult(false, "usar_adb_shell")
                "apps.baseline.capture" -> AppsGateway.captureBaseline(app)
                "apps.baseline.diff" -> AppsGateway.diff(app)
                "apps.discourage" -> AppsGateway.discourage(app, fields)
                "sms.recent" -> ok(
                    mapOf("messages" to jarr(app.sms.forwardList().map { it.toForwardJson() })),
                )
                "sms.pending" -> ok(
                    mapOf("pending" to jarr(app.sms.pendingMeta().map { it.toPendingMetaJson() })),
                )
                "sms.ack" -> {
                    val id = fields["id"]?.asString() ?: return RpcResult(false, "falta_id")
                    if (app.sms.ack(id)) ok() else RpcResult(false, "no_encontrado")
                }
                else -> RpcResult(false, "operacion_desconocida")
            }
        } catch (_: SecurityException) {
            RpcResult(false, "permiso_denegado")
        }
    }

    private fun diagnostics(): Map<String, JsonValue> {
        val battery = app.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val plugged = (battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        val percent = if (scale > 0 && level >= 0) (level * 100L) / scale else -1L
        val cm = app.getSystemService(ConnectivityManager::class.java)
        val caps = cm?.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        val transports = buildList {
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true) add("wifi")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true) add("cellular")
            if (caps?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true) add("vpn")
        }
        val stat = StatFs(Environment.getDataDirectory().absolutePath)
        val chosen = runCatching {
            val nics = Collections.list(NetworkInterface.getNetworkInterfaces()).map { nic ->
                LocalNic(nic.name, nic.isUp, Collections.list(nic.inetAddresses))
            }
            TailscaleBind.choose(nics)?.hostAddress
        }.getOrNull()
        return mapOf(
            "battery.percent" to jnum(percent),
            "battery.charging" to jbool(plugged),
            "network.validated" to jbool(caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true),
            "network.transports" to jstr(transports.joinToString(",")),
            "storage.freeBytes" to jnum(stat.availableBytes),
            "storage.totalBytes" to jnum(stat.totalBytes),
            "permissions.sms" to jstr(permission(Manifest.permission.READ_SMS)),
            "permissions.contacts" to jstr(permission(Manifest.permission.READ_CONTACTS)),
            "permissions.notifications" to jstr(notificationPermission()),
            "packages.count" to jnum(app.packageManager.getInstalledApplications(0).size.toLong()),
            "tailscale.address" to jstr(chosen ?: ""),
            "sms.rcsObservable" to jbool(false),
            "sms.forwardEnabled" to jbool(app.prefs.getBoolean(OppoAssistApp.KEY_FORWARD, false)),
        )
    }

    private fun permission(name: String): String =
        if (ContextCompat.checkSelfPermission(app, name) == PackageManager.PERMISSION_GRANTED) {
            "granted"
        } else {
            "denied"
        }

    private fun notificationPermission(): String {
        if (android.os.Build.VERSION.SDK_INT < 33) return "granted"
        return permission(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun ok(data: Map<String, JsonValue> = emptyMap()) = RpcResult(true, data = data)
}

object AppsGateway {
    fun list(app: OppoAssistApp): RpcResult {
        val baseline = baseline(app)
        val discouraged = readDiscouraged(app)
        val rows = app.packageManager.getInstalledApplications(0).map { info ->
            val installer = installerOf(app, info.packageName)
            val flags = InstallSignals.attention(
                info.packageName,
                installer,
                discouraged,
                baseline.isNotEmpty() && info.packageName in baseline,
            )
            jobj(
                "package" to jstr(info.packageName),
                "label" to jstr(app.packageManager.getApplicationLabel(info).toString().take(80)),
                "installer" to jstr(installer ?: ""),
                "attention" to jstr(flags.joinToString(",") { it.name }),
            )
        }.sortedBy { (it as JsonValue.Obj).value.getValue("package").asString() }
        return RpcResult(
            true,
            data = mapOf(
                "count" to jnum(rows.size.toLong()),
                "apps" to jarr(rows.take(400)),
            ),
        )
    }

    fun open(app: OppoAssistApp, fields: Map<String, JsonValue>): RpcResult {
        val pkg = packageField(fields) ?: return RpcResult(false, "paquete_invalido")
        val launch = app.packageManager.getLaunchIntentForPackage(pkg)
            ?: return RpcResult(false, "sin_actividad")
        launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(launch)
            RpcResult(true, data = mapOf("result" to jstr("pedido_de_apertura")))
        } catch (_: Exception) {
            RpcResult(false, "android_bloqueo_la_apertura")
        }
    }

    fun details(app: OppoAssistApp, fields: Map<String, JsonValue>): RpcResult {
        val pkg = packageField(fields) ?: return RpcResult(false, "paquete_invalido")
        val intent = Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.parse("package:$pkg"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(intent)
            RpcResult(true, data = mapOf("result" to jstr("ajustes_en_pantalla")))
        } catch (_: Exception) {
            RpcResult(false, "android_bloqueo_la_apertura")
        }
    }

    fun uninstall(app: OppoAssistApp, fields: Map<String, JsonValue>): RpcResult {
        val pkg = packageField(fields) ?: return RpcResult(false, "paquete_invalido")
        if (ProtectedPackages.uninstallBlocked(pkg, app.protectedPackages)) {
            return RpcResult(false, "paquete_protegido")
        }
        val intent = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            app.startActivity(intent)
            RpcResult(true, data = mapOf("result" to jstr("confirmacion_en_el_telefono")))
        } catch (_: Exception) {
            RpcResult(false, "android_bloqueo_la_apertura")
        }
    }

    fun captureBaseline(app: OppoAssistApp): RpcResult {
        val names = app.packageManager.getInstalledApplications(0).map { it.packageName }.toSet()
        app.prefs.edit().putStringSet(OppoAssistApp.KEY_BASELINE, names).apply()
        return RpcResult(true, data = mapOf("count" to jnum(names.size.toLong())))
    }

    fun diff(app: OppoAssistApp): RpcResult {
        val baseline = baseline(app)
        if (baseline.isEmpty()) return RpcResult(false, "sin_linea_base")
        val current = app.packageManager.getInstalledApplications(0).map { it.packageName }.toSet()
        val added = AppBaseline.added(baseline, current)
        return RpcResult(true, data = mapOf("added" to jarr(added.map { jstr(it) })))
    }

    fun discourage(app: OppoAssistApp, fields: Map<String, JsonValue>): RpcResult {
        val pkg = packageField(fields) ?: return RpcResult(false, "paquete_invalido")
        val all = readDiscouraged(app) + pkg
        discouragedFile(app).writeText(all.joinToString("\n"))
        return RpcResult(true, data = mapOf("discouraged" to jstr(pkg), "uninstalled" to jbool(false)))
    }

    fun baseline(app: OppoAssistApp): Set<String> =
        app.prefs.getStringSet(OppoAssistApp.KEY_BASELINE, emptySet()) ?: emptySet()

    fun readDiscouraged(app: OppoAssistApp): Set<String> {
        val file = discouragedFile(app)
        if (!file.isFile) return emptySet()
        return ProtectedPackages.parse(file.readText())
    }

    private fun discouragedFile(app: OppoAssistApp) = File(app.filesDir, "discouraged.list")

    private fun packageField(fields: Map<String, JsonValue>): String? {
        val pkg = fields["package"]?.asString() ?: return null
        return pkg.takeIf { PackageNames.isValid(it) }
    }

    private fun installerOf(app: OppoAssistApp, packageName: String): String? = try {
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            app.packageManager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            app.packageManager.getInstallerPackageName(packageName)
        }
    } catch (_: Exception) {
        null
    }
}

object ContactsGateway {
    fun list(app: OppoAssistApp): RpcResult {
        val people = linkedMapOf<Long, MutablePerson>()
        app.contentResolver.query(
            android.provider.ContactsContract.Contacts.CONTENT_URI,
            arrayOf(
                android.provider.ContactsContract.Contacts._ID,
                android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
            ),
            null,
            null,
            android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY + " ASC",
        )?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.Contacts._ID)
            val nameIdx = cursor.getColumnIndexOrThrow(android.provider.ContactsContract.Contacts.DISPLAY_NAME_PRIMARY)
            var count = 0
            while (cursor.moveToNext() && count < 500) {
                val id = cursor.getLong(idIdx)
                people[id] = MutablePerson(id, cursor.getString(nameIdx) ?: "", "", "")
                count++
            }
        }
        fill(
            app,
            people,
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTACT_ID,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
        ) { person, value -> if (person.phone.isEmpty()) person.phone = value }
        fill(
            app,
            people,
            android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_URI,
            android.provider.ContactsContract.CommonDataKinds.Email.CONTACT_ID,
            android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS,
        ) { person, value -> if (person.email.isEmpty()) person.email = value }
        val rows = people.values.map { person ->
            jobj(
                "id" to jstr(person.id.toString()),
                "name" to jstr(person.name),
                "phone" to jstr(person.phone),
                "email" to jstr(person.email),
            )
        }
        return RpcResult(true, data = mapOf("contacts" to jarr(rows)))
    }

    fun upsert(app: OppoAssistApp, fields: Map<String, JsonValue>): RpcResult {
        val name = fields["name"]?.asString()?.trim().orEmpty()
        val phone = fields["phone"]?.asString()?.trim().orEmpty()
        val email = fields["email"]?.asString()?.trim().orEmpty()
        if (!ContactInput.validName(name) || !ContactInput.validPhone(phone) || !ContactInput.validEmail(email)) {
            return RpcResult(false, "datos_invalidos")
        }
        val id = fields["id"]?.asString()?.trim().orEmpty()
        return if (id.isEmpty()) insert(app, name, phone, email) else update(app, id, name, phone, email)
    }

    fun delete(app: OppoAssistApp, fields: Map<String, JsonValue>): RpcResult {
        val id = fields["id"]?.asString()?.toLongOrNull() ?: return RpcResult(false, "falta_id")
        val uri = android.content.ContentUris.withAppendedId(
            android.provider.ContactsContract.Contacts.CONTENT_URI,
            id,
        )
        val rows = app.contentResolver.delete(uri, null, null)
        return if (rows > 0) RpcResult(true) else RpcResult(false, "no_encontrado")
    }

    private fun insert(app: OppoAssistApp, name: String, phone: String, email: String): RpcResult {
        val ops = arrayListOf<android.content.ContentProviderOperation>()
        ops += android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.RawContacts.CONTENT_URI)
            .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_TYPE, null)
            .withValue(android.provider.ContactsContract.RawContacts.ACCOUNT_NAME, null)
            .build()
        ops += dataInsert(0, android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
            .withValue(android.provider.ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            .build()
        ops += dataInsert(0, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
            .withValue(android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
            .withValue(
                android.provider.ContactsContract.CommonDataKinds.Phone.TYPE,
                android.provider.ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE,
            )
            .build()
        if (email.isNotEmpty()) {
            ops += dataInsert(0, android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE)
                .withValue(android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS, email)
                .withValue(
                    android.provider.ContactsContract.CommonDataKinds.Email.TYPE,
                    android.provider.ContactsContract.CommonDataKinds.Email.TYPE_HOME,
                )
                .build()
        }
        val results = app.contentResolver.applyBatch(android.provider.ContactsContract.AUTHORITY, ops)
        val rawId = results.firstOrNull()?.uri?.lastPathSegment
        return RpcResult(true, data = mapOf("rawId" to jstr(rawId ?: "")))
    }

    private fun update(app: OppoAssistApp, id: String, name: String, phone: String, email: String): RpcResult {
        if (id.toLongOrNull() == null) return RpcResult(false, "falta_id")
        val rawId = rawId(app, id) ?: return RpcResult(false, "no_encontrado")
        val where = "${android.provider.ContactsContract.Data.CONTACT_ID}=? AND ${android.provider.ContactsContract.Data.MIMETYPE}=?"
        app.contentResolver.delete(
            android.provider.ContactsContract.Data.CONTENT_URI,
            where,
            arrayOf(id, android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE),
        )
        app.contentResolver.delete(
            android.provider.ContactsContract.Data.CONTENT_URI,
            where,
            arrayOf(id, android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE),
        )
        val nameValues = android.content.ContentValues().apply {
            put(android.provider.ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
        }
        val updated = app.contentResolver.update(
            android.provider.ContactsContract.Data.CONTENT_URI,
            nameValues,
            where,
            arrayOf(id, android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE),
        )
        if (updated == 0) {
            insertData(
                app,
                rawId,
                android.provider.ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE,
                android.provider.ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME,
                name,
            )
        }
        insertData(
            app,
            rawId,
            android.provider.ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE,
            android.provider.ContactsContract.CommonDataKinds.Phone.NUMBER,
            phone,
        )
        if (email.isNotEmpty()) {
            insertData(
                app,
                rawId,
                android.provider.ContactsContract.CommonDataKinds.Email.CONTENT_ITEM_TYPE,
                android.provider.ContactsContract.CommonDataKinds.Email.ADDRESS,
                email,
            )
        }
        return RpcResult(true, data = mapOf("id" to jstr(id)))
    }

    private fun rawId(app: OppoAssistApp, contactId: String): Long? {
        app.contentResolver.query(
            android.provider.ContactsContract.RawContacts.CONTENT_URI,
            arrayOf(android.provider.ContactsContract.RawContacts._ID),
            "${android.provider.ContactsContract.RawContacts.CONTACT_ID}=?",
            arrayOf(contactId),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return null
            return cursor.getLong(0)
        }
        return null
    }

    private fun insertData(app: OppoAssistApp, rawId: Long, mime: String, key: String, value: String) {
        val values = android.content.ContentValues().apply {
            put(android.provider.ContactsContract.Data.RAW_CONTACT_ID, rawId)
            put(android.provider.ContactsContract.Data.MIMETYPE, mime)
            put(key, value)
        }
        app.contentResolver.insert(android.provider.ContactsContract.Data.CONTENT_URI, values)
    }

    private fun dataInsert(backRef: Int, mime: String) =
        android.content.ContentProviderOperation.newInsert(android.provider.ContactsContract.Data.CONTENT_URI)
            .withValueBackReference(android.provider.ContactsContract.Data.RAW_CONTACT_ID, backRef)
            .withValue(android.provider.ContactsContract.Data.MIMETYPE, mime)

    private fun fill(
        app: OppoAssistApp,
        people: Map<Long, MutablePerson>,
        uri: Uri,
        idColumn: String,
        valueColumn: String,
        write: (MutablePerson, String) -> Unit,
    ) {
        app.contentResolver.query(uri, arrayOf(idColumn, valueColumn), null, null, null)?.use { cursor ->
            val idIdx = cursor.getColumnIndexOrThrow(idColumn)
            val valueIdx = cursor.getColumnIndexOrThrow(valueColumn)
            while (cursor.moveToNext()) {
                val person = people[cursor.getLong(idIdx)] ?: continue
                write(person, cursor.getString(valueIdx) ?: "")
            }
        }
    }
}

private class MutablePerson(
    val id: Long,
    val name: String,
    var phone: String,
    var email: String,
)
