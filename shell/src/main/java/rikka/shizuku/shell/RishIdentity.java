package rikka.shizuku.shell;

import java.util.List;

/**
 * Resolves the terminal package that rish should present to Nightzuku.
 * Runtime identity must stay dynamic: no hardcoded terminal package ids.
 */
public final class RishIdentity {

    private RishIdentity() {
    }

    /**
     * Prefer the unique package for the current UID.
     * Use {@code RISH_APPLICATION_ID} only when UID lookup is empty or shared.
     *
     * @return package name, or {@code null} when the caller must abort
     */
    public static String resolveCallingPackage(List<String> packagesForUid, String envApplicationId) {
        if (packagesForUid != null && packagesForUid.size() == 1) {
            return packagesForUid.get(0);
        }
        if (envApplicationId == null || envApplicationId.isEmpty() || "PKG".equals(envApplicationId)) {
            return null;
        }
        return envApplicationId;
    }
}
