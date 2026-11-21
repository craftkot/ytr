package ru.ehtid.operator.reality;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * Immutable block-stepped CPU mesh for both sides of a surface revision.
 * Coordinates: tile-local X/Z, absolute Y. Each face is four XYZ vertices,
 * with outward CCW winding. Faces can be uploaded without recalculating the
 * surface on the rendering thread. The boundary neighbor heights must come
 * from the same immutable snapshot as the tile. This is NOT a vanilla chunk
 * mesh: caves, textures, lighting, fluids, GPU upload and server writes are
 * outside this primitive.
 */
public final class PreparedTerrainMesh {
    private static final int N = PreparedTerrainSurface.SIZE;
    public static final int MAX_QUADS = 3*N*N + 2*N;
    private final PreparedTerrainSurface surface;
    private final Mesh original;
    private final Mesh alternate;

    public static final class Mesh {
        private final int[] vertices;
        private final int[] heights;
        private Mesh(int[] vertices, int[] heights) {
            this.vertices = vertices;
            this.heights = heights;
        }
        public int quadCount() { return vertices.length / 12; }
        public IntBuffer vertices() { return IntBuffer.wrap(vertices).asReadOnlyBuffer(); }
        /** Proposed top plane for this column, not live Minecraft collision. */
        public int topY(int x, int z) {
            if (x<0 || z<0 || x>=N || z>=N) throw new IndexOutOfBoundsException();
            return heights[z*N+x];
        }
    }

    /** Neighbor order: west, east (indexed by z), north, south (indexed by x). */
    public static final class Halo {
        private final int[][] sides;
        public Halo(int[] west, int[] east, int[] north, int[] south) {
            sides = new int[][] { copy(west), copy(east), copy(north), copy(south) };
        }
        private static int[] copy(int[] input) {
            if (input == null || input.length != N)
                throw new IllegalArgumentException("Missing 32-column neighbor halo");
            return input.clone();
        }
        private int at(int side, int i) { return sides[side][i]; }
    }

    private PreparedTerrainMesh(PreparedTerrainSurface surface, Mesh original, Mesh alternate) {
        this.surface=surface; this.original=original; this.alternate=alternate;
    }

    public static CompletableFuture<PreparedTerrainMesh> prepareAsync(
            PreparedTerrainSurface surface, Halo halo, Executor worker) {
        Objects.requireNonNull(surface);
        Objects.requireNonNull(halo);
        return CompletableFuture.supplyAsync(() -> compile(surface,halo),
                Objects.requireNonNull(worker));
    }

    public static PreparedTerrainMesh compile(PreparedTerrainSurface surface, Halo halo) {
        Objects.requireNonNull(surface);
        Objects.requireNonNull(halo);
        return new PreparedTerrainMesh(surface, build(surface,halo,false),
                build(surface,halo,true));
    }

    private static Mesh build(PreparedTerrainSurface s, Halo halo, boolean alternative) {
        int[] heights=new int[N*N];
        for(int z=0;z<N;z++) for(int x=0;x<N;x++)
            heights[z*N+x]=alternative?s.after(x,z):s.before(x,z);
        int[] out=new int[MAX_QUADS*12];
        int at=0;
        for(int z=0;z<N;z++) for(int x=0;x<N;x++) {
            int y=heights[z*N+x];
            // Top face +Y.
            at=quad(out,at,x,y,z, x,y,z+1, x+1,y,z+1, x+1,y,z);
            int west=x>0?heights[z*N+x-1]:halo.at(0,z);
            int east=x<N-1?heights[z*N+x+1]:halo.at(1,z);
            int north=z>0?heights[(z-1)*N+x]:halo.at(2,x);
            int south=z<N-1?heights[(z+1)*N+x]:halo.at(3,x);
            // Only the higher column owns the vertical face. Shared edges
            // cannot introduce duplicate overlapping faces between two tiles.
            if(y>west) at=quad(out,at,x,west,z, x,west,z+1, x,y,z+1, x,y,z);
            if(y>east) at=quad(out,at,x+1,east,z+1, x+1,east,z, x+1,y,z, x+1,y,z+1);
            if(y>north) at=quad(out,at,x+1,north,z, x,north,z, x,y,z, x+1,y,z);
            if(y>south) at=quad(out,at,x,south,z+1, x+1,south,z+1, x+1,y,z+1, x,y,z+1);
        }
        return new Mesh(Arrays.copyOf(out,at),heights);
    }

    private static int quad(int[] out,int at,
            int ax,int ay,int az,int bx,int by,int bz,
            int cx,int cy,int cz,int dx,int dy,int dz) {
        out[at++]=ax;out[at++]=ay;out[at++]=az;
        out[at++]=bx;out[at++]=by;out[at++]=bz;
        out[at++]=cx;out[at++]=cy;out[at++]=cz;
        out[at++]=dx;out[at++]=dy;out[at++]=dz;
        return at;
    }

    public long revision() { return surface.revision(); }
    public int originX() { return surface.originX(); }
    public int originZ() { return surface.originZ(); }
    public Mesh original() { return original; }
    public Mesh alternate() { return alternate; }
    public boolean matchesOriginal(int[] current) { return surface.matchesOriginal(current); }
}
