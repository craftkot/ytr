package ru.ehtid.operator.reality;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class DynamicTerrainFieldTest {
    private static final class Buffer implements AutoCloseable {
        int closes;
        @Override public void close(){closes++;}
    }
    private static PreparedTerrainMesh mesh(TerrainRegionPlanner.Key key,long revision) {
        int[] heights=new int[1024],edge=new int[32];
        Arrays.fill(heights,70);Arrays.fill(edge,70);
        return PreparedTerrainMesh.compile(
                PreparedTerrainSurface.prepare(key.x(),key.z(),913L,revision,-64,320,heights),
                new PreparedTerrainMesh.Halo(edge,edge,edge,edge));
    }
    private static FrameRealitySelector.Bounds bounds(TerrainRegionPlanner.Key key) {
        return new FrameRealitySelector.Bounds(key.x(),60,key.z(),
                key.x()+32,90,key.z()+32);
    }
    @Test void cameraMovementEvictsPreloadedResourcesWithoutStaleAsyncAdoption() {
        try(var field=new DynamicTerrainField<Buffer>(2,16,800)) {
            var first=field.onCamera(.5,4.5,180);
            assertEquals(16,first.requested().size());
            var token=first.requested().getFirst();
            var buffer=new Buffer();
            assertTrue(field.publish(token,bounds(token.key()),mesh(token.key(),1),buffer,200));
            assertEquals(1,field.residentCount());
            field.sample(b->true);
            field.sample(b->false);
            var moved=field.onCamera(2048.5,2048.5,180);
            assertTrue(moved.evicted().contains(token));
            assertEquals(0,field.residentCount());
            assertEquals(1,field.retiredCount());
            assertEquals(200,field.gpuBytes());
            assertEquals(0,buffer.closes);
            assertFalse(field.accepts(token));
            var stale=new Buffer();
            assertFalse(field.publish(token,bounds(token.key()),mesh(token.key(),1),stale,200));
            assertEquals(0,stale.closes);
            field.releaseRetired();
            assertEquals(1,buffer.closes);
            assertEquals(0,field.gpuBytes());
            var returned=field.onCamera(.5,4.5,180);
            var reentry=returned.requested().stream()
                    .filter(r->r.key().equals(token.key())).findFirst().orElseThrow();
            assertNotEquals(token.generation(),reentry.generation());
            var newResource=new Buffer();
            assertTrue(field.publish(reentry,bounds(reentry.key()),mesh(reentry.key(),1),newResource,200));
            assertFalse(field.publish(token,bounds(token.key()),mesh(token.key(),1),new Buffer(),200));
            assertSame(newResource,field.active(reentry.key()).resource());
        }
    }
    @Test void rejectingOverBudgetOrWrongTileLeavesCallerOwnership() {
        try(var field=new DynamicTerrainField<Buffer>(1,4,150)) {
            var request=field.onCamera(0,0,180).requested().getFirst();
            var oversize=new Buffer();
            assertFalse(field.publish(request,bounds(request.key()),mesh(request.key(),1),oversize,200));
            var incorrect=new Buffer();
            var other=new TerrainRegionPlanner.Key(request.key().x()+32,request.key().z());
            assertFalse(field.publish(request,bounds(request.key()),mesh(other,1),incorrect,100));
            assertEquals(0,oversize.closes);
            assertEquals(0,incorrect.closes);
            assertEquals(0,field.gpuBytes());
            var valid=new Buffer();
            assertTrue(field.publish(request,bounds(request.key()),mesh(request.key(),1),valid,100));
            assertEquals(100,field.gpuBytes());
        }
    }
}
