package ru.ehtid.operator.reality;

import java.util.ArrayList;
import java.util.List;

/**
 * Deterministic forest-to-snowy-taiga BLOCK transformation in a small inner
 * part of a prepared 32x64x32 biome region. The edge is left untouched:
 * this is a fixture-scale physical terrain/foliage proof, not a new world
 * generator or a licence to replace arbitrary player-built structures.
 *
 * No Minecraft classes are required here, so every planned voxel can be
 * tested before a server or the render thread is involved.
 */
public final class BiomeSurfacePattern {
    public static final int MIN_X=-24,MAX_X=-9,MIN_Z=-55,MAX_Z=-38;
    public static final int GROUND_Y=199,MAX_Y=208;
    public static final int MAX_CHANGED=1024;
    public enum Material {
        AIR,GRASS,SNOW_BLOCK,OAK_LOG,OAK_LEAVES,SPRUCE_LOG,SPRUCE_LEAVES
    }
    public record Cell(int x,int y,int z,Material before,Material after) {
        public Cell {
            if(before==after || x<MIN_X||x>MAX_X || z<MIN_Z||z>MAX_Z
                    || y<GROUND_Y || y>MAX_Y)
                throw new IllegalArgumentException("Invalid snow/forest voxel change");
        }
    }
    private static final int[][] TREES={{-19,-49},{-13,-43}};
    private BiomeSurfacePattern(){}

    public static Material at(int x,int y,int z,boolean snowy) {
        if(x<MIN_X||x>MAX_X || z<MIN_Z||z>MAX_Z
                || y<GROUND_Y||y>MAX_Y)
            throw new IndexOutOfBoundsException("Outside physical biome fixture");
        if(y==GROUND_Y)
            return snowy && snowfall(x,z)?Material.SNOW_BLOCK:Material.GRASS;
        for(int[] tree:TREES) {
            int dx=Math.abs(x-tree[0]),dz=Math.abs(z-tree[1]);
            if(dx==0 && dz==0 && y<=(snowy?207:203))
                return snowy?Material.SPRUCE_LOG:Material.OAK_LOG;
            int radius=snowy?spruceRadius(y):oakRadius(y);
            if(radius>=0 && dx<=radius && dz<=radius
                    && (Math.max(dx,dz)>0 || y>(snowy?207:203)))
                return snowy?Material.SPRUCE_LEAVES:Material.OAK_LEAVES;
        }
        return Material.AIR;
    }

    private static boolean snowfall(int x,int z) {
        if(x>=-22&&x<=-11 && z>=-53&&z<=-40)return true;
        long mix=((long)x*0x9E3779B97F4A7C15L)
                ^((long)z*0xC2B2AE3D27D4EB4FL);
        mix^=mix>>>30;
        mix*=0xBF58476D1CE4E5B9L;
        return (mix&3L)==0;
    }
    private static int oakRadius(int y) {
        return switch(y){case 203,204->2;case 205->1;default->-1;};
    }
    private static int spruceRadius(int y) {
        return switch(y) {
            case 202,204->2;
            case 203,205,206->1;
            case 207,208->0;
            default->-1;
        };
    }

    public static List<Cell> changes() {
        var changes=new ArrayList<Cell>();
        for(int y=GROUND_Y;y<=MAX_Y;y++)
            for(int z=MIN_Z;z<=MAX_Z;z++)
                for(int x=MIN_X;x<=MAX_X;x++) {
                    var before=at(x,y,z,false);
                    var after=at(x,y,z,true);
                    if(before!=after) {
                        if(changes.size()>=MAX_CHANGED)
                            throw new IllegalStateException("Terrain change budget exceeded");
                        changes.add(new Cell(x,y,z,before,after));
                    }
                }
        return List.copyOf(changes);
    }
}
