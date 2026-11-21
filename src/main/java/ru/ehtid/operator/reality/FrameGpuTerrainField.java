package ru.ehtid.operator.reality;

import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.IdentityHashMap;
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
        final FrameRealitySelector.Bounds bounds;
        final int x,z;
        Active<R> pending;
        long latestRevision;
        Tile(Active<R> active, FrameRealitySelector.Bounds bounds, PreparedTerrainMesh pair) {
            this.active=active;this.bounds=bounds;x=pair.originX();z=pair.originZ();
            latestRevision=pair.revision();
        }
    }
    private final FrameRealitySelector<Active<R>> selector = new FrameRealitySelector<>();
    private final Map<String,Tile<R>> tiles = new LinkedHashMap<>();
    private final IdentityHashMap<R,Long> owned=new IdentityHashMap<>();
    private final List<R> retired=new ArrayList<>();
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
        if (owned.containsKey(resource) || !fits(bounds,pair)) return false;
        if (!selector.watch(id,bounds)) return false;
        var original=new Active<R>(-1,pair.original(),resource,false);
        var alternate=new Active<R>(pair.revision(),pair.alternate(),resource,true);
        // watch() installed a fresh region; a validated nonnegative mesh
        // revision and nonnull alternate make prepare() unconditional here.
        if (!selector.prepare(id,pair.revision(),alternate))
            throw new IllegalStateException("Fresh terrain region rejected");
        var tile=new Tile<>(original,bounds,pair);
        tile.pending=alternate;
        tiles.put(id,tile);
        owned.put(resource,bytes);
        used+=bytes;
        return true;
    }

    /** Stage a new independently generated revision without replacing the
     * active resource, or any already queued variant. Bounded memory counts
     * active, pending and retired resources until release actually completes.
     * The original conservative watch envelope must cover every later mesh.
     */
    public boolean stage(String id,PreparedTerrainMesh pair,R resource,long bytes) {
        Objects.requireNonNull(pair);Objects.requireNonNull(resource);
        var tile=tiles.get(id);
        if(tile==null || tile.pending!=null || bytes<=0 || bytes>budget-used
                || owned.containsKey(resource) || pair.revision()<=tile.latestRevision
                || pair.originX()!=tile.x || pair.originZ()!=tile.z || !fits(tile.bounds,pair))
            return false;
        var next=new Active<R>(pair.revision(),pair.alternate(),resource,true);
        if(!selector.prepare(id,pair.revision(),next))return false;
        tile.pending=next;tile.latestRevision=pair.revision();
        owned.put(resource,bytes);used+=bytes;
        return true;
    }

    private static boolean fits(FrameRealitySelector.Bounds bounds,PreparedTerrainMesh pair) {
        for(var mesh:new PreparedTerrainMesh.Mesh[]{pair.original(),pair.alternate()}) {
            var vertices=mesh.vertices();
            while(vertices.hasRemaining()) {
                double x=(double)pair.originX()+vertices.get(),y=vertices.get();
                double z=(double)pair.originZ()+vertices.get();
                if(x<bounds.minX()||x>bounds.maxX()||y<bounds.minY()||y>bounds.maxY()
                        ||z<bounds.minZ()||z>bounds.maxZ())return false;
            }
        }
        return true;
    }

    public List<FrameRealitySelector.Selection<Active<R>>> sample(
            Predicate<FrameRealitySelector.Bounds> potentiallyVisible) {
        var selected=selector.sample(potentiallyVisible);
        for (var change:selected) {
            var tile=tiles.get(change.regionId());
            var previous=tile.active.resource();
            tile.active=change.variant();tile.pending=null;
            if(previous!=tile.active.resource())retired.add(previous);
        }
        return selected;
    }

    public Active<R> active(String id) {
        var tile=tiles.get(id); return tile==null?null:tile.active;
    }
    public long usedBytes() { return used; }
    public int tileCount() { return tiles.size(); }

    public boolean hasPending(String id) {
        var tile=tiles.get(id);return tile!=null && tile.pending!=null;
    }
    public int retiredCount() {return retired.size();}

    /** Call only at a renderer-safe retirement point, never on the exit frame.
     * Failed closes retain ownership and budget so they can be retried. */
    public void releaseRetired() {
        RuntimeException failure=null;
        for(var it=retired.iterator();it.hasNext();) {
            R resource=it.next();
            try {resource.close();used-=owned.remove(resource);it.remove();}
            catch(Exception e) {
                if(failure==null)failure=new IllegalStateException("GPU retirement failed",e);
                else failure.addSuppressed(e);
            }
        }
        if(failure!=null)throw failure;
    }

    /** Reset all camera continuity and release every registered resource. */
    @Override public void close() {
        selector.clear();
        RuntimeException failure=null;
        for (var resource:owned.keySet()) {
            try { resource.close(); }
            catch (Exception e) {
                if (failure==null) failure=new IllegalStateException("GPU field release failed",e);
                else failure.addSuppressed(e);
            }
        }
        tiles.clear();owned.clear();retired.clear();
        used=0;
        if (failure!=null) throw failure;
    }
}
