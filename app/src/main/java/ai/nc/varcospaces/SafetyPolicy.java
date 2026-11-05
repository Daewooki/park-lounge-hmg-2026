package ai.nc.varcospaces;

/** No unknown vehicle state is treated as permission to show parked content. */
public final class SafetyPolicy {
    private SafetyPolicy() {}
    public static boolean mayInteract(boolean foreground, boolean connected, boolean requiresOptimization, int restrictions) {
        return foreground && connected && !requiresOptimization && restrictions == 0;
    }
    public static boolean mayUseParkedContent(boolean foreground, boolean connected, boolean requiresOptimization,
            int restrictions, boolean gearAvailable, boolean parkSelected) {
        return mayInteract(foreground, connected, requiresOptimization, restrictions) && gearAvailable && parkSelected;
    }
    public static boolean isPackagedPath(String path) {
        return path != null && (path.startsWith("web/") || path.startsWith("content/") || path.equals("catalog.json"))
            && !path.contains("..") && !path.contains("\\") && !path.contains("\0") && !path.contains(":") && !path.contains("%");
    }
    public static float volume(double value) {
        return Double.isFinite(value) ? (float)Math.max(0, Math.min(1, value)) : 0;
    }
}
