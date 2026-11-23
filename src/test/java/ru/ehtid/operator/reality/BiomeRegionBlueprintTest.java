package ru.ehtid.operator.reality;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BiomeRegionBlueprintTest {
    private static final String FOREST="minecraft:forest";
    private static final String TUNDRA="minecraft:snowy_taiga";
    private static String[] forest() {
        String[] out=new String[BiomeRegionBlueprint.CELLS];
        Arrays.fill(out,FOREST);
        return out;
    }
    @Test void fullQuartRegionTransitionsToSnowyBiomeButEveryWorldBoundaryIsUntouched() {
        var origin=new BiomeRegionBlueprint.Origin(-32,192,-64);
        var source=forest();
        var plan=BiomeRegionBlueprint.prepare(origin,source,TUNDRA,1,913);
        assertEquals(32D,origin.bounds().maxX()-origin.bounds().minX());
        assertEquals(64D,origin.bounds().maxY()-origin.bounds().minY());
        assertTrue(plan.changedQuartCells()>=4*4*14);
        assertTrue(plan.boundaryUnchanged());
        assertEquals(TUNDRA,plan.after(3,7,4));
        assertEquals(FOREST,plan.before(3,7,4));
        assertEquals(FOREST,plan.after(0,7,4));
        assertEquals(FOREST,plan.after(7,7,4));
        assertEquals(FOREST,plan.after(4,7,0));
        assertEquals(FOREST,plan.after(4,7,7));
        assertEquals(FOREST,plan.after(4,0,4));
        assertEquals(FOREST,plan.after(4,15,4));
        assertTrue(plan.matchesOriginal(source));
        assertFalse(plan.matchesAlternate(source));
        assertEquals(1,plan.revision());
        assertEquals(TUNDRA,plan.target());
    }

    @Test void irregularBorderIsDeterministicAndDoesNotSeverAdjacentRegionSeams() {
        var first=BiomeRegionBlueprint.prepare(
                new BiomeRegionBlueprint.Origin(0,192,0),forest(),TUNDRA,2,12);
        var again=BiomeRegionBlueprint.prepare(
                new BiomeRegionBlueprint.Origin(0,192,0),forest(),TUNDRA,2,12);
        var east=BiomeRegionBlueprint.prepare(
                new BiomeRegionBlueprint.Origin(32,192,0),forest(),TUNDRA,2,12);
        assertArrayEquals(first.alternateSnapshot(),again.alternateSnapshot());
        for(int y=0;y<16;y++)for(int z=0;z<8;z++)
            assertEquals(first.after(7,y,z),east.after(0,y,z));
        int original=BiomeRegionBlueprint.index(3,7,4);
        var edited=first.alternateSnapshot();
        edited[original]=FOREST;
        assertEquals(TUNDRA,first.after(3,7,4));
        assertEquals(FOREST,first.before(3,7,4));
        assertFalse(first.matchesAlternate(edited));
    }

    @Test void unloadedAndPartialBiomeDataFailClosed() {
        var origin=new BiomeRegionBlueprint.Origin(-32,192,-64);
        assertThrows(IllegalArgumentException.class,
                ()->new BiomeRegionBlueprint.Origin(1,192,0));
        assertThrows(IllegalArgumentException.class,
                ()->BiomeRegionBlueprint.prepare(origin,new String[12],TUNDRA,1,1));
        assertThrows(IllegalArgumentException.class,
                ()->BiomeRegionBlueprint.prepare(origin,forest(),"not_a_biome",1,1));
        assertThrows(IllegalArgumentException.class,
                ()->BiomeRegionBlueprint.prepare(origin,forest(),TUNDRA,0,1));
        var bad=forest();
        bad[BiomeRegionBlueprint.index(3,5,4)]=null;
        assertThrows(IllegalArgumentException.class,
                ()->BiomeRegionBlueprint.prepare(origin,bad,TUNDRA,1,1));
        bad[BiomeRegionBlueprint.index(3,5,4)]="minecraft:unregistered biome";
        assertThrows(IllegalArgumentException.class,
                ()->BiomeRegionBlueprint.prepare(origin,bad,TUNDRA,1,1));
        assertThrows(IllegalArgumentException.class,
                ()->BiomeRegionBlueprint.prepare(origin,forest(),FOREST,1,1));
    }
}
