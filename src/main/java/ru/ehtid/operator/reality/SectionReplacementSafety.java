package ru.ehtid.operator.reality;

import java.util.Map;
import java.util.Objects;

/**
 * Fast fail-closed guard before using a prepared terrain section.
 * A GPU range is never proof of complete vanilla geometry or compatible
 * collision. Callers still MUST require a separate world-specific coverage
 * certificate before they may mask a vanilla chunk render.
 */
public final class SectionReplacementSafety {
    private SectionReplacementSafety() {}

    public static TerrainRegionPlanner.Key owner(SectionTerrainMesh.Key key) {
        Objects.requireNonNull(key);
        return new TerrainRegionPlanner.Key(
                Math.multiplyExact(Math.floorDiv(key.x(),2),PreparedTerrainSurface.SIZE),
                Math.multiplyExact(Math.floorDiv(key.z(),2),PreparedTerrainSurface.SIZE));
    }

    public static boolean residentRangesValid(SectionTerrainMesh.Key section,
                                              TerrainRegionPlanner.Key tile,
                                              Map<SectionTerrainMesh.Key,GpuTerrainMeshPair.Range> original,
                                              Map<SectionTerrainMesh.Key,GpuTerrainMeshPair.Range> alternate,
                                              int originalVertices,int alternateVertices) {
        if(section==null || tile==null || original==null || alternate==null
                || originalVertices<=0 || alternateVertices<=0)return false;
        if(!owner(section).equals(tile))return false;
        return range(original.get(section),originalVertices)
                &&range(alternate.get(section),alternateVertices);
    }

    private static boolean range(GpuTerrainMeshPair.Range range,int size) {
        return range!=null && range.firstVertex()>=0 && range.vertexCount()>0
                && range.vertexCount()%3==0
                && (long)range.firstVertex()+range.vertexCount()<=size;
    }
}
