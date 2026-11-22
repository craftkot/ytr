package ru.ehtid.operator.reality;

import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Region-window + GPU-lifetime coordination. Call on one owning render
 * thread only; asynchronous workers may carry immutable Request tokens,
 * but must publish their completed resources back on that thread.
 *
 * A desired region is not automatically drawn or substituted for vanilla:
 * callers must separately validate actual loaded chunks, complete voxel
 * coverage, visibility and world consistency before masking any section.
 */
public final class DynamicTerrainField<R extends AutoCloseable> implements AutoCloseable {
    private final TerrainRegionWindow desired;
    private final FrameGpuTerrainField<R> resources;

    public DynamicTerrainField(int radius,int slots,long gpuBudget) {
        desired=new TerrainRegionWindow(radius,slots);
        resources=new FrameGpuTerrainField<>(gpuBudget);
    }

    /**
     * Replan from the actual camera; unload unused regions without closing
     * buffers in the middle of rendering. Accounted bytes remain charged
     * until a later safe releaseRetired() call.
     */
    public TerrainRegionWindow.Update onCamera(double x,double z,double yaw) {
        var update=desired.update(x,z,yaw);
        for(var evicted:update.evicted())resources.evict(evicted.key().id());
        return update;
    }

    /**
     * Adopt a complete uploaded pair only if this exact request generation
     * is still current. On false the caller retains and must close resource.
     */
    public boolean publish(TerrainRegionWindow.Request request,
                           FrameRealitySelector.Bounds bounds,
                           PreparedTerrainMesh mesh,R resource,long bytes) {
        Objects.requireNonNull(request);Objects.requireNonNull(bounds);
        Objects.requireNonNull(mesh);Objects.requireNonNull(resource);
        if(!desired.accepts(request)
                || mesh.originX()!=request.key().x()
                || mesh.originZ()!=request.key().z()
                || resources.active(request.key().id())!=null)
            return false;
        return resources.register(request.key().id(),bounds,mesh,resource,bytes);
    }

    public List<FrameRealitySelector.Selection<FrameGpuTerrainField.Active<R>>> sample(
            Predicate<FrameRealitySelector.Bounds> potentiallyVisible) {
        return resources.sample(potentiallyVisible);
    }
    public FrameGpuTerrainField.Active<R> active(TerrainRegionPlanner.Key key) {
        return key==null?null:resources.active(key.id());
    }
    public boolean accepts(TerrainRegionWindow.Request request) {
        return desired.accepts(request);
    }
    public List<TerrainRegionWindow.Request> wanted(){return desired.snapshot();}
    public int residentCount(){return resources.tileCount();}
    public long gpuBytes(){return resources.usedBytes();}
    public int retiredCount(){return resources.retiredCount();}

    /** Caller guarantees that previous render-pass submission has completed. */
    public void releaseRetired(){resources.releaseRetired();}

    @Override public void close() {
        desired.clear();
        resources.close();
    }
}
