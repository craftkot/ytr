package ru.ehtid.operator.reality;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;

/**
 * Client-only experimental persistent GPU vertex buffers. Both alternatives
 * are uploaded ahead of the visibility exit. A swap is just pointer selection.
 * The current format is position + RGBA triangles; no vanilla terrain pipeline,
 * textures, lighting or server collision changes are installed by this class.
 * All GPU ownership operations must run on Minecraft's render thread.
 */
public final class GpuTerrainMeshPair implements AutoCloseable {
    public static final int VERTEX_BYTES=3*Float.BYTES+4;
    private final GpuBuffer original,alternate;
    private final int originalVertices,alternateVertices;
    private boolean closed;

    private GpuTerrainMeshPair(GpuBuffer original,GpuBuffer alternate,int originalVertices,
                               int alternateVertices) {
        this.original=original;this.alternate=alternate;
        this.originalVertices=originalVertices;this.alternateVertices=alternateVertices;
    }

    /** Worker-safe preparation: both immutable CPU triangle buffers. */
    public static final class Packed {
        private final ByteBuffer original, alternate;
        private final int originalVertices, alternateVertices;

        private Packed(ByteBuffer original,ByteBuffer alternate,
                       int originalVertices,int alternateVertices) {
            Objects.requireNonNull(original);
            Objects.requireNonNull(alternate);
            if(!original.isDirect()||!alternate.isDirect()
                    ||originalVertices<=0||alternateVertices<=0
                    ||originalVertices>PreparedTerrainMesh.MAX_QUADS*6
                    ||alternateVertices>PreparedTerrainMesh.MAX_QUADS*6
                    ||original.remaining()!=originalVertices*VERTEX_BYTES
                    ||alternate.remaining()!=alternateVertices*VERTEX_BYTES)
                throw new IllegalArgumentException("Invalid packed GPU mesh");
            this.original=original.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
            this.alternate=alternate.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
            this.originalVertices=originalVertices;
            this.alternateVertices=alternateVertices;
        }
        public ByteBuffer original() {return original.asReadOnlyBuffer().order(ByteOrder.nativeOrder());}
        public ByteBuffer alternate() {return alternate.asReadOnlyBuffer().order(ByteOrder.nativeOrder());}
        public int originalVertices() {return originalVertices;}
        public int alternateVertices() {return alternateVertices;}
    }

    public static Packed pack(PreparedTerrainMesh meshes) {
        var a=triangles(meshes.original());
        var b=triangles(meshes.alternate());
        return new Packed(a,b,meshes.original().quadCount()*6,
                meshes.alternate().quadCount()*6);
    }

    private static ByteBuffer triangles(PreparedTerrainMesh.Mesh mesh) {
        ByteBuffer out=ByteBuffer.allocateDirect(mesh.quadCount()*6*VERTEX_BYTES)
                .order(ByteOrder.nativeOrder());
        var in=mesh.vertices();
        int[] q=new int[12];
        int[] order={0,1,2,0,2,3};
        while(in.hasRemaining()) {
            in.get(q);
            for(int i:order) {
                for(int axis=0;axis<3;axis++) out.putFloat(q[i*3+axis]);
                // White vertex color; the render pass selects diagnostic tint.
                out.putInt(0xffffffff);
            }
        }
        return out.flip();
    }

    public static GpuTerrainMeshPair upload(Packed bytes) {
        RenderSystem.assertOnRenderThread();
        GpuBuffer a=null,b=null;
        try {
            a=RenderSystem.getDevice().createBuffer(()->"Operator terrain original",
                    GpuBuffer.USAGE_VERTEX,bytes.original());
            b=RenderSystem.getDevice().createBuffer(()->"Operator terrain alternate",
                    GpuBuffer.USAGE_VERTEX,bytes.alternate());
            if(a.isClosed()||b.isClosed()
                    ||a.size()!=(long)bytes.originalVertices()*VERTEX_BYTES
                    ||b.size()!=(long)bytes.alternateVertices()*VERTEX_BYTES)
                throw new IllegalStateException("Incomplete GPU terrain allocation");
            return new GpuTerrainMeshPair(a,b,bytes.originalVertices(),bytes.alternateVertices());
        } catch(RuntimeException | Error exception) {
            if(b!=null)b.close();
            if(a!=null)a.close();
            throw exception;
        }
    }

    public GpuBuffer buffer(boolean alternative) {
        if(closed)throw new IllegalStateException("Closed terrain buffers");
        return alternative?alternate:original;
    }

    public int vertexCount(boolean alternative) {
        return alternative?alternateVertices:originalVertices;
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if(closed)return;
        closed=true;
        try { alternate.close(); } finally { original.close(); }
    }
}
