package ru.ehtid.operator.reality;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Worker-safe GPU preparation for complete cubic faces, not a heightfield.
 * The renderer thread may upload the returned immutable buffer without
 * touching a live chunk. Position coordinates are section-local [0,16].
 *
 * This is a diagnostic POSITION_COLOR format only. Real gameplay still
 * requires vanilla-equivalent block atlas, lighting and collision handling
 * before any original section can be suppressed.
 */
public final class VoxelSectionGpuPacking {
    public static final int VERTEX_BYTES=GpuTerrainMeshPair.VERTEX_BYTES;
    public static final int MAX_QUADS=VoxelSectionSurface.VOLUME*6;
    public static final int MAX_VERTICES=MAX_QUADS*6;
    private static final int[] TRIANGLES={0,1,2,0,2,3};

    public record Packed(ByteBuffer bytes,int vertexCount,int quadCount) {
        public Packed {
            Objects.requireNonNull(bytes);
            if(vertexCount<=0 || vertexCount>MAX_VERTICES || vertexCount%6!=0
                    || quadCount!=vertexCount/6 || bytes.remaining()!=vertexCount*VERTEX_BYTES
                    || !bytes.isDirect())
                throw new IllegalArgumentException("Invalid voxel GPU buffer");
            bytes=bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        }
        @Override public ByteBuffer bytes() {
            return bytes.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
        }
    }
    private VoxelSectionGpuPacking(){}

    public static Packed pack(VoxelSectionSurface.Mesh mesh) {
        Objects.requireNonNull(mesh);
        int quads=mesh.quads().size();
        if(quads<=0 || quads>MAX_QUADS)
            throw new IllegalArgumentException("Empty or oversized cubic mesh");
        int vertices=Math.multiplyExact(quads,6);
        ByteBuffer out=ByteBuffer.allocateDirect(Math.multiplyExact(vertices,VERTEX_BYTES))
                .order(ByteOrder.nativeOrder());
        for(var quad:mesh.quads()) {
            int[] v=quad.vertices();
            int color=faceShade(quad.face());
            for(int index:TRIANGLES) {
                for(int axis=0;axis<3;axis++)out.putFloat(v[3*index+axis]);
                out.putInt(color);
            }
        }
        return new Packed(out.flip(),vertices,quads);
    }

    /** RGB diagnostic face classification; not a real Minecraft texture. */
    private static int faceShade(VoxelSectionSurface.Face face) {
        return switch(face) {
            case UP -> 0xffd5e9bd;
            case DOWN -> 0xff506e45;
            case WEST,EAST -> 0xff9dbb95;
            case NORTH,SOUTH -> 0xff789b73;
        };
    }
}
