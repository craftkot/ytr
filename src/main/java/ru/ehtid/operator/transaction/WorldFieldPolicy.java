package ru.ehtid.operator.transaction;

import java.util.List;
import java.util.Map;

/**
 * Conservative, allocation-light spatial policy shared by the autonomous
 * world-field scheduler and deterministic tests. Coordinates are absolute,
 * never display or fake client coordinates. A plan cannot authorize writes.
 */
public final class WorldFieldPolicy {
    public static final int MAX_SECTORS = 16;
    public static final int SECTOR_WIDTH = 64;
    public static final int HIDDEN_RADIUS = 112;
    public static final long COOLDOWN_TICKS = 1200L;

    private WorldFieldPolicy() { }

    public record Observer(String dimension, double x, double z) { }

    public static String sector(String dimension, int x, int z) {
        if (dimension == null || dimension.isBlank()) throw new IllegalArgumentException("dimension");
        return dimension + "|" + Math.floorDiv(x, SECTOR_WIDTH)
                + "," + Math.floorDiv(z, SECTOR_WIDTH);
    }

    /**
     * A conservative horizontal distance exclusion for EVERY connected
     * player, including spectators. It is not an occlusion proof and cannot
     * guarantee invisibility at arbitrary custom render distances.
     */
    public static boolean nobodyNear(String dimension, int x, int z,
                                     List<Observer> connected) {
        if (dimension == null || connected == null || connected.isEmpty()) return false;
        for (Observer observer : connected) {
            if (observer == null || observer.dimension() == null) return false;
            if (!dimension.equals(observer.dimension())) continue;
            double dx = x + 0.5D - observer.x();
            double dz = z + 0.5D - observer.z();
            if (!Double.isFinite(dx) || !Double.isFinite(dz)
                    || dx * dx + dz * dz < (double) HIDDEN_RADIUS * HIDDEN_RADIUS) return false;
        }
        return true;
    }

    public static boolean maySchedule(Map<String, String> history, String sector,
                                      long now, long previous) {
        return history != null && sector != null && !sector.isBlank()
                && history.size() < MAX_SECTORS && !history.containsKey(sector)
                && now >= 0L && previous <= now
                && now - previous >= COOLDOWN_TICKS;
    }
}
