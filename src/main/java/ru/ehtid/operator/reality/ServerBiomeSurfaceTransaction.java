package ru.ehtid.operator.reality;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.LeavesBlock;
import net.minecraft.world.level.block.state.BlockState;

/**
 * Real vanilla block updates for a CI-only physical biome appearance change.
 *
 * Captures the exact pre-existing 16x18x10 forest volume, not just the cells
 * that will be replaced. A stale, modified, or unloaded cell vetoes the
 * transaction. A bounded patch changes grass to irregular snow and two
 * actual oak trees into taller spruce trees; every recorded BlockState is
 * restored on rollback.
 *
 * This is a disposable-world integration experiment. It has no durable
 * multi-block WAL, does not handle real structure generation or arbitrary
 * modded blocks, and MUST NOT run in normal singleplayer or on any server.
 */
public final class ServerBiomeSurfaceTransaction {
    private record Guard(BlockPos pos,BlockState original,BlockState replacement) {}
    private static final int FLAGS=2; // vanilla client packets; no shape-neighbor side effects
    private final ServerLevel level;
    private final BooleanSupplier safe;
    private final List<Guard> volume;
    private final List<Guard> changes;
    private boolean applied;

    public ServerBiomeSurfaceTransaction(ServerLevel level,BooleanSupplier safe) {
        requireDisposable();
        this.level=Objects.requireNonNull(level);
        this.safe=Objects.requireNonNull(safe);
        if(!level.getServer().isSameThread())
            throw new IllegalStateException("Biome surface plan must be captured on server thread");
        var volume=new ArrayList<Guard>();
        var changes=new ArrayList<Guard>();
        for(int y=BiomeSurfacePattern.GROUND_Y;y<=BiomeSurfacePattern.MAX_Y;y++)
            for(int z=BiomeSurfacePattern.MIN_Z;z<=BiomeSurfacePattern.MAX_Z;z++)
                for(int x=BiomeSurfacePattern.MIN_X;x<=BiomeSurfacePattern.MAX_X;x++) {
                    var pos=new BlockPos(x,y,z);
                    if(!level.hasChunkAt(pos))
                        throw new IllegalStateException("Unloaded biome fixture chunk: "+pos);
                    var state=level.getBlockState(pos);
                    var source=BiomeSurfacePattern.at(x,y,z,false);
                    if(!state.is(block(source)))
                        throw new IllegalStateException("Modified/non-fixture forest voxel: "+pos);
                    var target=state(source==BiomeSurfacePattern.at(x,y,z,true)
                            ?source:BiomeSurfacePattern.at(x,y,z,true));
                    // Unchanged cells retain exactly the ORIGINAL captured
                    // state (including leaf distance, snowiness and properties).
                    if(source==BiomeSurfacePattern.at(x,y,z,true))target=state;
                    var cell=new Guard(pos,state,target);
                    volume.add(cell);
                    if(!state.equals(target)) {
                        if(changes.size()>=BiomeSurfacePattern.MAX_CHANGED)
                            throw new IllegalStateException("Snow transition exceeds change budget");
                        changes.add(cell);
                    }
                }
        if(changes.size()<200 || changes.size()!=BiomeSurfacePattern.changes().size())
            throw new IllegalStateException("Unexpected forest-to-snow delta count");
        this.volume=List.copyOf(volume);
        this.changes=List.copyOf(changes);
        if(!safe.getAsBoolean())
            throw new IllegalStateException("Players too close to biome fixture");
    }

    private static void requireDisposable() {
        if(!Boolean.getBoolean("operator.reality.ciClientSmoke")
                || !"true".equals(System.getenv("GITHUB_ACTIONS")))
            throw new SecurityException("Physical biome blocks require disposable CI server");
    }

    private static Block block(BiomeSurfacePattern.Material kind) {
        return switch(kind) {
            case AIR->Blocks.AIR;
            case GRASS->Blocks.GRASS_BLOCK;
            case PODZOL->Blocks.PODZOL;
            case SNOW_BLOCK->Blocks.SNOW_BLOCK;
            case OAK_LOG->Blocks.OAK_LOG;
            case OAK_LEAVES->Blocks.OAK_LEAVES;
            case SPRUCE_LOG->Blocks.SPRUCE_LOG;
            case SPRUCE_LEAVES->Blocks.SPRUCE_LEAVES;
        };
    }
    private static BlockState state(BiomeSurfacePattern.Material kind) {
        BlockState state=block(kind).defaultBlockState();
        if(kind==BiomeSurfacePattern.Material.OAK_LEAVES
                || kind==BiomeSurfacePattern.Material.SPRUCE_LEAVES)
            return state.setValue(LeavesBlock.PERSISTENT,true);
        return state;
    }

