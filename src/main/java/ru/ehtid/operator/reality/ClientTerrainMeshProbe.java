package ru.ehtid.operator.reality;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.nio.file.Files;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.Vec3;
import com.mojang.renderpearl.api.commands.RenderPass;
import java.util.concurrent.CompletableFuture;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.AABB;
import ru.ehtid.operator.OperatorMod;

/**
 * CI-only, real heightmap and GPU allocation test for two adjacent terrain
 * tiles. Bounded snapshot reads on the owning client thread, immutable mesh
 * preparation away from it, then at most one GPU upload per visible frame.
 * The vanilla camera selects all ready regions in one frame pass.
 * This does not replace vanilla rendering or change server blocks/collision.
 */
public final class ClientTerrainMeshProbe {
    private static final boolean ENABLED =
            Boolean.getBoolean("operator.reality.ciClientSmoke")
                    && "true".equals(System.getenv("GITHUB_ACTIONS"));
    private static final int N=PreparedTerrainSurface.SIZE;
    private static final int TOTAL=N*N+4*N, BUDGET=96;
    private static final FrameGpuTerrainField<GpuTerrainMeshPair> FIELD=
            new FrameGpuTerrainField<>(FrameGpuTerrainField.DEFAULT_BUDGET);
    private static ClientLevel level;
    private static Tile[] tiles=newTiles();
    private static int next;
    private static boolean armed,preloaded,switched,returned;
    private static long frame,exitFrame,returnFrame;
    private static boolean autoReady,autoSubmitted;
    private static long repeatTarget=2,repeatArmed,repeatExit,repeatReturn,repeatDrawn;
    private static boolean repeatWaitingReturn,repeatBaselineDrawn;
    private static boolean debugDraw, depthDraw;
    private static Vec3 drawEye;
    private static int lastDrawPhase=-1;
    private static boolean lastDrawDepth;
    // Only explicit sections of the disposable CI fixture may be suppressed.
    private static final SectionTerrainMesh.Key TEST_SECTION=new SectionTerrainMesh.Key(-1,12,-1);
    private static final SectionTerrainMesh.Key EAST_SECTION=new SectionTerrainMesh.Key(0,12,-1);
    private static final List<SectionTerrainMesh.Key> SINGLE=List.of(TEST_SECTION);
    private static final List<SectionTerrainMesh.Key> BOTH=List.of(TEST_SECTION,EAST_SECTION);
    private record Replacement(Tile tile,FrameGpuTerrainField.Active<GpuTerrainMeshPair> active) {}
    private static final Map<SectionTerrainMesh.Key,Replacement> replacements=new LinkedHashMap<>();
    private static final Set<SectionTerrainMesh.Key> masked=new LinkedHashSet<>();
    private static final BlockPos.MutableBlockPos sectionCheck=new BlockPos.MutableBlockPos();
    private static int replacementMode,lastReplacementMode,suppressed;
    private static boolean replacementDrawn;
    private static Vec3 replacementEye;

    private record Prepared(PreparedTerrainMesh mesh,GpuTerrainMeshPair.Packed packed) {}
    private static final class Tile {
        final String id;
        final int x,z;
        final int[] capture=new int[TOTAL];
        int[] original;
        int cursor;
        long captureRevision=1;
        CompletableFuture<Prepared> pending;
        Prepared ready;
        FrameRealitySelector.Bounds bounds;
        AABB guarded;
        Tile(String id,int x,int z) {this.id=id;this.x=x;this.z=z;}
    }
    private static Tile[] newTiles() {
        return new Tile[]{new Tile("ci-east",0,-32),new Tile("ci-west",-32,-32)};
    }
    private ClientTerrainMeshProbe() {}

