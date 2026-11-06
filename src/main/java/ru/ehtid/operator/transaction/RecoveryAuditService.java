package ru.ehtid.operator.transaction;

import java.io.IOException;

import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import ru.ehtid.operator.storage.TransactionJournalStorage;

/**
 * Player-triggered, read-only audit of the latest prepared transaction against
 * loaded blocks and block entity data. Never loads chunks or changes blocks.
 */
public final class RecoveryAuditService {
    private RecoveryAuditService() { }

    public static String audit(MinecraftServer server, ServerPlayer player) {
        TransactionJournal journal;
        try {
            journal = TransactionJournalStorage.loadLatest(server, player.getUUID().toString());
        } catch (IOException error) {
            return "Recovery audit refused: " + error.getMessage();
        }
        ServerLevel level = (ServerLevel) player.level();
        if (!level.dimension().toString().equals(journal.dimension))
            return "Recovery audit refused: journal belongs to another dimension";

        RecoveryPlanner.Plan plan;
        try {
            plan = RecoveryPlanner.inspect(journal, (x, y, z) -> {
                BlockPos position = new BlockPos(x, y, z);
                if (!level.hasChunkAt(position)) return null;
                BlockState state = level.getBlockState(position);
                BlockEntity entity = level.getBlockEntity(position);
                String nbt = entity == null
                        ? null : entity.saveWithFullMetadata(level.registryAccess()).toString();
                return new RecoveryPlanner.State(
                        BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString(),
                        state.toString(), nbt
                );
            });
        } catch (RuntimeException failure) {
            return "Recovery audit refused: world state could not be read safely";
        }

        return "Recovery audit " + plan.status()
                + " | examined: " + plan.examined()
                + " | original: " + plan.alreadyOriginal()
                + " | possible prior-transaction writes: " + plan.appliedCells()
                + " | conflicts: " + plan.conflicts()
                + " | inaccessible cells: " + plan.unloaded()
                + " | modeled restore actions: " + plan.changes().size()
                + " | snapshot is not evidence of an actual completed relocation"
                + " | automatic recovery not enabled";
    }
}
