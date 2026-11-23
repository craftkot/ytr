package ru.ehtid.operator.reality;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoxelWorldTransactionTest {
    private static final SectionTerrainMesh.Key REGION=new SectionTerrainMesh.Key(-2,12,-2);
    private static byte[] stones() {
        byte[] result=new byte[4096];
        Arrays.fill(result,VoxelSectionSurface.STONE);
        return result;
    }
    private static VoxelSectionTransition plan() {
        byte[] old=stones(),next=old.clone();
        next[VoxelSectionSurface.index(7,8,8)]=VoxelSectionSurface.AIR;
        next[VoxelSectionSurface.index(8,8,8)]=VoxelSectionSurface.AIR;
        next[VoxelSectionSurface.index(9,8,8)]=VoxelSectionSurface.AIR;
        return VoxelSectionTransition.prepare(1,old,next,
                VoxelSectionSurface.Halo.filled(VoxelSectionSurface.STONE));
    }
    private static final class FakeWorld implements VoxelWorldTransaction.World {
        final byte[] state=stones();
        boolean safe=true,corruptDuringWrite=false,failCompensation=false;
        int writes,failOn=0;
        @Override public byte read(int x,int y,int z) {
            int dx=x-REGION.x()*16,dy=y-REGION.y()*16,dz=z-REGION.z()*16;
            return state[VoxelSectionSurface.index(dx,dy,dz)];
        }
        @Override public boolean write(int x,int y,int z,byte material) {
            writes++;
            int dx=x-REGION.x()*16,dy=y-REGION.y()*16,dz=z-REGION.z()*16;
            int index=VoxelSectionSurface.index(dx,dy,dz);
            if(failCompensation && writes>failOn && material==VoxelSectionSurface.STONE)return false;
            state[index]=material;
            if(corruptDuringWrite && writes==1)
                state[VoxelSectionSurface.index(4,4,4)]=VoxelSectionSurface.AIR;
            return failOn==0 || writes!=failOn;
        }
        @Override public boolean safeToChange(SectionTerrainMesh.Key section) {
            return safe && section.equals(REGION);
        }
    }
    @Test void fullSnapshotApplyThenRollbackRestoresOriginalPhysicalBlocks() {
        var world=new FakeWorld();var transition=plan();
        assertEquals(VoxelWorldTransaction.Status.APPLIED,
                VoxelWorldTransaction.apply(world,REGION,transition).status());
        assertArrayEquals(transition.alternateSnapshot(),world.state);
        assertEquals(VoxelWorldTransaction.Status.STALE,
                VoxelWorldTransaction.apply(world,REGION,transition).status());
        assertEquals(VoxelWorldTransaction.Status.RESTORED,
                VoxelWorldTransaction.rollback(world,REGION,transition).status());
        assertArrayEquals(transition.originalSnapshot(),world.state);
        assertEquals(6,world.writes);
    }
    @Test void unsafeRegionOrStaleUnchangedBlockRefusesAnyWrites() {
        var transition=plan();var world=new FakeWorld();
        world.safe=false;
        assertEquals(VoxelWorldTransaction.Status.UNSAFE,
                VoxelWorldTransaction.apply(world,REGION,transition).status());
        world.safe=true;
        world.state[VoxelSectionSurface.index(3,3,3)]=VoxelSectionSurface.AIR;
        assertEquals(VoxelWorldTransaction.Status.STALE,
                VoxelWorldTransaction.apply(world,REGION,transition).status());
        assertEquals(0,world.writes);
    }
    @Test void failedThirdWriteIncludingFalseAfterMutationIsCompensated() {
        var transition=plan();var world=new FakeWorld();
        world.failOn=3;
        var result=VoxelWorldTransaction.apply(world,REGION,transition);
        assertEquals(VoxelWorldTransaction.Status.WRITE_FAILED,result.status());
        assertEquals(3,result.changed());
        assertEquals(3,result.compensated());
        assertArrayEquals(transition.originalSnapshot(),world.state);
    }
    @Test void failedCompensationReportsDirtyWorldInsteadOfClaimingSuccess() {
        var transition=plan();var world=new FakeWorld();
        world.failOn=2;world.failCompensation=true;
        var result=VoxelWorldTransaction.apply(world,REGION,transition);
        assertEquals(VoxelWorldTransaction.Status.COMPENSATION_FAILED,result.status());
        assertFalse(Arrays.equals(transition.originalSnapshot(),world.state));
    }
    @Test void unrelatedConcurrentBlockMutationNeverGetsSilentlyOverwritten() {
        var transition=plan();var world=new FakeWorld();world.corruptDuringWrite=true;
        var result=VoxelWorldTransaction.apply(world,REGION,transition);
        assertEquals(VoxelWorldTransaction.Status.COMPENSATION_FAILED,result.status());
        assertEquals(VoxelSectionSurface.AIR,
                world.state[VoxelSectionSurface.index(4,4,4)]);
        for(var change:transition.changes())
            assertEquals(change.before(),world.state[change.index()]);
    }
    @Test void rollbackRequiresCompleteExpectedAlternateWorld() {
        var transition=plan();var world=new FakeWorld();
        assertEquals(VoxelWorldTransaction.Status.STALE,
                VoxelWorldTransaction.rollback(world,REGION,transition).status());
        assertEquals(0,world.writes);
        assertEquals(VoxelWorldTransaction.Status.APPLIED,
                VoxelWorldTransaction.apply(world,REGION,transition).status());
        world.state[VoxelSectionSurface.index(5,5,5)]=VoxelSectionSurface.AIR;
        int writes=world.writes;
        assertEquals(VoxelWorldTransaction.Status.STALE,
                VoxelWorldTransaction.rollback(world,REGION,transition).status());
        assertEquals(writes,world.writes);
    }
}
