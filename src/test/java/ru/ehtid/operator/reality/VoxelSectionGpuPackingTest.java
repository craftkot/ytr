package ru.ehtid.operator.reality;

import java.nio.ByteOrder;
import java.nio.ReadOnlyBufferException;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoxelSectionGpuPackingTest {
    @Test void sparse3dCavePacksReadOnlyNativeTrianglesWithoutHeightfieldCollapse() {
        byte[] cells=new byte[VoxelSectionSurface.VOLUME];
        cells[VoxelSectionSurface.index(8,6,8)]=VoxelSectionSurface.STONE;
        var mesh=new VoxelSectionSurface(cells,
                VoxelSectionSurface.Halo.filled(VoxelSectionSurface.AIR)).compile();
        var packed=VoxelSectionGpuPacking.pack(mesh);
        assertEquals(6,packed.quadCount());
        assertEquals(36,packed.vertexCount());
        assertEquals(36*16,packed.bytes().remaining());
        var buffer=packed.bytes();
        assertTrue(buffer.isDirect());
        assertTrue(buffer.isReadOnly());
        assertEquals(ByteOrder.nativeOrder(),buffer.order());
        // First WEST face, first three CCW corners.
        float[] expected={8,6,8, 8,6,9, 8,7,9, 8,6,8, 8,7,9, 8,7,8};
        for(int i=0;i<expected.length;i+=3) {
            for(int axis=0;axis<3;axis++)assertEquals(expected[i+axis],buffer.getFloat());
            assertEquals(0xff9dbb95,buffer.getInt());
        }
        assertThrows(ReadOnlyBufferException.class,()->packed.bytes().putInt(0,1));
        assertEquals(0,packed.bytes().position());
    }

    @Test void packedVoxelFaceCountMatchesCompleteSolidSection() {
        byte[] cells=new byte[VoxelSectionSurface.VOLUME];
        java.util.Arrays.fill(cells,VoxelSectionSurface.STONE);
        var mesh=new VoxelSectionSurface(cells,
                VoxelSectionSurface.Halo.filled(VoxelSectionSurface.AIR)).compile();
        var packed=VoxelSectionGpuPacking.pack(mesh);
        assertEquals(6*256,packed.quadCount());
        assertEquals(6*256*6,packed.vertexCount());
        assertEquals(packed.vertexCount()*16,packed.bytes().remaining());
        assertThrows(IllegalArgumentException.class,()->VoxelSectionGpuPacking.pack(
                new VoxelSectionSurface(new byte[VoxelSectionSurface.VOLUME],
                        VoxelSectionSurface.Halo.filled(VoxelSectionSurface.AIR)).compile()));
    }
}
