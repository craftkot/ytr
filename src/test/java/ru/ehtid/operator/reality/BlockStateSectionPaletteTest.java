package ru.ehtid.operator.reality;

import java.util.Arrays;
import java.util.Map;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BlockStateSectionPaletteTest {
    private static BlockStateSectionPalette.StateKey air(){
        return BlockStateSectionPalette.StateKey.simple("minecraft:air");
    }
    private static BlockStateSectionPalette.StateKey[] filled(BlockStateSectionPalette.StateKey state){
        var result=new BlockStateSectionPalette.StateKey[4096];
        Arrays.fill(result,state);return result;
    }
    private static BlockStateSectionPalette.StateKey[][] halo(BlockStateSectionPalette.StateKey state){
        var result=new BlockStateSectionPalette.StateKey[6][256];
        for(var face:result)Arrays.fill(face,state);
        return result;
    }

    @Test void preservesBlockNamesAndEveryPropertyAcrossFullSectionAndNeighborHalos() {
        var original=filled(air());
        var neighbors=halo(air());
        var eastLog=new BlockStateSectionPalette.StateKey(
                "minecraft:oak_log",Map.of("axis","x"));
        var verticalLog=new BlockStateSectionPalette.StateKey(
                "minecraft:oak_log",Map.of("axis","y"));
        var wetSlab=new BlockStateSectionPalette.StateKey(
                "minecraft:oak_slab",Map.of("type","top","waterlogged","true"));
        original[7]=eastLog;
        original[8]=verticalLog;
        original[9]=wetSlab;
        neighbors[0][1]=BlockStateSectionPalette.StateKey.simple("minecraft:stone");
        var snapshot=BlockStateSectionPalette.capture(original,neighbors);
        assertEquals(5,snapshot.paletteSize());
        assertEquals(4,snapshot.corePaletteSize());
        assertEquals(1,snapshot.countCore(eastLog));
        assertEquals(4093,snapshot.countCore(air()));
        assertEquals(eastLog,snapshot.at(7));
        assertEquals(verticalLog,snapshot.at(8));
        assertEquals(wetSlab,snapshot.at(9));
        assertEquals("minecraft:oak_slab[type=top,waterlogged=true]",wetSlab.canonical());
        assertEquals("minecraft:stone",snapshot.face(0,1).canonical());
        assertEquals(5632*2,snapshot.bytesForCellIndices());
        assertTrue(snapshot.matches(original,neighbors));
    }

    @Test void frozenSnapshotCannotBeAlteredByMutatingInputArraysOrProperties() {
        var original=filled(air());
        var neighbors=halo(air());
        var values=new java.util.HashMap<String,String>();
        values.put("facing","north");
        var stair=new BlockStateSectionPalette.StateKey("minecraft:oak_stairs",values);
        original[30]=stair;
        var snapshot=BlockStateSectionPalette.capture(original,neighbors);
        values.put("facing","east");
        Arrays.fill(original,BlockStateSectionPalette.StateKey.simple("minecraft:stone"));
        Arrays.fill(neighbors[0],BlockStateSectionPalette.StateKey.simple("minecraft:glass"));
        assertEquals("minecraft:oak_stairs[facing=north]",snapshot.at(30).canonical());
        assertEquals(air(),snapshot.face(0,0));
        assertFalse(snapshot.matches(original,neighbors));
        assertThrows(UnsupportedOperationException.class,()->snapshot.palette().clear());
        assertThrows(UnsupportedOperationException.class,
                ()->snapshot.at(30).properties().put("facing","south"));
    }

    @Test void missingNeighborsOrPropertiesAndInvalidIndicesAreRejected() {
        var original=filled(air());
        var neighbors=halo(air());
        original[4]=null;
        assertThrows(NullPointerException.class,
                ()->BlockStateSectionPalette.capture(original,neighbors));
        original[4]=air();
        neighbors[4]=new BlockStateSectionPalette.StateKey[255];
        assertThrows(IllegalArgumentException.class,
                ()->BlockStateSectionPalette.capture(original,neighbors));
        assertThrows(IllegalArgumentException.class,
                ()->new BlockStateSectionPalette.StateKey("malformed",Map.of()));
        assertThrows(IllegalArgumentException.class,
                ()->new BlockStateSectionPalette.StateKey("minecraft:stone",Map.of("?", "no")));
        var complete=halo(air());
        var snapshot=BlockStateSectionPalette.capture(original,complete);
        assertThrows(IndexOutOfBoundsException.class,()->snapshot.at(-1));
        assertThrows(IndexOutOfBoundsException.class,()->snapshot.at(5632));
        assertThrows(IndexOutOfBoundsException.class,()->snapshot.face(6,0));
        assertFalse(snapshot.matches(original,new BlockStateSectionPalette.StateKey[6][255]));
    }

    @Test void snapshotIncludesEveryCoreBlockEvenWhenTheMaterialPaletteIsSmall() {
        var all=filled(BlockStateSectionPalette.StateKey.simple("minecraft:deepslate"));
        var snapshot=BlockStateSectionPalette.capture(all,halo(air()));
        assertEquals(4096,snapshot.countCore(
                BlockStateSectionPalette.StateKey.simple("minecraft:deepslate")));
        assertEquals(2,snapshot.paletteSize());
        assertEquals(1,snapshot.corePaletteSize());
    }
}