    /** Seed only in the pre-existing disposable CI test world, before the
     * camera/frustum test begins. The original grass and oak canopy become
     * real vanilla blocks so visual verification can see the difference. */
    public static void seedFixture(ServerLevel level) {
        requireDisposable();
        if(!level.getServer().isSameThread())
            throw new IllegalStateException("Biome fixture seed requires server thread");
        for(int z=BiomeSurfacePattern.MIN_Z;z<=BiomeSurfacePattern.MAX_Z;z++)
            for(int x=BiomeSurfacePattern.MIN_X;x<=BiomeSurfacePattern.MAX_X;x++) {
                var ground=new BlockPos(x,BiomeSurfacePattern.GROUND_Y-1,z);
                if(!level.getBlockState(ground).is(Blocks.STONE)
                        && !level.setBlock(ground,Blocks.STONE.defaultBlockState(),FLAGS))
                    throw new IllegalStateException("Failed to seed forest subsoil");
                for(int y=BiomeSurfacePattern.GROUND_Y;y<=BiomeSurfacePattern.MAX_Y;y++) {
                    var pos=new BlockPos(x,y,z);
                    BlockState expected=state(BiomeSurfacePattern.at(x,y,z,false));
                    if(!level.getBlockState(pos).equals(expected)
                            && !level.setBlock(pos,expected,FLAGS))
                        throw new IllegalStateException("Failed to seed oak forest at "+pos);
                }
            }
    }

    public int expectedChanges(){return changes.size();}
    public boolean applied(){return applied;}

    /**
     * Vanilla continuously recalculates leaf DISTANCE as nearby logs and
     * leaves change. This derived value is not a user edit or a new block.
     * Compare every other BlockState property exactly: block identity,
     * persistent flag and waterlogged state must still match the journal.
     */
    static boolean matchesGuard(BlockState actual,BlockState expected) {
        if(actual.equals(expected))return true;
        if(actual.getBlock()!=expected.getBlock()
                || !actual.hasProperty(LeavesBlock.DISTANCE)
                || !expected.hasProperty(LeavesBlock.DISTANCE))
            return false;
        return actual.setValue(LeavesBlock.DISTANCE,
                expected.getValue(LeavesBlock.DISTANCE)).equals(expected);
    }

    private void validate(boolean alternate) {
        if(!safe.getAsBoolean())
            throw new IllegalStateException("A player has entered the biome safety halo");
        for(var g:volume) {
            if(!level.hasChunkAt(g.pos()))
                throw new IllegalStateException("Biome chunk unloaded during transition");
            var expected=alternate?g.replacement():g.original();
            if(!matchesGuard(level.getBlockState(g.pos()),expected))
                throw new IllegalStateException("Stale biome structure or outside block edit: "+g.pos());
        }
    }

    /** All-or-restore on a synchronous write failure, with separate
     * validation before the first mutation. No mesh or frustum calls here. */
    private int write(boolean forward) {
        int touched=0;
        try {
            for(int i=0;i<changes.size();i++) {
                var change=changes.get(i);
                if(i%32==0 && !safe.getAsBoolean())
                    throw new IllegalStateException("Player entered biome safety halo");
                var expected=forward?change.original():change.replacement();
                var target=forward?change.replacement():change.original();
                if(!matchesGuard(level.getBlockState(change.pos()),expected))
                    throw new IllegalStateException("Stale changed forest cell "+change.pos());
                // Include the CURRENT block in compensation, even if vanilla
                // reports write failure or the post-write verification fails.
                // This avoids leaving an acknowledged-but-unrolled partial edit.
                touched=i+1;
                if(!level.setBlock(change.pos(),target,FLAGS)
                        || !matchesGuard(level.getBlockState(change.pos()),target))
                    throw new IllegalStateException("Physical snow/tree update failed at "+change.pos());
            }
            return changes.size();
        } catch(RuntimeException failure) {
            for(int i=touched-1;i>=0;i--) {
                var cell=changes.get(i);
                var restore=forward?cell.original():cell.replacement();
                try {
                    if(!matchesGuard(level.getBlockState(cell.pos()),restore)
                            && (!level.setBlock(cell.pos(),restore,FLAGS)
                                || !matchesGuard(level.getBlockState(cell.pos()),restore)))
                        failure.addSuppressed(new IllegalStateException(
                                "Biome compensation failed at "+cell.pos()));
                } catch(RuntimeException compensation) {
                    failure.addSuppressed(compensation);
                }
            }
            throw failure;
        }
    }

    public int apply() {
        if(applied)throw new IllegalStateException("Forest already changed to snowy taiga");
        validate(false);
        int changed=write(true);
        validate(true);
        applied=true;
        return changed;
    }
    public int rollback() {
        if(!applied)throw new IllegalStateException("Snowy biome surface not applied");
        validate(true);
        int changed=write(false);
        validate(false);
        applied=false;
        return changed;
    }

    /** Client verification reads every modified physical BlockState. */
    public static List<BiomeSurfacePattern.Cell> expectedCells(){
        return BiomeSurfacePattern.changes();
    }
}
