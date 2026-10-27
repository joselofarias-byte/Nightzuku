package rikka.shizuku.shell;

import android.content.Intent;
import android.os.Bundle;

/**
 * Builds {@code IActivityManager.broadcastIntentWithFeature} arguments.
 * <p>
 * Android 16 / SDK 36 still needs REQUEST_BINDER delivered as a broadcast.
 * Keep this packing conservative and explicit: rish requires an unordered,
 * non-sticky broadcast and the real Android user id.
 */
public final class BroadcastIntentArgs {

    private BroadcastIntentArgs() {
    }

    public static java.lang.reflect.Method findBroadcastMethod(Object am) {
        java.lang.reflect.Method best = null;
        for (java.lang.reflect.Method m : am.getClass().getMethods()) {
            if ("broadcastIntentWithFeature".equals(m.getName())) {
                if (best == null || m.getParameterTypes().length > best.getParameterTypes().length) {
                    best = m;
                }
            }
        }
        return best;
    }

    public static boolean isAppOpParameter(Class<?>[] paramTypes, int index) {
        return index + 1 < paramTypes.length && paramTypes[index + 1] == Bundle.class;
    }

    /**
     * Layout used on Android 11+ hidden IActivityManager:
     * caller, featureId, intent, then version-specific trailing args.
     */
    public static Object[] build(Class<?>[] paramTypes, Intent intent, int userId) {
        Object[] args = new Object[paramTypes.length];
        args[0] = null;
        args[1] = null;
        args[2] = intent;

        for (int i = 3; i < paramTypes.length; i++) {
            Class<?> t = paramTypes[i];
            if (t == boolean.class) {
                // Android 16 tail booleans include serialized and sticky.
                // rish must use a normal unordered, non-sticky broadcast.
                args[i] = false;
            } else if (t == int.class) {
                if (i == paramTypes.length - 1) {
                    args[i] = userId;
                } else if (isAppOpParameter(paramTypes, i)) {
                    args[i] = -1;
                } else {
                    args[i] = 0;
                }
            } else if (t == long.class) {
                args[i] = 0L;
            } else {
                args[i] = null;
            }
        }
        return args;
    }
}
