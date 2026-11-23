package ru.ehtid.operator.transaction;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Blocks;

import ru.ehtid.operator.reality.SectionTerrainMesh;
import ru.ehtid.operator.reality.ServerVoxelWorldAccess;
import ru.ehtid.operator.reality.VoxelJournalRecovery;
import ru.ehtid.operator.reality.VoxelSectionSurface;
import ru.ehtid.operator.reality.VoxelSectionTransition;
import ru.ehtid.operator.reality.VoxelStartupAudit;
import ru.ehtid.operator.reality.VoxelWriteAheadLog;

/**
 * A deliberately destructive THREE-BOOT smoke test in one disposable CI
 * dedicated-server world. Never registered without the separate ciVoxelCrash
 * JVM property and requires GITHUB_ACTIONS + a fixed isolated world name and
 * marker file. No player is permitted.
 *
 * 1. Build a stone section, durably journal three interior stone->air
 *    mutations, persist only TWO physical writes, then HALT the JVM.
 * 2. Cold boot: verify the mixed world from Anvil, recover from the exact
 *    persisted PREPARED record and save the restored section.
 * 3. Cold boot: prove unchanged original blocks and RESOLVED on-disk WAL.
 */
public final class ServerVoxelCrashSmoke {
    private static final String WORLD_NAME="voxel-crash-world";
    private static final String DIMENSION="minecraft:overworld";
    private static final UUID JOURNAL_ID=
            UUID.fromString("19239f45-e562-456b-a90c-77031c987fd1");
    private static final SectionTerrainMesh.Key SECTION=
            new SectionTerrainMesh.Key(0,12,0);
    private static final int SIZE=VoxelSectionSurface.SIZE;

    private ServerVoxelCrashSmoke(){}

    public static String phase(MinecraftServer server,int stage) {
        try {
            Path world=server.getWorldPath(LevelResource.ROOT);
            if(!Boolean.getBoolean("operator.reality.ciVoxelCrash"))
                return "OPERATOR_VOXEL_CI_FAILED: separate JVM opt-in absent";
            if(!"true".equals(System.getenv("GITHUB_ACTIONS")))
                return "OPERATOR_VOXEL_CI_FAILED: outside GitHub Actions";
            if(!server.isDedicatedServer())
                return "OPERATOR_VOXEL_CI_FAILED: integrated server disallowed";
            if(!WORLD_NAME.equals(world.toRealPath().getFileName().toString()))
                return "OPERATOR_VOXEL_CI_FAILED: wrong isolated world: "+world.toRealPath().getFileName();
            if(!Files.isRegularFile(world.resolve(
                    "operator_reality/ci-voxel-crash.allow"),LinkOption.NOFOLLOW_LINKS))
                return "OPERATOR_VOXEL_CI_FAILED: isolated world marker absent";
            if(!server.getPlayerList().getPlayers().isEmpty())
                return "OPERATOR_VOXEL_CI_FAILED: players connected";
            ServerLevel level=server.overworld();
            if(level==null)return "OPERATOR_VOXEL_CI_FAILED: overworld unavailable";
            var directory=world.resolve("operator_reality/voxel-wal");
            // Server thread owns all chunk reads, writes and recovery calls.
            for(int cx=-1;cx<=1;cx++)for(int cz=-1;cz<=1;cz++)
                level.getChunk(cx,cz);
            var access=new ServerVoxelWorldAccess(level,
                    ()->server.getPlayerList().getPlayers().isEmpty());

            return switch(stage) {
                case 1 -> prepareAndCrash(server,level,access,directory);
                case 2 -> reopenAndRestore(server,access,directory);
                case 3 -> verifyColdRestart(access,directory);
                default -> "OPERATOR_VOXEL_CI_FAILED: invalid stage";
            };
        } catch(IOException | RuntimeException problem) {
            return "OPERATOR_VOXEL_CI_FAILED: "+problem.getClass().getSimpleName()
                    +" "+problem.getMessage();
        }
    }

