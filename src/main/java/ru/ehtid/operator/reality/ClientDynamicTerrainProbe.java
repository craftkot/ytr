package ru.ehtid.operator.reality;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.renderpearl.api.commands.RenderPass;
import net.minecraft.world.phys.Vec3;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.phys.AABB;
import ru.ehtid.operator.OperatorMod;

/**
 * CI-only proof that camera-driven tiles can become REAL resident GPU pairs.
 *
 * An immutable height snapshot is captured in small client-tick slices; the
 * worker receives only copied primitives. After the worker completes, the
 * client thread re-reads every column and halo cell before accepting the
 * result. Render-thread publication requires the exact live window generation.
 *
 * Nothing in this diagnostic suppresses vanilla geometry or changes blocks.
 * It intentionally retains retired resources until renderer shutdown: real
 * in-flight GPU synchronization and safe production eviction still need work.
 */
public final class ClientDynamicTerrainProbe {
    private static final boolean ENABLED =
            Boolean.getBoolean("operator.reality.ciClientSmoke")
            && "true".equals(System.getenv("GITHUB_ACTIONS"));
    private static final int N=PreparedTerrainSurface.SIZE;
    private static final int CELLS=N*N+4*N;
    private static final int READ_BUDGET=96;
    private static final int MAX_JOBS=4;
    private static final int WANTED=4;
    private static ClientLevel level;
    private static DynamicTerrainField<GpuTerrainMeshPair> field;
    private static final Map<TerrainRegionPlanner.Key,Job> jobs=new LinkedHashMap<>();
    private static boolean requested,allReady,diagnosticDraw,xraySelected;
    private static Vec3 drawEye;
    private static long extractionFrame,lastDrawFrame=-1;
    private static int nextJob;
    // Only an explicitly identified disposable CI fixture may suppress
    // vanilla sections. Normal play never reaches this code path.
    private static final List<SectionTerrainMesh.Key> CI_SECTIONS=List.of(
            new SectionTerrainMesh.Key(-1,12,-1),
            new SectionTerrainMesh.Key(0,12,-1));
    private record MaskedCandidate(TerrainRegionPlanner.Key tile,
                                   FrameGpuTerrainField.Active<GpuTerrainMeshPair> active) {}
    private static final Map<SectionTerrainMesh.Key,MaskedCandidate> candidates=new LinkedHashMap<>();
    private static final Set<SectionTerrainMesh.Key> masked=new LinkedHashSet<>();
    private static Vec3 replacementEye;
    private static int replaceMode,reportedMode,maskedLayers;
    private static boolean replacementDrawn;

    private record Prepared(PreparedTerrainMesh mesh,GpuTerrainMeshPair.Packed packed) {}
    private static final class Job {
        final TerrainRegionWindow.Request request;
        final int[] first=new int[CELLS];
        final int[] check=new int[CELLS];
        int cursor,validated;
        CompletableFuture<Prepared> worker;
        Prepared ready;
        final int minY,maxY;
        Job(TerrainRegionWindow.Request request,int minY,int maxY) {
            this.request=request;this.minY=minY;this.maxY=maxY;
        }
        void cancel() {
            if(worker!=null)worker.cancel(false);
        }
    }

    private ClientDynamicTerrainProbe() {}

    public static void tick(Minecraft client) {
        if(!ENABLED || !client.isSameThread())return;
        var server=client.getSingleplayerServer();
        requested=server!=null && Files.isRegularFile(server.getWorldPath(LevelResource.ROOT)
                .resolve("operator_reality/ci-dynamic-tiles.allow"));
        var root=server==null?null:server.getWorldPath(LevelResource.ROOT)
                .resolve("operator_reality");
        diagnosticDraw=requested && Files.isRegularFile(root.resolve("ci-dynamic-draw.allow"));
        // X-ray is a separate inspection of selected alternatives. It
        // proves resident geometry exists even when the depth-tested
        // surface is legitimately occluded by the original voxel world.
        xraySelected=requested && Files.isRegularFile(root.resolve("ci-dynamic-xray-selected.allow"));
        int nextMode=0;
        if(requested) {
            if(Files.isRegularFile(root.resolve("ci-dynamic-replace-section.allow")))nextMode=2;
            else if(Files.isRegularFile(root.resolve("ci-dynamic-hide-section.allow")))nextMode=1;
        }
        if(replaceMode!=nextMode) {
            if(replaceMode!=0 && nextMode==0)
                OperatorMod.LOGGER.info("OPERATOR_DYNAMIC_SECTION_DISABLED mode={}",replaceMode);
            reportedMode=0;
            replaceMode=nextMode;
        }
        if(!requested || client.level==null || field==null || client.level!=level)return;
        jobs.entrySet().removeIf(entry->{
            if(field.accepts(entry.getValue().request))return false;
            entry.getValue().cancel();
            return true;
        });
        for(var request:field.wanted()) {
            if(field.active(request.key())!=null || jobs.containsKey(request.key()))continue;
            if(jobs.size()>=MAX_JOBS)break;
            jobs.put(request.key(),new Job(request,level.getMinY(),level.getMaxY()));
        }
        // Round robin: a missing chunk must not monopolize all capture
        // slices while other camera-desired regions are already loaded.
        var pending=List.copyOf(jobs.values());
        for(int n=0;n<pending.size();n++) {
            var job=pending.get(Math.floorMod(nextJob++,pending.size()));
            if(job.ready!=null && job.validated==CELLS)continue;
            if(job.worker!=null && !job.worker.isDone())continue;
            if(job.worker!=null && job.worker.isCompletedExceptionally()) {
                OperatorMod.LOGGER.error("OPERATOR_DYNAMIC_GPU_WORKER_FAILED key={}",job.request.key());
                job.cancel();
                jobs.remove(job.request.key());
                return;
            }
            advance(job);
            return; // One bounded capture or verification slice per tick.
        }
    }

