package ru.ehtid.operator.reality;

import java.nio.file.Files;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.TreeMap;

import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.Property;
import net.minecraft.world.level.storage.LevelResource;

import ru.ehtid.operator.OperatorMod;

/**
 * A read-only CI experiment recording EXACT Minecraft BlockState identities,
 * including all registered state properties, across 4096 cells and 1536
 * neighbor halo cells. It never replaces vanilla rendering or world blocks.
 *
 * Reading is capped at 96 cells per client tick. The complete 5632-cell
 * source is independently re-read before a palette snapshot is accepted.
 */
public final class ClientBlockStatePaletteProbe {
    private static final boolean ENABLED=
            Boolean.getBoolean("operator.reality.ciClientSmoke")
            && "true".equals(System.getenv("GITHUB_ACTIONS"));
    private static final SectionTerrainMesh.Key SECTION=
            new SectionTerrainMesh.Key(-3,12,-2);
    private static final int N=BlockStateSectionPalette.SIZE;
    private static final int CORE=BlockStateSectionPalette.BLOCKS;
    private static final int SIDE=BlockStateSectionPalette.FACE_CELLS;
    private static final int COUNT=BlockStateSectionPalette.TOTAL;
    private static final int PER_TICK=96;

    private static final BlockStateSectionPalette.StateKey[] captured=
            new BlockStateSectionPalette.StateKey[COUNT];
    private static final BlockPos.MutableBlockPos cursorPos=
            new BlockPos.MutableBlockPos();
    private static ClientLevel level;
    private static int captureCursor,verificationCursor;
    private static boolean success,failed;

    private ClientBlockStatePaletteProbe(){}

    public static void tick(Minecraft client) {
        if(!ENABLED || !client.isSameThread())return;
        if(client.level!=level){
            level=client.level;
            reset();
        }
        var server=client.getSingleplayerServer();
        if(level==null || server==null || success || failed)return;
        if(!Files.isRegularFile(server.getWorldPath(LevelResource.ROOT)
                .resolve("operator_reality/ci-blockstate-palette.allow")))return;

        if(captureCursor<COUNT) {
            int end=Math.min(COUNT,captureCursor+PER_TICK);
            while(captureCursor<end) {
                BlockStateSectionPalette.StateKey entry=read(captureCursor);
                if(entry==null)return; // Chunk is not yet available.
                captured[captureCursor++]=entry;
            }
            return;
        }
        int end=Math.min(COUNT,verificationCursor+PER_TICK);
        while(verificationCursor<end) {
            var entry=read(verificationCursor);
            if(entry==null)return;
            if(!captured[verificationCursor].equals(entry)){
                fail("CHANGED_DURING_CAPTURE_"+verificationCursor);
                return;
            }
            verificationCursor++;
        }
        if(verificationCursor<COUNT)return;

        try {
            var core=Arrays.copyOfRange(captured,0,CORE);
            var halo=new BlockStateSectionPalette.StateKey[6][SIDE];
            for(int face=0;face<halo.length;face++)
                System.arraycopy(captured,CORE+face*SIDE,halo[face],0,SIDE);
            var snapshot=BlockStateSectionPalette.capture(core,halo);
            var axisX=new BlockStateSectionPalette.StateKey(
                    "minecraft:oak_log",Map.of("axis","x"));
            var slab=snapshot.palette().stream().anyMatch(
                    key->key.blockId().equals("minecraft:oak_slab"));
            var grass=snapshot.palette().stream().anyMatch(
                    key->key.blockId().equals("minecraft:grass_block"));
            var glass=snapshot.palette().stream().anyMatch(
                    key->key.blockId().equals("minecraft:glass"));
            var light=snapshot.palette().stream().anyMatch(
                    key->key.blockId().equals("minecraft:sea_lantern"));
            if(snapshot.corePaletteSize()<8 || !slab || !grass || !glass || !light
                    || snapshot.countCore(axisX)!=1 || !snapshot.matches(core,halo)) {
                fail("INCOMPLETE_PALETTE");
                return;
            }
            OperatorMod.LOGGER.info(
                    "OPERATOR_BLOCKSTATE_PALETTE_SUCCESS section=-3,12,-2 blocks={} faces={} variants={} axisX={} slab={} grass={} glass={} light={} indexedBytes={}",
                    CORE,COUNT-CORE,snapshot.corePaletteSize(),snapshot.countCore(axisX),
                    slab,grass,glass,light,snapshot.bytesForCellIndices());
            success=true;
        } catch(RuntimeException error) {
            fail("PALETTE_"+error.getClass().getSimpleName());
        }
    }

    private static <T extends Comparable<T>> String valueName(
            BlockState state,Property<T> property) {
        return property.getName(state.getValue(property));
    }
    private static BlockStateSectionPalette.StateKey key(BlockState state) {
        var name=BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
        var values=new TreeMap<String,String>();
        state.getProperties().forEach(property->
                values.put(property.getName(),valueName(state,property)));
        return new BlockStateSectionPalette.StateKey(name,values);
    }

    private static BlockStateSectionPalette.StateKey read(int index) {
        int x,y,z;
        if(index<CORE) {
            x=index%N;y=index/(N*N);z=index/N%N;
        } else {
            int face=(index-CORE)/SIDE,cell=(index-CORE)%SIDE;
            switch(face) {
                case 0 -> {x=-1;y=cell/N;z=cell%N;}
                case 1 -> {x=N;y=cell/N;z=cell%N;}
                case 2 -> {x=cell%N;y=-1;z=cell/N;}
                case 3 -> {x=cell%N;y=N;z=cell/N;}
                case 4 -> {x=cell%N;y=cell/N;z=-1;}
                case 5 -> {x=cell%N;y=cell/N;z=N;}
                default -> throw new IllegalArgumentException("Unknown face");
            }
        }
        cursorPos.set(SECTION.x()*N+x,SECTION.y()*N+y,SECTION.z()*N+z);
        if(!level.hasChunkAt(cursorPos))return null;
        return key(level.getBlockState(cursorPos));
    }

    private static void fail(String cause) {
        failed=true;
        OperatorMod.LOGGER.error("OPERATOR_BLOCKSTATE_PALETTE_FAILED {}",cause);
    }
    private static void reset() {
        Arrays.fill(captured,null);
        captureCursor=verificationCursor=0;
        success=failed=false;
    }
}
