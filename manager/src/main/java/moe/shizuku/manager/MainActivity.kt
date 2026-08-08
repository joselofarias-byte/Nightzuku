package moe.shizuku.manager

import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.setPadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import moe.shizuku.manager.home.HomeActivity
import moe.shizuku.manager.shizuku.NightDogRecovery

class MainActivity : HomeActivity() {

    private var nightDogStatusView: TextView? = null
    private var lastStage: NightDogRecovery.Stage? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        installNightDogStatusOverlay()

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                NightDogRecovery.snapshot.collect { snapshot ->
                    renderNightDogStatus(snapshot)
                }
            }
        }
    }

    override fun onDestroy() {
        nightDogStatusView = null
        super.onDestroy()
    }

    private fun installNightDogStatusOverlay() {
        val root = findViewById<ViewGroup>(android.R.id.content)
        val horizontalMargin = dp(16)
        val overlayBottomMargin = dp(16)

        nightDogStatusView = TextView(this).apply {
            visibility = View.GONE
            setPadding(dp(12))
            textSize = 13f
            setTextColor(Color.WHITE)
            elevation = dp(6).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(16).toFloat()
                setColor(0xF0202124.toInt())
            }
        }.also { view ->
            root.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                ).apply {
                    leftMargin = horizontalMargin
                    rightMargin = horizontalMargin
                    bottomMargin = overlayBottomMargin
                }
            )
        }
    }

    private fun renderNightDogStatus(snapshot: NightDogRecovery.Snapshot) {
        val previousStage = lastStage
        lastStage = snapshot.stage

        if (snapshot.binderAlive && snapshot.stage == NightDogRecovery.Stage.RUNNING) {
            nightDogStatusView?.visibility = View.GONE
            if (previousStage != NightDogRecovery.Stage.RUNNING) {
                Toast.makeText(this, "Servicio iniciado correctamente", Toast.LENGTH_SHORT).show()
            }
            return
        }

        if (!snapshot.desiredRunning || snapshot.stage == NightDogRecovery.Stage.MANUALLY_STOPPED) {
            nightDogStatusView?.visibility = View.GONE
            return
        }

        val (headline, color) = when (snapshot.stage) {
            NightDogRecovery.Stage.ERROR ->
                "Error al iniciar el servicio" to 0xFFEF5350.toInt()

            NightDogRecovery.Stage.STARTING_SERVICE ->
                "Iniciando servicio…" to 0xFFFFCA28.toInt()

            NightDogRecovery.Stage.DISCOVERING_ADB ->
                "Buscando transporte ADB…" to 0xFFFFCA28.toInt()

            NightDogRecovery.Stage.WAITING_FOR_ADB ->
                "Recuperando servicio…" to 0xFFFFCA28.toInt()

            else ->
                "Comprobando Binder y ADB…" to 0xFFFFCA28.toInt()
        }

        val binder = if (snapshot.binderAlive) "OK" else "no disponible"
        val transport = snapshot.transport ?: "buscando"
        val endpoint = snapshot.endpoint ?: "no resuelto"
        val detail = buildString {
            append("Chequeo: ${snapshot.stage.name}  •  Binder: $binder")
            append("\nTransporte: $transport  •  Endpoint: $endpoint")
            append("\nIntentos: ${snapshot.failedAttempts}  •  ${snapshot.lastResult}")
        }

        val text = SpannableString("$headline\n$detail").apply {
            setSpan(
                StyleSpan(Typeface.BOLD),
                0,
                headline.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
            setSpan(
                ForegroundColorSpan(color),
                0,
                headline.length,
                Spannable.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }

        nightDogStatusView?.apply {
            this.text = text
            visibility = View.VISIBLE
        }
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
