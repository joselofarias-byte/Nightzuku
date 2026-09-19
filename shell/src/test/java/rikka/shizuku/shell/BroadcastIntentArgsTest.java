package rikka.shizuku.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.content.Intent;
import android.os.Bundle;

import org.junit.Test;

public class BroadcastIntentArgsTest {

    @SuppressWarnings("unused")
    public static class FakeActivityManager {
        public void otherMethod() {
        }

        public int broadcastIntentWithFeature(Object caller, String featureId, Intent intent) {
            return 0;
        }

        public int broadcastIntentWithFeature(
                Object caller,
                String featureId,
                Intent intent,
                String resolvedType,
                Object finishedReceiver,
                int resultCode,
                String resultData,
                Bundle resultExtras,
                String[] requiredPermissions,
                String[] excludePermissions,
                String[] excludePackages,
                int appOp,
                Bundle options,
                boolean serialized,
                boolean sticky,
                int userId) {
            return 0;
        }
    }

    @Test
    public void prefersLongestBroadcastIntentWithFeatureOverload() {
        java.lang.reflect.Method method = BroadcastIntentArgs.findBroadcastMethod(new FakeActivityManager());
        assertEquals("broadcastIntentWithFeature", method.getName());
        assertEquals(16, method.getParameterTypes().length);
    }

    @Test
    public void packsAndroid16StyleBroadcastArgs() {
        Intent intent = new Intent("rikka.shizuku.intent.action.REQUEST_BINDER");
        Class<?>[] types = BroadcastIntentArgs.findBroadcastMethod(new FakeActivityManager()).getParameterTypes();
        Object[] args = BroadcastIntentArgs.build(types, intent);

        assertEquals(16, args.length);
        assertNull(args[0]);
        assertNull(args[1]);
        assertSame(intent, args[2]);
        assertNull(args[3]);
        assertNull(args[4]);
        assertEquals(0, args[5]);
        assertNull(args[6]);
        assertNull(args[7]);
        assertNull(args[8]);
        assertNull(args[9]);
        assertNull(args[10]);
        assertEquals(-1, args[11]);
        assertNull(args[12]);
        assertEquals(Boolean.TRUE, args[13]);
        assertEquals(Boolean.FALSE, args[14]);
        assertEquals(0, args[15]);
    }

    @Test
    public void appOpIsTheIntImmediatelyBeforeBundle() {
        Class<?>[] types = new Class<?>[]{
                Object.class, String.class, Intent.class, int.class, Bundle.class
        };
        assertTrue(BroadcastIntentArgs.isAppOpParameter(types, 3, 0));
    }

    @Test
    public void secondIntBeforeNonStringIsAppOpFallback() {
        Class<?>[] types = new Class<?>[]{
                Object.class, String.class, Intent.class, int.class, int.class, Object.class
        };
        assertFalse(BroadcastIntentArgs.isAppOpParameter(types, 3, 0));
        assertTrue(BroadcastIntentArgs.isAppOpParameter(types, 4, 1));
    }
}
