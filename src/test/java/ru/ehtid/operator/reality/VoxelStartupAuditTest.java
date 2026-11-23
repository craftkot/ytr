package ru.ehtid.operator.reality;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class VoxelStartupAuditTest {
    @TempDir Path directory;
    private static final String DIMENSION="minecraft:overworld";
    private static final SectionTerrainMesh.Key SECTION=
            new SectionTerrainMesh.Key(-2,12,-2);

    private VoxelWriteAheadLog.Entry create() throws IOException {
        byte[] before=new byte[4096];
        Arrays.fill(before,VoxelSectionSurface.STONE);
        byte[] after=before.clone();
        after[VoxelSectionSurface.index(8,8,8)]=VoxelSectionSurface.AIR;
        var plan=VoxelSectionTransition.prepare(2,before,after,
                VoxelSectionSurface.Halo.filled(VoxelSectionSurface.STONE));
        var entry=VoxelWriteAheadLog.prepare(UUID.randomUUID(),DIMENSION,SECTION,plan);
        VoxelWriteAheadLog.create(directory,entry);
        return entry;
    }

    @Test void noJournalDirectoryIsSafeAndHasNoPending() throws IOException {
        var scan=VoxelStartupAudit.scan(directory.resolve("does-not-exist"),DIMENSION);
        assertEquals(0,scan.total());
        assertFalse(scan.blocksNewWrites());
        assertTrue(scan.pending().isEmpty());
    }

    @Test void preparedAndAppliedBothBlockNewChangesUntilDurableResolution() throws IOException {
        var entry=create();
        var scan=VoxelStartupAudit.scan(directory,DIMENSION);
        assertEquals(1,scan.total());
        assertEquals(0,scan.resolved());
        assertEquals(1,scan.pending().size());
        assertTrue(scan.blocksNewWrites());
        assertEquals(entry.id(),scan.pending().getFirst().id());
        assertThrows(UnsupportedOperationException.class,()->scan.pending().clear());

        var applied=VoxelWriteAheadLog.advance(
                directory,entry,VoxelWriteAheadLog.Phase.APPLIED);
        assertEquals(VoxelWriteAheadLog.Phase.APPLIED,
                VoxelStartupAudit.scan(directory,DIMENSION).pending().getFirst().phase());
        VoxelWriteAheadLog.advance(directory,applied,VoxelWriteAheadLog.Phase.RESOLVED);
        var resolved=VoxelStartupAudit.scan(directory,DIMENSION);
        assertEquals(1,resolved.total());
        assertEquals(1,resolved.resolved());
        assertFalse(resolved.blocksNewWrites());
    }

    @Test void evenOneUnfinishedWalBlocksWorldWithResolvedHistory() throws IOException {
        var first=create();
        VoxelWriteAheadLog.advance(directory,first,VoxelWriteAheadLog.Phase.RESOLVED);
        var second=create();
        var result=VoxelStartupAudit.scan(directory,DIMENSION);
        assertEquals(2,result.total());
        assertEquals(1,result.resolved());
        assertEquals(1,result.pending().size());
        assertEquals(second.id(),result.pending().getFirst().id());
        assertTrue(result.blocksNewWrites());
    }

    @Test void corruptedOrUnexpectedRecordsMustFailWholeStartupScan() throws IOException {
        var entry=create();
        assertThrows(IOException.class,()->VoxelStartupAudit.scan(directory,"minecraft:the_nether"));
        Path path=VoxelWriteAheadLog.path(directory,entry.id());
        var bytes=Files.readAllBytes(path);
        bytes[bytes.length-40]^=7;
        Files.write(path,bytes);
        assertThrows(IOException.class,()->VoxelStartupAudit.scan(directory,DIMENSION));
        Files.delete(path);
        Files.writeString(directory.resolve("voxel-stale.tmp"),"pending interrupted atomic write");
        assertThrows(IOException.class,()->VoxelStartupAudit.scan(directory,DIMENSION));
    }

    @Test void noncanonicalUuidAndSymlinkDirectoryAreNeverTrusted() throws IOException {
        Files.writeString(directory.resolve("1234.vwal"),"broken journal");
        assertThrows(IOException.class,()->VoxelStartupAudit.scan(directory,DIMENSION));
        var target=directory.resolve("linked");
        try {
            Files.createSymbolicLink(target,directory);
            assertThrows(IOException.class,()->VoxelStartupAudit.scan(target,DIMENSION));
        } catch(UnsupportedOperationException | java.nio.file.FileSystemException unsupported) {
            // Some Windows hosts forbid symlinks; directory audit still fails.
        }
    }
}
