package ru.ehtid.operator.reality;

import java.util.Objects;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.buffers.GpuBuffer;

/**
 * A single independently owned resident GPU buffer for an actual 16^3
 * cubic section. Unlike the heightfield pair this includes vertical cave
 * ceilings and internal stone/air walls from a validated voxel snapshot.
 *
 * Proof-only: positional diagnostic colors are NOT vanilla block materials,
 * and owning this buffer does not authorize vanilla section suppression.
 * All methods including close are called on the Minecraft render thread.
 */
public final class GpuVoxelSectionMesh implements AutoCloseable {
    private final GpuBuffer buffer;
    private final int vertexCount;
    private boolean closed;

    private GpuVoxelSectionMesh(GpuBuffer buffer,int vertexCount) {
        this.buffer=Objects.requireNonNull(buffer);
        this.vertexCount=vertexCount;
    }

    public static GpuVoxelSectionMesh upload(VoxelSectionGpuPacking.Packed packed) {
        RenderSystem.assertOnRenderThread();
        Objects.requireNonNull(packed);
        var buffer=RenderSystem.getDevice().createBuffer(
                ()->"Operator voxel section diagnostic",
                GpuBuffer.USAGE_VERTEX,packed.bytes());
        if(buffer.isClosed() || buffer.size()!=(long)packed.vertexCount()*VoxelSectionGpuPacking.VERTEX_BYTES) {
            buffer.close();
            throw new IllegalStateException("Incomplete voxel section GPU upload");
        }
        return new GpuVoxelSectionMesh(buffer,packed.vertexCount());
    }

    public int vertexCount(){return vertexCount;}
    public GpuBuffer buffer(){
        if(closed || buffer.isClosed())throw new IllegalStateException("Closed voxel GPU buffer");
        return buffer;
    }

    @Override public void close() {
        RenderSystem.assertOnRenderThread();
        if(closed)return;
        closed=true;buffer.close();
    }
}