    private static String prepareAndCrash(MinecraftServer server,ServerLevel level,
                                          ServerVoxelWorldAccess access,Path directory)
            throws IOException {
        var audit=VoxelStartupAudit.scan(directory,DIMENSION);
        if(audit.total()!=0)
            return "OPERATOR_VOXEL_CI_FAILED: original crash fixture journal already exists";

        // Every block and neighbor face is known, avoiding unknown materials.
        for(int y=0;y<SIZE;y++)for(int z=0;z<SIZE;z++)for(int x=0;x<SIZE;x++) {
            var pos=new BlockPos(x,SECTION.y()*SIZE+y,z);
            if(!level.setBlock(pos,Blocks.STONE.defaultBlockState(),2)
                    && !level.getBlockState(pos).is(Blocks.STONE))
                throw new IllegalStateException("Failed to build disposable cubic scene");
        }
        byte[] before=access.snapshot(SECTION),after=before.clone();
        for(int x=7;x<=9;x++)
            after[VoxelSectionSurface.index(x,8,8)]=VoxelSectionSurface.AIR;
        var transition=VoxelSectionTransition.prepare(
                1,before,after,access.halo(SECTION));
        var entry=VoxelWriteAheadLog.prepare(
                JOURNAL_ID,DIMENSION,SECTION,transition);

        // Persist the original solid section and the immutable WAL before
        // deliberately writing a partial mutation. Never guess after a crash.
        if(!server.saveEverything(true,true,true))
            return "OPERATOR_VOXEL_CI_FAILED: original stone section not flushed";
        VoxelWriteAheadLog.create(directory,entry);
        if(VoxelStartupAudit.scan(directory,DIMENSION).pending().size()!=1)
            return "OPERATOR_VOXEL_CI_FAILED: crash intent was not durable";

        for(int x=7;x<=8;x++) {
            int yy=SECTION.y()*SIZE+8;
            if(!access.write(x,yy,8,VoxelSectionSurface.AIR)
                    || access.read(x,yy,8)!=VoxelSectionSurface.AIR)
                throw new IllegalStateException("Could not write exact partial voxel fixture");
        }
        if(!server.saveEverything(true,true,true))
            return "OPERATOR_VOXEL_CI_FAILED: partial voxel writes not flushed";
        if(access.read(7,200,8)!=VoxelSectionSurface.AIR
                || access.read(8,200,8)!=VoxelSectionSurface.AIR
                || access.read(9,200,8)!=VoxelSectionSurface.STONE)
            return "OPERATOR_VOXEL_CI_FAILED: wrong two-block crash boundary";
        System.err.println("OPERATOR_VOXEL_CI_PARTIAL_BLOCKS_FLUSHED_2");
        System.err.println("OPERATOR_VOXEL_CI_FORCED_HALT_AFTER_2");
        System.err.flush();
        Runtime.getRuntime().halt(173);
        return "OPERATOR_VOXEL_CI_FAILED: JVM halt unexpectedly returned";
    }

    private static String reopenAndRestore(MinecraftServer server,
                                           ServerVoxelWorldAccess access,Path directory)
            throws IOException {
        var scan=VoxelStartupAudit.scan(directory,DIMENSION);
        if(scan.total()!=1 || scan.pending().size()!=1
                || scan.pending().getFirst().phase()!=VoxelWriteAheadLog.Phase.PREPARED)
            return "OPERATOR_VOXEL_CI_FAILED: startup did not discover PREPARED crash intent";
        if(access.read(7,200,8)!=VoxelSectionSurface.AIR
                || access.read(8,200,8)!=VoxelSectionSurface.AIR
                || access.read(9,200,8)!=VoxelSectionSurface.STONE)
            return "OPERATOR_VOXEL_CI_FAILED: Anvil did not persist exactly two partial mutations";
        var result=VoxelJournalRecovery.recover(access,directory,JOURNAL_ID,DIMENSION,SECTION,
                ()->server.saveEverything(true,true,true));
        if(result.status()!=VoxelJournalRecovery.Status.RECOVERED || result.restored()!=2)
            return "OPERATOR_VOXEL_CI_FAILED: expected to recover exactly two cells, got "
                    +result.status()+" restored="+result.restored();
        var journal=VoxelWriteAheadLog.load(directory,JOURNAL_ID,DIMENSION,SECTION);
        if(!Arrays.equals(access.snapshot(SECTION),journal.before())
                || VoxelStartupAudit.scan(directory,DIMENSION).blocksNewWrites())
            return "OPERATOR_VOXEL_CI_FAILED: restoration not verified";
        if(!server.saveEverything(true,true,true))
            return "OPERATOR_VOXEL_CI_FAILED: recovered blocks not saved";
        return "OPERATOR_VOXEL_CI_PHASE2_OK: restored=2 journal=RESOLVED";
    }

    private static String verifyColdRestart(ServerVoxelWorldAccess access,Path directory)
            throws IOException {
        var scan=VoxelStartupAudit.scan(directory,DIMENSION);
        if(scan.total()!=1 || scan.resolved()!=1 || scan.blocksNewWrites())
            return "OPERATOR_VOXEL_CI_FAILED: WAL not clear on second restart";
        var journal=VoxelWriteAheadLog.load(directory,JOURNAL_ID,DIMENSION,SECTION);
        if(journal.phase()!=VoxelWriteAheadLog.Phase.RESOLVED
                || !Arrays.equals(access.snapshot(SECTION),journal.before()))
            return "OPERATOR_VOXEL_CI_FAILED: original section was not persisted";
        var again=VoxelJournalRecovery.recover(access,directory,JOURNAL_ID,DIMENSION,SECTION);
        if(again.status()!=VoxelJournalRecovery.Status.ALREADY_RESOLVED)
            return "OPERATOR_VOXEL_CI_FAILED: resolved WAL was not idempotent";
        return "OPERATOR_VOXEL_CI_PHASE3_OK: cold restart world intact, WAL resolved";
    }
}
