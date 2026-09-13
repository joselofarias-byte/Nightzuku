package moe.shizuku.manager;

import android.app.ActivityThread;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.SharedPreferences;
import android.os.Build;
import android.text.TextUtils;

import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatDelegate;

import java.lang.annotation.Retention;
import java.util.Locale;

import moe.shizuku.manager.utils.EmptySharedPreferencesImpl;
import moe.shizuku.manager.utils.EnvironmentUtils;

import static java.lang.annotation.RetentionPolicy.SOURCE;

public class ShizukuSettings {

    public static final String NAME = "settings";
    public static final String NIGHT_MODE = "night_mode";
    public static final String LANGUAGE = "language";
    public static final String KEEP_START_ON_BOOT = "start_on_boot";
    public static final String ADB_TCP_ENABLED = "adb_tcp_enabled";
    public static final String ADB_TCP_HOST = "adb_tcp_host";
    public static final String ADB_TCP_PORT = "adb_tcp_port";

    private static final String DEFAULT_ADB_TCP_HOST = "127.0.0.1";
    private static final int DEFAULT_ADB_TCP_PORT = 5555;

    private static SharedPreferences sPreferences;

    public static SharedPreferences getPreferences() {
        return sPreferences;
    }

    @NonNull
    private static Context getSettingsStorageContext(@NonNull Context context) {
        Context storageContext;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            storageContext = context.createDeviceProtectedStorageContext();
        } else {
            storageContext = context;
        }

        storageContext = new ContextWrapper(storageContext) {
            @Override
            public SharedPreferences getSharedPreferences(String name, int mode) {
                try {
                    return super.getSharedPreferences(name, mode);
                } catch (IllegalStateException e) {

                    return new EmptySharedPreferencesImpl();
                }
            }
        };

        return storageContext;
    }

    public static void initialize(Context context) {
        if (sPreferences == null) {
            sPreferences = getSettingsStorageContext(context)
                    .getSharedPreferences(NAME, Context.MODE_PRIVATE);
        }
    }

    @IntDef({
            LaunchMethod.UNKNOWN,
            LaunchMethod.ROOT,
            LaunchMethod.ADB,
    })
    @Retention(SOURCE)
    public @interface LaunchMethod {
        int UNKNOWN = -1;
        int ROOT = 0;
        int ADB = 1;
    }

    @LaunchMethod
    public static int getLastLaunchMode() {
        return getPreferences().getInt("mode", LaunchMethod.UNKNOWN);
    }

    public static void setLastLaunchMode(@LaunchMethod int method) {
        getPreferences().edit().putInt("mode", method).apply();
    }

    public static boolean isAdbTcpEnabled() {
        return getPreferences().getBoolean(ADB_TCP_ENABLED, false);
    }

    public static String getAdbTcpHost() {
        return getPreferences().getString(ADB_TCP_HOST, DEFAULT_ADB_TCP_HOST);
    }

    public static int getAdbTcpPort() {
        return getPreferences().getInt(ADB_TCP_PORT, DEFAULT_ADB_TCP_PORT);
    }

    public static boolean setAdbTcpEndpoint(boolean enabled, @NonNull String host, int port) {
        String normalizedHost = host.trim();
        if (normalizedHost.isEmpty() || port < 1 || port > 65535) {
            return false;
        }

        getPreferences().edit()
                .putBoolean(ADB_TCP_ENABLED, enabled)
                .putString(ADB_TCP_HOST, normalizedHost)
                .putInt(ADB_TCP_PORT, port)
                .apply();
        return true;
    }

    public static void setAdbTcpEnabled(boolean enabled) {
        getPreferences().edit().putBoolean(ADB_TCP_ENABLED, enabled).apply();
    }

    @AppCompatDelegate.NightMode
    public static int getNightMode() {
        int defValue = AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM;
        if (EnvironmentUtils.isWatch(ActivityThread.currentActivityThread().getApplication())) {
            defValue = AppCompatDelegate.MODE_NIGHT_YES;
        }
        return getPreferences().getInt(NIGHT_MODE, defValue);
    }

    public static Locale getLocale() {
        String tag = getPreferences().getString(LANGUAGE, null);
        if (TextUtils.isEmpty(tag) || "SYSTEM".equals(tag)) {
            return Locale.getDefault();
        }
        return Locale.forLanguageTag(tag);
    }
}
