package ru.ehtid.operator.reality;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameGpuTerrainFieldTest {
    private static final class Resource implements AutoCloseable {
        int closes;
        public void close() { closes++; }
    }
    private static PreparedTerrainMesh pair(int x,long revision) {
        int[] heights=new int[1024], edge=new int[32];
        Arrays.fill(heights,70); Arrays.fill(edge,70);
        return PreparedTerrainMesh.compile(
            PreparedTerrainSurface.prepare(x,0,913,revision,-64,320,heights),
            new PreparedTerrainMesh.Halo(edge,edge,edge,edge));
    }
    private static FrameRealitySelector.Bounds bounds(int x) {
        return new FrameRealitySelector.Bounds(x,60,0,x+32,90,32);
    }
    @Test void independentRegionsSwitchTogetherOnFirstHiddenFrame() {
        try(var field=new FrameGpuTerrainField<Resource>(1000)) {
            var a=pair(0,1); var b=pair(32,2); var c=pair(64,3);
            var ra=new Resource(); var rb=new Resource(); var rc=new Resource();
            assertTrue(field.register("a",bounds(0),a,ra,100));
            assertTrue(field.register("b",bounds(32),b,rb,200));
            assertTrue(field.register("c",bounds(64),c,rc,300));
            assertTrue(field.sample(box->true).isEmpty());
            var selected=field.sample(box->box.minX()==64);
            assertEquals(2,selected.size());
            assertEquals(1,selected.getFirst().exitFrame()-selected.getFirst().previousVisibleFrame());
            assertSame(a.alternate(),field.active("a").mesh());
            assertSame(ra,field.active("a").resource());
            assertTrue(field.active("a").alternate());
            assertSame(b.alternate(),field.active("b").mesh());
            assertSame(c.original(),field.active("c").mesh());
            assertFalse(field.active("c").alternate());
            assertTrue(field.sample(box->true).isEmpty());
            assertTrue(field.active("a").alternate());
            assertEquals(600,field.usedBytes());
        }
    }
    @Test void budgetRejectionAndWorldResetHaveExplicitOwnership() {
        var field=new FrameGpuTerrainField<Resource>(200);
        var pair=pair(0,1); var owned=new Resource(); var rejected=new Resource();
        assertTrue(field.register("a",bounds(0),pair,owned,200));
        assertFalse(field.register("b",bounds(32),pair,rejected,1));
        assertFalse(field.register("a",bounds(0),pair,rejected,1));
        field.close(); field.close();
        assertEquals(1,owned.closes);
        assertEquals(0,rejected.closes);
        assertEquals(0,field.usedBytes()); assertEquals(0,field.tileCount());
        assertTrue(field.register("new",bounds(0),pair,rejected,100));
        assertTrue(field.sample(box->false).isEmpty());
        field.close(); assertEquals(1,rejected.closes);
    }
    @Test void invalidCameraAndLateRegistrationNeverSwitchWhileHidden() {
        try(var field=new FrameGpuTerrainField<Resource>(1000)) {
            var pair=pair(0,1); var resource=new Resource();
            field.sample(box->true);
            assertTrue(field.register("a",bounds(0),pair,resource,100));
            assertFalse(field.register("b",bounds(32),pair,resource,100));
            assertTrue(field.sample(box->false).isEmpty());
            field.sample(box->true); field.sample(null);
            assertTrue(field.sample(box->false).isEmpty());
            field.sample(box->true);
            assertEquals(1,field.sample(box->false).size());
        }
    }
    @Test void manyNewRevisionsKeepActiveUntilNextVisibleToHiddenTransition() {
        try(var field=new FrameGpuTerrainField<Resource>(200)) {
            var current=new Resource();
            assertTrue(field.register("a",bounds(0),pair(0,1),current,100));
            field.sample(box->true);field.sample(box->false);
            for(long revision=2;revision<=65;revision++) {
                var next=new Resource();
                var mesh=pair(0,revision);
                assertTrue(field.stage("a",mesh,next,100));
                assertTrue(field.hasPending("a"));
                assertTrue(field.sample(box->false).isEmpty());
                assertSame(current,field.active("a").resource());
                field.sample(box->true);
                var chosen=field.sample(box->false);
                assertEquals(1,chosen.size());
                assertEquals(revision,chosen.getFirst().revision());
                assertEquals(1,chosen.getFirst().exitFrame()-chosen.getFirst().previousVisibleFrame());
                assertSame(next,field.active("a").resource());
                assertSame(mesh.alternate(),field.active("a").mesh());
                assertEquals(0,current.closes);
                assertEquals(200,field.usedBytes());
                assertFalse(field.stage("a",pair(0,revision+1),new Resource(),1));
                field.releaseRetired();
                assertEquals(1,current.closes);
                assertEquals(100,field.usedBytes());
                assertEquals(0,field.retiredCount());
                current=next;
            }
        }
    }
    @Test void stagingRejectsStaleDuplicateWrongOriginAndUncoveredGeometry() {
        try(var field=new FrameGpuTerrainField<Resource>(500)) {
            var original=new Resource(); var next=new Resource();var rejected=new Resource();
            assertTrue(field.register("a",bounds(0),pair(0,1),original,100));
            assertFalse(field.stage("a",pair(0,2),next,100));
            field.sample(b->true);field.sample(b->false);
            assertFalse(field.stage("a",pair(0,1),next,100));
            assertFalse(field.stage("a",pair(32,2),next,100));
            assertFalse(field.stage("a",pair(0,2),original,100));
            int[] heights=new int[1024],edges=new int[32];
            Arrays.fill(heights,120);Arrays.fill(edges,120);
            var outside=PreparedTerrainMesh.compile(
                    PreparedTerrainSurface.prepare(0,0,913,2,-64,320,heights),
                    new PreparedTerrainMesh.Halo(edges,edges,edges,edges));
            assertFalse(field.stage("a",outside,rejected,100));
            assertTrue(field.stage("a",pair(0,2),next,100));
            assertFalse(field.stage("a",pair(0,3),rejected,100));
            field.sample(b->true);field.sample(null);
            assertTrue(field.sample(b->false).isEmpty());
            assertSame(original,field.active("a").resource());
            assertEquals(0,rejected.closes);
            field.close();
            assertEquals(1,original.closes);assertEquals(1,next.closes);
        }
    }
}
