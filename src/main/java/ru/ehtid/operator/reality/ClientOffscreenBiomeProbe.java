package ru.ehtid.operator.reality;

import java.nio.file.Files;
import java.util.Arrays;
import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.level.storage.LevelResource;
import ru.ehtid.operator.OperatorMod;

/**
 * Strictly opt-in, read-only real Minecraft client proof of offscreen
 * biome REGION selection. Captures the actual 1024 quart-resolution biome
 * IDs in bounded slices, rechecks every cell, prepares snowy taiga as an
 * immutable alternate, then observes a real frustum visible->hidden edge.
 *
 * NO biome packet, terrain edit, biome registry write or vanilla draw mask
 * is performed. These signals only prove that the camera-selected PREVIEW
 * is ready and selected with an exact region identity and safe distance.
 */
public final class ClientOffscreenBiomeProbe {
    private static final boolean ENABLED=Boolean.getBoolean("operator.reality.ciClientSmoke")
            && "true".equals(System.getenv("GITHUB_ACTIONS"));
    private static final BiomeRegionBlueprint.Origin ORIGIN=
            new BiomeRegionBlueprint.Origin(-32,192,-64);
    private static final String TARGET="minecraft:snowy_taiga";
    private static final int COUNT=BiomeRegionBlueprint.CELLS;
    private static final String[] frozen=new String[COUNT];
    private static final int PER_TICK=64;
    private static final BlockPos.MutableBlockPos samplePos=
            new BlockPos.MutableBlockPos();
    private static final OffscreenBiomeTransition selection=new OffscreenBiomeTransition();

    private static ClientLevel world;
    private static boolean enabled,ready,visibleLogged,selectedLogged,failed;
    private static boolean appliedObserved,restoredObserved;
    private static int cursor,verified,appliedCursor,restoredCursor,cameraAckPhase;
    private static BiomeRegionBlueprint preview;
    private static String[] originalBiomes,alternateBiomes;

    private ClientOffscreenBiomeProbe(){}

    public static void tick(Minecraft client) {
        if(!ENABLED||!client.isSameThread())return;
        if(client.level!=world) {
            world=client.level;
            reset();
        }
        var server=client.getSingleplayerServer();
        var directory=server==null?null:server.getWorldPath(LevelResource.ROOT)
                .resolve("operator_reality");
        enabled=directory!=null && Files.isRegularFile(
                directory.resolve("ci-biome-offscreen.allow"));
        verifyServerChange(directory);
        if(!enabled||world==null||ready||failed)return;

        if(cursor<COUNT) {
            int end=Math.min(cursor+PER_TICK,COUNT);
            for(;cursor<end;cursor++) {
                var name=read(cursor);
                if(name==null)return;
                frozen[cursor]=name;
            }
            return;
        }
        int end=Math.min(verified+PER_TICK,COUNT);
        for(;verified<end;verified++) {
            var name=read(verified);
            if(name==null)return;
            if(!name.equals(frozen[verified])) {
                failed=true;
                OperatorMod.LOGGER.error("OPERATOR_BIOME_REGION_FAILED source changed during capture");
                return;
            }
        }
        if(verified<COUNT)return;
        try {
            var plan=BiomeRegionBlueprint.prepare(
                    ORIGIN,Arrays.copyOf(frozen,COUNT),TARGET,1L,913L);
            if(!plan.boundaryUnchanged() || !selection.watch(ORIGIN)
                    || !selection.prepare(plan))
                throw new IllegalStateException("Biome blueprint not adopted");
            preview=plan;
            originalBiomes=plan.originalSnapshot();
            alternateBiomes=plan.alternateSnapshot();
            ready=true;
            OperatorMod.LOGGER.info(
                    "OPERATOR_BIOME_REGION_READY origin={},{},{} sourceQuartCells={} changedQuartCells={} target={} seams=true",
                    ORIGIN.x(),ORIGIN.y(),ORIGIN.z(),
                    COUNT,plan.changedQuartCells(),plan.target());
        } catch(RuntimeException problem) {
            failed=true;
            OperatorMod.LOGGER.error("OPERATOR_BIOME_REGION_FAILED {}",problem.getMessage());
        }
    }

    private static String read(int i) {
        int x=i%BiomeRegionBlueprint.WIDTH;
        int z=(i/BiomeRegionBlueprint.WIDTH)%BiomeRegionBlueprint.WIDTH;
        int y=i/(BiomeRegionBlueprint.WIDTH*BiomeRegionBlueprint.WIDTH);
        samplePos.set(ORIGIN.x()+x*4+2,ORIGIN.y()+y*4+2,
                ORIGIN.z()+z*4+2);
        if(!world.hasChunkAt(samplePos))return null;
        // Read the actual stored quart-cell biome, NOT getBiome(BlockPos):
        // vanilla getBiome uses a climate jitter sampler near cell borders.
        // An offscreen rewrite must match the raw 4x4x4 chunk biome grid.
        return world.getNoiseBiome(ORIGIN.x()/4+x,ORIGIN.y()/4+y,
                ORIGIN.z()/4+z).unwrapKey()
                .map(key->key.identifier().toString()).orElse(null);
    }

