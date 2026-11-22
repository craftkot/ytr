package ru.ehtid.operator.reality;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoxelSectionSurfaceTest {
    private static final int N=VoxelSectionSurface.SIZE;
    private static byte[] cube(byte kind) {
        byte[] result=new byte[VoxelSectionSurface.VOLUME];
        Arrays.fill(result,kind);return result;
    }
    private static byte[] face(byte kind) {
        byte[] result=new byte[VoxelSectionSurface.FACE_CELLS];
        Arrays.fill(result,kind);return result;
    }

    @Test void solitaryStoneExposesSixDirectionsAndPreservesAir() {
        var cells=cube(VoxelSectionSurface.AIR);
        cells[VoxelSectionSurface.index(8,6,8)]=VoxelSectionSurface.STONE;
        var mesh=new VoxelSectionSurface(cells,VoxelSectionSurface.Halo.filled(VoxelSectionSurface.AIR)).compile();
        assertEquals(1,mesh.stoneCount());
        assertEquals(4095,mesh.airCount());
        assertEquals(6,mesh.quads().size());
        for(var side:VoxelSectionSurface.Face.values())assertEquals(1,mesh.faceCount(side));
    }

    @Test void stonePairsNeverProduceInteriorFaces() {
        var cells=cube(VoxelSectionSurface.AIR);
        cells[VoxelSectionSurface.index(8,6,8)]=VoxelSectionSurface.STONE;
        cells[VoxelSectionSurface.index(9,6,8)]=VoxelSectionSurface.STONE;
        var mesh=new VoxelSectionSurface(cells,VoxelSectionSurface.Halo.filled(VoxelSectionSurface.AIR)).compile();
        assertEquals(10,mesh.quads().size());
        assertEquals(2,mesh.stoneCount());
    }

    @Test void sealedCaveProducesCeilingFloorAndWallsButNoExternalFaces() {
        var cells=cube(VoxelSectionSurface.STONE);
        for(int x=7;x<=9;x++)cells[VoxelSectionSurface.index(x,7,8)]=VoxelSectionSurface.AIR;
        var mesh=new VoxelSectionSurface(cells,VoxelSectionSurface.Halo.filled(VoxelSectionSurface.STONE)).compile();
        assertEquals(4093,mesh.stoneCount());
        assertEquals(14,mesh.quads().size());
        assertEquals(3,mesh.faceCount(VoxelSectionSurface.Face.UP));
        assertEquals(3,mesh.faceCount(VoxelSectionSurface.Face.DOWN));
        assertEquals(3,mesh.faceCount(VoxelSectionSurface.Face.NORTH));
        assertEquals(3,mesh.faceCount(VoxelSectionSurface.Face.SOUTH));
        assertEquals(1,mesh.faceCount(VoxelSectionSurface.Face.WEST));
        assertEquals(1,mesh.faceCount(VoxelSectionSurface.Face.EAST));
    }

    @Test void adjacentChunkBoundaryUsesExactOppositeHaloCells() {
        var west=cube(VoxelSectionSurface.AIR);
        west[VoxelSectionSurface.index(15,7,7)]=VoxelSectionSurface.STONE;
        var east=cube(VoxelSectionSurface.AIR);
        east[VoxelSectionSurface.index(0,7,7)]=VoxelSectionSurface.STONE;
        var neighbor=face(VoxelSectionSurface.AIR);
        neighbor[7*N+7]=VoxelSectionSurface.STONE;
        var air=face(VoxelSectionSurface.AIR);
        var a=new VoxelSectionSurface(west,new VoxelSectionSurface.Halo(
                air,neighbor,air,air,air,air)).compile();
        var b=new VoxelSectionSurface(east,new VoxelSectionSurface.Halo(
                neighbor,air,air,air,air,air)).compile();
        assertEquals(5,a.quads().size());
        assertEquals(5,b.quads().size());
        assertEquals(0,a.faceCount(VoxelSectionSurface.Face.EAST));
        assertEquals(0,b.faceCount(VoxelSectionSurface.Face.WEST));
        assertEquals(10,a.quads().size()+b.quads().size());
    }

    @Test void aSolidSectionProducesOnlySixOuterFacesUnlessNeighborsAreStone() {
        var all=cube(VoxelSectionSurface.STONE);
        var mesh=new VoxelSectionSurface(all,VoxelSectionSurface.Halo.filled(VoxelSectionSurface.AIR)).compile();
        assertEquals(6*N*N,mesh.quads().size());
        for(var side:VoxelSectionSurface.Face.values())assertEquals(N*N,mesh.faceCount(side));
        assertEquals(0,new VoxelSectionSurface(all,VoxelSectionSurface.Halo.filled(
                VoxelSectionSurface.STONE)).compile().quads().size());
    }

    @Test void unsupportedMaterialsAndMissingNeighborsFailClosed() {
        var cells=cube(VoxelSectionSurface.AIR);
        var halo=VoxelSectionSurface.Halo.filled(VoxelSectionSurface.AIR);
        cells[VoxelSectionSurface.index(0,0,0)]=VoxelSectionSurface.UNSUPPORTED;
        assertThrows(IllegalArgumentException.class,()->new VoxelSectionSurface(cells,halo));
        cells[VoxelSectionSurface.index(0,0,0)]=VoxelSectionSurface.AIR;
        var wrong=face(VoxelSectionSurface.AIR);
        wrong[3]=VoxelSectionSurface.UNSUPPORTED;
        assertThrows(IllegalArgumentException.class,
                ()->new VoxelSectionSurface.Halo(wrong,face(VoxelSectionSurface.AIR),
                        face(VoxelSectionSurface.AIR),face(VoxelSectionSurface.AIR),
                        face(VoxelSectionSurface.AIR),face(VoxelSectionSurface.AIR)));
        assertThrows(IllegalArgumentException.class,()->new VoxelSectionSurface(
                new byte[VoxelSectionSurface.VOLUME-1],halo));
        assertThrows(IndexOutOfBoundsException.class,()->VoxelSectionSurface.index(-1,0,0));
        assertThrows(IndexOutOfBoundsException.class,()->VoxelSectionSurface.index(16,0,0));
    }

    @Test void sourceMutationCannotChangeAlreadyPreparedVoxelSnapshot() {
        var cells=cube(VoxelSectionSurface.AIR);
        cells[VoxelSectionSurface.index(1,2,3)]=VoxelSectionSurface.STONE;
        var halo=VoxelSectionSurface.Halo.filled(VoxelSectionSurface.AIR);
        var surface=new VoxelSectionSurface(cells,halo);
        Arrays.fill(cells,VoxelSectionSurface.STONE);
        assertEquals(VoxelSectionSurface.STONE,surface.cell(1,2,3));
        assertEquals(VoxelSectionSurface.AIR,surface.cell(1,2,4));
        assertEquals(6,surface.compile().quads().size());
        assertThrows(UnsupportedOperationException.class,()->surface.compile().quads().clear());
    }
    @Test void everyVoxelFaceProducesOutwardWoundTightUnitCubeGeometry() {
        var core=cube(VoxelSectionSurface.AIR);
        core[VoxelSectionSurface.index(8,6,8)]=VoxelSectionSurface.STONE;
        var mesh=new VoxelSectionSurface(core,VoxelSectionSurface.Halo.filled(VoxelSectionSurface.AIR)).compile();
        for(var quad:mesh.quads()) {
            var v=quad.vertices();
            assertEquals(12,v.length);
            for(int i=0;i<12;i+=3) {
                assertTrue(v[i]>=8 && v[i]<=9);
                assertTrue(v[i+1]>=6 && v[i+1]<=7);
                assertTrue(v[i+2]>=8 && v[i+2]<=9);
            }
            int ax=v[3]-v[0],ay=v[4]-v[1],az=v[5]-v[2];
            int bx=v[6]-v[0],by=v[7]-v[1],bz=v[8]-v[2];
            int nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx;
            int[] normal=switch(quad.face()) {
                case WEST -> new int[]{-1,0,0};
                case EAST -> new int[]{1,0,0};
                case DOWN -> new int[]{0,-1,0};
                case UP -> new int[]{0,1,0};
                case NORTH -> new int[]{0,0,-1};
                case SOUTH -> new int[]{0,0,1};
            };
            assertArrayEquals(normal,new int[]{nx,ny,nz});
        }
    }
}