    private static boolean read(Job job,int index,int[] dest) {
        int x,z;
        if(index<N*N) {
            x=job.request.key().x()+index%N;
            z=job.request.key().z()+index/N;
        } else {
            int side=(index-N*N)/N,k=(index-N*N)%N;
            x=switch(side) {
                case 0 -> job.request.key().x()-1;
                case 1 -> job.request.key().x()+N;
                default -> job.request.key().x()+k;
            };
            z=switch(side) {
                case 2 -> job.request.key().z()-1;
                case 3 -> job.request.key().z()+N;
                default -> job.request.key().z()+k;
            };
        }
        if(!level.hasChunkAt(new BlockPos(x,job.minY,z)))return false;
        dest[index]=level.getHeight(Heightmap.Types.WORLD_SURFACE,x,z);
        return true;
    }

    private static void advance(Job job) {
        if(job.worker==null) {
            int end=Math.min(CELLS,job.cursor+READ_BUDGET);
            for(;job.cursor<end;job.cursor++) {
                if(!read(job,job.cursor,job.first)) {
                    job.cursor=0;return;
                }
            }
            if(job.cursor<CELLS)return;
            int[] frozen=job.first.clone();
            int x=job.request.key().x(),z=job.request.key().z();
            int min=job.minY,max=job.maxY;
            job.worker=CompletableFuture.supplyAsync(()->{
                var surface=PreparedTerrainSurface.prepare(x,z,913L,1L,min,max,
                        Arrays.copyOf(frozen,N*N));
                var halo=new PreparedTerrainMesh.Halo(
                        Arrays.copyOfRange(frozen,N*N,N*N+N),
                        Arrays.copyOfRange(frozen,N*N+N,N*N+2*N),
                        Arrays.copyOfRange(frozen,N*N+2*N,N*N+3*N),
                        Arrays.copyOfRange(frozen,N*N+3*N,CELLS));
                var mesh=PreparedTerrainMesh.compile(surface,halo);
                return new Prepared(mesh,GpuTerrainMeshPair.pack(mesh));
            });
            return;
        }
        if(job.ready==null) {
            try { job.ready=job.worker.join(); }
            catch(CompletionException ex) {
                OperatorMod.LOGGER.error("OPERATOR_DYNAMIC_GPU_PACK_FAILED",ex);
                job.cancel();jobs.remove(job.request.key());return;
            }
        }
        int end=Math.min(CELLS,job.validated+READ_BUDGET);
        for(;job.validated<end;job.validated++) {
            if(!read(job,job.validated,job.check)
                    || job.check[job.validated]!=job.first[job.validated]) {
                job.cancel();jobs.remove(job.request.key());return;
            }
        }
    }

