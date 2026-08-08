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
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.TextView
import androidx.core.view.setPadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.launch
import moe.shizuku.manager.home.HomeActivity
import moe.shizuku.manager.shizuku.NightDogRecovery

class MainActivity : HomeActivity() {

    private var nightDogStatusView: TextView? = null

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
        val overlayHorizontalMargin = dp(16)
        val overlayBottomMargin = dp(20)

        nightDogStatusView = TextView(this).apply {
            setPadding(dp(16))
            textSize = 14f
            setTextColor(Color.WHITE)
            elevation = dp(6).toFloat()
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp(18).toFloat()
                setColor(0xEB202124.toInt())
            }
        }.also { view ->
            root.addView(
                view,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.WRAP_CONTENT,
                    Gravity.BOTTOM
                ).apply {
                    leftMargin = overlayHorizontalMargin
                    rightMargin = overlayHorizontalMargin
                    bottomMargin = overlayBottomMargin
                }
            )
        }
    }

    private fun renderNightDogStatus(snapshot: NightDogRecovery.Snapshot) {
        val (headline, color) = when {
            snapshot.binderAlive && snapshot.stage == NightDogRecovery.Stage.RUNNING ->
                "Servicio iniciado correctamente" to 0xFF66BB6A.toInt()

            snapshot.stage == NightDogRecovery.Stage.ERROR ->
                "Error al iniciar el servicio" to 0xFFEF5350.toInt()

            snapshot.stage == NightDogRecovery.Stage.MANUALLY_STOPPED || !snapshot.desiredRunning ->
                "Servicio detenido" to 0xFFBDBDBD.toInt()

            snapshot.stage == NightDogRecovery.Stage.STARTING_SERVICE ->
                "Iniciando servicio…" to 0xFFFFCA28.toInt()

            snapshot.stage == NightDogRecovery.Stage.DISCOVERING_ADB ->
                "Buscando transporte ADB…" to 0xFFFFCA28.toInt()

            snapshot.stage == NightDogRecovery.Stage.WAITING_FOR_ADB ->
                "Recuperando servicio…" to 0xFFFFCA28.toInt()

            else ->
                "Comprobando Binder y ADB…" to 0xFFFFCA28.toInt()
        }

        val binder = if (snapshot.binderAlive) "OK" else "no disponible"
        val transport = snapshot.transport ?: "buscando"
        val endpoint = snapshot.endpoint ?: "no resuelto"
        val detail = buildString {
            append("Chequeo: ${snapshot.stage.name}")
            append("  •  Binder: $binder")
            append("\nTransporte: $transport")
            append("  •  Endpoint: $endpoint")
            append("\nIntentos: ${snapshot.failedAttempts}")
            append("  •  Resultado: ${snapshot.lastResult}")
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

        nightDogStatusView?.text = text
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
