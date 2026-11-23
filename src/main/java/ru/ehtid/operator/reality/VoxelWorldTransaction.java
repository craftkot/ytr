package ru.ehtid.operator.reality;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Exact compare-before-write bridge between an immutable volumetric plan
 * and a (server-thread-owned) block world.
 *
 * Refuses to apply unless every one of the 4096 section cells still matches
 * the prepared source snapshot and the caller's safety gate is satisfied.
 * Refuses rollback unless the FULL alternate snapshot remains unchanged.
 * A partial write failure triggers best-effort reverse compensation, never
 * silently pretending success. No GPU buffer is authoritative for physics.
 *
 * IMPORTANT: This is an in-memory transaction, not a crash-safe journal.
 * Do not enable it in normal gameplay until it is integrated with a
 * persistent write-ahead log, world/chunk tickets, full block-state material
 * mapping, player safety and renderer invalidation.
 */
public final class VoxelWorldTransaction {
    public interface World {
        /** Must be read on the owning world thread; unsupported states fail. */
        byte read(int x,int y,int z);
        /** Returns false if the world refused the update. */
        boolean write(int x,int y,int z,byte material);
        /** No player, entity or other protected volume may intersect. */
        boolean safeToChange(SectionTerrainMesh.Key section);
    }

    public enum Status {
        APPLIED, RESTORED, UNSAFE, STALE, WRITE_FAILED, COMPENSATION_FAILED
    }
    public record Result(Status status,int changed,int compensated) {
        public Result {
            Objects.requireNonNull(status);
            if(changed<0 || compensated<0)
                throw new IllegalArgumentException("Negative block update count");
        }
        public boolean success() {
            return status==Status.APPLIED || status==Status.RESTORED;
        }
    }

    private VoxelWorldTransaction() {}

    public static Result apply(World world,SectionTerrainMesh.Key section,
                               VoxelSectionTransition transition) {
        return execute(world,section,transition,false);
    }
    public static Result rollback(World world,SectionTerrainMesh.Key section,
                                  VoxelSectionTransition transition) {
        return execute(world,section,transition,true);
    }

    private static Result execute(World world,SectionTerrainMesh.Key section,
                                  VoxelSectionTransition transition,boolean undo) {
        Objects.requireNonNull(world);
        Objects.requireNonNull(section);
        Objects.requireNonNull(transition);
        if(!world.safeToChange(section))
            return new Result(Status.UNSAFE,0,0);

        // Validate EVERY block, not merely those being changed. A stale
        // collision/material field must never become a seemingly valid plan.
        byte[] expected=undo?transition.alternateSnapshot():transition.originalSnapshot();
        try {
            for(int i=0;i<expected.length;i++) {
                int x=Math.addExact(Math.multiplyExact(section.x(),16),i%16);
                int y=Math.addExact(Math.multiplyExact(section.y(),16),i/256);
                int z=Math.addExact(Math.multiplyExact(section.z(),16),(i/16)%16);
                if(world.read(x,y,z)!=expected[i])
                    return new Result(Status.STALE,0,0);
            }
        } catch(RuntimeException error) {
            return new Result(Status.STALE,0,0);
        }

        List<VoxelSectionTransition.WorldChange> ordered=new ArrayList<>();
        for(var change:transition.changes())ordered.add(change.at(section));
        if(undo)java.util.Collections.reverse(ordered);
        List<VoxelSectionTransition.WorldChange> attempted=new ArrayList<>();
        boolean applied=true;
        try {
            for(var change:ordered) {
                if(!world.safeToChange(section)) {
                    applied=false;break;
                }
                attempted.add(change); // include a write that mutates then returns false
                byte target=undo?change.before():change.after();
                if(!world.write(change.x(),change.y(),change.z(),target)
                        || world.read(change.x(),change.y(),change.z())!=target) {
                    applied=false;break;
                }
            }
            if(applied) {
                byte[] finalState=undo?transition.originalSnapshot():transition.alternateSnapshot();
                for(int i=0;i<finalState.length;i++) {
                    int x=Math.addExact(Math.multiplyExact(section.x(),16),i%16);
                    int y=Math.addExact(Math.multiplyExact(section.y(),16),i/256);
                    int z=Math.addExact(Math.multiplyExact(section.z(),16),(i/16)%16);
                    if(world.read(x,y,z)!=finalState[i]) {
                        applied=false;break;
                    }
                }
            }
        } catch(RuntimeException error) {
            applied=false;
        }
        if(applied)return new Result(undo?Status.RESTORED:Status.APPLIED,
                attempted.size(),0);

        int compensated=0;
        boolean compensationComplete=true;
        for(int i=attempted.size()-1;i>=0;i--) {
            var change=attempted.get(i);
            byte expectedNow=undo?change.before():change.after();
            byte restore=undo?change.after():change.before();
            try {
                byte now=world.read(change.x(),change.y(),change.z());
                if(now==restore)continue;
                if(now!=expectedNow
                        || !world.write(change.x(),change.y(),change.z(),restore)
                        || world.read(change.x(),change.y(),change.z())!=restore) {
                    compensationComplete=false;continue;
                }
                compensated++;
            } catch(RuntimeException failure) {
                compensationComplete=false;
            }
        }
        // Do not claim a clean rollback if unplanned concurrent mutations
        // occurred in any cell of the section during the attempted commit.
        try {
            for(int i=0;i<expected.length;i++) {
                int x=Math.addExact(Math.multiplyExact(section.x(),16),i%16);
                int y=Math.addExact(Math.multiplyExact(section.y(),16),i/256);
                int z=Math.addExact(Math.multiplyExact(section.z(),16),(i/16)%16);
                if(world.read(x,y,z)!=expected[i]) {
                    compensationComplete=false;break;
                }
            }
        } catch(RuntimeException error) {
            compensationComplete=false;
        }
        return new Result(compensationComplete?Status.WRITE_FAILED:Status.COMPENSATION_FAILED,
                attempted.size(),compensated);
    }
}
