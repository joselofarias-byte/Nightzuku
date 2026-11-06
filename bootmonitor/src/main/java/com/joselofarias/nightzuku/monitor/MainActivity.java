package com.joselofarias.nightzuku.monitor;

import android.app.Activity;
import android.content.ContentValues;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;
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
    private TextView report;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout body = new LinearLayout(this);
        body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(30, 28, 30, 32);
        scroll.addView(body);
        TextView title = new TextView(this);
        title.setText("Monitor de arranque Nightzuku");
        title.setTextSize(24);
        title.setPadding(0, 0, 0, 16);
        body.addView(title);
        TextView guide = new TextView(this);
        guide.setText("Prepará la prueba una vez, reiniciá el teléfono y desbloquealo. Después abrí solo este monitor. No abras Nightzuku antes de guardar el informe.");
        guide.setTextSize(16);
        guide.setPadding(0, 0, 0, 18);
        body.addView(guide);

        button(body, "1. Permitir observar notificaciones", () ->
            startActivity(new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)));
        button(body, "2. Preparar prueba (borrar registro anterior)", () -> {
            MonitorStore.reset(this);
            refresh();
        });
        button(body, "Actualizar informe", this::refresh);
        button(body, "Guardar informe en Descargas", this::saveReport);

        report = new TextView(this);
        report.setTextSize(16);
        report.setTextIsSelectable(true);
        report.setPadding(0, 22, 0, 0);
        body.addView(report);
        setContentView(scroll);
    }

    private void button(LinearLayout parent, String label, Runnable action) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(v -> action.run());
        parent.addView(b);
    }

    @Override public void onResume() {
        super.onResume();
        if (report != null) refresh();
    }

    private boolean listenerEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(getPackageName() + "/");
    }

    private String time(long millis) {
        if (millis == 0) return "Sin registro";
        return DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.MEDIUM)
            .format(new Date(millis));
    }

    private String buildReport() {
        long boot = MonitorStore.bootTime(this);
        long post = MonitorStore.postTime(this);
        long observed = MonitorStore.observedTime(this);
        long connected = MonitorStore.connectedTime(this);
        long removed = MonitorStore.removedTime(this);
        long systemBootEpoch = MonitorTimeline.bootEpoch(
            System.currentTimeMillis(), SystemClock.elapsedRealtime());
        String verdict = boot == 0 ? "Aún no se registró BOOT_COMPLETED en este monitor."
            : !listenerEnabled() ? "Falta conceder acceso a notificaciones; no se puede evaluar Nightzuku."
            : post == 0 ? "El monitor arrancó, pero no observó la notificación persistente de Nightzuku."
            : "Nightzuku publicó su notificación persistente después del reinicio.";
        return "MONITOR DE ARRANQUE NIGHTZUKU\n\n"
            + "Resultado: " + verdict + "\n\n"
            + "Dispositivo: " + Build.MANUFACTURER + " " + Build.MODEL + " · Android " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")\n"
            + "Acceso a notificaciones: " + (listenerEnabled() ? "Concedido" : "Sin conceder") + "\n"
            + "BOOT_COMPLETED recibido: " + time(boot) + "\n"
            + "Observador conectado: " + time(connected) + "\n"
            + "Notificación NightDog publicada: " + time(post) + "\n"
            + "Notificación observada: " + time(observed) + "\n"
            + "Notificación retirada: " + time(removed) + "\n"
            + (MonitorTimeline.belongsToCurrentBoot(post, systemBootEpoch)
                ? "Demora desde arranque del sistema: "
                    + MonitorTimeline.secondsSinceBoot(post, systemBootEpoch) + " segundos\n"
                : "")
            + "\nLa notificación demuestra que se cargó el proceso de Nightzuku y arrancó su servicio persistente. No demuestra por sí sola que el Binder/RISH esté operativo. Abrir Nightzuku antes de guardar el informe altera la prueba.\n";
    }

    private void refresh() { report.setText(buildReport()); }

    private void saveReport() {
        String name = "NIGHTZUKU-BOOT-MONITOR-" +
            new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(new Date()) + ".txt";
        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, name);
        values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
        values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
        values.put(MediaStore.Downloads.IS_PENDING, 1);
        Uri uri = null;
        try {
            uri = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IllegalStateException("No se pudo crear el informe");
            try (OutputStream out = getContentResolver().openOutputStream(uri)) {
                if (out == null) throw new IllegalStateException("No se pudo escribir el informe");
                out.write(buildReport().getBytes(StandardCharsets.UTF_8));
            }
            values.clear();
            values.put(MediaStore.Downloads.IS_PENDING, 0);
            getContentResolver().update(uri, values, null, null);
            Toast.makeText(this, "Guardado en Descargas: " + name, Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            if (uri != null) getContentResolver().delete(uri, null, null);
            Toast.makeText(this, "Error al guardar: " + e.getMessage(), Toast.LENGTH_LONG).show();
        }
    }
}
