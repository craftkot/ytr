package ru.ehtid.operator.reality;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.client.Camera;
import net.minecraft.world.phys.Vec3;
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
    private record Prepared(VoxelSectionSurface.Mesh mesh,VoxelSectionGpuPacking.Packed packed) {}
    private static CompletableFuture<Prepared> worker;
    private static Prepared ready;
    private static GpuVoxelSectionMesh resident;
    private static Vec3 drawEye;
    private static boolean drawRequested;
    private static long frame,lastDrawFrame=-1;
    private static final BlockPos.MutableBlockPos pos=new BlockPos.MutableBlockPos();

    private ClientVoxelSectionProbe(){}

    public static void tick(Minecraft client) {
        if(!ENABLED || !client.isSameThread())return;
        var server=client.getSingleplayerServer();
        var root=server==null?null:server.getWorldPath(LevelResource.ROOT)
                .resolve("operator_reality");
        boolean enabled=root!=null && Files.isRegularFile(root.resolve("ci-voxel-section.allow"));
        drawRequested=enabled && Files.isRegularFile(root.resolve("ci-voxel-draw.allow"));
        if(client.level!=world) {
            clear();world=client.level;
        }
        if(!enabled || world==null || success || failed)return;
        if(worker!=null) {
            if(!worker.isDone())return;
            try {
                var prepared=worker.join();
                var mesh=prepared.mesh();
                if(mesh.stoneCount()<200 || mesh.quads().size()<250)
                    throw new IllegalStateException("Insufficient real stone section geometry");
                OperatorMod.LOGGER.info(
                        "OPERATOR_VOXEL_SECTION_REAL_SUCCESS x={} y={} z={} stone={} air={} exposedFaces={} top={} bottom={}",
                        KEY.x(),KEY.y(),KEY.z(),mesh.stoneCount(),mesh.airCount(),
                        mesh.quads().size(),mesh.faceCount(VoxelSectionSurface.Face.UP),
                        mesh.faceCount(VoxelSectionSurface.Face.DOWN));
                ready=prepared;
                success=true;
            } catch(RuntimeException error) {
                fail("WORKER_"+error.getMessage());
            }
            return;
        }
        int end=Math.min(COUNT,cursor+PER_TICK);
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
            return new Prepared(mesh,VoxelSectionGpuPacking.pack(mesh));
        });
    }

    /** Upload once on a real extraction frame. Never read a live block,
     * compute triangles or suppress vanilla geometry in this code path. */
    public static void onFrame(Camera camera) {
        if(!ENABLED || !RenderSystem.isOnRenderThread())return;
        frame++;drawEye=null;
        if(Minecraft.getInstance().level!=world)return;
        if(resident==null && ready!=null) {
            resident=GpuVoxelSectionMesh.upload(ready.packed());
            OperatorMod.LOGGER.info("OPERATOR_VOXEL_GPU_UPLOADED section={},{},{} vertices={} bytes={}",
                    KEY.x(),KEY.y(),KEY.z(),resident.vertexCount(),
                    (long)resident.vertexCount()*VoxelSectionGpuPacking.VERTEX_BYTES);
            ready=null;
        }
        if(drawRequested && resident!=null && camera!=null
                && !camera.isPanoramicMode() && camera.getCapturedFrustum()==null
                && camera.getCullFrustum()!=null) drawEye=camera.position();
    }

    public static void draw(RenderPass pass) {
        if(!ENABLED || !drawRequested || resident==null || drawEye==null
                || lastDrawFrame==frame)return;
        lastDrawFrame=frame;
        TerrainDiagnosticDraw.drawVoxel(pass,resident,KEY,drawEye,false);
        OperatorMod.LOGGER.info("OPERATOR_VOXEL_GPU_DRAW frame={} section={},{},{} vertices={}",
                frame,KEY.x(),KEY.y(),KEY.z(),resident.vertexCount());
    }

    public static void closeGpu() {
        if(!ENABLED || !RenderSystem.isOnRenderThread())return;
        if(resident!=null)resident.close();
        resident=null;drawEye=null;drawRequested=false;ready=null;lastDrawFrame=-1;
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
        if(RenderSystem.isOnRenderThread())closeGpu();
    }
}
