package ru.ehtid.operator.reality;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Material-aware topology preparation on immutable, complete 16^3 palette
 * snapshots. Only known full opaque cubes are emitted in this stage; slabs,
 * glass, fluids, block entities and modded shapes remain vanilla-owned.
 *
 * This component NEVER authorizes vanilla render suppression or changes
 * any real blocks. It preserves exact StateKey properties on every output
 * face for future atlas, lighting and model resolution.
 */
public final class BlockStateFacePlanner {
    public enum Shape { AIR, FULL_OPAQUE, VANILLA_REQUIRED }
    @FunctionalInterface public interface Classifier {
        Shape classify(BlockStateSectionPalette.StateKey state);
    }
    public record Face(BlockStateSectionPalette.StateKey state,
                       VoxelSectionSurface.Quad quad) {
        public Face {
            Objects.requireNonNull(state);
            Objects.requireNonNull(quad);
        }
    }
    public record Result(List<Face> faces,int opaqueCells,int airCells,
                         int vanillaCells,int unresolvedNeighbors) {
        public Result {
            faces=List.copyOf(faces);
            if(opaqueCells<0 || airCells<0 || vanillaCells<0 || unresolvedNeighbors<0
                    || opaqueCells+airCells+vanillaCells!=BlockStateSectionPalette.BLOCKS)
                throw new IllegalArgumentException("Invalid section coverage");
        }
        /** Only a topology metric. Even complete topology does NOT certify
         * vanilla textures, lighting, collision or material replacement. */
        public boolean entirelyFullCubesOrAir() {
            return vanillaCells==0 && unresolvedNeighbors==0;
        }
    }
    private BlockStateFacePlanner(){}

    public static Result plan(BlockStateSectionPalette snapshot,Classifier classifier) {
        Objects.requireNonNull(snapshot);
        Objects.requireNonNull(classifier);
        var mapped=new java.util.HashMap<BlockStateSectionPalette.StateKey,Shape>();
        for(var state:snapshot.palette())
            mapped.put(state,Objects.requireNonNull(classifier.classify(state),
                    "Unknown shape for "+state.canonical()));
        byte[] mask=new byte[BlockStateSectionPalette.TOTAL];
        for(int i=0;i<mask.length;i++)
            mask[i]=(byte)mapped.get(snapshot.at(i)).ordinal();
        return plan(snapshot,mask);
    }

    /**
     * Position-specific material classification from the live Minecraft
     * client. These immutable tags are captured on the client thread and
     * revalidated before compilation. A palette state may have DIFFERENT
     * shapes at different positions, so a registry-name-only classifier is
     * insufficient for modded and neighbor-dependent states.
     *
     * Encoding: 0=AIR, 1=FULL_OPAQUE, 2=VANILLA_REQUIRED.
     * This is not authority for vanilla masking, light or physics.
     */
    public static Result plan(BlockStateSectionPalette snapshot,byte[] positionShapes) {
        Objects.requireNonNull(snapshot);
        if(positionShapes==null || positionShapes.length!=BlockStateSectionPalette.TOTAL)
            throw new IllegalArgumentException("Incomplete material-shape snapshot");
        var tags=positionShapes.clone();
        for(byte b:tags)if(b<0 || b>=Shape.values().length)
            throw new IllegalArgumentException("Unsupported per-position shape tag");
        var faces=new ArrayList<Face>();
        int opaque=0,air=0,vanilla=0,unresolved=0;
        final int n=BlockStateSectionPalette.SIZE;
        for(int y=0;y<n;y++)for(int z=0;z<n;z++)for(int x=0;x<n;x++) {
            int index=(y*n+z)*n+x;
            Shape shape=Shape.values()[tags[index]];
            if(shape==Shape.AIR){air++;continue;}
            if(shape==Shape.VANILLA_REQUIRED){vanilla++;continue;}
            opaque++;
            var state=snapshot.at(index);
            for(var direction:VoxelSectionSurface.Face.values()) {
                int nx=x+direction.dx,ny=y+direction.dy,nz=z+direction.dz;
                int adjacentIndex;
                if(nx>=0 && nx<n && ny>=0 && ny<n && nz>=0 && nz<n) {
                    adjacentIndex=(ny*n+nz)*n+nx;
                } else {
                    int offset=switch(direction) {
                        case WEST,EAST -> y*n+z;
                        case DOWN,UP -> z*n+x;
                        case NORTH,SOUTH -> y*n+x;
                    };
                    adjacentIndex=BlockStateSectionPalette.BLOCKS
                            +direction.ordinal()*BlockStateSectionPalette.FACE_CELLS+offset;
                }
                Shape adjacent=Shape.values()[tags[adjacentIndex]];
                if(adjacent==Shape.AIR)
                    faces.add(new Face(state,new VoxelSectionSurface.Quad(x,y,z,direction)));
                else if(adjacent==Shape.VANILLA_REQUIRED)
                    unresolved++;
            }
        }
        return new Result(faces,opaque,air,vanilla,unresolved);
    }

    /**
     * Tiny explicit material whitelist for the initial CI fixture.
     * Production MUST use Minecraft's full per-position shape, opacity,
     * lighting, fluid and model information rather than assuming a registry
     * name implies a cube.
     */
    public static Shape testFixtureShape(BlockStateSectionPalette.StateKey state) {
        return switch(state.blockId()) {
            case "minecraft:air","minecraft:cave_air","minecraft:void_air" -> Shape.AIR;
            case "minecraft:stone","minecraft:deepslate","minecraft:dirt",
                 "minecraft:grass_block","minecraft:oak_log" -> Shape.FULL_OPAQUE;
            default -> Shape.VANILLA_REQUIRED;
        };
    }
}