    public static void tick(Minecraft client) {
        if(!ENABLED || !client.isSameThread()) return;
        if(client.level!=level) {
            reset();
            level=client.level;
        }
        boolean requested=client.getSingleplayerServer()!=null && Files.isRegularFile(
                client.getSingleplayerServer().getWorldPath(LevelResource.ROOT)
                        .resolve("operator_reality/ci-gpu-draw.allow"));
        if(debugDraw && !requested) {
            drawEye=null;
            OperatorMod.LOGGER.info("OPERATOR_TERRAIN_GPU_DRAW_DISABLED revision={}",lastDrawPhase);
        }
        debugDraw=requested;
        depthDraw=requested && Files.isRegularFile(client.getSingleplayerServer()
                .getWorldPath(LevelResource.ROOT).resolve("operator_reality/ci-gpu-depth.allow"));
        var server=client.getSingleplayerServer();
        int requestedReplacement=0;
        if(server!=null && !requested) {
            var root=server.getWorldPath(LevelResource.ROOT).resolve("operator_reality");
            if(Files.isRegularFile(root.resolve("ci-gpu-repeat.allow"))) requestedReplacement=6;
            else if(Files.isRegularFile(root.resolve("ci-gpu-auto-return.allow"))) requestedReplacement=5;
            else if(Files.isRegularFile(root.resolve("ci-gpu-replace-multi.allow"))) requestedReplacement=4;
            else if(Files.isRegularFile(root.resolve("ci-gpu-hide-multi.allow"))) requestedReplacement=3;
            else if(Files.isRegularFile(root.resolve("ci-gpu-replace.allow"))) requestedReplacement=2;
            else if(Files.isRegularFile(root.resolve("ci-gpu-hide-section.allow"))) requestedReplacement=1;
        }
        if(replacementMode!=0 && requestedReplacement==0)
            OperatorMod.LOGGER.info("OPERATOR_SECTION_REPLACEMENT_DISABLED mode={}",replacementMode);
        if(replacementMode!=requestedReplacement) {
            lastReplacementMode=0;autoReady=false;autoSubmitted=false;
        }
        replacementMode=requestedReplacement;
        if(replacementMode==6 && returned && level!=null) {tickRepeat();return;}
        if(armed || level==null || client.player==null
                || client.getSingleplayerServer()==null) return;
        if(Math.abs(client.player.getX()-.5D)>.3D
                || Math.abs(client.player.getZ()-4.5D)>.3D
                || Math.abs(client.player.getY()-200D)>1D
                || Math.abs(Math.abs(client.player.getYRot())-180F)>8F) return;
        // One tile per tick, so two tiles do not double the read budget.
        Tile t=tiles[next++ % tiles.length];
        if(t.ready==null && !advance(t)) return;
        int changed=0,originalQuads=0,alternateQuads=0;
        for(var tile:tiles) {
            if(tile.ready==null) return;
            var pair=tile.ready.mesh();
            originalQuads+=pair.original().quadCount();
            alternateQuads+=pair.alternate().quadCount();
            for(int z=0;z<N;z++)for(int x=0;x<N;x++)
                if(pair.original().topY(x,z)!=pair.alternate().topY(x,z))changed++;
        }
        armed=true;
        OperatorMod.LOGGER.info("OPERATOR_TERRAIN_MESH_ARMED columns={} changed={} originalQuads={} alternateQuads={} tiles={}",
                tiles.length*N*N,changed,originalQuads,alternateQuads,tiles.length);
    }