    public static void onFrame(Camera camera) {
        if(!ENABLED || !RenderSystem.isOnRenderThread())return;
        extractionFrame++;
        drawEye=null;replacementEye=null;
        candidates.clear();masked.clear();maskedLayers=0;replacementDrawn=false;
        var current=Minecraft.getInstance().level;
        if(current!=level) {
            closeGpu();
            level=current;
        }
        if(!requested || current==null || camera==null || camera.getCullFrustum()==null
                || camera.isPanoramicMode() || camera.getCapturedFrustum()!=null)return;
        var pos=camera.position();
        if(!Double.isFinite(pos.x)||!Double.isFinite(pos.y)||!Double.isFinite(pos.z)
                || !Float.isFinite(camera.yRot()))return;
        if(field==null)
            field=new DynamicTerrainField<>(2,WANTED,FrameGpuTerrainField.DEFAULT_BUDGET);
        var changed=field.onCamera(pos.x,pos.z,camera.yRot());
        if(!changed.evicted().isEmpty()) {
            allReady=false;
            for(var request:changed.evicted()) {
                var job=jobs.remove(request.key());
                if(job!=null)job.cancel();
            }
            OperatorMod.LOGGER.info("OPERATOR_DYNAMIC_GPU_EVICT count={} resident={} retired={} bytes={}",
                    changed.evicted().size(),field.residentCount(),field.retiredCount(),field.gpuBytes());
        }
        var switches=field.sample(bounds->camera.getCullFrustum().isVisible(new AABB(
                bounds.minX(),bounds.minY(),bounds.minZ(),
                bounds.maxX(),bounds.maxY(),bounds.maxZ()))
                || new AABB(bounds.minX(),bounds.minY(),bounds.minZ(),
                        bounds.maxX(),bounds.maxY(),bounds.maxZ()).inflate(1D).contains(pos));
        if(!switches.isEmpty()) {
            long bytes=field.gpuBytes();
            OperatorMod.LOGGER.info("OPERATOR_DYNAMIC_GPU_SWITCH frame={} regions={} resident={} bytes={}",
                    extractionFrame,switches.size(),field.residentCount(),bytes);
            for(var change:switches)
                OperatorMod.LOGGER.info("OPERATOR_DYNAMIC_GPU_SELECTED frame={} id={} revision={} alternate={} previousVisible={} exit={}",
                        extractionFrame,change.regionId(),change.revision(),
                        change.variant().alternate(),change.previousVisibleFrame(),change.exitFrame());
        }
        // Publication happens only on the owning render thread, max one pair
        // per frame. The worker completion has already been validated on tick.
        for(Iterator<Map.Entry<TerrainRegionPlanner.Key,Job>> it=jobs.entrySet().iterator();
                it.hasNext();) {
            var entry=it.next();
            var job=entry.getValue();
            if(job.ready==null || job.validated<CELLS)continue;
            it.remove();
            if(!field.accepts(job.request))continue;
            var packed=job.ready.packed();
            long bytes=(long)packed.original().remaining()+packed.alternate().remaining();
            if(bytes<=0 || bytes>FrameGpuTerrainField.DEFAULT_BUDGET-field.gpuBytes()) {
                OperatorMod.LOGGER.info("OPERATOR_DYNAMIC_GPU_BUDGET_BLOCK bytes={} used={}",
                        bytes,field.gpuBytes());
                continue;
            }
            var pair=GpuTerrainMeshPair.upload(packed);
            boolean adopted=false;
            try {
                var key=job.request.key();
                var bounds=new FrameRealitySelector.Bounds(key.x(),job.minY,key.z(),
                        key.x()+N,job.maxY,key.z()+N);
                adopted=field.publish(job.request,bounds,job.ready.mesh(),pair,bytes);
                if(adopted)
                    OperatorMod.LOGGER.info("OPERATOR_DYNAMIC_GPU_TILE_READY x={} z={} generation={} bytes={} resident={}",
                            key.x(),key.z(),job.request.generation(),bytes,field.residentCount());
            } finally {
                if(!adopted)pair.close();
            }
            break;
        }
        if((diagnosticDraw || xraySelected) && field.residentCount()>0)drawEye=pos;
        if(replaceMode!=0 && !diagnosticDraw && level==current
                && Math.abs(pos.x-.5D)<.3D && Math.abs(pos.z-4.5D)<.3D
                && pos.y>200D && pos.y<204D
                && Math.abs(Math.abs(camera.yRot())-180F)<8F
                && TerrainDiagnosticDraw.replacementReady()) {
            prepareMaskedFrame(pos);
        }
        if(field.residentCount()==WANTED && !allReady) {
            allReady=true;
            OperatorMod.LOGGER.info("OPERATOR_DYNAMIC_GPU_WINDOW_READY resident={} wanted={} bytes={}",
                    field.residentCount(),field.wanted().size(),field.gpuBytes());
        }
    }

