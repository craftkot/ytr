package ru.ehtid.operator.reality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Immutable, bounded before/after voxel-revision plan for ONE 16^3 section.
 *
 * Every changed cell must stay strictly inside the section. The unchanged
 * one-block boundary and an identical immutable six-face halo guarantee
 * that independently built neighbor sections cannot acquire an open seam.
 * More elaborate multi-section boundary changes require a separate atomic
 * transaction/collision plan and are deliberately refused here.
 *
 * The plan is not an authorization to change a live Minecraft world. It
 * does not prove player invisibility, server-side consistency, collision
 * safety, persistence or the availability of vanilla materials and light.
 */
public final class VoxelSectionTransition {
    public static final int MAX_CHANGES=512;
    public record Change(int index,byte before,byte after) {
        public Change {
            if(index<0 || index>=VoxelSectionSurface.VOLUME
                    || before==after
                    || (before!=VoxelSectionSurface.AIR && before!=VoxelSectionSurface.STONE)
                    || (after!=VoxelSectionSurface.AIR && after!=VoxelSectionSurface.STONE))
                throw new IllegalArgumentException("Invalid cubic section mutation");
        }
        public int x(){return index%VoxelSectionSurface.SIZE;}
        public int y(){return index/(VoxelSectionSurface.SIZE*VoxelSectionSurface.SIZE);}
        public int z(){return (index/VoxelSectionSurface.SIZE)%VoxelSectionSurface.SIZE;}
        public WorldChange at(SectionTerrainMesh.Key section) {
            Objects.requireNonNull(section);
            int n=VoxelSectionSurface.SIZE;
            return new WorldChange(Math.addExact(Math.multiplyExact(section.x(),n),x()),
                    Math.addExact(Math.multiplyExact(section.y(),n),y()),
                    Math.addExact(Math.multiplyExact(section.z(),n),z()),before,after);
        }
    }
    public record WorldChange(int x,int y,int z,byte before,byte after) {}
    private final long revision;
    private final byte[] original,alternate;
    private final VoxelSectionSurface.Mesh beforeMesh,afterMesh;
    private final List<Change> changes;

    private VoxelSectionTransition(long revision,byte[] original,byte[] alternate,
                                   VoxelSectionSurface.Mesh beforeMesh,
                                   VoxelSectionSurface.Mesh afterMesh,List<Change> changes) {
        this.revision=revision;this.original=original;this.alternate=alternate;
        this.beforeMesh=beforeMesh;this.afterMesh=afterMesh;
        this.changes=List.copyOf(changes);
    }

    public static VoxelSectionTransition prepare(long revision,byte[] before,byte[] after,
                                                  VoxelSectionSurface.Halo halo) {
        if(revision<=0)throw new IllegalArgumentException("Revision must be positive");
        var old=new VoxelSectionSurface(before,halo);
        var next=new VoxelSectionSurface(after,halo);
        var changes=new ArrayList<Change>();
        for(int i=0;i<VoxelSectionSurface.VOLUME;i++) {
            int x=i%16,y=i/256,z=(i/16)%16;
            byte from=old.cell(x,y,z),to=next.cell(x,y,z);
            if(from==to)continue;
            if(x==0||x==15||y==0||y==15||z==0||z==15)
                throw new IllegalArgumentException("Changing section boundary requires atomic neighbor update");
            if(changes.size()==MAX_CHANGES)
                throw new IllegalArgumentException("Section mutation budget exceeded");
            changes.add(new Change(i,from,to));
        }
        if(changes.isEmpty())throw new IllegalArgumentException("No volumetric transition");
        return new VoxelSectionTransition(revision,before.clone(),after.clone(),
                old.compile(),next.compile(),changes);
    }
    public long revision(){return revision;}
    public List<Change> changes(){return changes;}
    public VoxelSectionSurface.Mesh originalMesh(){return beforeMesh;}
    public VoxelSectionSurface.Mesh alternateMesh(){return afterMesh;}
    public boolean matchesOriginal(byte[] live) {
        return live!=null && Arrays.equals(original,live);
    }
    public boolean matchesAlternate(byte[] live) {
        return live!=null && Arrays.equals(alternate,live);
    }
    public byte[] originalSnapshot(){return original.clone();}
    public byte[] alternateSnapshot(){return alternate.clone();}
}
