package ru.ehtid.operator.reality;

import java.util.Arrays;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable quart-resolution (4-block) BIOME DATA preview for an entire
 * 32x64x32 Minecraft region. Biome identity is independent from BlockState.
 *
 * All six outside quart-cell faces are unchanged. A deterministic, irregular
 * transitional band lies INSIDE those faces, so neighboring regions cannot
 * suddenly disagree at their ownership boundary. This deliberately does NOT
 * change terrain, climate rendering, light, fluids, features or collisions.
 *
 * This is a PREPARED DATA PLAN, not authority to edit a Minecraft chunk's
 * biome container, send biome packets, or swap any vanilla draw geometry.
 */
public final class BiomeRegionBlueprint {
    public static final int BLOCK_SIZE=4;
    public static final int WIDTH=8, HEIGHT=16;
    public static final int CELLS=WIDTH*HEIGHT*WIDTH;
    private static final Pattern ID=Pattern.compile("[a-z0-9_.-]+:[a-z0-9_./-]+");

    public record Origin(int x,int y,int z) {
        public Origin {
            if(x%32!=0 || z%32!=0 || y%64!=0)
                throw new IllegalArgumentException("Biome origin is not quart-aligned to region");
            Math.addExact(x,32);
            Math.addExact(y,64);
            Math.addExact(z,32);
        }
        public FrameRealitySelector.Bounds bounds() {
            return new FrameRealitySelector.Bounds(x,y,z,x+32D,y+64D,z+32D);
        }
    }

    private final Origin origin;
    private final String[] before,after;
    private final String target;
    private final long revision;
    private final int mutations;

    private BiomeRegionBlueprint(Origin origin,String[] before,String[] after,
                                 String target,long revision,int mutations) {
        this.origin=origin;
        this.before=before.clone();this.after=after.clone();
        this.target=target;this.revision=revision;this.mutations=mutations;
    }

    public static int index(int x,int y,int z) {
        if(x<0||x>=WIDTH||z<0||z>=WIDTH||y<0||y>=HEIGHT)
            throw new IndexOutOfBoundsException("Quart coordinate outside biome region");
        return (y*WIDTH+z)*WIDTH+x;
    }

    /**
     * Prepare a forest -> tundra (or any biome identity) transition strictly
     * from a verified complete source snapshot, with no live world access.
     * The core x/z=2..5 is changed, the inner 1/6 band is irregularly blended,
     * the 0/7 boundary and top/bottom y slices remain the exact original.
     */
    public static BiomeRegionBlueprint prepare(Origin origin,String[] original,
                                               String target,long revision,long seed) {
        Objects.requireNonNull(origin);
        if(original==null || original.length!=CELLS)
            throw new IllegalArgumentException("Incomplete biome source region");
        if(target==null || !ID.matcher(target).matches() || revision<=0)
            throw new IllegalArgumentException("Bad biome revision/target");
        String[] source=original.clone();
        for(String id:source)
            if(id==null || !ID.matcher(id).matches())
                throw new IllegalArgumentException("Unregistered biome identity or unloaded quart");
        String[] output=source.clone();
        int changed=0;
        for(int y=1;y<HEIGHT-1;y++)for(int z=1;z<WIDTH-1;z++)
            for(int x=1;x<WIDTH-1;x++) {
                boolean interior=x>=2 && x<=5 && z>=2 && z<=5;
                // Stable over Y for continuous natural-looking biome columns.
                boolean band=(mix(seed,origin.x()/4+x,origin.z()/4+z)&3L)==0;
                if(interior||band) {
                    int i=index(x,y,z);
                    if(!source[i].equals(target)) {
                        output[i]=target;
                        changed++;
                    }
                }
            }
        if(changed==0)
            throw new IllegalArgumentException("No novel biome state to transition");
        return new BiomeRegionBlueprint(origin,source,output,target,revision,changed);
    }

    private static long mix(long seed,long x,long z) {
        long value=seed ^ (x*0x9E3779B97F4A7C15L)
                ^ (z*0xC2B2AE3D27D4EB4FL);
        value^=value>>>30;
        value*=0xBF58476D1CE4E5B9L;
        value^=value>>>27;
        value*=0x94D049BB133111EBL;
        return value^(value>>>31);
    }

    public Origin origin(){return origin;}
    public String target(){return target;}
    public long revision(){return revision;}
    public int changedQuartCells(){return mutations;}
    public String before(int x,int y,int z){return before[index(x,y,z)];}
    public String after(int x,int y,int z){return after[index(x,y,z)];}
    public String[] originalSnapshot(){return before.clone();}
    public String[] alternateSnapshot(){return after.clone();}

    public boolean matchesOriginal(String[] live) {
        return live!=null && Arrays.equals(before,live);
    }
    public boolean matchesAlternate(String[] live) {
        return live!=null && Arrays.equals(after,live);
    }
    public boolean boundaryUnchanged() {
        for(int y=0;y<HEIGHT;y++)for(int z=0;z<WIDTH;z++)for(int x=0;x<WIDTH;x++)
            if((x==0||x==WIDTH-1||z==0||z==WIDTH-1||y==0||y==HEIGHT-1)
                    && !before[index(x,y,z)].equals(after[index(x,y,z)]))
                return false;
        return true;
    }
}