    /**
     * The dynamically managed GPU field supplies these ranges. They are
     * specifically authorized by the disposable fixture whitelist, not by
     * range existence alone. Real voxel coverage is still NOT established;
     * production must not suppress its terrain using heightfields.
     */
    private static void prepareMaskedFrame(Vec3 eye) {
        for(var section:CI_SECTIONS) {
            var tile=SectionReplacementSafety.owner(section);
            if(!level.hasChunkAt(new BlockPos(section.x()*16,section.y()*16,section.z()*16))) {
                candidates.clear();return;
            }
            var active=field.active(tile);
            if(active==null || active.resource().buffer(active.alternate()).isClosed()
                    || !SectionReplacementSafety.residentRangesValid(section,tile,
                            active.resource().ranges(false),active.resource().ranges(true),
                            active.resource().vertexCount(false),
                            active.resource().vertexCount(true))) {
                candidates.clear();return;
            }
            candidates.put(section,new MaskedCandidate(tile,active));
        }
        if(candidates.size()==CI_SECTIONS.size())replacementEye=eye;
    }

    /** Mask only the two explicitly certified CI demo locations and only
     * after an exact prevalidated resident range has been found. */
    public static boolean maskVanillaSection(BlockPos origin) {
        if(!ENABLED || replaceMode==0 || replacementEye==null
                || !RenderSystem.isOnRenderThread() || level!=Minecraft.getInstance().level
                || (origin.getX()&15)!=0 || (origin.getY()&15)!=0 || (origin.getZ()&15)!=0)
            return false;
        var key=new SectionTerrainMesh.Key(origin.getX()>>4,origin.getY()>>4,origin.getZ()>>4);
        if(!candidates.containsKey(key))return false;
        masked.add(key);
        maskedLayers++;
        return true;
    }

    /** Must be called after vanilla's solid-pass submission, once per
     * extraction frame. No uploads or CPU meshing occur in this pass. */
    public static void drawMaskedSection(RenderPass pass) {
        if(!ENABLED || replacementEye==null || masked.isEmpty() || replacementDrawn)return;
        replacementDrawn=true;
        if(replaceMode==2) {
            for(var section:masked) {
                var candidate=candidates.get(section);
                if(candidate==null)continue;
                TerrainDiagnosticDraw.drawReplacement(pass,candidate.active().resource(),
                        candidate.active().alternate(),candidate.tile().x(),
                        candidate.tile().z(),replacementEye,section);
            }
        }
        if(masked.size()==CI_SECTIONS.size() && reportedMode!=replaceMode) {
            reportedMode=replaceMode;
            OperatorMod.LOGGER.info("OPERATOR_DYNAMIC_SECTION_REPLACEMENT mode={} sections={} layers={} drawn={} bytes={}",
                    replaceMode,masked.size(),maskedLayers,replaceMode==2,field.gpuBytes());
        }
    }

    /** Diagnostic overlay ONLY, depth-tested against vanilla world.
     * It neither suppresses existing sections nor updates collision.
     * All geometry is already resident; submission performs no GPU upload.
     */
    public static void draw(RenderPass pass) {
        if(!ENABLED || !(diagnosticDraw || xraySelected) || drawEye==null || field==null
                || field.residentCount()==0 || lastDrawFrame==extractionFrame)return;
        lastDrawFrame=extractionFrame;
        int submitted=0,vertices=0,alternate=0;
        for(var request:field.wanted()) {
            var active=field.active(request.key());
            if(active==null || active.resource().buffer(active.alternate()).isClosed()
                    || (xraySelected && !active.alternate()))continue;
            TerrainDiagnosticDraw.draw(pass,active.resource(),active.alternate(),
                    request.key().x(),request.key().z(),drawEye,!xraySelected);
            submitted++;
            vertices+=active.resource().vertexCount(active.alternate());
            if(active.alternate())alternate++;
        }
        if(submitted>0) {
            if(xraySelected)
                OperatorMod.LOGGER.info("OPERATOR_DYNAMIC_GPU_XRAY frame={} selected={} vertices={} resident={} bytes={}",
                        extractionFrame,alternate,vertices,field.residentCount(),field.gpuBytes());
            else OperatorMod.LOGGER.info("OPERATOR_DYNAMIC_GPU_DRAW frame={} tiles={} alternate={} vertices={} resident={} bytes={}",
                    extractionFrame,submitted,alternate,vertices,field.residentCount(),field.gpuBytes());
        }
    }

    public static void closeGpu() {
        if(!ENABLED)return;
        for(var job:jobs.values())job.cancel();
        jobs.clear();
        if(field!=null) {
            field.close();
            field=null;
        }
        allReady=false;nextJob=0;drawEye=null;diagnosticDraw=false;xraySelected=false;lastDrawFrame=-1;
        candidates.clear();masked.clear();replacementEye=null;
        replaceMode=0;reportedMode=0;maskedLayers=0;replacementDrawn=false;
    }
}
