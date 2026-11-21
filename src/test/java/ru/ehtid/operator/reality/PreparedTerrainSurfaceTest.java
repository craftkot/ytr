package ru.ehtid.operator.reality;

import java.util.Arrays;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PreparedTerrainSurfaceTest {
    private static int[] original() {
        int[] heights = new int[32*32];
        Arrays.fill(heights, 70);
        return heights;
    }

    @Test void deterministicChangesPreserveTwoCellHaloAcrossAdjacentTiles() {
        int[] heights=original();
        var a=PreparedTerrainSurface.prepare(0,0,913L,1,-64,320,heights);
        var b=PreparedTerrainSurface.prepare(32,0,913L,9,-64,320,heights);
        var repeat=PreparedTerrainSurface.prepare(0,0,913L,1,-64,320,heights);
        assertTrue(a.changedColumns()>0);
        assertTrue(b.changedColumns()>0);
        for(int z=0;z<32;z++) {
            for(int x=0;x<32;x++) {
                assertEquals(a.after(x,z),repeat.after(x,z));
                assertTrue(Math.abs(a.after(x,z)-a.before(x,z))<=4);
                if(x<2||x>=30||z<2||z>=30) {
                    assertEquals(70,a.after(x,z));
                    assertEquals(70,b.after(x,z));
                }
            }
            // The changes cannot create a new cliff on a tile boundary,
            // even when neighboring tiles have different revisions.
            assertEquals(a.after(31,z),b.after(0,z));
            assertEquals(a.after(30,z),b.after(1,z));
        }
    }

    @Test void deferredWorkerUsesFrozenSnapshotAndNeverChangesOriginal() {
        int[] heights=original();
        var tasks=new ArrayDeque<Runnable>();
        var future=PreparedTerrainSurface.prepareAsync(
                -32,32,42,7,-64,320,heights,tasks::add);
        assertFalse(future.isDone());
        Arrays.fill(heights,100);
        tasks.remove().run();
        var variant=future.join();
        assertEquals(70, variant.before(10,10));
        assertTrue(variant.matchesOriginal(original()));
        assertFalse(variant.matchesOriginal(heights));
        assertEquals(7,variant.revision());
        var select=new FrameRealitySelector<PreparedTerrainSurface>();
        select.watch("tile",new FrameRealitySelector.Bounds(-32,60,32,0,90,64));
        select.prepare("tile",7,variant);
        select.sample(b->true);
        var switched=select.sample(b->false).getFirst();
        assertSame(variant,switched.variant());
        assertEquals(1,switched.exitFrame()-switched.previousVisibleFrame());
    }

    @Test void invalidInputsFailBeforeSchedulingAndHeightBoundsHold() {
        int[] heights=original();
        assertThrows(IllegalArgumentException.class,
                ()->PreparedTerrainSurface.prepare(1,0,0,0,-64,320,heights));
        assertThrows(IllegalArgumentException.class,
                ()->PreparedTerrainSurface.prepare(0,0,0,-1,-64,320,heights));
        assertThrows(IllegalArgumentException.class,
                ()->PreparedTerrainSurface.prepare(0,0,0,0,-64,69,heights));
        for(int y:new int[]{-64,320}) {
            Arrays.fill(heights,y);
            var surface=PreparedTerrainSurface.prepare(0,0,0,2,-64,320,heights);
            for(int z=0;z<32;z++) for(int x=0;x<32;x++)
                assertTrue(surface.after(x,z)>=-64&&surface.after(x,z)<=320);
        }
    }
}
