package ru.ehtid.operator.reality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Main-thread selection of PREPARED immutable region variants on the first
 * frame in which an entire region leaves the conservative camera frustum.
 *
 * This is intentionally not a server mutation, a permission to write, or
 * a GPU mesh switch. It provides a frame-exact signal for a future renderer
 * and authoritative transaction protocol. No generation or blocking I/O
 * may take place inside sample(). A missed deadline keeps the current variant.
 */
public final class FrameRealitySelector<T> {
    public static final int MAX_WATCHED_REGIONS = 64;

    public record Bounds(double minX, double minY, double minZ,
                         double maxX, double maxY, double maxZ) {
        public Bounds {
            if (!Double.isFinite(minX) || !Double.isFinite(minY) || !Double.isFinite(minZ)
                    || !Double.isFinite(maxX) || !Double.isFinite(maxY) || !Double.isFinite(maxZ)
                    || minX >= maxX || minY >= maxY || minZ >= maxZ)
                throw new IllegalArgumentException("Invalid region bounds");
        }
    }

    public record Selection<T>(String regionId, long revision, T variant,
                               long previousVisibleFrame, long exitFrame) { }

    private static final class Region<T> {
        final Bounds bounds;
        long revision = -1;
        long selectedRevision = -1;
        long lastVisibleFrame = -1;
        T ready;
        Region(Bounds bounds) { this.bounds = bounds; }
    }

    private final Map<String, Region<T>> regions = new LinkedHashMap<>();
    private long frame;

    public boolean watch(String id, Bounds bounds) {
        Objects.requireNonNull(bounds);
        if (id == null || id.isBlank() || regions.containsKey(id)
                || regions.size() >= MAX_WATCHED_REGIONS) return false;
        regions.put(id, new Region<>(bounds));
        return true;
    }

    /** Caller owns the main thread; payload must be published fully prepared and immutable. */
    public boolean prepare(String id, long revision, T variant) {
        var region = regions.get(id);
        if (region == null || revision < 0 || revision <= region.revision || variant == null)
            return false;
        region.revision = revision;
        region.ready = variant;
        return true;
    }

    /**
     * A true predicate means potentially visible (INCLUDING a safety margin).
     * A false predicate must mean the entire AABB is outside the view volume.
     * An invalid view (panorama, captured/debug frustum, unavailable camera)
     * clears visibility continuity; it cannot trigger a delayed transition.
     */
    public List<Selection<T>> sample(Predicate<Bounds> potentiallyVisible) {
        long currentFrame = ++frame;
        if (potentiallyVisible == null) {
            for (var region : regions.values()) region.lastVisibleFrame = -1;
            return List.of();
        }
        // Evaluate the entire bounded set first. A throwing/invalid camera
        // predicate must not select even the first region in a partial frame.
        long visible = 0L;
        int index = 0;
        try {
            for (var region : regions.values()) {
                if (potentiallyVisible.test(region.bounds)) visible |= 1L << index;
                index++;
            }
        } catch (RuntimeException invalidView) {
            for (var region : regions.values()) region.lastVisibleFrame = -1;
            return List.of();
        }
        List<Selection<T>> selections = null;
        index = 0;
        for (var entry : regions.entrySet()) {
            var region = entry.getValue();
            if ((visible & (1L << index++)) != 0) {
                region.lastVisibleFrame = currentFrame;
                continue;
            }
            if (region.lastVisibleFrame == currentFrame - 1
                    && region.lastVisibleFrame >= 0
                    && region.ready != null && region.revision > region.selectedRevision) {
                if (selections == null) selections = new ArrayList<>();
                selections.add(new Selection<>(entry.getKey(), region.revision,
                        region.ready, region.lastVisibleFrame, currentFrame));
                region.selectedRevision = region.revision;
                region.ready = null;
            }
            // No later switch while hidden, including late async preparation.
            region.lastVisibleFrame = -1;
        }
        return selections == null ? List.of() : List.copyOf(selections);
    }

    public long frame() { return frame; }
    public int watchedCount() { return regions.size(); }

    /** World disconnect, dimension change, or resource/geometry invalidation. */
    public void clear() {
        regions.clear();
        frame = 0L;
    }
}
