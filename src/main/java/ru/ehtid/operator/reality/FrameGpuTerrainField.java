package ru.ehtid.operator.reality;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.List;

/**
 * Render-thread-owned field of independently preloaded terrain alternatives.
 * Registration transfers ownership only on success. Each frame selects both
 * the CPU geometry and already-uploaded resource together, without uploading
 * or compiling anything. The caller owns visibility, snapshot validation and
 * actual renderer integration; this class does not change Minecraft blocks.
 */
public final class FrameGpuTerrainField<R extends AutoCloseable> implements AutoCloseable {
    public static final long DEFAULT_BUDGET = 32L * 1024 * 1024;
    public record Active<R>(long revision, PreparedTerrainMesh.Mesh mesh,
                            R resource, boolean alternate) {}
    private static final class Tile<R> {
        Active<R> active;
        long bytes;
        Tile(Active<R> active, long bytes) { this.active=active; this.bytes=bytes; }
    }
    private final FrameRealitySelector<Active<R>> selector = new FrameRealitySelector<>();
    private final Map<String,Tile<R>> tiles = new LinkedHashMap<>();
    private final long budget;
    private long used;
    public FrameGpuTerrainField(long budget) {
        if (budget<=0) throw new IllegalArgumentException("Invalid GPU field budget");
        this.budget=budget;
    }

    /**
     * Register a complete pair, never a half-uploaded alternative.
     * On false the caller still owns the resource and must release it.
     * Resource identity cannot be shared by multiple tiles.
     */
    public boolean register(String id, FrameRealitySelector.Bounds bounds,
                            PreparedTerrainMesh pair, R resource, long bytes) {
        Objects.requireNonNull(bounds);
        Objects.requireNonNull(pair);
        Objects.requireNonNull(resource);
        if (bytes<=0 || bytes>budget-used || tiles.containsKey(id)) return false;
        for (var tile:tiles.values()) if (tile.active.resource()==resource) return false;
        if (!selector.watch(id,bounds)) return false;
        var original=new Active<R>(-1,pair.original(),resource,false);
        var alternate=new Active<R>(pair.revision(),pair.alternate(),resource,true);
        // watch() installed a fresh region; a validated nonnegative mesh
        // revision and nonnull alternate make prepare() unconditional here.
        if (!selector.prepare(id,pair.revision(),alternate))
            throw new IllegalStateException("Fresh terrain region rejected");
        tiles.put(id,new Tile<>(original,bytes));
        used+=bytes;
        return true;
    }

    public List<FrameRealitySelector.Selection<Active<R>>> sample(
            Predicate<FrameRealitySelector.Bounds> potentiallyVisible) {
        var selected=selector.sample(potentiallyVisible);
        for (var change:selected) tiles.get(change.regionId()).active=change.variant();
        return selected;
    }

    public Active<R> active(String id) {
        var tile=tiles.get(id); return tile==null?null:tile.active;
    }
    public long usedBytes() { return used; }
    public int tileCount() { return tiles.size(); }

    /** Reset all camera continuity and release every registered resource. */
    @Override public void close() {
        selector.clear();
        RuntimeException failure=null;
        for (var tile:tiles.values()) {
            try { tile.active.resource().close(); }
            catch (Exception e) {
                if (failure==null) failure=new IllegalStateException("GPU field release failed",e);
                else failure.addSuppressed(e);
            }
        }
        tiles.clear();
        used=0;
        if (failure!=null) throw failure;
    }
}
