package ru.ehtid.operator.reality;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Frame-exact selection of ALREADY PREPARED biome-region plans.
 *
 * A candidate may be selected only in the very first frame after the
 * complete 3D region was actually visible. The next frame must prove that
 * the ENTIRE inflated volume has left the view frustum and is at least
 * 16 blocks away from the player. No late preparation, camera discontinuity,
 * invalid frustum, NaN coordinate, or stationary hidden region may switch.
 *
 * This controller NEVER writes Minecraft biome storage. The consumer must
 * separately verify latest chunk/biome snapshots, server visibility, other
 * players, boundaries, persistence and climate/terrain correspondence.
 */
public final class OffscreenBiomeTransition {
    public static final int MAX_REGIONS=32;
    public static final double SAFETY_DISTANCE=16D;
    private static final double SAFETY_SQ=SAFETY_DISTANCE*SAFETY_DISTANCE;
    public record Selection(BiomeRegionBlueprint.Origin origin,long frame,
                            long visibleFrame,BiomeRegionBlueprint blueprint) {
        public Selection {
            Objects.requireNonNull(origin);
            Objects.requireNonNull(blueprint);
            if(frame!=visibleFrame+1 || !origin.equals(blueprint.origin()))
                throw new IllegalArgumentException("Invalid offscreen biome selection");
        }
    }
    private static final class Watched {
        final FrameRealitySelector.Bounds bounds;
        long lastVisible=-1;
        long revision=-1;
        long selectedRevision=-1;
        BiomeRegionBlueprint prepared;
        Watched(FrameRealitySelector.Bounds bounds){this.bounds=bounds;}
    }
    private final LinkedHashMap<BiomeRegionBlueprint.Origin,Watched> watched=
            new LinkedHashMap<>();
    private long frame;

    public boolean watch(BiomeRegionBlueprint.Origin origin) {
        Objects.requireNonNull(origin);
        if(watched.containsKey(origin)||watched.size()>=MAX_REGIONS)return false;
        watched.put(origin,new Watched(origin.bounds()));
        return true;
    }

    public boolean prepare(BiomeRegionBlueprint blueprint) {
        Objects.requireNonNull(blueprint);
        Watched region=watched.get(blueprint.origin());
        if(region==null || blueprint.revision()<=region.revision)return false;
        region.revision=blueprint.revision();
        region.prepared=blueprint;
        return true;
    }

    /**
     * The caller MUST feed a conservative camera-frustum test, not a ray or
     * screen-centre check. A true result means even potentially visible, and
     * MUST include a safety margin of at least one block. An invalid camera
     * invalidates continuity and cannot trigger a switch on the next frame.
     */
    public List<Selection> sample(Predicate<FrameRealitySelector.Bounds> visible,
                                  double playerX,double playerY,double playerZ,
                                  boolean validCamera) {
        final long now=++frame;
        if(!validCamera || visible==null
                || !Double.isFinite(playerX)||!Double.isFinite(playerY)
                || !Double.isFinite(playerZ)) {
            resetVisibility();
            return List.of();
        }
        // Fully evaluate predicates before committing ANY regional selection.
        var bits=new boolean[watched.size()];
        int ix=0;
        try {
            for(var region:watched.values()) {
                bits[ix++]=visible.test(region.bounds);
            }
        } catch(RuntimeException badCamera) {
            resetVisibility();
            return List.of();
        }
        var selected=new ArrayList<Selection>();
        ix=0;
        for(var entry:watched.entrySet()) {
            Watched region=entry.getValue();
            boolean isVisible=bits[ix++];
            if(isVisible) {
                region.lastVisible=now;
                continue;
            }
            boolean immediatelyExited=region.lastVisible==now-1
                    && region.lastVisible>=0;
            region.lastVisible=-1;
            if(!immediatelyExited || region.prepared==null
                    || region.revision<=region.selectedRevision
                    || distanceSq(region.bounds,playerX,playerY,playerZ)<SAFETY_SQ)
                continue;
            var plan=region.prepared;
            if(!plan.boundaryUnchanged())continue;
            selected.add(new Selection(entry.getKey(),now,now-1,plan));
            region.selectedRevision=plan.revision();
            region.prepared=null;
        }
        return List.copyOf(selected);
    }

    private static double distanceSq(FrameRealitySelector.Bounds b,
                                     double x,double y,double z) {
        double dx=Math.max(Math.max(b.minX()-x,0D),x-b.maxX());
        double dy=Math.max(Math.max(b.minY()-y,0D),y-b.maxY());
        double dz=Math.max(Math.max(b.minZ()-z,0D),z-b.maxZ());
        return dx*dx+dy*dy+dz*dz;
    }
    private void resetVisibility() {
        for(var r:watched.values())r.lastVisible=-1;
    }
    public long frame(){return frame;}
    public int watching(){return watched.size();}
    public void clear(){watched.clear();frame=0;}
}
