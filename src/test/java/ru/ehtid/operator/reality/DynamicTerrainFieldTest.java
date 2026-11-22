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
    @Test void rapidPivotKeepsResidentPairsAndSelectsPreparedAlternatesBeforeReturn() {
        try(var field=new DynamicTerrainField<Buffer>(2,4,2000)) {
            var original=field.onCamera(.5,4.5,180).requested();
            assertEquals(4,original.size());
            var buffers=new Buffer[4];
            for(int i=0;i<4;i++) {
                var token=original.get(i);
                buffers[i]=new Buffer();
                assertTrue(field.publish(token,bounds(token.key()),mesh(token.key(),1),buffers[i],100));
            }
            assertEquals(4,field.residentCount());
            assertTrue(field.sample(b->true).isEmpty());
            for(int turn=0;turn<16;turn++) {
                var update=field.onCamera(.5,4.5,turn%2==0?0:180);
                assertTrue(update.requested().isEmpty());
                assertTrue(update.evicted().isEmpty());
                assertEquals(4,field.residentCount());
                assertEquals(400,field.gpuBytes());
                for(int i=0;i<4;i++)
                    assertSame(buffers[i],field.active(original.get(i).key()).resource());
                // An exit on the first turn must still select all four
                // already-prepared resident alternatives without upload.
                boolean nowVisible=turn%2!=0;
                var changes=field.sample(b->nowVisible);
                if(turn==0) {
                    assertEquals(4,changes.size());
                    assertTrue(changes.stream().allMatch(x->x.exitFrame()-x.previousVisibleFrame()==1));
                    for(var token:original)assertTrue(field.active(token.key()).alternate());
                } else assertTrue(changes.isEmpty());
            }
            assertEquals(0,field.retiredCount());
            for(var b:buffers)assertEquals(0,b.closes);
        }
    }
}
