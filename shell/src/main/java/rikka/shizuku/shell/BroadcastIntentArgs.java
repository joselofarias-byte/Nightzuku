package rikka.shizuku.shell;

import android.content.Intent;
import android.os.Bundle;

/**
 * Builds {@code IActivityManager.broadcastIntentWithFeature} arguments.
 * <p>
 * Android 16 / SDK 36 still needs REQUEST_BINDER delivered as a broadcast.
 * Starting {@code ShellRequestHandlerActivity} can return without error and
 * never deliver the receiver binder, which is the HONOR 200 ELI-NX9 timeout.
 * Keep this packing conservative: do not replace it with upstream
 * {@code broadcastIntent} or an activity start on SDK 30+.
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

    public static boolean isAppOpParameter(Class<?>[] paramTypes, int index, int intIndex) {
        if (index + 1 < paramTypes.length && paramTypes[index + 1] == Bundle.class) {
            return true;
        }
        return intIndex == 1 && index + 1 < paramTypes.length && paramTypes[index + 1] != String.class;
    }

    /**
     * Layout used on Android 11+ hidden IActivityManager:
     * caller, featureId, intent, then version-specific trailing args.
     */
    public static Object[] build(Class<?>[] paramTypes, Intent intent) {
        Object[] args = new Object[paramTypes.length];
        args[0] = null;
        args[1] = null;
        args[2] = intent;
        int intIndex = 0;
        int booleanIndex = 0;
        for (int i = 3; i < paramTypes.length; i++) {
            Class<?> t = paramTypes[i];
            if (t == boolean.class) {
                args[i] = booleanIndex++ == 0;
            } else if (t == int.class) {
                args[i] = isAppOpParameter(paramTypes, i, intIndex) ? -1 : 0;
                intIndex++;
            } else if (t == long.class) {
                args[i] = 0L;
            } else {
                args[i] = null;
            }
        }
        return args;
    }
}
