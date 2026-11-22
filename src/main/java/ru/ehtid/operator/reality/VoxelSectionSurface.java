package ru.ehtid.operator.reality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Strict stone-cube voxel mesher. Unlike a heightfield it preserves sealed
 * spaces, tunnels, cave ceilings and vertical surfaces at arbitrary Y.
 *
 * All inputs are immutable snapshots: the worker cannot query live chunks.
 * A face is emitted only when a STONE cube borders AIR. All six neighboring
 * section faces are supplied explicitly, so contiguous stone across loaded
 * section boundaries never creates duplicate internal walls.
 *
 * Only AIR and full-cube STONE are supported in this first proof. Any
 * arbitrary Minecraft block state, fluid, non-full collision cube or
 * missing neighbor MUST be marked UNSUPPORTED and causes fail-closed
 * rejection. This output has no texture, light or collision representation
 * and is NOT authority to suppress vanilla section rendering.
 */
public final class VoxelSectionSurface {
    public static final int SIZE=16, VOLUME=SIZE*SIZE*SIZE, FACE_CELLS=SIZE*SIZE;
    public static final byte AIR=0,STONE=1,UNSUPPORTED=2;
    public enum Face {
        WEST(-1,0,0),EAST(1,0,0),
        DOWN(0,-1,0),UP(0,1,0),
        NORTH(0,0,-1),SOUTH(0,0,1);
        final int dx,dy,dz;
        Face(int dx,int dy,int dz){this.dx=dx;this.dy=dy;this.dz=dz;}
    }
    public record Quad(int x,int y,int z,Face face) {
        public Quad {
            if(x<0 || x>=SIZE || y<0 || y>=SIZE || z<0 || z>=SIZE)
                throw new IllegalArgumentException("Outside voxel section");
            Objects.requireNonNull(face);
        }
    }
    public record Mesh(List<Quad> quads,int stoneCount,int airCount) {
        public Mesh {
            quads=List.copyOf(quads);
            if(stoneCount<0 || airCount<0 || stoneCount+airCount!=VOLUME)
                throw new IllegalArgumentException("Invalid voxel totals");
        }
        public int faceCount(Face face) {
            return (int)quads.stream().filter(quad->quad.face()==face).count();
        }
    }

    /**
     * Neighbor faces are specified in the same order as Face:
     *  west/east index=y*16+z, down/up index=z*16+x,
     *  north/south index=y*16+x.
     */
    public static final class Halo {
        private final byte[][] faces;
        public Halo(byte[] west,byte[] east,byte[] down,byte[] up,
                    byte[] north,byte[] south) {
            byte[][] sources={west,east,down,up,north,south};
            faces=new byte[Face.values().length][];
            for(var face:Face.values()) {
                byte[] side=Objects.requireNonNull(sources[face.ordinal()]);
                if(side.length!=FACE_CELLS)throw new IllegalArgumentException("Incomplete neighbor face");
                faces[face.ordinal()]=side.clone();
                for(byte cell:side)requireSupported(cell);
            }
        }
        public static Halo filled(byte kind) {
            requireSupported(kind);
            byte[] all=new byte[FACE_CELLS];
            Arrays.fill(all,kind);
            return new Halo(all,all,all,all,all,all);
        }
        private byte at(Face face,int x,int y,int z) {
            int index=switch(face) {
                case WEST,EAST -> y*SIZE+z;
                case DOWN,UP -> z*SIZE+x;
                case NORTH,SOUTH -> y*SIZE+x;
            };
            return faces[face.ordinal()][index];
        }
    }

    private final byte[] blocks;
    private final Halo halo;

    public VoxelSectionSurface(byte[] blocks,Halo halo) {
        if(blocks==null || blocks.length!=VOLUME)
            throw new IllegalArgumentException("Incomplete voxel section");
        this.blocks=blocks.clone();
        for(byte cell:this.blocks)requireSupported(cell);
        this.halo=Objects.requireNonNull(halo);
    }
    public byte cell(int x,int y,int z) {
        return blocks[index(x,y,z)];
    }
    public static int index(int x,int y,int z) {
        if(x<0 || x>=SIZE || y<0 || y>=SIZE || z<0 || z>=SIZE)
            throw new IndexOutOfBoundsException();
        return (y*SIZE+z)*SIZE+x;
    }
    private static void requireSupported(byte cell) {
        if(cell!=AIR && cell!=STONE)throw new IllegalArgumentException(
                "Unsupported or missing Minecraft voxel");
    }
    public Mesh compile() {
        var quads=new ArrayList<Quad>();
        int stones=0;
        for(int y=0;y<SIZE;y++)for(int z=0;z<SIZE;z++)for(int x=0;x<SIZE;x++) {
            if(cell(x,y,z)==AIR)continue;
            stones++;
            for(var face:Face.values()) {
                int nx=x+face.dx,ny=y+face.dy,nz=z+face.dz;
                byte neighbor=(nx<0 || nx>=SIZE || ny<0 || ny>=SIZE || nz<0 || nz>=SIZE)
                        ?halo.at(face,x,y,z):cell(nx,ny,nz);
                if(neighbor==AIR)quads.add(new Quad(x,y,z,face));
            }
        }
        return new Mesh(quads,stones,VOLUME-stones);
    }
}
