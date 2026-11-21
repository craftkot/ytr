package ru.ehtid.operator.reality;

import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class GpuTerrainMeshPackingTest {
    @Test void triangulationPacksImmutableNativePositionDataBeforeUpload() {
        int[] h=new int[1024], side=new int[32];
        Arrays.fill(h,70);Arrays.fill(side,70);
        var surface=PreparedTerrainSurface.prepare(0,0,913,1,-64,320,h);
        var pair=PreparedTerrainMesh.compile(surface,
                new PreparedTerrainMesh.Halo(side,side,side,side));
        var packed=GpuTerrainMeshPair.pack(pair);
        assertEquals(6144,packed.originalVertices());
        assertEquals(pair.alternate().quadCount()*6,packed.alternateVertices());
        assertEquals(6144*12,packed.original().remaining());
        assertEquals(packed.alternateVertices()*12,packed.alternate().remaining());
        var bytes=packed.original();
        assertTrue(bytes.isDirect());
        assertTrue(bytes.isReadOnly());
        assertEquals(ByteOrder.nativeOrder(),bytes.order());
        assertThrows(ReadOnlyBufferException.class,()->bytes.putFloat(0,42F));
        float[] expected={0,70,0, 0,70,1, 1,70,1,
                          0,70,0, 1,70,1, 1,70,0};
        for(float coordinate:expected) assertEquals(coordinate,bytes.getFloat());
        // Accessors return independent positions over immutable storage.
        assertEquals(0,packed.original().position());
        assertEquals(6144*12,packed.original().remaining());
    }
}
