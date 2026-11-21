package ru.ehtid.operator.reality;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;

/**
 * Main-thread double buffer: both immutable CPU mesh variants are compiled
 * before publication. A visibility exit swaps only the active reference.
 * The renderer can read a consistent mesh + proposed top-plane snapshot.
 * No upload, world mutation, or new mesh compilation occurs here.
 */
public final class FrameTerrainBuffers {
    public record Active(long revision, PreparedTerrainMesh.Mesh mesh) {}
    private record Ready(PreparedTerrainMesh mesh, long epoch) {}
    private static final class Tile {
        Active active;
        long latestRevision=-1;
    }
    private final FrameRealitySelector<Ready> selector=new FrameRealitySelector<>();
    private final Map<String,Tile> tiles=new LinkedHashMap<>();
    private long epoch;

    /** Register a tile with its precompiled original geometry. */
    public boolean watch(String id, FrameRealitySelector.Bounds bounds,
                         PreparedTerrainMesh.Mesh original) {
        Objects.requireNonNull(original);
        if (!selector.watch(id,bounds)) return false;
        var tile=new Tile();
        tile.active=new Active(-1,original);
        tiles.put(id,tile);
        return true;
    }

    public long epoch() { return epoch; }

    /**
     * Publish on owning frame thread after worker completion AND checking
     * the input snapshot against current terrain. A late result from a
     * previous world epoch can never replace this world's active mesh.
     */
    public boolean prepared(String id, long workerEpoch, PreparedTerrainMesh mesh,
                            int[] currentHeights) {
        var tile=tiles.get(id);
        if (tile==null || mesh==null || workerEpoch!=epoch
                || mesh.revision()<=tile.latestRevision
                || !mesh.matchesOriginal(currentHeights)) return false;
        // Do not adopt a candidate built from a different active geometry.
        for (int z=0;z<PreparedTerrainSurface.SIZE;z++)
            for (int x=0;x<PreparedTerrainSurface.SIZE;x++)
                if (mesh.original().topY(x,z)!=tile.active.mesh().topY(x,z))
                    return false;
        if (!selector.prepare(id,mesh.revision(),new Ready(mesh,workerEpoch))) return false;
        tile.latestRevision=mesh.revision();
        return true;
    }

    public int sample(Predicate<FrameRealitySelector.Bounds> potentiallyVisible) {
        int count=0;
        for(var selection:selector.sample(potentiallyVisible)) {
            var tile=tiles.get(selection.regionId());
            var ready=selection.variant();
            if(tile==null || ready.epoch()!=epoch) continue;
            tile.active=new Active(selection.revision(),ready.mesh().alternate());
            count++;
        }
        return count;
    }

    public Active active(String id) {
        var tile=tiles.get(id);
        return tile==null?null:tile.active;
    }

    /** Call on world/resource changes or any invalidated preimage. */
    public void clear() {
        epoch++;
        selector.clear();
        tiles.clear();
    }
}
