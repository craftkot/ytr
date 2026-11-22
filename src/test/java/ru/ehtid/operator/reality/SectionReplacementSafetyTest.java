package ru.ehtid.operator.reality;

import java.util.Map;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SectionReplacementSafetyTest {
    private static GpuTerrainMeshPair.Packed packed() {
        int[] h=new int[1024], halo=new int[32];
        Arrays.fill(h,203); Arrays.fill(halo,203);
        var mesh=PreparedTerrainMesh.compile(
                PreparedTerrainSurface.prepare(-32,-32,913L,1,-64,320,h),
                new PreparedTerrainMesh.Halo(halo,halo,halo,halo));
        return GpuTerrainMeshPair.pack(mesh);
    }
    @Test void negativeSectionCoordinatesUseFloorDivision() {
        var west=new SectionTerrainMesh.Key(-1,12,-1);
        assertEquals(new TerrainRegionPlanner.Key(-32,-32),SectionReplacementSafety.owner(west));
        assertEquals(new TerrainRegionPlanner.Key(0,-32),
                SectionReplacementSafety.owner(new SectionTerrainMesh.Key(0,12,-1)));
        assertEquals(new TerrainRegionPlanner.Key(-64,0),
                SectionReplacementSafety.owner(new SectionTerrainMesh.Key(-3,12,1)));
    }
    @Test void onlyCompleteOwnedPositiveTriangleRangesAreEligible() {
        var p=packed();
        var section=new SectionTerrainMesh.Key(-1,12,-1);
        var tile=new TerrainRegionPlanner.Key(-32,-32);
        assertTrue(SectionReplacementSafety.residentRangesValid(section,tile,
                p.ranges(false),p.ranges(true),p.originalVertices(),p.alternateVertices()));
        assertFalse(SectionReplacementSafety.residentRangesValid(section,
                new TerrainRegionPlanner.Key(0,-32),p.ranges(false),p.ranges(true),
                p.originalVertices(),p.alternateVertices()));
        assertFalse(SectionReplacementSafety.residentRangesValid(section,tile,
                Map.of(),p.ranges(true),p.originalVertices(),p.alternateVertices()));
        assertFalse(SectionReplacementSafety.residentRangesValid(section,tile,
                p.ranges(false),p.ranges(true),0,p.alternateVertices()));
        var invalid=Map.of(section,new GpuTerrainMeshPair.Range(Integer.MAX_VALUE,6));
        assertFalse(SectionReplacementSafety.residentRangesValid(section,tile,
                invalid,p.ranges(true),p.originalVertices(),p.alternateVertices()));
        assertFalse(SectionReplacementSafety.residentRangesValid(section,tile,
                Map.of(section,new GpuTerrainMeshPair.Range(0,2)),p.ranges(true),
                p.originalVertices(),p.alternateVertices()));
    }
}
