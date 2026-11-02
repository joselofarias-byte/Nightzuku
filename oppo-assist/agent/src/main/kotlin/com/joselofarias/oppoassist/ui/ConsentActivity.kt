package com.joselofarias.oppoassist.ui

import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.joselofarias.oppoassist.OppoAssistApp
import com.joselofarias.oppoassist.R

class ConsentActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_consent)
        val id = intent.getStringExtra(EXTRA_ID)
        val app = OppoAssistApp.from(this)
        val message = id?.let { app.sms.pendingForLocal(it) }
        val from = findViewById<TextView>(R.id.from)
        val body = findViewById<TextView>(R.id.body)
        if (message == null) {
            from.text = "Ese pedido ya no está."
            body.text = ""
            findViewById<Button>(R.id.allow).isEnabled = false
            findViewById<Button>(R.id.deny).isEnabled = false
            return
        }
        from.text = message.from
        body.text = message.body
        findViewById<Button>(R.id.allow).setOnClickListener {
            app.sms.approve(message.id, System.currentTimeMillis())
            finish()
        }
        findViewById<Button>(R.id.deny).setOnClickListener {
            app.sms.deny(message.id)
            finish()
        }
    }

    companion object {
        const val EXTRA_ID = "id"
    }
}