    private static boolean advance(Tile t) {
        if(t.pending!=null && !t.pending.isDone()) return false;
        if(t.pending!=null && t.pending.isCompletedExceptionally()) {
            OperatorMod.LOGGER.error("OPERATOR_TERRAIN_MESH_WORKER_FAILED region={}",t.id);
            reset(); return false;
        }
        int end=Math.min(TOTAL,t.cursor+BUDGET);
        for(;t.cursor<end;t.cursor++) {
            int i=t.cursor,x,z;
            if(i<N*N) {
                x=t.x+i%N;z=t.z+i/N;
            } else {
                int side=(i-N*N)/N,k=(i-N*N)%N;
                x=switch(side) {case 0 -> t.x-1;case 1 -> t.x+N;default -> t.x+k;};
                z=switch(side) {case 2 -> t.z-1;case 3 -> t.z+N;default -> t.z+k;};
            }
            if(!level.hasChunkAt(new BlockPos(x,level.getMinY(),z))) {
                t.cursor=0; return false;
            }
            t.capture[i]=level.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);
        }
        if(t.cursor!=TOTAL) return false;
        if(t.pending==null) {
            t.original=t.capture.clone();
            int[] frozen=t.original;
            int minY=level.getMinY(),maxY=level.getMaxY(),x=t.x,z=t.z;
            long revision=t.captureRevision;
            t.pending=CompletableFuture.supplyAsync(()->{
                var surface=PreparedTerrainSurface.prepare(x,z,913L,revision,minY,maxY,
                        Arrays.copyOf(frozen,N*N));
                var halo=new PreparedTerrainMesh.Halo(
                        Arrays.copyOfRange(frozen,N*N,N*N+N),
                        Arrays.copyOfRange(frozen,N*N+N,N*N+2*N),
                        Arrays.copyOfRange(frozen,N*N+2*N,N*N+3*N),
                        Arrays.copyOfRange(frozen,N*N+3*N,TOTAL));
                var mesh=PreparedTerrainMesh.compile(surface,halo);
                return new Prepared(mesh,GpuTerrainMeshPair.pack(mesh));
            });
            t.cursor=0;return false;
        }
        if(!Arrays.equals(t.original,t.capture)) {
            reset();return false;
        }
        var prepared=t.pending.join();
        var pair=prepared.mesh();
        int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE,changed=0;
        // Include vertical faces down to the halo, not just top heights.
        for(var mesh:new PreparedTerrainMesh.Mesh[]{pair.original(),pair.alternate()}) {
            var vertices=mesh.vertices();
            while(vertices.hasRemaining()) {
                vertices.get();int y=vertices.get();vertices.get();
                min=Math.min(min,y);max=Math.max(max,y);
            }
        }
        for(int z=0;z<N;z++)for(int x=0;x<N;x++)
            if(pair.original().topY(x,z)!=pair.alternate().topY(x,z))changed++;
        if(changed==0) {
            OperatorMod.LOGGER.error("OPERATOR_TERRAIN_MESH_NO_VARIATION region={}",t.id);
            reset(); return false;
        }
        if(t.bounds==null) {
            // Conservative envelope covers future +/-4 variants as well.
            t.bounds=new FrameRealitySelector.Bounds(t.x,min-5,t.z,t.x+N,max+5,t.z+N);
            t.guarded=new AABB(t.x,min-5,t.z,t.x+N,max+5,t.z+N).inflate(.25D);
        }
        t.ready=prepared;return true;
    }

    private static boolean visible(Camera camera,Tile tile) {
        return camera.getCullFrustum().isVisible(tile.guarded)
                || tile.guarded.inflate(1D).contains(camera.position());
    }

    public static void onFrame(Camera camera) {
        drawEye=null;clearReplacementFrame();
        if(!ENABLED || !RenderSystem.isOnRenderThread() || !armed || level==null
                || Minecraft.getInstance().level!=level) return;
        frame++;
        if(camera==null || camera.isPanoramicMode()
                || camera.getCapturedFrustum()!=null || camera.getCullFrustum()==null
                || camera.entity()==null) {
            FIELD.sample(null);return;
        }
        var pos=camera.position();
        if(!Double.isFinite(pos.x)||!Double.isFinite(pos.y)||!Double.isFinite(pos.z)) {
            FIELD.sample(null);return;
        }
        if(debugDraw && preloaded && Math.abs(pos.x-.5D)<.3D
                && Math.abs(pos.z-4.5D)<.3D && pos.y>200D && pos.y<204D
                && Math.abs(Math.abs(camera.yRot())-180F)<8F) drawEye=pos;
        boolean fixtureView=Math.abs(pos.x-.5D)<.3D
                && Math.abs(pos.z-4.5D)<.3D && pos.y>200D && pos.y<204D
                && Math.abs(Math.abs(camera.yRot())-180F)<8F;
        // Compile the replacement pipeline before the player turns, not on
        // the first visible frame. The Python controller waits for this ACK.
        if(replacementMode==5 && !autoReady && preloaded && !switched && fixtureView
                && TerrainDiagnosticDraw.replacementReady()) {
            autoReady=true;
            OperatorMod.LOGGER.info("OPERATOR_AUTO_RETURN_ARMED frame={} sections=2 bytes={}",frame,FIELD.usedBytes());
        }
        if(replacementMode==6 && returned) {
            boolean repeatView=Math.abs(pos.x-.5D)<.3D && Math.abs(pos.z-10.5D)<.3D
                    && pos.y>224D && pos.y<227D && Math.abs(Math.abs(camera.yRot())-180F)<8F;
            repeatFrame(camera,pos,repeatView);return;
        }
        if(replacementMode!=0 && preloaded && returned && fixtureView
                && (replacementMode!=5 || autoReady)) {
            prepareReplacementFrame(pos,replacementMode>=3?BOTH:SINGLE);
        }
        if(returned) return;
        if(!preloaded) {
            // No upload on an exit frame. Wait for a view of both tiles;
            // upload just one pair per frame, then establish continuity.
            for(var tile:tiles) if(!visible(camera,tile)) return;
            for(var tile:tiles) {
                if(FIELD.active(tile.id)!=null) continue;
                var packed=tile.ready.packed();
                long bytes=(long)packed.original().remaining()+packed.alternate().remaining();
                if(bytes>FrameGpuTerrainField.DEFAULT_BUDGET-FIELD.usedBytes()) {
                    reset();return;
                }
                var pair=GpuTerrainMeshPair.upload(packed);
                boolean adopted=false;
                try {
                    adopted=FIELD.register(tile.id,tile.bounds,tile.ready.mesh(),pair,bytes);
                    if(!adopted) {reset();return;}
                    long a=pair.buffer(false).size(),b=pair.buffer(true).size();
                    OperatorMod.LOGGER.info("OPERATOR_TERRAIN_GPU_TILE_READY region={} originalBytes={} alternateBytes={}",tile.id,a,b);
                    tile.ready=null;tile.pending=null;tile.original=null;
                } finally {
                    if(!adopted)pair.close();
                }
                break;
            }
            if(FIELD.tileCount()!=tiles.length) return;
            preloaded=true;
            OperatorMod.LOGGER.info("OPERATOR_TERRAIN_GPU_PRELOADED tiles={} totalBytes={}",
                    FIELD.tileCount(),FIELD.usedBytes());
        }
        // A single sample evaluates every tile BEFORE any selection.
        long start=System.nanoTime();
        var changes=FIELD.sample(bounds->{
            for(var tile:tiles) if(tile.bounds==bounds) return visible(camera,tile);
            throw new IllegalStateException("Unknown terrain tile");
        });
        long elapsed=System.nanoTime()-start;
        if(!switched && !changes.isEmpty()) {
            if(changes.size()!=tiles.length)
                throw new IllegalStateException("Expected both fixture tiles to exit on one frame");
            int vertices=0,quads=0;long bytes=0;
            for(var change:changes) {
                var after=FIELD.active(change.regionId());
                var pair=after.resource();
                var active=pair.buffer(after.alternate());
                if(!after.alternate() || after.revision()!=1 || active==pair.buffer(false)
                        || active.isClosed() || change.exitFrame()-change.previousVisibleFrame()!=1)
                    throw new IllegalStateException("GPU terrain pair failed to switch");
                vertices+=pair.vertexCount(true);quads+=after.mesh().quadCount();bytes+=active.size();
            }
            switched=true;exitFrame=frame;
            OperatorMod.LOGGER.info("OPERATOR_TERRAIN_GPU_SWITCH frame={} vertices={} bytes={} tiles={}",
                    frame,vertices,bytes,changes.size());
            OperatorMod.LOGGER.info("OPERATOR_TERRAIN_MESH_SWITCH frame={} nanos={} revision=1 quads={}",
                    frame,elapsed,quads);
        } else if(switched) {
            for(var tile:tiles) if(!visible(camera,tile)) return;
            int vertices=0,quads=0;
            for(var tile:tiles) {
                var active=FIELD.active(tile.id);
                if(!active.alternate() || active.resource().buffer(true).isClosed())
                    throw new IllegalStateException("GPU terrain variant lost on return");
                vertices+=active.resource().vertexCount(true);quads+=active.mesh().quadCount();
            }
            returned=true;returnFrame=frame;
            // Prepare on THIS extraction, before vanilla builds its draw
            // groups. Never defer automatic substitution to the next tick.
            if(replacementMode==5 && autoReady && fixtureView) {
                prepareReplacementFrame(pos,BOTH);
            }
            OperatorMod.LOGGER.info("OPERATOR_TERRAIN_GPU_RETURN frame={} vertices={} tiles={}",
                    frame,vertices,tiles.length);
            OperatorMod.LOGGER.info("OPERATOR_TERRAIN_MESH_RETURN exitFrame={} returnFrame={} revision=1 quads={}",
                    exitFrame,frame,quads);
        }
    }

    // CI-only repeat controller. Each revision is captured again from live
    // heightmaps, generated on the worker, then revalidated before upload.
    private static void tickRepeat() {
        if(repeatWaitingReturn || repeatArmed==repeatTarget || repeatTarget>4) return;
        var t=tiles[Math.floorMod(next++,tiles.length)];
        if(FIELD.hasPending(t.id) || t.ready!=null) return;
        if(t.captureRevision!=repeatTarget) {
            t.captureRevision=repeatTarget;t.cursor=0;t.original=null;t.pending=null;
        }
        advance(t);
    }

    private static boolean allVisible(Camera camera) {
        for(var tile:tiles) if(!visible(camera,tile)) return false;
        return true;
    }

    private static void repeatFrame(Camera camera,Vec3 pos,boolean fixtureView) {
        var changes=FIELD.sample(bounds->{
            for(var t:tiles) if(t.bounds==bounds)return visible(camera,t);
            throw new IllegalStateException("Unknown repeat region");
        });
        if(!changes.isEmpty()) {
            if(changes.size()!=tiles.length || repeatArmed!=repeatTarget)
                throw new IllegalStateException("Partial repeat transition");
            for(var change:changes) {
                if(change.revision()!=repeatTarget || change.exitFrame()-change.previousVisibleFrame()!=1)
                    throw new IllegalStateException("Invalid repeat exit");
            }
            repeatWaitingReturn=true;repeatExit=frame;
            OperatorMod.LOGGER.info("OPERATOR_REPEAT_EXIT revision={} frame={} tiles={} bytes={}",
                    repeatTarget,frame,changes.size(),FIELD.usedBytes());
            return;
        }
        if(!fixtureView || !allVisible(camera)) return;
        if(repeatWaitingReturn) {
            if(repeatReturn<repeatExit)repeatReturn=frame;
            prepareReplacementFrame(pos,BOTH);
            return;
        }
        // Current revision stays drawn while preparing the next. Retire only
        // after the previous return draw was submitted, outside the exit.
        FIELD.releaseRetired();
        prepareReplacementFrame(pos,BOTH);
        if(repeatTarget>4 || repeatArmed==repeatTarget) return;
        // Both CPU meshes must be ready before starting the staged upload.
        for(var t:tiles) if(!FIELD.hasPending(t.id) && t.ready==null)return;
        for(var t:tiles) {
            if(FIELD.hasPending(t.id))continue;
            var prepared=t.ready;
            var packed=prepared.packed();
            long bytes=(long)packed.original().remaining()+packed.alternate().remaining();
            if(bytes>FrameGpuTerrainField.DEFAULT_BUDGET-FIELD.usedBytes())
                throw new IllegalStateException("Repeat GPU budget exceeded");
            var pair=GpuTerrainMeshPair.upload(packed);
            boolean adopted=false;
            try {
                adopted=FIELD.stage(t.id,prepared.mesh(),pair,bytes);
                if(!adopted)throw new IllegalStateException("Repeat staging rejected "+t.id);
                t.ready=null;t.pending=null;t.original=null;
            } finally {
                if(!adopted)pair.close();
            }
            break;
        }
        for(var t:tiles)if(!FIELD.hasPending(t.id))return;
        repeatArmed=repeatTarget;
        OperatorMod.LOGGER.info("OPERATOR_REPEAT_ARMED revision={} frame={} tiles=2 bytes={}",repeatTarget,frame,FIELD.usedBytes());
    }

    /** Submit the selected resident buffers to the actual world render pass.
     * CI diagnostic only: selectable read-only depth test, no vanilla terrain replacement. */
    public static void draw(RenderPass pass) {
        if(!ENABLED || !debugDraw || drawEye==null || !preloaded) return;
        int phase=switched?1:0, count=0;
        for(var tile:tiles) {
            var active=FIELD.active(tile.id);
            if(active==null || active.alternate()!=switched) return;
        }
        for(var tile:tiles) {
            var active=FIELD.active(tile.id);
            TerrainDiagnosticDraw.draw(pass,active.resource(),active.alternate(),
                    tile.x,tile.z,drawEye,depthDraw);
            count++;
        }
        if(lastDrawPhase!=phase || lastDrawDepth!=depthDraw) {
            lastDrawPhase=phase;
            lastDrawDepth=depthDraw;
            OperatorMod.LOGGER.info("OPERATOR_TERRAIN_GPU_DRAW revision={} tiles={} mode={}",
                    phase,count,depthDraw?"depth":"xray");
        }
    }

    private static void clearReplacementFrame() {
        replacements.clear();masked.clear();replacementEye=null;
        suppressed=0;replacementDrawn=false;
    }

    /** All requested sections must have live ranges before any mask is allowed. */
    private static void prepareReplacementFrame(Vec3 eye,List<SectionTerrainMesh.Key> keys) {
        if(!TerrainDiagnosticDraw.replacementReady()) return;
        for(var key:keys) {
            if(!level.hasChunkAt(sectionCheck.set(key.x()*16,key.y()*16,key.z()*16))) {
                clearReplacementFrame();return;
            }
            Replacement match=null;
            for(var tile:tiles) {
                var active=FIELD.active(tile.id);
                if(active==null || !active.alternate() || active.resource().buffer(true).isClosed()
                        || !active.resource().ranges(true).containsKey(key)
                        || !active.resource().ranges(false).containsKey(key)) continue;
                var range=active.resource().ranges(true).get(key);
                if(range.vertexCount()<=0 || range.firstVertex()<0
                        || (long)range.firstVertex()+range.vertexCount()>active.resource().vertexCount(true)
                        || match!=null) {clearReplacementFrame();return;}
                match=new Replacement(tile,active);
            }
            if(match==null) {clearReplacementFrame();return;}
            replacements.put(key,match);
        }
        replacementEye=eye;
    }

    /** Exact section selection during vanilla draw extraction. No cache mutation.
     * Only the disposable CI fixture may intentionally mask partial geometry. */
    public static boolean maskVanillaSection(BlockPos origin) {
        if(!ENABLED || replacementMode==0 || replacementEye==null
                || !RenderSystem.isOnRenderThread() || Minecraft.getInstance().level!=level
                || (origin.getX()&15)!=0 || (origin.getY()&15)!=0 || (origin.getZ()&15)!=0) return false;
        var key=new SectionTerrainMesh.Key(origin.getX()>>4,origin.getY()>>4,origin.getZ()>>4);
        if(!replacements.containsKey(key)) return false;
        masked.add(key);
        suppressed++;
        return true;
    }

    /** Submit only actually masked sections, exactly once in the solid pass. */
    public static void drawMaskedSection(RenderPass pass) {
        if(masked.isEmpty() || replacementEye==null || replacementDrawn) return;
        replacementDrawn=true;
        boolean draw=replacementMode==2 || replacementMode==4 || replacementMode==5 || replacementMode==6;
        if(draw) for(var key:masked) {
            var replacement=replacements.get(key);
            TerrainDiagnosticDraw.drawReplacement(pass,replacement.active().resource(),true,
                    replacement.tile().x,replacement.tile().z,replacementEye,key);
        }
        if(replacementMode==6 && !repeatBaselineDrawn && masked.size()==BOTH.size()
                && !repeatWaitingReturn && repeatTarget==2) {
            for(var key:masked)if(replacements.get(key).active().revision()!=1)
                throw new IllegalStateException("Wrong repeat baseline revision");
            repeatBaselineDrawn=true;
            OperatorMod.LOGGER.info("OPERATOR_REPEAT_BASELINE_DRAW frame={} sections=2",frame);
        }
        if(replacementMode==6 && repeatWaitingReturn && masked.size()==BOTH.size()) {
            for(var key:masked) if(replacements.get(key).active().revision()!=repeatTarget)
                throw new IllegalStateException("Wrong repeat revision drawn");
            if(frame!=repeatReturn)throw new IllegalStateException("Repeat missed first return frame");
            repeatDrawn=repeatTarget;
            OperatorMod.LOGGER.info("OPERATOR_REPEAT_DRAW revision={} exitFrame={} returnFrame={} drawFrame={} sections={} bytes={}",
                    repeatDrawn,repeatExit,repeatReturn,frame,masked.size(),FIELD.usedBytes());
            repeatWaitingReturn=false;repeatTarget++;
        }
        if(replacementMode==5 && !autoSubmitted && masked.size()==BOTH.size()) {
            autoSubmitted=true;
            OperatorMod.LOGGER.info("OPERATOR_AUTO_RETURN_DRAW exitFrame={} returnFrame={} drawFrame={} sections={} bytes={}",
                    exitFrame,returnFrame,frame,masked.size(),FIELD.usedBytes());
        }
        if(lastReplacementMode!=replacementMode && masked.size()==replacements.size()) {
            lastReplacementMode=replacementMode;
            if(replacementMode<3) {
                OperatorMod.LOGGER.info("OPERATOR_SECTION_REPLACEMENT mode={} section=-1,12,-1 suppressedLayers={} drawn={}",
                        replacementMode,suppressed,draw);
            } else {
                OperatorMod.LOGGER.info("OPERATOR_MULTI_SECTION_REPLACEMENT mode={} sections={} suppressedLayers={} drawn={}",
                        replacementMode,masked.size(),suppressed,draw);
            }
        }
    }

    public static void closeGpu() {
        drawEye=null;debugDraw=false;depthDraw=false;lastDrawPhase=-1;lastDrawDepth=false;
        replacementMode=0;lastReplacementMode=0;clearReplacementFrame();
        FIELD.close();
        for(var tile:tiles) if(tile.pending!=null) tile.pending.cancel(false);
    }

    private static void reset() {
        closeGpu();tiles=newTiles();next=0;
        armed=false;preloaded=false;switched=false;returned=false;frame=0;exitFrame=0;returnFrame=0;
        autoReady=false;autoSubmitted=false;
        repeatTarget=2;repeatArmed=0;repeatExit=0;repeatReturn=0;repeatDrawn=0;repeatWaitingReturn=false;repeatBaselineDrawn=false;
    }
}
