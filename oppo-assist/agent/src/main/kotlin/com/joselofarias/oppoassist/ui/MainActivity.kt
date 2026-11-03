package com.joselofarias.oppoassist.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.joselofarias.oppoassist.OppoAssistApp
import com.joselofarias.oppoassist.R
import com.joselofarias.oppoassist.rpc.AppsGateway
import com.joselofarias.oppoassist.service.AssistService

class MainActivity : AppCompatActivity() {
    private val permissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { render() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        findViewById<Button>(R.id.permissions).setOnClickListener { askPermissions() }
        findViewById<Button>(R.id.battery).setOnClickListener { askBattery() }
        findViewById<Button>(R.id.service).setOnClickListener {
            ContextCompat.startForegroundService(this, Intent(this, AssistService::class.java))
            render()
        }
        findViewById<Button>(R.id.baseline).setOnClickListener {
            val result = AppsGateway.captureBaseline(OppoAssistApp.from(this))
            val count = (result.data["count"] as? com.joselofarias.oppoassist.core.JsonValue.Num)?.value
            findViewById<TextView>(R.id.status).text = "Lista guardada ($count aplicaciones)."
        }
        findViewById<Button>(R.id.token).setOnClickListener { showToken() }
        findViewById<Button>(R.id.refresh).setOnClickListener { render() }
    }

    override fun onResume() {
        super.onResume()
        render()
    }

    private fun askPermissions() {
        val wanted = mutableListOf(
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.READ_SMS,
            Manifest.permission.READ_CONTACTS,
            Manifest.permission.WRITE_CONTACTS,
        )
        if (Build.VERSION.SDK_INT >= 33) wanted += Manifest.permission.POST_NOTIFICATIONS
        permissions.launch(wanted.toTypedArray())
    }

    private fun askBattery() {
        val power = getSystemService(PowerManager::class.java)
        if (power.isIgnoringBatteryOptimizations(packageName)) {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            return
        }
        startActivity(
            Intent(
                Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                Uri.parse("package:$packageName"),
            ),
        )
    }

    private fun showToken() {
        val token = OppoAssistApp.from(this).token
        AlertDialog.Builder(this)
            .setTitle(getString(R.string.show_token))
            .setMessage(token + "\n\n" + getString(R.string.token_warning))
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }

    private fun render() {
        val app = OppoAssistApp.from(this)
        val status = app.prefs.getString(OppoAssistApp.KEY_STATUS, "El servicio todavía no informó estado.")
        val sms = granted(Manifest.permission.READ_SMS)
        val contacts = granted(Manifest.permission.READ_CONTACTS)
        findViewById<TextView>(R.id.status).text = "$status\nSMS: $sms\nContactos: $contacts"
    }

    private fun granted(permission: String): String =
        if (ContextCompat.checkSelfPermission(this, permission) == android.content.pm.PackageManager.PERMISSION_GRANTED) {
            "otorgado"
        } else {
            "falta"
        }
}
