package ru.ehtid.operator.reality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VoxelJournalRecoveryTest {
    private static final SectionTerrainMesh.Key SECTION=
            new SectionTerrainMesh.Key(-2,12,-2);
    private static final String DIMENSION="minecraft:overworld";
    @TempDir Path temporary;

    private static byte[] original() {
        byte[] blocks=new byte[4096];
        Arrays.fill(blocks,VoxelSectionSurface.STONE);
        return blocks;
    }
    private static VoxelSectionTransition transition() {
        var before=original();var after=before.clone();
        for(int x=7;x<=9;x++)
            after[VoxelSectionSurface.index(x,8,8)]=VoxelSectionSurface.AIR;
        return VoxelSectionTransition.prepare(42,before,after,
                VoxelSectionSurface.Halo.filled(VoxelSectionSurface.STONE));
    }
    private static final class InMemoryWorld implements VoxelWorldTransaction.World {
        final byte[] blocks=original();
        boolean safe=true;
        int writes,refuseAt;
        @Override public boolean safeToChange(SectionTerrainMesh.Key key){
            return safe && key.equals(SECTION);
        }
        private static int idx(int x,int y,int z) {
            return VoxelSectionSurface.index(x-SECTION.x()*16,
                    y-SECTION.y()*16,z-SECTION.z()*16);
        }
        @Override public byte read(int x,int y,int z){return blocks[idx(x,y,z)];}
        @Override public boolean write(int x,int y,int z,byte material) {
            writes++;
            if(refuseAt==writes)return false;
            blocks[idx(x,y,z)]=material;
            return true;
        }
    }
    private VoxelWriteAheadLog.Entry create() throws IOException {
        var entry=VoxelWriteAheadLog.prepare(UUID.randomUUID(),DIMENSION,SECTION,transition());
        VoxelWriteAheadLog.create(temporary,entry);
        return entry;
    }

    @Test void recoverTwoPartiallyAppliedCellsAfterReopeningDiskJournal() throws IOException {
        var entry=create();var world=new InMemoryWorld();
        var changes=transition().changes();
        for(int i=0;i<2;i++){
            var change=changes.get(i).at(SECTION);
            assertTrue(world.write(change.x(),change.y(),change.z(),change.after()));
        }
        var reloaded=VoxelWriteAheadLog.load(temporary,entry.id(),DIMENSION,SECTION);
        assertEquals(VoxelWriteAheadLog.Phase.PREPARED,reloaded.phase());
        assertEquals(3,reloaded.changedCount());
        assertTrue(entry.samePlan(reloaded));
        assertArrayEquals(entry.before(),reloaded.before());
        assertArrayEquals(entry.after(),reloaded.after());
        var outcome=VoxelJournalRecovery.recover(world,temporary,entry.id(),DIMENSION,SECTION);
        assertEquals(VoxelJournalRecovery.Status.RECOVERED,outcome.status());
        assertEquals(2,outcome.restored());
        assertArrayEquals(original(),world.blocks);
        assertEquals(VoxelWriteAheadLog.Phase.RESOLVED,
                VoxelWriteAheadLog.load(temporary,entry.id(),DIMENSION,SECTION).phase());
        assertEquals(VoxelJournalRecovery.Status.ALREADY_RESOLVED,
                VoxelJournalRecovery.recover(world,temporary,entry.id(),DIMENSION,SECTION).status());
        assertEquals(4,world.writes);
    }

    @Test void recoverFullyAppliedTransactionEvenIfAppliedPhaseWasNeverSaved() throws IOException {
        var entry=create();var world=new InMemoryWorld();
        assertEquals(VoxelWorldTransaction.Status.APPLIED,
                VoxelWorldTransaction.apply(world,SECTION,transition()).status());
        var outcome=VoxelJournalRecovery.recover(world,temporary,entry.id(),DIMENSION,SECTION);
        assertEquals(VoxelJournalRecovery.Status.RECOVERED,outcome.status());
        assertEquals(3,outcome.restored());
        assertArrayEquals(original(),world.blocks);
    }

    @Test void recoverAfterDurableAppliedMarkerAndRejectStalePhaseUpdate() throws IOException {
        var entry=create();var world=new InMemoryWorld();
        assertEquals(VoxelWorldTransaction.Status.APPLIED,
                VoxelWorldTransaction.apply(world,SECTION,transition()).status());
        var applied=VoxelWriteAheadLog.advance(temporary,entry,VoxelWriteAheadLog.Phase.APPLIED);
        assertEquals(VoxelWriteAheadLog.Phase.APPLIED,applied.phase());
        assertThrows(IOException.class,()->VoxelWriteAheadLog.advance(
                temporary,entry,VoxelWriteAheadLog.Phase.RESOLVED));
        assertEquals(VoxelJournalRecovery.Status.RECOVERED,
                VoxelJournalRecovery.recover(world,temporary,entry.id(),DIMENSION,SECTION).status());
        assertArrayEquals(original(),world.blocks);
        assertThrows(IOException.class,()->VoxelWriteAheadLog.advance(
                temporary,applied,VoxelWriteAheadLog.Phase.RESOLVED));
    }

    @Test void unknownChangedAndUnchangedBlocksOrUnsafePlayerRefuseRecovery() throws IOException {
        var entry=create();var world=new InMemoryWorld();
        world.blocks[VoxelSectionSurface.index(2,2,2)]=VoxelSectionSurface.AIR;
        assertEquals(VoxelJournalRecovery.Status.DIVERGED,
                VoxelJournalRecovery.recover(world,temporary,entry.id(),DIMENSION,SECTION).status());
        assertEquals(0,world.writes);
        world.blocks[VoxelSectionSurface.index(2,2,2)]=VoxelSectionSurface.STONE;
        world.safe=false;
        assertEquals(VoxelJournalRecovery.Status.UNSAFE,
                VoxelJournalRecovery.recover(world,temporary,entry.id(),DIMENSION,SECTION).status());
        assertEquals(0,world.writes);
        world.safe=true;
        assertEquals(VoxelJournalRecovery.Status.RECOVERED,
                VoxelJournalRecovery.recover(world,temporary,entry.id(),DIMENSION,SECTION).status());
    }

    @Test void failedRecoveryWriteKeepsWalPendingAndSupportsIdempotentRetry() throws IOException {
        var entry=create();var world=new InMemoryWorld();
        var change=transition().changes().getLast().at(SECTION);
        world.write(change.x(),change.y(),change.z(),change.after());
        world.refuseAt=2;
        var failed=VoxelJournalRecovery.recover(world,temporary,entry.id(),DIMENSION,SECTION);
        assertEquals(VoxelJournalRecovery.Status.WRITE_FAILED,failed.status());
        assertEquals(VoxelWriteAheadLog.Phase.PREPARED,
                VoxelWriteAheadLog.load(temporary,entry.id(),DIMENSION,SECTION).phase());
        world.refuseAt=0;
        var recovered=VoxelJournalRecovery.recover(world,temporary,entry.id(),DIMENSION,SECTION);
        assertEquals(VoxelJournalRecovery.Status.RECOVERED,recovered.status());
        assertEquals(1,recovered.restored());
        assertArrayEquals(original(),world.blocks);
    }

    @Test void corruptionAndWrongWorldCannotAuthorizeAnyBlockWrites() throws IOException {
        var entry=create();var world=new InMemoryWorld();
        assertThrows(IOException.class,()->VoxelJournalRecovery.recover(
                world,temporary,entry.id(),"minecraft:the_nether",SECTION));
        assertThrows(IOException.class,()->VoxelJournalRecovery.recover(
                world,temporary,entry.id(),DIMENSION,new SectionTerrainMesh.Key(-1,12,-2)));
        assertThrows(IOException.class,()->VoxelWriteAheadLog.create(temporary,entry));
        Path path=VoxelWriteAheadLog.path(temporary,entry.id());
        byte[] bytes=Files.readAllBytes(path);
        bytes[bytes.length-70]^=0x41;
        Files.write(path,bytes);
        assertThrows(IOException.class,()->VoxelJournalRecovery.recover(
                world,temporary,entry.id(),DIMENSION,SECTION));
        assertEquals(0,world.writes);
    }

    @Test void snapshotCopiesAndSeamMutationValidationAreFailClosed() throws IOException {
        var entry=create();
        var before=entry.before();before[0]=VoxelSectionSurface.AIR;
        assertEquals(VoxelSectionSurface.STONE,entry.before()[0]);
        assertThrows(IllegalArgumentException.class,()->new VoxelWriteAheadLog.Entry(
                entry.id(),entry.dimension(),entry.section(),entry.revision(),
                entry.phase(),entry.before(),entry.before()));
        var wrong=entry.after();
        wrong[VoxelSectionSurface.index(0,8,8)]=VoxelSectionSurface.AIR;
        assertThrows(IllegalArgumentException.class,()->new VoxelWriteAheadLog.Entry(
                entry.id(),entry.dimension(),entry.section(),entry.revision(),
                entry.phase(),entry.before(),wrong));
    }
    @Test void unresolvedUntilRestoredWorldIsDurablyFlushed() throws IOException {
        var entry=create();
        var world=new InMemoryWorld();
        var change=transition().changes().getFirst().at(SECTION);
        world.write(change.x(),change.y(),change.z(),change.after());
        int[] flushes={0};
        var first=VoxelJournalRecovery.recover(world,temporary,entry.id(),DIMENSION,SECTION,
                ()->{flushes[0]++;return false;});
        assertEquals(VoxelJournalRecovery.Status.FLUSH_FAILED,first.status());
        assertEquals(1,first.restored());
        assertEquals(VoxelWriteAheadLog.Phase.PREPARED,
                VoxelWriteAheadLog.load(temporary,entry.id(),DIMENSION,SECTION).phase());
        assertArrayEquals(original(),world.blocks);
        var second=VoxelJournalRecovery.recover(world,temporary,entry.id(),DIMENSION,SECTION,
                ()->{flushes[0]++;return true;});
        assertEquals(VoxelJournalRecovery.Status.RECOVERED,second.status());
        assertEquals(0,second.restored());
        assertEquals(2,flushes[0]);
        assertEquals(VoxelWriteAheadLog.Phase.RESOLVED,
                VoxelWriteAheadLog.load(temporary,entry.id(),DIMENSION,SECTION).phase());
    }


}
