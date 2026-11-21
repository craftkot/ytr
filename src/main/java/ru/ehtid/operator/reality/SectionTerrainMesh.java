package ru.ehtid.operator.reality;

import java.nio.IntBuffer;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Splits existing heightfield faces into Minecraft's 16^3 render-section
 * ownership units. No faces are added on artificial section boundaries.
 * Coordinates remain tile-local X/Z and absolute Y. A top face belongs
 * to the solid block immediately below; vertical walls belong to the
 * higher column, including when their plane lies on a section border.
 *
 * This is geometry partitioning only: a section is NOT safe to suppress
 * merely because it appears here. Full vanilla geometry/material coverage
 * must be established separately before replacing any section draw.
 */
public final class SectionTerrainMesh {
    public static final int MAX_SECTIONS=256;
    public static final int MAX_SPLIT_QUADS=100_000;
    public record Key(int x,int y,int z) {}
    public static final class Section {
        private final int[] data;
        private Section(int[] data) { this.data=data; }
        public int quadCount() { return data.length/12; }
        public IntBuffer vertices() { return IntBuffer.wrap(data).asReadOnlyBuffer(); }
    }
    private static final class Builder {
        int[] data=new int[96];
        int size;
        void add(int[] q,int low,int high,int clipLow,int clipHigh) {
            if(data.length-size<12) data=Arrays.copyOf(data,Math.multiplyExact(data.length,2));
            for(int i=0;i<12;i++) {
                int v=q[i];
                data[size++]=i%3==1 && low!=high ? (v==low?clipLow:clipHigh):v;
            }
        }
        Section freeze(){return new Section(Arrays.copyOf(data,size));}
    }

    private SectionTerrainMesh() {}

    public static Map<Key,Section> split(PreparedTerrainMesh pair,boolean alternate) {
        Objects.requireNonNull(pair);
        var vertices=(alternate?pair.alternate():pair.original()).vertices();
        Map<Key,Builder> split=new LinkedHashMap<>();
        int[] q=new int[12];
        int count=0;
        while(vertices.hasRemaining()) {
            vertices.get(q);
            int minX=q[0],maxX=q[0],minY=q[1],maxY=q[1],minZ=q[2],maxZ=q[2];
            for(int i=3;i<12;i+=3) {
                minX=Math.min(minX,q[i]); maxX=Math.max(maxX,q[i]);
                minY=Math.min(minY,q[i+1]);maxY=Math.max(maxY,q[i+1]);
                minZ=Math.min(minZ,q[i+2]);maxZ=Math.max(maxZ,q[i+2]);
            }
            long ax=(long)q[3]-q[0],ay=(long)q[4]-q[1],az=(long)q[5]-q[2];
            long bx=(long)q[6]-q[0],by=(long)q[7]-q[1],bz=(long)q[8]-q[2];
            long nx=ay*bz-az*by,ny=az*bx-ax*bz,nz=ax*by-ay*bx;
            int ownerX=minX,ownerZ=minZ;
            if(nx>0)ownerX--;
            if(nz>0)ownerZ--;
            if(ownerX<0||ownerX>=PreparedTerrainSurface.SIZE
                    ||ownerZ<0||ownerZ>=PreparedTerrainSurface.SIZE)
                throw new IllegalArgumentException("Face outside owner tile");
            int sx=Math.floorDiv(pair.originX()+ownerX,16);
            int sz=Math.floorDiv(pair.originZ()+ownerZ,16);
            if(minY==maxY) {
                if(ny<=0)throw new IllegalArgumentException("Expected upward surface face");
                int sy=Math.toIntExact(Math.floorDiv((long)minY-1,16));
                if(++count>MAX_SPLIT_QUADS)throw new IllegalArgumentException("Section face budget");
                builder(split,new Key(sx,sy,sz)).add(q,minY,maxY,minY,maxY);
            } else {
                if(ny!=0 || (nx==0)==(nz==0))
                    throw new IllegalArgumentException("Expected vertical axis-aligned face");
                long first=Math.floorDiv((long)minY,16);
                long last=Math.floorDiv((long)maxY-1,16);
                if(last-first>=MAX_SECTIONS)throw new IllegalArgumentException("Vertical face span");
                for(long sy=first;sy<=last;sy++) {
                    if(++count>MAX_SPLIT_QUADS)throw new IllegalArgumentException("Section face budget");
                    int low=Math.toIntExact(Math.max((long)minY,sy*16));
                    int high=Math.toIntExact(Math.min((long)maxY,(sy+1)*16));
                    builder(split,new Key(sx,Math.toIntExact(sy),sz)).add(q,minY,maxY,low,high);
                }
            }
        }
        Map<Key,Section> result=new LinkedHashMap<>();
        split.forEach((key,b)->result.put(key,b.freeze()));
        return java.util.Collections.unmodifiableMap(result);
    }
    private static Builder builder(Map<Key,Builder> all,Key key) {
        var existing=all.get(key);
        if(existing!=null)return existing;
        if(all.size()>=MAX_SECTIONS)throw new IllegalArgumentException("Section count budget");
        var next=new Builder();all.put(key,next);return next;
    }
}
