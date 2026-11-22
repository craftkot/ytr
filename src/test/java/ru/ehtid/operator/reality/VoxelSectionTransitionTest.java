package ru.ehtid.operator.reality;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VoxelSectionTransitionTest {
    private static byte[] stone() {
        byte[] result=new byte[VoxelSectionSurface.VOLUME];
        Arrays.fill(result,VoxelSectionSurface.STONE);
        return result;
    }
    private static VoxelSectionSurface.Halo halo() {
        return VoxelSectionSurface.Halo.filled(VoxelSectionSurface.STONE);
    }

    @Test void interiorCaveAppearsWithAllExternalSectionBoundariesUnchanged() {
        byte[] before=stone(),after=before.clone();
        int index=VoxelSectionSurface.index(7,8,9);
        after[index]=VoxelSectionSurface.AIR;
        var revision=VoxelSectionTransition.prepare(2,before,after,halo());
        assertEquals(1,revision.changes().size());
        var change=revision.changes().getFirst();
        assertEquals(index,change.index());
        assertEquals(7,change.x());assertEquals(8,change.y());assertEquals(9,change.z());
        assertEquals(0,revision.originalMesh().quads().size());
        assertEquals(6,revision.alternateMesh().quads().size());
        var world=change.at(new SectionTerrainMesh.Key(-2,12,-2));
        assertEquals(-25,world.x());
        assertEquals(200,world.y());
        assertEquals(-23,world.z());
        assertEquals(VoxelSectionSurface.STONE,world.before());
        assertEquals(VoxelSectionSurface.AIR,world.after());
        assertTrue(revision.matchesOriginal(before));
        assertTrue(revision.matchesAlternate(after));
        Arrays.fill(before,VoxelSectionSurface.AIR);
        Arrays.fill(after,VoxelSectionSurface.STONE);
        assertFalse(revision.matchesOriginal(before));
        assertFalse(revision.matchesAlternate(after));
        assertEquals(VoxelSectionSurface.STONE,revision.originalSnapshot()[index]);
        assertEquals(VoxelSectionSurface.AIR,revision.alternateSnapshot()[index]);
    }

    @Test void rejectEveryExternalBoundaryAndUnsupportedOrMissingState() {
        int[][] edges={{0,7,7},{15,7,7},{7,0,7},{7,15,7},{7,7,0},{7,7,15}};
        for(var edge:edges) {
            byte[] before=stone(),after=before.clone();
            after[VoxelSectionSurface.index(edge[0],edge[1],edge[2])]=VoxelSectionSurface.AIR;
            assertThrows(IllegalArgumentException.class,
                    ()->VoxelSectionTransition.prepare(1,before,after,halo()));
        }
        byte[] before=stone(),after=before.clone();
        after[VoxelSectionSurface.index(7,7,7)]=VoxelSectionSurface.UNSUPPORTED;
        assertThrows(IllegalArgumentException.class,
                ()->VoxelSectionTransition.prepare(1,before,after,halo()));
        assertThrows(IllegalArgumentException.class,
                ()->VoxelSectionTransition.prepare(0,before,before,halo()));
        assertThrows(IllegalArgumentException.class,
                ()->VoxelSectionTransition.prepare(1,before,before,halo()));
    }

    @Test void mutationCountIsBoundedAndWorldCellOrderingStable() {
        byte[] before=stone(),after=before.clone();
        for(int i=0;i<512;i++) {
            int x=1+i%14,z=1+(i/14)%14,y=1+i/(14*14);
            after[VoxelSectionSurface.index(x,y,z)]=VoxelSectionSurface.AIR;
        }
        var revision=VoxelSectionTransition.prepare(3,before,after,halo());
        assertEquals(512,revision.changes().size());
        assertTrue(revision.alternateMesh().quads().size()>0);
        assertTrue(revision.alternateMesh().quads().size()<=512*6);
        assertEquals(0,revision.originalMesh().quads().size());
        for(var change:revision.changes())
            assertTrue(change.x()>0 && change.x()<15
                    && change.y()>0 && change.y()<15
                    && change.z()>0 && change.z()<15);
        after[VoxelSectionSurface.index(14,14,14)]=VoxelSectionSurface.AIR;
        assertThrows(IllegalArgumentException.class,
                ()->VoxelSectionTransition.prepare(4,before,after,halo()));
    }

    @Test void snapshotCopiesAndChangesAreImmutable() {
        byte[] before=stone(),after=before.clone();
        after[VoxelSectionSurface.index(2,2,2)]=VoxelSectionSurface.AIR;
        var plan=VoxelSectionTransition.prepare(1,before,after,halo());
        var external=plan.alternateSnapshot();
        Arrays.fill(external,VoxelSectionSurface.AIR);
        assertTrue(plan.matchesAlternate(after));
        assertThrows(UnsupportedOperationException.class,()->plan.changes().clear());
        assertThrows(IllegalArgumentException.class,
                ()->new VoxelSectionTransition.Change(0,VoxelSectionSurface.STONE,
                        VoxelSectionSurface.STONE));
    }
}
