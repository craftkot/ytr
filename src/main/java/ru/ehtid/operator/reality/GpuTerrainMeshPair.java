package ru.ehtid.operator.reality;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import java.util.Map;
import java.util.LinkedHashMap;
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
    private final Map<SectionTerrainMesh.Key,Range> originalRanges,alternateRanges;
    public record Range(int firstVertex,int vertexCount) {}
    private boolean closed;

    private GpuTerrainMeshPair(GpuBuffer original,GpuBuffer alternate,int originalVertices,
                               int alternateVertices,Map<SectionTerrainMesh.Key,Range> originalRanges,
                               Map<SectionTerrainMesh.Key,Range> alternateRanges) {
        this.original=original;this.alternate=alternate;
        this.originalVertices=originalVertices;this.alternateVertices=alternateVertices;
        this.originalRanges=originalRanges;this.alternateRanges=alternateRanges;
    }

    /** Worker-safe preparation: both immutable CPU triangle buffers. */
    public static final class Packed {
        private final ByteBuffer original, alternate;
        private final int originalVertices, alternateVertices;
        private final Map<SectionTerrainMesh.Key,Range> originalRanges,alternateRanges;

        private Packed(ByteBuffer original,ByteBuffer alternate,
                       int originalVertices,int alternateVertices,
                       Map<SectionTerrainMesh.Key,Range> originalRanges,
                       Map<SectionTerrainMesh.Key,Range> alternateRanges) {
            Objects.requireNonNull(original);
            Objects.requireNonNull(alternate);
            if(!original.isDirect()||!alternate.isDirect()
                    ||originalVertices<=0||alternateVertices<=0
                    ||originalVertices>SectionTerrainMesh.MAX_SPLIT_QUADS*6
                    ||alternateVertices>SectionTerrainMesh.MAX_SPLIT_QUADS*6
                    ||original.remaining()!=originalVertices*VERTEX_BYTES
                    ||alternate.remaining()!=alternateVertices*VERTEX_BYTES)
                throw new IllegalArgumentException("Invalid packed GPU mesh");
            this.original=original.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
            this.alternate=alternate.asReadOnlyBuffer().order(ByteOrder.nativeOrder());
            this.originalVertices=originalVertices;
            this.alternateVertices=alternateVertices;
            this.originalRanges=originalRanges;this.alternateRanges=alternateRanges;
        }
        public ByteBuffer original() {return original.asReadOnlyBuffer().order(ByteOrder.nativeOrder());}
        public ByteBuffer alternate() {return alternate.asReadOnlyBuffer().order(ByteOrder.nativeOrder());}
        public int originalVertices() {return originalVertices;}
        public int alternateVertices() {return alternateVertices;}
        public Map<SectionTerrainMesh.Key,Range> ranges(boolean alternative) {
            return alternative?alternateRanges:originalRanges;
        }
    }

    private record PackedVariant(ByteBuffer bytes,int vertices,Map<SectionTerrainMesh.Key,Range> ranges) {}

    public static Packed pack(PreparedTerrainMesh meshes) {
        var a=triangles(SectionTerrainMesh.split(meshes,false));
        var b=triangles(SectionTerrainMesh.split(meshes,true));
        return new Packed(a.bytes(),b.bytes(),a.vertices(),b.vertices(),a.ranges(),b.ranges());
    }

    private static PackedVariant triangles(Map<SectionTerrainMesh.Key,SectionTerrainMesh.Section> sections) {
        int vertices=0;
        for(var section:sections.values()) vertices=Math.addExact(vertices,section.quadCount()*6);
        if(vertices<=0||vertices>SectionTerrainMesh.MAX_SPLIT_QUADS*6)
            throw new IllegalArgumentException("Section vertex budget");
        ByteBuffer out=ByteBuffer.allocateDirect(Math.multiplyExact(vertices,VERTEX_BYTES))
                .order(ByteOrder.nativeOrder());
        Map<SectionTerrainMesh.Key,Range> ranges=new LinkedHashMap<>();
        int[] q=new int[12];
        int[] order={0,1,2,0,2,3};
        for(var entry:sections.entrySet()) {
            var in=entry.getValue().vertices();
            int first=out.position()/VERTEX_BYTES;
            while(in.hasRemaining()) {
                in.get(q);
                for(int i:order) {
                    for(int axis=0;axis<3;axis++) out.putFloat(q[i*3+axis]);
                    // Encode geometry, never revision ID. Height bands and face
                    // orientation expose internal shape even when the outer
                    // silhouette is occluded by the tunnel.
                    int shade=diagnosticShade(q,i);
                    out.put((byte)shade).put((byte)shade).put((byte)shade).put((byte)255);
                }
            }
            ranges.put(entry.getKey(),new Range(first,out.position()/VERTEX_BYTES-first));
        }
        return new PackedVariant(out.flip(),vertices,java.util.Collections.unmodifiableMap(ranges));
    }

    static int diagnosticShade(int[] q,int vertex) {
        int ax=q[3]-q[0],ay=q[4]-q[1],az=q[5]-q[2];
        int bx=q[6]-q[0],by=q[7]-q[1],bz=q[8]-q[2];
        long nx=(long)ay*bz-(long)az*by,ny=(long)az*bx-(long)ax*bz;
        int band=Math.floorMod(q[vertex*3+1],8);
        int direction=ny!=0?14:nx!=0?0:7;
        // All values remain bright enough for the existing cyan/magenta mask.
        return 184+band*7+direction;
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
            return new GpuTerrainMeshPair(a,b,bytes.originalVertices(),bytes.alternateVertices(),
                    bytes.ranges(false),bytes.ranges(true));
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

    /** Immutable section-to-draw-range lookup; no extra GPU allocation. */
    public Map<SectionTerrainMesh.Key,Range> ranges(boolean alternative) {
        return alternative?alternateRanges:originalRanges;
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if(closed)return;
        closed=true;
        try { alternate.close(); } finally { original.close(); }
    }
}
