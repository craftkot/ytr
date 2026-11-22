package ru.ehtid.operator.reality;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Pure, deterministic camera-relative planning for 32x32 terrain regions.
 * Never reads a Minecraft level or allocates GPU objects. The caller must
 * acquire loaded chunk snapshots, establish safety and publish complete GPU
 * pairs before any vanilla section is eligible for suppression.
 */
public final class TerrainRegionPlanner {
    public static final int TILE_SIZE=PreparedTerrainSurface.SIZE;
    public static final int MAX_RESIDENT=FrameRealitySelector.MAX_WATCHED_REGIONS;
    public static final int MAX_RADIUS=4;
    private static final int MIN_ORIGIN=-29_999_968;
    private static final int MAX_ORIGIN=29_999_936;

    public record Key(int x,int z) {
        public Key {
            if(Math.floorMod(x,TILE_SIZE)!=0 || Math.floorMod(z,TILE_SIZE)!=0
                    || x<MIN_ORIGIN || x>MAX_ORIGIN || z<MIN_ORIGIN || z>MAX_ORIGIN)
                throw new IllegalArgumentException("Invalid tile origin");
        }
        public String id() {return "terrain:"+x+":"+z;}
    }

    public record Delta(List<Key> retained,List<Key> requested,List<Key> evicted) {
        public Delta {
            retained=List.copyOf(retained);
            requested=List.copyOf(requested);
            evicted=List.copyOf(evicted);
        }
    }

    private TerrainRegionPlanner() {}

    /**
     * Snapshot priority is centered one tile ahead of the camera, not a
     * fixed world coordinate. Minecraft yaw 0 looks south (+Z), 180 north.
     * At most 64 regions are returned, even with the full 9x9 search grid.
     */
    public static List<Key> plan(double cameraX,double cameraZ,double yaw,
                                  int radius,int limit) {
        if(!Double.isFinite(cameraX)||!Double.isFinite(cameraZ)||!Double.isFinite(yaw)
                || cameraX<MIN_ORIGIN || cameraZ<MIN_ORIGIN
                || cameraX>=MAX_ORIGIN+TILE_SIZE || cameraZ>=MAX_ORIGIN+TILE_SIZE
                || radius<0 || radius>MAX_RADIUS || limit<1 || limit>MAX_RESIDENT)
            throw new IllegalArgumentException("Invalid camera or planning budget");
        long cx=Math.floorDiv((long)Math.floor(cameraX),TILE_SIZE)*TILE_SIZE;
        long cz=Math.floorDiv((long)Math.floor(cameraZ),TILE_SIZE)*TILE_SIZE;
        double rad=Math.toRadians(Math.IEEEremainder(yaw,360D));
        double frontX=cameraX-Math.sin(rad)*TILE_SIZE;
        double frontZ=cameraZ+Math.cos(rad)*TILE_SIZE;
        ArrayList<Key> found=new ArrayList<>((radius*2+1)*(radius*2+1));
        for(int z=-radius;z<=radius;z++)for(int x=-radius;x<=radius;x++) {
            long px=cx+(long)x*TILE_SIZE,pz=cz+(long)z*TILE_SIZE;
            if(px<MIN_ORIGIN || px>MAX_ORIGIN || pz<MIN_ORIGIN || pz>MAX_ORIGIN)
                continue;
            found.add(new Key((int)px,(int)pz));
        }
        found.sort(Comparator
                .comparingDouble((Key k)->distanceSq(k,frontX,frontZ))
                .thenComparingInt(Key::z).thenComparingInt(Key::x));
        return List.copyOf(found.subList(0,Math.min(limit,found.size())));
    }

    private static double distanceSq(Key key,double x,double z) {
        double dx=key.x()+TILE_SIZE/2D-x,dz=key.z()+TILE_SIZE/2D-z;
        return dx*dx+dz*dz;
    }

    /** Set difference in deterministic order; no implicit GPU eviction. */
    public static Delta delta(Set<Key> resident,List<Key> desired) {
        Objects.requireNonNull(resident);Objects.requireNonNull(desired);
        if(resident.size()>MAX_RESIDENT || desired.size()>MAX_RESIDENT)
            throw new IllegalArgumentException("Terrain residency budget exceeded");
        Set<Key> unique=new LinkedHashSet<>(desired);
        if(unique.size()!=desired.size() || unique.contains(null) || resident.contains(null))
            throw new IllegalArgumentException("Duplicate or null tile");
        ArrayList<Key> retained=new ArrayList<>(),requested=new ArrayList<>(),evicted=new ArrayList<>();
        for(var key:desired) {
            if(resident.contains(key))retained.add(key);
            else requested.add(key);
        }
        for(var key:resident)if(!unique.contains(key))evicted.add(key);
        return new Delta(retained,requested,evicted);
    }
}
