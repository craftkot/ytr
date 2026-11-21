package ru.ehtid.operator.reality;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Immutable CPU-side alternative surface for a 2x2-chunk tile. The server or
 * client must capture real input heights on its owning game thread, then
 * schedule the pure calculation on a worker. No Minecraft API access here.
 *
 * This is a heightfield planning primitive, not a full voxel/biome generator,
 * not a compiled GPU mesh, and not authorization to modify a world.
 */
public final class PreparedTerrainSurface {
    public static final int SIZE = 32;
    public static final int HALO = 2;
    public static final int BLEND_WIDTH = 8;
    public static final int MAX_DELTA = 4;
    private static final int CELLS = SIZE * SIZE;

    private final int originX, originZ;
    private final long revision;
    private final int[] before, after;

    private PreparedTerrainSurface(int originX, int originZ, long revision,
                                   int[] before, int[] after) {
        this.originX = originX;
        this.originZ = originZ;
        this.revision = revision;
        this.before = before;
        this.after = after;
    }

    /**
     * Copies the mutable input before scheduling; subsequent changes to the
     * source array cannot leak into this revision. No worker touches game state.
     */
    public static CompletableFuture<PreparedTerrainSurface> prepareAsync(
            int originX, int originZ, long worldSeed, long revision,
            int minY, int maxY, int[] original, Executor worker) {
        int[] snapshot = validatedCopy(originX, originZ, revision, minY, maxY, original);
        Objects.requireNonNull(worker);
        return CompletableFuture.supplyAsync(() -> generate(
                originX, originZ, worldSeed, revision, minY, maxY, snapshot),worker);
    }

    public static PreparedTerrainSurface prepare(
            int originX, int originZ, long worldSeed, long revision,
            int minY, int maxY, int[] original) {
        return generate(originX, originZ, worldSeed, revision, minY, maxY,
                validatedCopy(originX, originZ, revision, minY, maxY, original));
    }

    private static int[] validatedCopy(int x, int z, long revision,
                                      int minY, int maxY, int[] original) {
        if (Math.floorMod(x, SIZE) != 0 || Math.floorMod(z, SIZE) != 0
                || x < -29_999_968 || x > 29_999_968
                || z < -29_999_968 || z > 29_999_968
                || revision < 0 || minY >= maxY
                || original == null || original.length != CELLS)
            throw new IllegalArgumentException("Invalid terrain surface snapshot");
        int[] snapshot = original.clone();
        for (int y : snapshot)
            if (y < minY || y > maxY) throw new IllegalArgumentException("Out-of-world height");
        return snapshot;
    }

    private static PreparedTerrainSurface generate(int x0, int z0, long seed,
                  long revision, int minY, int maxY, int[] snapshot) {
        int[] next = snapshot.clone();
        for (int z=0; z<SIZE; z++) {
            for (int x=0; x<SIZE; x++) {
                int edge = Math.min(Math.min(x, SIZE-1-x), Math.min(z,SIZE-1-z));
                if (edge < HALO) continue;
                // The blend ramp starts with two identical rows of original
                // columns. Neighboring independently prepared tiles therefore
                // cannot add jumps at their shared edge.
                double t = Math.min(1D, (double)(edge-HALO+1)/BLEND_WIDTH);
                double fade = t*t*(3D-2D*t);
                double noise = smoothNoise(x0+x,z0+z,seed,revision);
                int delta = (int)Math.round(MAX_DELTA * fade * noise);
                int i = z*SIZE+x;
                next[i] = (int)Math.max(minY, Math.min((long)maxY, (long)snapshot[i]+delta));
            }
        }
        return new PreparedTerrainSurface(x0,z0,revision,snapshot,next);
    }

    private static double smoothNoise(int x,int z,long seed,long revision) {
        int period=16;
        int gx=Math.floorDiv(x,period), gz=Math.floorDiv(z,period);
        double tx=Math.floorMod(x,period)/(double)period;
        double tz=Math.floorMod(z,period)/(double)period;
        tx=tx*tx*(3D-2D*tx);
        tz=tz*tz*(3D-2D*tz);
        double a=lerp(noise(gx,gz,seed,revision),noise(gx+1,gz,seed,revision),tx);
        double b=lerp(noise(gx,gz+1,seed,revision),noise(gx+1,gz+1,seed,revision),tx);
        return lerp(a,b,tz);
    }

    private static double noise(int x,int z,long seed,long revision) {
        long h=seed ^ (revision*0x9E3779B97F4A7C15L)
                ^ (x*0xD1B54A32D192ED03L) ^ (z*0x94D049BB133111EBL);
        h=(h^(h>>>30))*0xBF58476D1CE4E5B9L;
        h=(h^(h>>>27))*0x94D049BB133111EBL;
        h^=h>>>31;
        return ((h>>>11)*0x1.0p-53)*2D-1D;
    }

    private static double lerp(double a,double b,double t) { return a+(b-a)*t; }
    private static int index(int x,int z) {
        if (x<0 || x>=SIZE || z<0 || z>=SIZE)
            throw new IndexOutOfBoundsException("Local surface coordinate");
        return z*SIZE+x;
    }

    public long revision() { return revision; }
    public int originX() { return originX; }
    public int originZ() { return originZ; }
    public int before(int x,int z) { return before[index(x,z)]; }
    public int after(int x,int z) { return after[index(x,z)]; }
    public int changedColumns() {
        int count=0;
        for(int i=0;i<CELLS;i++) if (before[i]!=after[i]) count++;
        return count;
    }
    /** Stale snapshots fail closed before any future authoritative apply. */
    public boolean matchesOriginal(int[] current) {
        return java.util.Arrays.equals(before,current);
    }
}
