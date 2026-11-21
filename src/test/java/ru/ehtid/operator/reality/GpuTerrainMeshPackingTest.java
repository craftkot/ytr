package ru.ehtid.operator.reality;

import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuTerrainMeshPackingTest {
    @Test void triangulationPacksImmutableNativePositionColorDataBeforeUpload() {
        int[] h=new int[1024], side=new int[32];
        Arrays.fill(h,70);Arrays.fill(side,70);
        var surface=PreparedTerrainSurface.prepare(0,0,913,1,-64,320,h);
        var pair=PreparedTerrainMesh.compile(surface,
                new PreparedTerrainMesh.Halo(side,side,side,side));
        var packed=GpuTerrainMeshPair.pack(pair);
        assertEquals(6144,packed.originalVertices());
        assertEquals(pair.alternate().quadCount()*6,packed.alternateVertices());
        assertEquals(6144*16,packed.original().remaining());
        assertEquals(packed.alternateVertices()*16,packed.alternate().remaining());
        var bytes=packed.original();
        assertTrue(bytes.isDirect());
        assertTrue(bytes.isReadOnly());
        assertEquals(ByteOrder.nativeOrder(),bytes.order());
        assertThrows(ReadOnlyBufferException.class,()->bytes.putFloat(0,42F));
        float[] expected={0,70,0, 0,70,1, 1,70,1,
                          0,70,0, 1,70,1, 1,70,0};
        for(int i=0;i<expected.length;i+=3) {
            for(int axis=0;axis<3;axis++) assertEquals(expected[i+axis],bytes.getFloat());
            assertEquals(0xffffffff,bytes.getInt());
        }
        // Accessors return independent positions over immutable storage.
        assertEquals(0,packed.original().position());
        assertEquals(6144*16,packed.original().remaining());
    }
    @Test void gpuRangesAreContiguousAndMatchEachSection() {
        int[] h=new int[1024],side=new int[32];
        Arrays.fill(side,20);
        for(int z=0;z<32;z++) for(int x=0;x<32;x++) h[z*32+x]=x<16?65:20;
        var pair=PreparedTerrainMesh.compile(
                PreparedTerrainSurface.prepare(-32,0,913,1,-64,320,h),
                new PreparedTerrainMesh.Halo(side,side,side,side));
        var packed=GpuTerrainMeshPair.pack(pair);
        for(boolean alt:new boolean[]{false,true}) {
            var sections=SectionTerrainMesh.split(pair,alt);
            var ranges=packed.ranges(alt);
            assertEquals(sections.keySet(),ranges.keySet());
            int next=0;
            for(var e:ranges.entrySet()) {
                assertEquals(next,e.getValue().firstVertex());
                assertEquals(sections.get(e.getKey()).quadCount()*6,e.getValue().vertexCount());
                next+=e.getValue().vertexCount();
            }
            assertEquals(alt?packed.alternateVertices():packed.originalVertices(),next);
            assertThrows(UnsupportedOperationException.class,ranges::clear);
        }
    }
}
