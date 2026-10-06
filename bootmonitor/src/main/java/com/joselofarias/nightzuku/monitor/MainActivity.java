package com.joselofarias.nightzuku.monitor;

import android.app.Activity;
import android.content.ContentValues;
import android.content.ComponentName;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public final class MainActivity extends Activity {
    private TextView status;
    private TextView statusDetail;
    private TextView report;
    private Button detailsButton;
    private Button permissionButton;
    private boolean detailsVisible;
    private NightzukuDiagnostics.Snapshot nightzuku;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);

        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(20), dp(20), dp(28));
        scroll.addView(body);

        TextView title = new TextView(this);
        title.setText("Monitor de arranque Nightzuku");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setPadding(0, 0, 0, dp(12));
        body.addView(title);

        TextView intro = new TextView(this);
        intro.setText(
            "Esta prueba responde una sola pregunta:\n"
                + "¿Nightzuku se inicia solo al encender el teléfono?\n\n"
                + "1. Tocá «Preparar nueva prueba».\n"
                + "2. Reiniciá el teléfono y desbloquealo.\n"
                + "3. No abras Nightzuku.\n"
                + "4. Abrí este monitor y tocá «Comprobar resultado»."
        );
        intro.setTextSize(17);
        intro.setPadding(0, 0, 0, dp(18));
        body.addView(intro);

        permissionButton = button(body, "Dar permiso necesario", () -> {
            if (listenerEnabled()) {
                reconnectObserver();
                Toast.makeText(
                    this,
                    "Permiso concedido. Intentando reconectar el monitor…",
                    Toast.LENGTH_SHORT
                ).show();
            } else {
                startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS));
            }
        });

        button(body, "Preparar nueva prueba", () -> {
            MonitorStore.reset(this);
            refresh();
            Toast.makeText(
                this,
                "Prueba preparada. Ahora reiniciá el teléfono y no abras Nightzuku.",
                Toast.LENGTH_LONG
            ).show();
        });

        button(body, "Comprobar resultado", this::checkResult);

        TextView resultTitle = new TextView(this);
        resultTitle.setText("RESULTADO");
        resultTitle.setTextSize(14);
        resultTitle.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        resultTitle.setPadding(0, dp(16), 0, dp(6));
        body.addView(resultTitle);

        status = new TextView(this);
        status.setTextSize(23);
        status.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        status.setPadding(0, 0, 0, dp(8));
        body.addView(status);

        statusDetail = new TextView(this);
        statusDetail.setTextSize(17);
        statusDetail.setPadding(0, 0, 0, dp(14));
        body.addView(statusDetail);

        detailsButton = button(body, "Ver detalles técnicos", this::toggleDetails);

        report = new TextView(this);
        report.setTextSize(14);
        report.setTextIsSelectable(true);
        report.setPadding(0, dp(8), 0, dp(8));
        report.setVisibility(View.GONE);
        body.addView(report);

        button(body, "Guardar informe", this::saveReport);

        setContentView(scroll);
        refresh();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private Button button(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextSize(16);
        b.setOnClickListener(v -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        );
        params.setMargins(0, 0, 0, dp(8));
        b.setLayoutParams(params);
        parent.addView(b);
        return b;
    }

    private void toggleDetails() {
        detailsVisible = !detailsVisible;
        report.setVisibility(detailsVisible ? View.VISIBLE : View.GONE);
        detailsButton.setText(detailsVisible ? "Ocultar detalles técnicos" : "Ver detalles técnicos");
    }

    @Override public void onResume() {
        super.onResume();
        if (report != null) {
            refresh();
            if (listenerEnabled()) {
                reconnectObserver();
                mainHandler.postDelayed(this::refresh, 900L);
            }
        }
    }

    private void checkResult() {
        refresh();
        if (!listenerEnabled()) return;

        MonitorTimeline.Observation state = observation(systemBootEpoch());
        if (state == MonitorTimeline.Observation.WAITING_FOR_LISTENER) {
            status.setText("… CONECTANDO EL MONITOR");
            status.setTextColor(Color.rgb(95, 99, 104));
            statusDetail.setText(
                "El permiso está concedido. Android todavía no conectó el observador; "
                    + "estoy intentando reconectarlo ahora."
            );
            reconnectObserver();
            mainHandler.postDelayed(this::refresh, 700L);
            mainHandler.postDelayed(this::refresh, 1600L);
            mainHandler.postDelayed(this::refresh, 3000L);
        }
    }

    private void reconnectObserver() {
        try {
            NotificationListenerService.requestRebind(
                new ComponentName(this, NightzukuNotificationListener.class)
            );
        } catch (RuntimeException ignored) {
            // El resultado seguirá mostrando que el observador no está conectado.
        }
    }

    private boolean listenerEnabled() {
        String enabled = Settings.Secure.getString(
            getContentResolver(),
            "enabled_notification_listeners"
        );
        return enabled != null && enabled.contains(getPackageName() + "/");
    }

    private long systemBootEpoch() {
        return MonitorTimeline.bootEpoch(
            System.currentTimeMillis(),
            SystemClock.elapsedRealtime()
        );
    }

    private MonitorTimeline.Observation observation(long bootEpoch) {
        return MonitorTimeline.observation(
            listenerEnabled(),
            MonitorStore.connectedTime(this),
            MonitorStore.postTime(this),
            bootEpoch
        );
    }

    private void refresh() {
        long bootEpoch = systemBootEpoch();
        boolean access = listenerEnabled();
        nightzuku = NightzukuDiagnostics.read(this);

        permissionButton.setVisibility(nightzuku.available ? View.GONE : View.VISIBLE);
        permissionButton.setText(access ? "Permiso concedido ✓" : "Dar permiso necesario");

        if (nightzuku.available) {
            renderNightzukuDiagnostics();
            report.setText(buildReport());
            return;
        }

        switch (observation(bootEpoch)) {
            case OBSERVED:
                status.setText("✓ NIGHTZUKU ARRANCÓ SOLO");
                status.setTextColor(Color.rgb(19, 115, 51));
                statusDetail.setText(
                    "El monitor detectó que Nightzuku inició su servicio después del reinicio "
                        + "sin que abrieras la aplicación."
                );
                break;
            case NO_ACCESS:
                status.setText("⚠ FALTA UN PERMISO");
                status.setTextColor(Color.rgb(176, 96, 0));
                statusDetail.setText(
                    "Tocá «Dar permiso necesario» y permití que Monitor Nightzuku observe "
                        + "las notificaciones. Después prepará una nueva prueba."
                );
                break;
            case WAITING_FOR_LISTENER:
                status.setText("⚠ MONITOR SIN CONEXIÓN");
                status.setTextColor(Color.rgb(176, 96, 0));
                statusDetail.setText(
                    "El permiso está concedido, pero Android todavía no conectó el observador. "
                        + "Tocá «Comprobar resultado» y el monitor intentará reconectarse "
                        + "automáticamente. Esto no abre ni modifica Nightzuku."
                );
                break;
            default:
                status.setText("✕ NO SE DETECTÓ EL ARRANQUE AUTOMÁTICO");
                status.setTextColor(Color.rgb(179, 38, 30));
                statusDetail.setText(
                    "El monitor se conectó, pero no vio la notificación de Nightzuku "
                        + "durante este arranque."
                );
                break;
        }
        report.setText(buildReport());
    }


    private void renderNightzukuDiagnostics() {
        if (!nightzuku.desiredRunning) {
            status.setText("✕ PERSISTENCIA DESACTIVADA");
            status.setTextColor(Color.rgb(179, 38, 30));
            statusDetail.setText(
                "Nightzuku tiene guardado el estado «detenido». Así no intentará "
                    + "arrancar automáticamente al reiniciar."
            );
            return;
        }

        if (nightzuku.fgsForeground) {
            status.setText("✓ NIGHTZUKU ARRANCÓ SOLO");
            status.setTextColor(Color.rgb(19, 115, 51));
            statusDetail.setText(
                "Nightzuku confirma desde su propio registro que el servicio persistente "
                    + "entró en primer plano durante este arranque."
            );
            return;
        }

        if (!nightzuku.fgsFailureDetail.isEmpty()) {
            status.setText("✕ NIGHTZUKU INTENTÓ ARRANCAR Y FALLÓ");
            status.setTextColor(Color.rgb(179, 38, 30));
            statusDetail.setText("Fallo registrado: " + nightzuku.fgsFailureDetail);
            return;
        }

        if (!nightzuku.receiverSkipDetail.isEmpty()) {
            status.setText("✕ ARRANQUE RECIBIDO, PERO OMITIDO");
            status.setTextColor(Color.rgb(179, 38, 30));
            statusDetail.setText(
                "Android entregó el evento de arranque, pero Nightzuku lo descartó: "
                    + nightzuku.receiverSkipDetail
            );
            return;
        }

        if (nightzuku.fgsOnStart && !nightzuku.fgsForeground) {
            status.setText("✕ EL SERVICIO INICIÓ, PERO NO QUEDÓ ACTIVO");
            status.setTextColor(Color.rgb(179, 38, 30));
            statusDetail.setText(
                "Nightzuku llegó a ejecutar el servicio, pero no hay registro de que "
                    + "alcanzara el estado persistente de primer plano."
            );
            return;
        }

        if (nightzuku.receiverFgsRequested && !nightzuku.fgsOnStart) {
            status.setText("✕ ANDROID/MAGICOS NO INICIÓ EL SERVICIO");
            status.setTextColor(Color.rgb(179, 38, 30));
            statusDetail.setText(
                "Nightzuku recibió el arranque y pidió iniciar su servicio, pero no "
                    + "hay registro de que Android ejecutara el servicio."
            );
            return;
        }

        if (nightzuku.receiverSeen) {
            status.setText("✕ NIGHTZUKU RECIBIÓ EL ARRANQUE");
            status.setTextColor(Color.rgb(179, 38, 30));
            statusDetail.setText(
                "El evento llegó a Nightzuku, pero la secuencia no alcanzó el servicio persistente. "
                    + "Abrí «Ver detalles técnicos» para ver el último paso registrado."
            );
            return;
        }

        if (nightzuku.jobStart || nightzuku.jobFgsRequested) {
            status.setText("⚠ SE ACTIVÓ EL RESCATE DE NIGHTZUKU");
            status.setTextColor(Color.rgb(176, 96, 0));
            statusDetail.setText(
                "No aparece el arranque normal, pero sí el mecanismo de rescate periódico. "
                    + "Todavía no hay confirmación de servicio persistente activo."
            );
            return;
        }

        status.setText("✕ NIGHTZUKU NO RECIBIÓ EL ARRANQUE");
        status.setTextColor(Color.rgb(179, 38, 30));
        statusDetail.setText(
            "No hay ningún evento de arranque de Nightzuku registrado desde el último "
                + "reinicio. Esto apunta al arranque automático de Android/MagicOS, no al monitor."
        );
    }

    private String time(long millis) {
        if (millis == 0) return "Sin registro";
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
            .format(new Date(millis));
    }

    private String currentTime(long millis, long bootEpoch) {
        return MonitorTimeline.belongsToCurrentBoot(millis, bootEpoch)
            ? time(millis) : "Sin registro de este arranque";
    }

    private String simpleVerdict(MonitorTimeline.Observation state) {
        switch (state) {
            case OBSERVED:
                return "Nightzuku arrancó solo.";
            case NO_ACCESS:
                return "Falta conceder el permiso para observar notificaciones.";
            case WAITING_FOR_LISTENER:
                return "El monitor tiene permiso, pero el observador todavía no está conectado.";
            default:
                return "No se detectó el arranque automático de Nightzuku.";
        }
    }

    private String buildReport() {
        long boot = MonitorStore.bootTime(this);
        long post = MonitorStore.postTime(this);
        long observed = MonitorStore.observedTime(this);
        long connected = MonitorStore.connectedTime(this);
        long removed = MonitorStore.removedTime(this);
        long bootEpoch = systemBootEpoch();
        MonitorTimeline.Observation state = observation(bootEpoch);

        String technicalVerdict;
        switch (state) {
            case OBSERVED:
                technicalVerdict = "Nightzuku publicó su notificación persistente en este arranque.";
                break;
            case NO_ACCESS:
                technicalVerdict = "Falta conceder acceso a notificaciones; no se puede evaluar Nightzuku.";
                break;
            case WAITING_FOR_LISTENER:
                technicalVerdict = "El observador aún no se conectó en este arranque; resultado indeterminado.";
                break;
            default:
                technicalVerdict = "El observador se conectó, pero no observó la notificación de Nightzuku en este arranque.";
        }

        String internal = "";
        if (nightzuku != null && nightzuku.available) {
            internal = "DIAGNÓSTICO INTERNO NIGHTZUKU\n"
                + "Versión: " + nightzuku.versionName + "\n"
                + "Persistencia deseada: " + (nightzuku.desiredRunning ? "Activa" : "Detenida") + "\n"
                + "Blindaje de arranque: " + (nightzuku.hardeningApplied ? "Aplicado" : "No aplicado") + "\n"
                + "Permiso de notificaciones de Nightzuku: "
                    + (nightzuku.notificationsGranted ? "Concedido" : "Sin conceder") + "\n"
                + "Usuario desbloqueado: " + (nightzuku.userUnlocked ? "Sí" : "No") + "\n"
                + "Segundos desde el arranque: " + nightzuku.bootAgeSeconds + "\n"
                + "Receiver de arranque: " + (nightzuku.receiverSeen ? "Sí" : "No") + "\n"
                + "Solicitud FGS: " + (nightzuku.receiverFgsRequested ? "Sí" : "No") + "\n"
                + "FGS onStart: " + (nightzuku.fgsOnStart ? "Sí" : "No") + "\n"
                + "FGS en primer plano: " + (nightzuku.fgsForeground ? "Sí" : "No") + "\n"
                + "Último evento: " + nightzuku.latestEvent + "\n"
                + "Último detalle: " + nightzuku.latestDetail + "\n"
                + "Fallo FGS: " + nightzuku.fgsFailureDetail + "\n"
                + "Fallo recuperación: " + nightzuku.recoveryFailureDetail + "\n"
                + "Traza de este arranque:\n"
                + (nightzuku.trace.isEmpty() ? "(sin eventos)" : nightzuku.trace)
                + "\n\n";
        } else if (nightzuku != null) {
            internal = "DIAGNÓSTICO INTERNO NIGHTZUKU\n"
                + "No disponible: " + nightzuku.error + "\n\n";
        }

        return "MONITOR DE ARRANQUE NIGHTZUKU\n\n"
            + internal
            + "Resultado simple: " + simpleVerdict(state) + "\n"
            + "Resultado técnico: " + technicalVerdict + "\n\n"
            + "Dispositivo: " + Build.MANUFACTURER + " " + Build.MODEL
            + " · Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n"
            + "Acceso a notificaciones: " + (listenerEnabled() ? "Concedido" : "Sin conceder") + "\n"
            + "BOOT_COMPLETED recibido: " + currentTime(boot, bootEpoch) + "\n"
            + "Observador conectado: " + currentTime(connected, bootEpoch) + "\n"
            + "Notificación NightDog publicada: " + currentTime(post, bootEpoch) + "\n"
            + "Notificación observada: " + currentTime(observed, bootEpoch) + "\n"
            + "Notificación retirada: " + currentTime(removed, bootEpoch) + "\n"
            + (MonitorTimeline.belongsToCurrentBoot(post, bootEpoch)
                ? "Demora desde arranque del sistema: "
                    + MonitorTimeline.secondsSinceBoot(post, bootEpoch) + " segundos\n"
                : "")
            + "\nNota: la notificación confirma que se cargó el proceso de Nightzuku y "
            + "arrancó su servicio persistente. La comprobación avanzada de Binder/RISH "
            + "se realiza por separado. Abrir Nightzuku antes de guardar el informe altera la prueba.\n";
    }

    private void saveReport() {
        String name = "NIGHTZUKU-BOOT-MONITOR-"
            + new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date())
            + ".txt";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(
            MediaStore.Downloads.RELATIVE_PATH,
            Environment.DIRECTORY_DOWNLOADS + "/"
        );
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = getContentResolver().insert(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                values
            );
            if (uri == null) throw new IllegalStateException("No se pudo crear el informe");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("No se pudo escribir el informe");
                out.write(buildReport().getBytes(StandardCharsets.UTF_8));
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            Toast.makeText(
                this,
                "Guardado en Descargas: " + name,
                Toast.LENGTH_LONG
            ).show();
        } catch (Exception e) {
            if (uri != null) getContentResolver().delete(uri, null, null);
            Toast.makeText(
                this,
                "Error al guardar: " + e.getMessage(),
                Toast.LENGTH_LONG
            ).show();
        }
    }
}
