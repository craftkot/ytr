package ru.ehtid.operator.transaction;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class WorldFieldPolicyTest {
    @Test void sectorsAreStableForPositiveAndNegativeChunkCoordinates() {
        assertEquals("minecraft:overworld|0,0", WorldFieldPolicy.sector("minecraft:overworld",0,63));
        assertEquals("minecraft:overworld|1,0", WorldFieldPolicy.sector("minecraft:overworld",64,0));
        assertEquals("minecraft:overworld|-1,-1", WorldFieldPolicy.sector("minecraft:overworld",-1,-64));
        assertEquals("minecraft:overworld|-2,0", WorldFieldPolicy.sector("minecraft:overworld",-65,0));
        assertNotEquals(WorldFieldPolicy.sector("minecraft:overworld",64,0),
                WorldFieldPolicy.sector("minecraft:the_nether",64,0));
    }

    @Test void allRealObserversAreExcludedEvenWhenFacingAwayOrSpectating() {
        var observers = List.of(
                new WorldFieldPolicy.Observer("minecraft:overworld", 0.5, 0.5),
                new WorldFieldPolicy.Observer("minecraft:overworld", 1000.5,1000.5));
        assertFalse(WorldFieldPolicy.nobodyNear("minecraft:overworld", 0, 0, observers));
        assertFalse(WorldFieldPolicy.nobodyNear("minecraft:overworld", 1000,1000, observers));
        assertTrue(WorldFieldPolicy.nobodyNear("minecraft:overworld", 200,0,observers));
        assertFalse(WorldFieldPolicy.nobodyNear("minecraft:overworld",0,0,List.of()));
        assertFalse(WorldFieldPolicy.nobodyNear("minecraft:overworld",0,0,
                java.util.Arrays.asList(new WorldFieldPolicy.Observer("minecraft:overworld",0,0),null)));
        assertTrue(WorldFieldPolicy.nobodyNear("minecraft:the_end", 0,0, observers));
    }

    @Test void globalFieldRefusesPlayerBuiltStructureProximity() {
        var journal = TunnelFoldService.twoCellJournal(
                java.util.UUID.fromString("fafe6475-202f-4a4d-bf6c-250dd5b58001"),
                "minecraft:overworld", new net.minecraft.core.BlockPos(0,45,0),
                new net.minecraft.core.BlockPos(1,45,0), 200L);
        var data = new ru.ehtid.operator.data.OperatorWorldData();
        var memory = new ru.ehtid.operator.data.PlayerMemory();
        data.players.put("profile", memory);
        assertFalse(WorldFieldService.nearTrackedConstruction(data, journal));
        memory.recordPlacedBlock(8, 45, 0, "minecraft:overworld",
                "minecraft:stone", 10L);
        assertTrue(WorldFieldService.nearTrackedConstruction(data, journal),
                "Recorded player stone walls cannot be treated as natural terrain");
        memory.removePlacedBlock(8,45,0,"minecraft:overworld",11L);
        memory.recordPlacedBlock(9, 45, 0, "minecraft:overworld",
                "minecraft:stone", 12L);
        assertFalse(WorldFieldService.nearTrackedConstruction(data, journal),
                "A known building beyond the conservative halo is not the target");
        memory.recordPlacedBlock(0,45,0,"minecraft:the_nether",
                "minecraft:stone", 12L);
        assertFalse(WorldFieldService.nearTrackedConstruction(data, journal),
                "Structures in a different dimension must not poison the overworld");
    }

    @Test void budgetCooldownAndPreviouslyChangedSectorsFailClosed() {
        Map<String,String> history = new LinkedHashMap<>();
        assertFalse(WorldFieldPolicy.maySchedule(history,"a",1100,0));
        assertTrue(WorldFieldPolicy.maySchedule(history,"a",1200,0));
        history.put("a","owner/id");
        assertFalse(WorldFieldPolicy.maySchedule(history,"a",2400,0));
        assertTrue(WorldFieldPolicy.maySchedule(history,"b",2400,1200));
        assertFalse(WorldFieldPolicy.maySchedule(history,"b",1500,1800));
        for (int i=0;i<WorldFieldPolicy.MAX_SECTORS;i++)
            history.put("sector"+i,"owner/"+i);
        assertFalse(WorldFieldPolicy.maySchedule(history,"unseen",24000,0));
        assertFalse(WorldFieldPolicy.maySchedule(null,"unseen",24000,0));
    }
}
