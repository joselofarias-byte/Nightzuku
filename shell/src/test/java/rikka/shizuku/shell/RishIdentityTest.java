package rikka.shizuku.shell;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

public class RishIdentityTest {

    @Test
    public void uniqueUidPrefersOfficialTermux() {
        assertEquals("com.termux",
                RishIdentity.resolveCallingPackage(Collections.singletonList("com.termux"), "com.ignored.override"));
    }

    @Test
    public void uniqueUidPrefersNewTermux() {
        assertEquals("com.newtermux.dev",
                RishIdentity.resolveCallingPackage(Collections.singletonList("com.newtermux.dev"), null));
    }

    @Test
    public void uniqueUidIgnoresPlaceholderEnv() {
        assertEquals("com.newtermux.dev",
                RishIdentity.resolveCallingPackage(Collections.singletonList("com.newtermux.dev"), "PKG"));
    }

    @Test
    public void sharedUidUsesExplicitOverride() {
        List<String> shared = Arrays.asList("com.termux", "com.shared.other");
        assertEquals("com.explicit.override",
                RishIdentity.resolveCallingPackage(shared, "com.explicit.override"));
    }

    @Test
    public void sharedUidUsesNewTermuxOverride() {
        List<String> shared = Arrays.asList("android.uid.system", "com.newtermux.dev");
        assertEquals("com.newtermux.dev",
                RishIdentity.resolveCallingPackage(shared, "com.newtermux.dev"));
    }

    @Test
    public void emptyUidLookupUsesEnv() {
        assertEquals("com.termux",
                RishIdentity.resolveCallingPackage(Collections.emptyList(), "com.termux"));
    }

    @Test
    public void missingEnvOnAmbiguousUidAborts() {
        assertNull(RishIdentity.resolveCallingPackage(Arrays.asList("a.b", "c.d"), null));
        assertNull(RishIdentity.resolveCallingPackage(Collections.emptyList(), ""));
        assertNull(RishIdentity.resolveCallingPackage(null, "PKG"));
        assertNull(RishIdentity.resolveCallingPackage(null, null));
    }
}
