package com.joselofarias.nightzuku.monitor;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;

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
        SharedPreferences p = prefs(c);
        long bootEpoch = MonitorTimeline.bootEpoch(when, SystemClock.elapsedRealtime());
        SharedPreferences.Editor edit = p.edit().putLong(BOOT, when);
        // The listener can observe NightDog before our BOOT_COMPLETED receiver runs.
        if (p.getLong(CONNECTED, 0) < bootEpoch) edit.remove(CONNECTED);
        if (!MonitorTimeline.belongsToCurrentBoot(p.getLong(POSTED, 0), bootEpoch)) {
            edit.remove(POSTED).remove(OBSERVED).remove(REMOVED);
        }
        edit.commit();
    }

    static void listenerConnected(Context c) {
        prefs(c).edit().putLong(CONNECTED, System.currentTimeMillis()).apply();
    }

    static void posted(Context c, long postTime) {
        SharedPreferences p = prefs(c);
        // Use the actual system boot epoch, independent of receiver ordering.
        long bootEpoch = MonitorTimeline.bootEpoch(
            System.currentTimeMillis(), SystemClock.elapsedRealtime());
        if (!MonitorTimeline.belongsToCurrentBoot(postTime, bootEpoch)) return;
        long existing = p.getLong(POSTED, 0);
        if (MonitorTimeline.shouldRecordPost(existing, postTime, bootEpoch)) {
            SharedPreferences.Editor edit = p.edit().putLong(POSTED, postTime)
                .putLong(OBSERVED, System.currentTimeMillis());
            if (!MonitorTimeline.belongsToCurrentBoot(existing, bootEpoch)) edit.remove(REMOVED);
            edit.apply();
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
