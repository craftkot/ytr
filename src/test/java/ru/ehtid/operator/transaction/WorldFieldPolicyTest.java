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
