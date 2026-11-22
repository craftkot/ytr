package ru.ehtid.operator.reality;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.storage.LevelResource;
import ru.ehtid.operator.OperatorMod;

/**
 * CI-only read-only voxel coverage experiment. Captures a real Minecraft
 * section and all six adjacent voxel faces, in bounded client tick slices.
 *
 * Only AIR and STONE are accepted; anything else or an unloaded chunk
 * cancels the experiment rather than inventing missing geometry. After a
 * second full pass confirms byte-for-byte source stability, the immutable
 * snapshot is meshed on a worker thread. No vanilla draw is ever masked.
 */
public final class ClientVoxelSectionProbe {
    private static final boolean ENABLED=Boolean.getBoolean("operator.reality.ciClientSmoke")
            && "true".equals(System.getenv("GITHUB_ACTIONS"));
    private static final SectionTerrainMesh.Key KEY=new SectionTerrainMesh.Key(-2,12,-2);
    private static final int N=VoxelSectionSurface.SIZE;
    private static final int CORE=VoxelSectionSurface.VOLUME;
    private static final int HALO=VoxelSectionSurface.FACE_CELLS;
    private static final int COUNT=CORE+6*HALO;
    private static final int PER_TICK=96;
    private static final byte[] first=new byte[COUNT];
    private static int cursor,verified;
    private static boolean success,failed;
    private static ClientLevel world;
    private static CompletableFuture<VoxelSectionSurface.Mesh> worker;
    private static final BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();

    private ClientVoxelSectionProbe(){}

    public static void tick(Minecraft client) {
        if(!ENABLED || !client.isSameThread())return;
        var server=client.getSingleplayerServer();
        boolean enabled=server!=null && Files.isRegularFile(server.getWorldPath(LevelResource.ROOT)
                .resolve("operator_reality/ci-voxel-section.allow"));
        if(client.level!=world) {
            clear();world=client.level;
        }
        if(!enabled || world==null || success || failed)return;
        if(worker!=null) {
            if(!worker.isDone())return;
            try {
                var mesh=worker.join();
                if(mesh.stoneCount()<200 || mesh.quads().size()<250)
                    throw new IllegalStateException("Insufficient real stone section geometry");
                OperatorMod.LOGGER.info(
                        "OPERATOR_VOXEL_SECTION_REAL_SUCCESS x={} y={} z={} stone={} air={} exposedFaces={} top={} bottom={}",
                        KEY.x(),KEY.y(),KEY.z(),mesh.stoneCount(),mesh.airCount(),
                        mesh.quads().size(),mesh.faceCount(VoxelSectionSurface.Face.UP),
                        mesh.faceCount(VoxelSectionSurface.Face.DOWN));
                success=true;
            } catch(CompletionException | RuntimeException error) {
                fail("WORKER_"+error.getMessage());
            }
            return;
        }
        int end=Math.min(COUNT,(verified==0 && cursor<COUNT?cursor:verified)+PER_TICK);
        if(cursor<COUNT) {
            for(;cursor<end;cursor++) {
                int value=sample(cursor);
                if(value<0) {fail("UNLOADED_OR_UNSUPPORTED_"+cursor);return;}
                first[cursor]=(byte)value;
            }
            return;
        }
        end=Math.min(COUNT,verified+PER_TICK);
        for(;verified<end;verified++) {
            if(sample(verified)!=first[verified]) {fail("CHANGED_"+verified);return;}
        }
        if(verified<COUNT)return;
        byte[] frozen=first.clone();
        worker=CompletableFuture.supplyAsync(()->{
            var mesh=new VoxelSectionSurface(
                    Arrays.copyOfRange(frozen,0,CORE),
                    new VoxelSectionSurface.Halo(
                            Arrays.copyOfRange(frozen,CORE,CORE+HALO),
                            Arrays.copyOfRange(frozen,CORE+HALO,CORE+2*HALO),
                            Arrays.copyOfRange(frozen,CORE+2*HALO,CORE+3*HALO),
                            Arrays.copyOfRange(frozen,CORE+3*HALO,CORE+4*HALO),
                            Arrays.copyOfRange(frozen,CORE+4*HALO,CORE+5*HALO),
                            Arrays.copyOfRange(frozen,CORE+5*HALO,COUNT))).compile();
            return mesh;
        });
    }

    private static int sample(int index) {
        int x,y,z;
        if(index<CORE) {
            x=index%N;
            z=(index/N)%N;
            y=index/(N*N);
        } else {
            int face=(index-CORE)/HALO;
            int i=(index-CORE)%HALO;
            switch(face) {
                case 0 -> {x=-1;y=i/N;z=i%N;}   // west: y,z
                case 1 -> {x=N;y=i/N;z=i%N;}    // east
                case 2 -> {x=i%N;y=-1;z=i/N;}   // down: z,x
                case 3 -> {x=i%N;y=N;z=i/N;}    // up
                case 4 -> {x=i%N;y=i/N;z=-1;}   // north: y,x
                case 5 -> {x=i%N;y=i/N;z=N;}    // south
                default -> throw new IllegalArgumentException("Bad halo face");
            }
        }
        pos.set(KEY.x()*N+x,KEY.y()*N+y,KEY.z()*N+z);
        if(!world.hasChunkAt(pos))return -1;
        var state=world.getBlockState(pos);
        return state.isAir()?VoxelSectionSurface.AIR
                :state.is(Blocks.STONE)?VoxelSectionSurface.STONE:-1;
    }

    private static void fail(String error) {
        failed=true;
        OperatorMod.LOGGER.error("OPERATOR_VOXEL_SECTION_REAL_FAILED {}",error);
    }
    private static void clear() {
        if(worker!=null)worker.cancel(false);
        worker=null;cursor=0;verified=0;success=false;failed=false;
    }
}
