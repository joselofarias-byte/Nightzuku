package com.joselofarias.nightzuku.monitor;

import android.content.Context;
import android.content.SharedPreferences;

final class MonitorStore {
    static final String TARGET = "com.joselofarias.nightzuku";
    static final int NOTIFICATION_ID = 1360;
    static final String CHANNEL = "nightdog_persistence";
    private static final String PREFS = "boot_monitor";
    private static final String BOOT = "boot_wall_time";
    private static final String CONNECTED = "listener_connected";
    private static final String POSTED = "nightzuku_post_time";
    private static final String OBSERVED = "nightzuku_observed_time";
    private static final String REMOVED = "nightzuku_removed_time";

    private MonitorStore() {}
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences(PREFS, Context.MODE_PRIVATE); }

    static void reset(Context c) {
        prefs(c).edit().clear().commit();
    }

    static void boot(Context c, long when) {
        prefs(c).edit()
            .putLong(BOOT, when)
            .remove(CONNECTED).remove(POSTED).remove(OBSERVED).remove(REMOVED)
            .commit();
    }

    static void listenerConnected(Context c) {
        prefs(c).edit().putLong(CONNECTED, System.currentTimeMillis()).apply();
    }

    static void posted(Context c, long postTime) {
        SharedPreferences p = prefs(c);
        long boot = p.getLong(BOOT, 0);
        // Ignore a notification carried over from before this boot event.
        if (boot == 0 || postTime < boot - 60_000L) return;
        long existing = p.getLong(POSTED, 0);
        if (existing == 0 || postTime < existing) {
            p.edit().putLong(POSTED, postTime)
                .putLong(OBSERVED, System.currentTimeMillis()).apply();
        }
    }

    static void removed(Context c) {
        prefs(c).edit().putLong(REMOVED, System.currentTimeMillis()).apply();
    }

    static long bootTime(Context c) { return prefs(c).getLong(BOOT, 0); }
    static long connectedTime(Context c) { return prefs(c).getLong(CONNECTED, 0); }
    static long postTime(Context c) { return prefs(c).getLong(POSTED, 0); }
    static long observedTime(Context c) { return prefs(c).getLong(OBSERVED, 0); }
    static long removedTime(Context c) { return prefs(c).getLong(REMOVED, 0); }
}
