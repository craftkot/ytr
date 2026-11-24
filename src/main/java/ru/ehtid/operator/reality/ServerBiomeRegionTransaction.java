package ru.ehtid.operator.reality;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.function.BooleanSupplier;

import net.minecraft.core.Holder;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.protocol.game.ClientboundChunksBiomesPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.biome.Biomes;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.level.chunk.status.ChunkStatus;

/**
 * Experimental server-owned real biome quartet transaction for ONE 32x64x32
 * region. Only the disposable CI world is allowed to instantiate it.
 *
 * Full source validation, protected boundary and player safety are checked
 * before apply/rollback. Minecraft's own ChunkAccess.fillBiomesFromNoise
 * preserves all unrelated quart cells. Sends vanilla biome packets to
 * clients. Does not touch geometry, foliage, lighting or block collisions.
 *
 * NOT crash-durable; no production invocation until a biome-specific WAL
 * and all-player frustum authority are implemented. Server-thread only.
 */
public final class ServerBiomeRegionTransaction {
    private static final int W=BiomeRegionBlueprint.WIDTH;
    private final ServerLevel level;
    private final BiomeRegionBlueprint plan;
    private final Holder<Biome>[] source;
    private final Holder<Biome> target;
    private final List<LevelChunk> chunks;
    private final BooleanSupplier safety;
    private boolean applied;

    @SuppressWarnings("unchecked")
    public ServerBiomeRegionTransaction(ServerLevel level,
                                        BiomeRegionBlueprint.Origin origin,
                                        BooleanSupplier safety) {
        this.level=Objects.requireNonNull(level);
        this.safety=Objects.requireNonNull(safety);
        this.chunks=loadedChunks(level,Objects.requireNonNull(origin));
        this.source=(Holder<Biome>[])new Holder[BiomeRegionBlueprint.CELLS];
        String[] ids=new String[source.length];
        for(int i=0;i<source.length;i++) {
            int x=origin.x()/4+i%W;
            int y=origin.y()/4+i/(W*W);
            int z=origin.z()/4+(i/W)%W;
            Holder<Biome> biome=readQuart(x,y,z);
            source[i]=biome;
            ids[i]=identity(biome);
        }
        this.plan=BiomeRegionBlueprint.prepare(origin,ids,
                "minecraft:snowy_taiga",1,913);
        this.target=level.registryAccess().lookupOrThrow(Registries.BIOME)
                .getOrThrow(Biomes.SNOWY_TAIGA);
        if(!plan.boundaryUnchanged() || plan.changedQuartCells()<200)
            throw new IllegalArgumentException("Incomplete biome preview");
    }

    public BiomeRegionBlueprint plan(){return plan;}
    public boolean applied(){return applied;}

    private static List<LevelChunk> loadedChunks(ServerLevel level,
                                                   BiomeRegionBlueprint.Origin origin) {
        var chunks=new ArrayList<LevelChunk>(4);
        for(int dz=0;dz<2;dz++)for(int dx=0;dx<2;dx++) {
            int cx=Math.floorDiv(origin.x(),16)+dx;
            int cz=Math.floorDiv(origin.z(),16)+dz;
            var full=level.getChunk(cx,cz,ChunkStatus.FULL,false);
            if(!(full instanceof LevelChunk live))
                throw new IllegalStateException("Biome region is not fully loaded");
            chunks.add(live);
        }
        return List.copyOf(chunks);
    }

    private Holder<Biome> readQuart(int x,int y,int z) {
        int chunkX=Math.floorDiv(x,4),chunkZ=Math.floorDiv(z,4);
        for(var chunk:chunks)if(chunk.getPos().x==chunkX && chunk.getPos().z==chunkZ)
            return chunk.getNoiseBiome(x,y,z);
        throw new IllegalStateException("Outside the owned four biome chunks");
    }
    private static String identity(Holder<Biome> holder) {
        return holder.unwrapKey().orElseThrow(()->new IllegalStateException(
                "Direct/unknown biome registry holder")).identifier().toString();
    }
    private boolean matches(boolean expectAlternate) {
        String[] expected=expectAlternate?plan.alternateSnapshot():plan.originalSnapshot();
        var origin=plan.origin();
        for(int i=0;i<expected.length;i++) {
            int x=origin.x()/4+i%W;
            int y=origin.y()/4+i/(W*W);
            int z=origin.z()/4+(i/W)%W;
            if(!identity(readQuart(x,y,z)).equals(expected[i]))return false;
        }
        return true;
    }

    /**
     * Real-world permission gate. The fixture invokes this with a player
     * distance predicate (>=16 from entire 3D region) and exact CI marker.
     */
    public int apply() {
        if(applied || !safety.getAsBoolean() || !matches(false))
            throw new IllegalStateException("Biome transition is stale or unsafe");
        int edits=write(true);
        if(edits!=plan.changedQuartCells() || !matches(true))
            throw new IllegalStateException("Incomplete real server biome apply");
        applied=true;
        return edits;
    }

    public int rollback() {
        if(!applied || !safety.getAsBoolean() || !matches(true))
            throw new IllegalStateException("Biome rollback is stale or unsafe");
        int edits=write(false);
        if(edits!=plan.changedQuartCells() || !matches(false))
            throw new IllegalStateException("Incomplete real server biome rollback");
        applied=false;
        return edits;
    }

    private int write(boolean alternate) {
        var origin=plan.origin();
        int bx=origin.x()/4,by=origin.y()/4,bz=origin.z()/4;
        int[] edits={0};
        for(var chunk:chunks) {
            int[] localChanges={0};
            chunk.fillBiomesFromNoise((qx,qy,qz)->{
                if(qx>=bx && qx<bx+W && qy>=by
                        && qy<by+BiomeRegionBlueprint.HEIGHT
                        && qz>=bz && qz<bz+W) {
                    int i=BiomeRegionBlueprint.index(qx-bx,qy-by,qz-bz);
                    Holder<Biome> desired=alternate
                            ?(plan.alternateSnapshot()[i].equals(plan.originalSnapshot()[i])
                                ?source[i]:target)
                            :source[i];
                    if(!identity(desired).equals(identity(chunk.getNoiseBiome(qx,qy,qz)))) {
                        localChanges[0]++;edits[0]++;
                    }
                    return desired;
                }
                return chunk.getNoiseBiome(qx,qy,qz);
            });
            if(localChanges[0]>0)chunk.markUnsaved();
        }
        if(edits[0]>0) {
            var packet=ClientboundChunksBiomesPacket.forChunks(chunks);
            for(var player:level.players()) {
                if(player.level()==level)
                    player.connection.send(packet);
            }
        }
        return edits[0];
    }
}
