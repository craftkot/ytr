package ru.ehtid.operator.reality;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Blocks;

/**
 * Server-thread adapter for the strict stone/air voxel transaction proof.
 * Rejects unloaded chunks, unsupported block states and external safety
 * vetoes. Writes use vanilla block updates, NOT a GPU-only replacement.
 *
 * Not suitable for production gameplay until durable WAL, chunk ticketing,
 * entity/collision safety and the full block palette are integrated.
 */
public final class ServerVoxelWorldAccess implements VoxelWorldTransaction.World {
    private static final int SIZE=VoxelSectionSurface.SIZE;
    private final ServerLevel level;
    private final BooleanSupplier safetyGate;
    private final BlockPos.MutableBlockPos cursor=new BlockPos.MutableBlockPos();

    public ServerVoxelWorldAccess(ServerLevel level,BooleanSupplier safetyGate) {
        this.level=Objects.requireNonNull(level);
        this.safetyGate=Objects.requireNonNull(safetyGate);
    }

    @Override public boolean safeToChange(SectionTerrainMesh.Key section) {
        Objects.requireNonNull(section);
        int x=Math.multiplyExact(section.x(),SIZE);
        int y=Math.multiplyExact(section.y(),SIZE);
        int z=Math.multiplyExact(section.z(),SIZE);
        return safetyGate.getAsBoolean()
                && y>=level.getMinY() && (long)y+SIZE<=level.getMaxY()
                && level.hasChunkAt(cursor.set(x,y,z))
                && level.hasChunkAt(cursor.set(x+SIZE-1,y,z+SIZE-1));
    }

    @Override public byte read(int x,int y,int z) {
        cursor.set(x,y,z);
        if(y<level.getMinY() || y>=level.getMaxY() || !level.hasChunkAt(cursor))
            throw new IllegalStateException("Unloaded voxel block");
        var state=level.getBlockState(cursor);
        if(state.isAir())return VoxelSectionSurface.AIR;
        if(state.is(Blocks.STONE))return VoxelSectionSurface.STONE;
        return VoxelSectionSurface.UNSUPPORTED;
    }

    @Override public boolean write(int x,int y,int z,byte material) {
        if(material!=VoxelSectionSurface.AIR && material!=VoxelSectionSurface.STONE)
            return false;
        cursor.set(x,y,z);
        if(y<level.getMinY() || y>=level.getMaxY() || !level.hasChunkAt(cursor))
            return false;
        var previous=level.getBlockState(cursor);
        if(!previous.isAir() && !previous.is(Blocks.STONE))return false;
        var target=(material==VoxelSectionSurface.AIR?Blocks.AIR:Blocks.STONE).defaultBlockState();
        return previous.equals(target) || level.setBlock(cursor,target,3);
    }

    /** Read only on the server thread. All 4096 cells are compared again
     * by VoxelWorldTransaction immediately before mutation. */
    public byte[] snapshot(SectionTerrainMesh.Key section) {
        byte[] out=new byte[VoxelSectionSurface.VOLUME];
        int bx=Math.multiplyExact(section.x(),SIZE);
        int by=Math.multiplyExact(section.y(),SIZE);
        int bz=Math.multiplyExact(section.z(),SIZE);
        for(int y=0;y<SIZE;y++)for(int z=0;z<SIZE;z++)for(int x=0;x<SIZE;x++) {
            byte kind=read(bx+x,by+y,bz+z);
            if(kind==VoxelSectionSurface.UNSUPPORTED)
                throw new IllegalArgumentException("Unsupported block in voxel snapshot");
            out[VoxelSectionSurface.index(x,y,z)]=kind;
        }
        return out;
    }

    /** Captures all six real neighbor faces, rejects missing chunks and
     * preserves their exact occupancy for future geometry construction. */
    public VoxelSectionSurface.Halo halo(SectionTerrainMesh.Key section) {
        int bx=Math.multiplyExact(section.x(),SIZE);
        int by=Math.multiplyExact(section.y(),SIZE);
        int bz=Math.multiplyExact(section.z(),SIZE);
        byte[][] faces=new byte[6][VoxelSectionSurface.FACE_CELLS];
        for(int y=0;y<SIZE;y++)for(int i=0;i<SIZE;i++) {
            faces[0][y*SIZE+i]=read(bx-1,by+y,bz+i);
            faces[1][y*SIZE+i]=read(bx+SIZE,by+y,bz+i);
            faces[4][y*SIZE+i]=read(bx+i,by+y,bz-1);
            faces[5][y*SIZE+i]=read(bx+i,by+y,bz+SIZE);
        }
        for(int z=0;z<SIZE;z++)for(int x=0;x<SIZE;x++) {
            faces[2][z*SIZE+x]=read(bx+x,by-1,bz+z);
            faces[3][z*SIZE+x]=read(bx+x,by+SIZE,bz+z);
        }
        return new VoxelSectionSurface.Halo(
                faces[0],faces[1],faces[2],faces[3],faces[4],faces[5]);
    }
}