    /** Read the real client quart biome storage after a vanilla server biome
     * packet, then after its rollback. No server result is accepted as proof
     * that the CLIENT actually received the full update. */
    private static void verifyServerChange(java.nio.file.Path directory) {
        if(directory==null || world==null || preview==null)return;
        boolean apply=Files.isRegularFile(directory.resolve("ci-biome-client-apply.allow"));
        boolean restore=Files.isRegularFile(directory.resolve("ci-biome-client-restore.allow"));
        if(apply && !appliedObserved) {
            int stop=Math.min(appliedCursor+PER_TICK,COUNT);
            for(;appliedCursor<stop;) {
                String state=read(appliedCursor);
                if(state==null || !alternateBiomes[appliedCursor].equals(state))return;
                appliedCursor++;
            }
            if(appliedCursor==COUNT) {
                appliedObserved=true;
                OperatorMod.LOGGER.info(
                        "OPERATOR_BIOME_CLIENT_APPLIED origin=-32,192,-64 checkedQuartCells={} changedQuartCells={} target=minecraft:snowy_taiga",
                        COUNT,preview.changedQuartCells());
            }
        }
        if(restore && appliedObserved && !restoredObserved) {
            int stop=Math.min(restoredCursor+PER_TICK,COUNT);
            for(;restoredCursor<stop;) {
                String state=read(restoredCursor);
                if(state==null || !originalBiomes[restoredCursor].equals(state))return;
                restoredCursor++;
            }
            if(restoredCursor==COUNT) {
                restoredObserved=true;
                OperatorMod.LOGGER.info(
                        "OPERATOR_BIOME_CLIENT_RESTORED origin=-32,192,-64 checkedQuartCells={} restoredQuartCells={}",
                        COUNT,preview.changedQuartCells());
            }
        }
    }

    public static void onFrame(Camera camera) {
        if(!ENABLED || !RenderSystem.isOnRenderThread())return;
        var integrated=Minecraft.getInstance().getSingleplayerServer();
        var root=integrated==null?null:integrated.getWorldPath(LevelResource.ROOT)
                .resolve("operator_reality");
        boolean viewCheck=root!=null && (
                Files.isRegularFile(root.resolve("ci-biome-camera-hide-first.allow"))
                || Files.isRegularFile(root.resolve("ci-biome-camera-show-first.allow"))
                || Files.isRegularFile(root.resolve("ci-biome-camera-hide-second.allow"))
                || Files.isRegularFile(root.resolve("ci-biome-camera-show-second.allow")));
        if((!enabled && !viewCheck) || !ready || world!=Minecraft.getInstance().level
                || camera==null || camera.isPanoramicMode()
                || camera.getCapturedFrustum()!=null || camera.getCullFrustum()==null) {
            selection.sample(null,0,0,0,false);
            return;
        }
        var eye=camera.position();
        var bounds=ORIGIN.bounds();
        AABB volume=new AABB(bounds.minX(),bounds.minY(),bounds.minZ(),
                bounds.maxX(),bounds.maxY(),bounds.maxZ()).inflate(1D);
        boolean possiblyVisible=camera.getCullFrustum().isVisible(volume);
        if(viewCheck) {
            int requested=0;
            if(Files.isRegularFile(root.resolve("ci-biome-camera-hide-first.allow")))
                requested=1;
            else if(Files.isRegularFile(root.resolve("ci-biome-camera-show-first.allow")))
                requested=2;
            else if(Files.isRegularFile(root.resolve("ci-biome-camera-hide-second.allow")))
                requested=3;
            else if(Files.isRegularFile(root.resolve("ci-biome-camera-show-second.allow")))
                requested=4;
            boolean expectedVisible=requested==2 || requested==4;
            if(requested>cameraAckPhase && expectedVisible==possiblyVisible) {
                cameraAckPhase=requested;
                OperatorMod.LOGGER.info(
                        "OPERATOR_BIOME_CLIENT_CAMERA phase={} visible={} safeFrustum=true",
                        requested,possiblyVisible);
            }
        }
        if(possiblyVisible&&!visibleLogged) {
            visibleLogged=true;
            OperatorMod.LOGGER.info(
                    "OPERATOR_BIOME_REGION_VISIBLE frame={} origin={},{},{}",
                    selection.frame()+1,ORIGIN.x(),ORIGIN.y(),ORIGIN.z());
        }
        var selections=selection.sample(candidate->camera.getCullFrustum()
                .isVisible(new AABB(candidate.minX(),candidate.minY(),candidate.minZ(),
                        candidate.maxX(),candidate.maxY(),candidate.maxZ()).inflate(1D)),
                eye.x,eye.y,eye.z,true);
        if(!selections.isEmpty()&&!selectedLogged) {
            var switchEvent=selections.getFirst();
            selectedLogged=true;
            OperatorMod.LOGGER.info(
                    "OPERATOR_BIOME_OFFSCREEN_SELECTED frame={} previous={} origin={},{},{} changedQuartCells={} target={} previewOnly=true",
                    switchEvent.frame(),switchEvent.visibleFrame(),ORIGIN.x(),
                    ORIGIN.y(),ORIGIN.z(),
                    switchEvent.blueprint().changedQuartCells(),
                    switchEvent.blueprint().target());
        }
    }

    private static void reset() {
        Arrays.fill(frozen,null);
        cursor=verified=0;
        enabled=ready=visibleLogged=selectedLogged=failed=false;
        appliedObserved=restoredObserved=false;
        appliedCursor=restoredCursor=cameraAckPhase=0;
        preview=null;
        originalBiomes=alternateBiomes=null;
        selection.clear();
    }
}
