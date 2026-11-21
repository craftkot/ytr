package ru.ehtid.operator.reality;

import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import java.util.ArrayDeque;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameTerrainBuffersTest {
    private static int[] flat(int n,int y) {
        int[] a=new int[n]; Arrays.fill(a,y); return a;
    }
    private static PreparedTerrainMesh.Halo halo() {
        int[] border=flat(32,70);
        return new PreparedTerrainMesh.Halo(border,border,border,border);
    }
    private static PreparedTerrainMesh generate(long revision) {
        return PreparedTerrainMesh.compile(
                PreparedTerrainSurface.prepare(0,0,913,revision,-64,320,flat(1024,70)),
                halo());
    }
    private static FrameRealitySelector.Bounds bounds() {
        return new FrameRealitySelector.Bounds(0,60,0,32,90,32);
    }

    @Test void flatOriginalAndAlternateArePrecompiledImmutableBlockMeshes() {
        var pair=generate(1);
        assertEquals(1024,pair.original().quadCount());
        assertTrue(pair.alternate().quadCount()>1024);
        assertTrue(pair.alternate().quadCount()<=PreparedTerrainMesh.MAX_QUADS);
        assertThrows(ReadOnlyBufferException.class,()->pair.alternate().vertices().put(0,99));
        int changed=0;
        for(int z=0;z<32;z++)for(int x=0;x<32;x++) {
            if(pair.alternate().topY(x,z)!=70)changed++;
            if(x<2||x>=30||z<2||z>=30)assertEquals(70,pair.alternate().topY(x,z));
        }
        assertTrue(changed>0);
    }

    @Test void pinnedSharedBoundaryContainsNoDuplicateSideFaces() {
        var pair=generate(1);
        var v=pair.alternate().vertices();
        while(v.remaining()>0) {
            int[] xyz=new int[12];v.get(xyz);
            for(int axis:new int[]{0,2}) {
                boolean allLow=true,allHigh=true;
                for(int i=axis;i<12;i+=3) {
                    allLow&=xyz[i]==0;allHigh&=xyz[i]==32;
                }
                // Flat original neighbor halos mean there can be no vertical
                // wall added on the perimeter of either variant.
                assertFalse(allLow||allHigh);
            }
        }
    }

    @Test void firstHiddenFrameChangesActualMeshReferenceAndKeepsItOnTurnBack() {
        var pair=generate(1);
        var buffers=new FrameTerrainBuffers();
        assertTrue(buffers.watch("r",bounds(),pair.original()));
        assertTrue(buffers.prepared("r",buffers.epoch(),pair,flat(1024,70)));
        var original=buffers.active("r");
        assertEquals(0,buffers.sample(b->true));
        assertSame(original,buffers.active("r"));
        assertEquals(1,buffers.sample(b->false));
        var after=buffers.active("r");
        assertSame(pair.alternate(),after.mesh());
        assertEquals(1,after.revision());
        assertEquals(0,buffers.sample(b->true));
        assertSame(after,buffers.active("r"));
        assertEquals(0,buffers.sample(b->false));
        assertSame(after,buffers.active("r"));
    }

    @Test void staleOrLateWorkerCannotReplaceActiveGeometry() {
        var pair=generate(1);
        var buffers=new FrameTerrainBuffers();
        var tasks=new ArrayDeque<Runnable>();
        var future=PreparedTerrainMesh.prepareAsync(
                PreparedTerrainSurface.prepare(0,0,913,2,-64,320,flat(1024,70)),
                halo(),tasks::add);
        assertFalse(future.isDone());
        assertTrue(buffers.watch("r",bounds(),pair.original()));
        long epoch=buffers.epoch();
        buffers.sample(b->true);
        assertEquals(0,buffers.sample(b->false));
        tasks.remove().run();
        var ready=future.join();
        assertFalse(buffers.prepared("r",epoch,ready,flat(1024,71)));
        assertTrue(buffers.prepared("r",epoch,ready,flat(1024,70)));
        assertEquals(0,buffers.sample(b->false));
        assertSame(pair.original(),buffers.active("r").mesh());
        buffers.clear();
        buffers.watch("r",bounds(),pair.original());
        assertFalse(buffers.prepared("r",epoch,ready,flat(1024,70)));
        assertEquals(0,buffers.sample(b->true));
        assertEquals(0,buffers.sample(b->false));
    }
}
