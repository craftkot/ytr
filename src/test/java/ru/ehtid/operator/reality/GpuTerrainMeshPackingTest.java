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
}
