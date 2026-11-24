package ru.ehtid.operator.reality;

import java.nio.file.Files;
import java.util.List;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.storage.LevelResource;
import ru.ehtid.operator.OperatorMod;

/**
 * Verify the actual synced CLIENT BlockStates for ALL changed grass, snow,
 * logs and leaves of the CI biome transition. Server logs alone are not
 * evidence of a visible world edit. Read-only and disabled outside GitHub CI.
 */
public final class ClientBiomeSurfaceProbe {
    private static final boolean ENABLED=
            Boolean.getBoolean("operator.reality.ciClientSmoke")
            && "true".equals(System.getenv("GITHUB_ACTIONS"));
    private static final List<BiomeSurfacePattern.Cell> CHANGES=
            BiomeSurfacePattern.changes();
    private static final int PER_TICK=64;
    private static final BlockPos.MutableBlockPos POS=new BlockPos.MutableBlockPos();
    private static ClientLevel level;
    private static int applyCursor,restoreCursor;
    private static boolean applied,restored;

    private ClientBiomeSurfaceProbe(){}

    public static void tick(Minecraft client) {
        if(!ENABLED || !client.isSameThread())return;
        if(level!=client.level){
            level=client.level;
            applyCursor=restoreCursor=0;
            applied=restored=false;
        }
        var server=client.getSingleplayerServer();
        if(server==null || level==null)return;
        var root=server.getWorldPath(LevelResource.ROOT).resolve("operator_reality");
        if(!applied && Files.isRegularFile(root.resolve("ci-biome-surface-client-apply.allow"))) {
            int end=Math.min(CHANGES.size(),applyCursor+PER_TICK);
            while(applyCursor<end) {
                if(!matches(CHANGES.get(applyCursor),true))return;
                applyCursor++;
            }
            if(applyCursor==CHANGES.size()){
                applied=true;
                OperatorMod.LOGGER.info(
                        "OPERATOR_BIOME_SURFACE_CLIENT_APPLIED changed={} snowyGround=true spruceTrees=true",
                        CHANGES.size());
            }
        }
        if(applied && !restored && Files.isRegularFile(
                root.resolve("ci-biome-surface-client-restore.allow"))) {
            int end=Math.min(CHANGES.size(),restoreCursor+PER_TICK);
            while(restoreCursor<end) {
                if(!matches(CHANGES.get(restoreCursor),false))return;
                restoreCursor++;
            }
            if(restoreCursor==CHANGES.size()){
                restored=true;
                OperatorMod.LOGGER.info(
                        "OPERATOR_BIOME_SURFACE_CLIENT_RESTORED changed={} grass=true oakTrees=true",
                        CHANGES.size());
            }
        }
    }

    private static Block material(BiomeSurfacePattern.Material kind) {
        return switch(kind){
            case AIR->Blocks.AIR;
            case GRASS->Blocks.GRASS_BLOCK;
            case PODZOL->Blocks.PODZOL;
            case SNOW_BLOCK->Blocks.SNOW_BLOCK;
            case OAK_LOG->Blocks.OAK_LOG;
            case OAK_LEAVES->Blocks.OAK_LEAVES;
            case SPRUCE_LOG->Blocks.SPRUCE_LOG;
            case SPRUCE_LEAVES->Blocks.SPRUCE_LEAVES;
        };
    }
    private static boolean matches(BiomeSurfacePattern.Cell cell,boolean alternate) {
        POS.set(cell.x(),cell.y(),cell.z());
        if(!level.hasChunkAt(POS))return false;
        var target=alternate?cell.after():cell.before();
        var state=level.getBlockState(POS);
        if(!state.is(material(target)))return false;
        if(target==BiomeSurfacePattern.Material.OAK_LEAVES
                || target==BiomeSurfacePattern.Material.SPRUCE_LEAVES)
            return state.getValue(LeavesBlock.PERSISTENT);
        return true;
    }
}
