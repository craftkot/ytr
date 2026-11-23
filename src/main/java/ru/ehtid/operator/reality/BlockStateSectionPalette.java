package ru.ehtid.operator.reality;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Full 16^3 block-state palette, including the six neighbor-section faces.
 *
 * Material identity includes EVERY named property of the Minecraft block
 * state (e.g. axis=x, waterlogged=false, half=top). This snapshot never
 * confuses two orientations of a block with the same registry ID.
 *
 * This is a geometry/material INPUT ONLY. A noncube, emissive, translucent,
 * fluid or block-entity state must still be handled by vanilla rendering
 * and physics until its exact models and metadata have been reproduced.
 * Nothing here authorizes suppressing a vanilla section or changing blocks.
 */
public final class BlockStateSectionPalette {
    public static final int SIZE=16;
    public static final int BLOCKS=SIZE*SIZE*SIZE;
    public static final int FACE_CELLS=SIZE*SIZE;
    public static final int FACE_COUNT=6;
    public static final int TOTAL=BLOCKS+FACE_COUNT*FACE_CELLS;

    public record StateKey(String blockId,Map<String,String> properties) {
        public StateKey {
            Objects.requireNonNull(blockId);
            Objects.requireNonNull(properties);
            if(!blockId.matches("[a-z0-9_.-]+:[a-z0-9_./-]+"))
                throw new IllegalArgumentException("Invalid block registry ID");
            var sorted=new TreeMap<String,String>();
            properties.forEach((key,value)->{
                if(key==null || value==null
                        || !key.matches("[a-z0-9_]+")
                        || !value.matches("[a-z0-9_.-]+"))
                    throw new IllegalArgumentException("Invalid block property");
                if(sorted.put(key,value)!=null)
                    throw new IllegalArgumentException("Duplicate block property");
            });
            properties=Collections.unmodifiableMap(sorted);
        }
        public static StateKey simple(String id) {
            return new StateKey(id,Map.of());
        }
        public String canonical() {
            if(properties.isEmpty())return blockId;
            var names=new ArrayList<String>();
            properties.forEach((key,value)->names.add(key+"="+value));
            return blockId+"["+String.join(",",names)+"]";
        }
    }

    private final List<StateKey> palette;
    private final short[] indices;
    private final int uniqueCoreStates;

    private BlockStateSectionPalette(List<StateKey> palette,short[] indices,int uniqueCoreStates) {
        this.palette=List.copyOf(palette);
        this.indices=indices.clone();
        this.uniqueCoreStates=uniqueCoreStates;
    }

    public static BlockStateSectionPalette capture(StateKey[] core,StateKey[][] halo) {
        if(core==null || core.length!=BLOCKS || halo==null || halo.length!=FACE_COUNT)
            throw new IllegalArgumentException("Incomplete block-state section");
        for(var face:halo)
            if(face==null || face.length!=FACE_CELLS)
                throw new IllegalArgumentException("Incomplete neighbor-section face");
        var ids=new LinkedHashMap<StateKey,Integer>();
        short[] values=new short[TOTAL];
        for(int i=0;i<TOTAL;i++) {
            var state=Objects.requireNonNull(i<BLOCKS
                    ?core[i]:halo[(i-BLOCKS)/FACE_CELLS][(i-BLOCKS)%FACE_CELLS],
                    "Missing block-state entry");
            Integer index=ids.get(state);
            if(index==null) {
                index=ids.size();
                if(index>0xffff)throw new IllegalArgumentException("Palette overflow");
                ids.put(state,index);
            }
            values[i]=(short)(int)index;
        }
        var coreIds=new java.util.HashSet<Integer>();
        for(int i=0;i<BLOCKS;i++)coreIds.add(Short.toUnsignedInt(values[i]));
        return new BlockStateSectionPalette(new ArrayList<>(ids.keySet()),values,coreIds.size());
    }

    public int paletteSize(){return palette.size();}
    public int corePaletteSize(){return uniqueCoreStates;}
    public List<StateKey> palette(){return palette;}
    public StateKey cell(int x,int y,int z) {
        return at((y*SIZE+z)*SIZE+x);
    }
    public StateKey face(int face,int index) {
        if(face<0 || face>=FACE_COUNT || index<0 || index>=FACE_CELLS)
            throw new IndexOutOfBoundsException();
        return at(BLOCKS+face*FACE_CELLS+index);
    }
    public StateKey at(int index) {
        if(index<0 || index>=TOTAL)throw new IndexOutOfBoundsException();
        return palette.get(Short.toUnsignedInt(indices[index]));
    }
    public boolean matches(StateKey[] core,StateKey[][] halo) {
        if(core==null || core.length!=BLOCKS || halo==null || halo.length!=FACE_COUNT)
            return false;
        for(var face:halo)if(face==null || face.length!=FACE_CELLS)return false;
        for(int i=0;i<TOTAL;i++) {
            StateKey actual=i<BLOCKS?core[i]:
                    halo[(i-BLOCKS)/FACE_CELLS][(i-BLOCKS)%FACE_CELLS];
            if(!at(i).equals(actual))return false;
        }
        return true;
    }
    public int countCore(StateKey state) {
        int count=0;
        for(int i=0;i<BLOCKS;i++)if(at(i).equals(state))count++;
        return count;
    }
    public int countCoreDifferentFrom(StateKey state) {
        return BLOCKS-countCore(state);
    }
    public int bytesForCellIndices(){return indices.length*Short.BYTES;}
}
