package ru.ehtid.operator.reality;

import java.util.LinkedHashSet;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainRegionPlannerTest {
    @Test void fixtureCameraSelectsBothFormerlyHardcodedTiles() {
        var result=TerrainRegionPlanner.plan(.5D,4.5D,180D,2,2);
        assertEquals(List.of(new TerrainRegionPlanner.Key(0,-32),
                new TerrainRegionPlanner.Key(-32,-32)),result);
        assertEquals("terrain:-32:-32",result.get(1).id());
        assertEquals(result,TerrainRegionPlanner.plan(.5D,4.5D,-180D,2,2));
        assertEquals(List.of(new TerrainRegionPlanner.Key(0,32),
                new TerrainRegionPlanner.Key(-32,32)),
                TerrainRegionPlanner.plan(.5D,4.5D,0D,2,2));
    }

    @Test void movingPlayerAcrossNegativeBoundariesUpdatesWorkingSet() {
        var previous=TerrainRegionPlanner.plan(-.25D,-.25D,180,2,25);
        var next=TerrainRegionPlanner.plan(-32.25D,-.25D,180,2,25);
        var delta=TerrainRegionPlanner.delta(new LinkedHashSet<>(previous),next);
        assertEquals(20,delta.retained().size());
        assertEquals(5,delta.requested().size());
        assertEquals(5,delta.evicted().size());
        assertEquals(new LinkedHashSet<>(next).size(),next.size());
        assertTrue(delta.evicted().stream().allMatch(k->!next.contains(k)));
        assertTrue(delta.requested().stream().allMatch(k->!previous.contains(k)));
    }

    @Test void fullRadiusReturnsAtMost64UniqueRegions() {
        var grid=TerrainRegionPlanner.plan(250.5,-7.5,75,4,64);
        assertEquals(64,grid.size());
        assertEquals(64,new LinkedHashSet<>(grid).size());
        assertEquals(0,TerrainRegionPlanner.delta(new LinkedHashSet<>(grid),grid).requested().size());
        for(var key:grid) {
            assertEquals(0,Math.floorMod(key.x(),32));
            assertEquals(0,Math.floorMod(key.z(),32));
        }
    }

    @Test void worldBorderClipsInsteadOfOverflowing() {
        var grid=TerrainRegionPlanner.plan(29_999_967.5D,-29_999_967.5D,180,4,64);
        assertFalse(grid.isEmpty());
        for(var key:grid) {
            assertTrue(key.x()<=29_999_936);
            assertTrue(key.z()>=-29_999_968);
        }
        assertThrows(IllegalArgumentException.class,
                ()->TerrainRegionPlanner.plan(Double.NaN,0,0,2,2));
        assertThrows(IllegalArgumentException.class,
                ()->TerrainRegionPlanner.plan(0,0,0,5,2));
        assertThrows(IllegalArgumentException.class,
                ()->TerrainRegionPlanner.plan(0,0,0,2,65));
        var duplicated=List.of(new TerrainRegionPlanner.Key(0,0),
                new TerrainRegionPlanner.Key(0,0));
        assertThrows(IllegalArgumentException.class,
                ()->TerrainRegionPlanner.delta(new LinkedHashSet<>(),duplicated));
    }
}
