package ru.ehtid.operator.reality;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Idempotent fail-closed repair of a PREPARED or APPLIED voxel WAL.
 *
 * PREPARED does not mean nothing was written: the game may have crashed
 * halfway through a vanilla setBlock call or before the APPLIED marker.
 * All changed cells must still contain either the exact BEFORE or AFTER
 * material; all 4096 unchanged cells must remain identical to BEFORE.
 *
 * Recovery always rolls toward the original, never toward an unconfirmed
 * generation. The journal remains pending on any safety failure, write
 * failure or checksum mismatch; it is marked RESOLVED only after a complete
 * 4096-block validation, allowing a later idempotent retry.
 */
public final class VoxelJournalRecovery {
    public enum Status {
        RECOVERED, ALREADY_RESOLVED, UNSAFE, DIVERGED, WRITE_FAILED, FLUSH_FAILED
    }
    public record Outcome(Status status,int restored,int changedCount) {
        public Outcome {
            Objects.requireNonNull(status);
            if(restored<0 || changedCount<0 || restored>changedCount)
                throw new IllegalArgumentException("Invalid recovery counts");
        }
        public boolean success() {
            return status==Status.RECOVERED || status==Status.ALREADY_RESOLVED;
        }
    }
    private VoxelJournalRecovery(){}

    /**
     * Invoke only on the owning Minecraft server/world thread with chunks
     * loaded and protected-volume checks enabled. An IOException means the
     * WAL is corrupt/unavailable or its atomic RESOLVED update failed.
     */
    /** Unit-test and in-memory adapter only. Real Minecraft callers MUST use
     * the overload supplying a server/world durability flush callback. */
    public static Outcome recover(VoxelWorldTransaction.World world,Path directory,UUID id,
                                  String dimension,SectionTerrainMesh.Key section)
            throws IOException {
        return recover(world,directory,id,dimension,section,()->true);
    }

    /** The physical chunk changes must be flushed before the WAL can be
     * marked RESOLVED. A failure leaves it pending for a cold-start retry. */
    public static Outcome recover(VoxelWorldTransaction.World world,Path directory,UUID id,
                                  String dimension,SectionTerrainMesh.Key section,
                                  BooleanSupplier flushRestoredWorld)
            throws IOException {
        Objects.requireNonNull(flushRestoredWorld);
        Objects.requireNonNull(world);
        var journal=VoxelWriteAheadLog.load(directory,id,dimension,section);
        int count=journal.changedCount();
        if(journal.phase()==VoxelWriteAheadLog.Phase.RESOLVED)
            return new Outcome(Status.ALREADY_RESOLVED,0,count);
        if(!world.safeToChange(section))
            return new Outcome(Status.UNSAFE,0,count);

        byte[] before=journal.before(),after=journal.after();
        int bx=Math.multiplyExact(section.x(),16);
        int by=Math.multiplyExact(section.y(),16);
        int bz=Math.multiplyExact(section.z(),16);

        // Validate the whole section in a read-only pass before writing.
        // A third material or a modified unchanged cell is a hard veto.
        for(int i=0;i<before.length;i++) {
            int x=bx+i%16,y=by+i/256,z=bz+(i/16)%16;
            try {
                byte value=world.read(x,y,z);
                if(value!=before[i] && (before[i]==after[i] || value!=after[i]))
                    return new Outcome(Status.DIVERGED,0,count);
            } catch(RuntimeException invalid) {
                return new Outcome(Status.DIVERGED,0,count);
            }
        }

        int restored=0;
        for(int i=before.length-1;i>=0;i--) {
            if(before[i]==after[i])continue;
            int x=bx+i%16,y=by+i/256,z=bz+(i/16)%16;
            try {
                if(!world.safeToChange(section))
                    return new Outcome(Status.UNSAFE,restored,count);
                byte current=world.read(x,y,z);
                if(current==before[i])continue;
                if(current!=after[i])
                    return new Outcome(Status.DIVERGED,restored,count);
                if(!world.write(x,y,z,before[i]) || world.read(x,y,z)!=before[i])
                    return new Outcome(Status.WRITE_FAILED,restored,count);
                restored++;
            } catch(RuntimeException failed) {
                return new Outcome(Status.WRITE_FAILED,restored,count);
            }
        }

        // Verify the physical world again before marking the intent done.
        try {
            for(int i=0;i<before.length;i++) {
                int x=bx+i%16,y=by+i/256,z=bz+(i/16)%16;
                if(world.read(x,y,z)!=before[i])
                    return new Outcome(Status.DIVERGED,restored,count);
            }
            if(!world.safeToChange(section))
                return new Outcome(Status.UNSAFE,restored,count);
        } catch(RuntimeException changed) {
            return new Outcome(Status.DIVERGED,restored,count);
        }
        // Crash consistency: NEVER resolve the durable WAL before the
        // restored Minecraft chunks have been flushed to their Anvil files.
        // A crash during/after a failed flush must leave PREPARED/APPLIED.
        try {
            if(!flushRestoredWorld.getAsBoolean())
                return new Outcome(Status.FLUSH_FAILED,restored,count);
        } catch(RuntimeException failed) {
            return new Outcome(Status.FLUSH_FAILED,restored,count);
        }
        VoxelWriteAheadLog.advance(directory,journal,VoxelWriteAheadLog.Phase.RESOLVED);
        return new Outcome(Status.RECOVERED,restored,count);
    }
}
