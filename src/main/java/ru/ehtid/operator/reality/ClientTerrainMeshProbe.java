package ru.ehtid.operator.reality;

import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import ru.ehtid.operator.OperatorMod;

/**
 * CI-only real-client bridge. Snapshot actual loaded client heightmap columns
 * on the client thread (bounded per tick); generate and compile both immutable
 * CPU meshes on a worker. Re-read the entire input including its halo before
 * publication. The real vanilla camera then selects the mesh reference on the
 * first outside-frustum frame. Does not install meshes into the vanilla GPU
 * renderer, mutate block states or change collision.
 */
public final class ClientTerrainMeshProbe {
    private static final boolean ENABLED =
            Boolean.getBoolean("operator.reality.ciClientSmoke")
                    && "true".equals(System.getenv("GITHUB_ACTIONS"));
    private static final int X=0, Z=-32, N=PreparedTerrainSurface.SIZE;
    private static final int TOTAL=N*N+4*N, BUDGET=96;
    private static final String REGION="ci-surface-tile";
    private static final FrameTerrainBuffers BUFFERS=new FrameTerrainBuffers();
    private static ClientLevel level;
    private static int[] capture=new int[TOTAL];
    private static int[] original;
    private static int cursor;
    private static CompletableFuture<PreparedTerrainMesh> pending;
    private static AABB guarded;
    private static boolean armed, switched, returned;
    private static long frame, exitFrame;

    private ClientTerrainMeshProbe() {}

    public static void tick(Minecraft client) {
        if(!ENABLED || !client.isSameThread()) return;
        if(client.level!=level) {
            level=client.level;
            reset();
        }
        if(armed || level==null || client.player==null
                || client.getSingleplayerServer()==null) return;
        // The existing graphical test's initial in-place camera fixture.
        if(Math.abs(client.player.getX()-.5D)>.3D
                || Math.abs(client.player.getZ()-4.5D)>.3D
                || Math.abs(client.player.getY()-200D)>1D
                || Math.abs(Math.abs(client.player.getYRot())-180F)>8F) return;
        if(pending!=null && !pending.isDone()) return;
        if(pending!=null && pending.isCompletedExceptionally()) {
            OperatorMod.LOGGER.error("OPERATOR_TERRAIN_MESH_WORKER_FAILED");
            reset();
            return;
        }
        int end=Math.min(TOTAL,cursor+BUDGET);
        for(;cursor<end;cursor++) {
            int i=cursor, x,z;
            if(i<N*N) {
                x=X+i%N; z=Z+i/N;
            } else {
                int side=(i-N*N)/N, k=(i-N*N)%N;
                x=switch(side) {case 0 -> X-1;case 1 -> X+N;default -> X+k;};
                z=switch(side) {case 2 -> Z-1;case 3 -> Z+N;default -> Z+k;};
            }
            if(!level.hasChunkAt(new BlockPos(x,level.getMinY(),z))) {
                cursor=0;
                return;
            }
            capture[i]=level.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);
        }
        if(cursor!=TOTAL) return;
        if(pending==null) {
            original=capture.clone();
            int[] frozen=original;
            int minY=level.getMinY(),maxY=level.getMaxY();
            pending=CompletableFuture.supplyAsync(()->{
                var surface=PreparedTerrainSurface.prepare(X,Z,913L,1L,minY,maxY,
                        Arrays.copyOf(frozen,N*N));
                var halo=new PreparedTerrainMesh.Halo(
                        Arrays.copyOfRange(frozen,N*N,N*N+N),
                        Arrays.copyOfRange(frozen,N*N+N,N*N+2*N),
                        Arrays.copyOfRange(frozen,N*N+2*N,N*N+3*N),
                        Arrays.copyOfRange(frozen,N*N+3*N,TOTAL));
                return PreparedTerrainMesh.compile(surface,halo);
            });
            cursor=0;
            return;
        }
        if(!Arrays.equals(original,capture)) {
            reset();
            return;
        }
        var pair=pending.join();
        int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE, changed=0;
        for(int z=0;z<N;z++)for(int x=0;x<N;x++) {
            int a=pair.original().topY(x,z), b=pair.alternate().topY(x,z);
            min=Math.min(min,Math.min(a,b));max=Math.max(max,Math.max(a,b));
            if(a!=b)changed++;
        }
        if(changed==0) {
            OperatorMod.LOGGER.error("OPERATOR_TERRAIN_MESH_NO_VARIATION");
            reset(); return;
        }
        var bounds=new FrameRealitySelector.Bounds(X,min-1,Z,X+N,max+1,Z+N);
        guarded=new AABB(X,min-1,Z,X+N,max+1,Z+N).inflate(.25D);
        if(!BUFFERS.watch(REGION,bounds,pair.original())
                || !BUFFERS.prepared(REGION,BUFFERS.epoch(),pair,Arrays.copyOf(capture,N*N))) {
            reset(); return;
        }
        armed=true;
        OperatorMod.LOGGER.info("OPERATOR_TERRAIN_MESH_ARMED columns={} changed={} originalQuads={} alternateQuads={}",
                N*N,changed,pair.original().quadCount(),pair.alternate().quadCount());
    }

    public static void onFrame(Camera camera) {
        if(!ENABLED || !armed || returned || level==null
                || Minecraft.getInstance().level!=level) return;
        frame++;
        if(camera==null || camera.isPanoramicMode()
                || camera.getCapturedFrustum()!=null || camera.getCullFrustum()==null
                || camera.entity()==null) {
            BUFFERS.sample(null); return;
        }
        boolean visible=camera.getCullFrustum().isVisible(guarded);
        var before=BUFFERS.active(REGION);
        long start=System.nanoTime();
        int swaps=BUFFERS.sample(b->visible);
        long duration=System.nanoTime()-start;
        var after=BUFFERS.active(REGION);
        if(!switched && swaps==1 && !visible && before.mesh()!=after.mesh()
                && after.revision()==1) {
            switched=true;exitFrame=frame;
            OperatorMod.LOGGER.info("OPERATOR_TERRAIN_MESH_SWITCH frame={} nanos={} revision={} quads={}",
                    frame,duration,after.revision(),after.mesh().quadCount());
        } else if(switched && visible && after.revision()==1
                && before.mesh()==after.mesh()) {
            returned=true;
            OperatorMod.LOGGER.info("OPERATOR_TERRAIN_MESH_RETURN exitFrame={} returnFrame={} revision={} quads={}",
                    exitFrame,frame,after.revision(),after.mesh().quadCount());
        }
    }

    private static void reset() {
        BUFFERS.clear(); capture=new int[TOTAL];original=null;
        cursor=0;pending=null;guarded=null;
        armed=false;switched=false;returned=false;frame=0;exitFrame=0;
    }
}
