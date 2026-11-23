package ru.ehtid.operator.reality;

import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OffscreenBiomeTransitionTest {
    private static BiomeRegionBlueprint prepared(
            BiomeRegionBlueprint.Origin origin,long revision) {
        var blocks=new String[BiomeRegionBlueprint.CELLS];
        Arrays.fill(blocks,"minecraft:forest");
        return BiomeRegionBlueprint.prepare(origin,blocks,
                "minecraft:snowy_taiga",revision,918);
    }
    private static final BiomeRegionBlueprint.Origin FAR=
            new BiomeRegionBlueprint.Origin(-32,192,-64);

    @Test void firstInvisibleFrameSwitchesPreviouslyVisiblePreparedBiome() {
        var engine=new OffscreenBiomeTransition();
        assertTrue(engine.watch(FAR));
        assertFalse(engine.watch(FAR));
        assertTrue(engine.prepare(prepared(FAR,1)));
        assertEquals(0,engine.sample(bounds->true,0,202,4.5D,true).size());
        var selected=engine.sample(bounds->false,0,202,4.5D,true);
        assertEquals(1,selected.size());
        assertEquals(2,selected.getFirst().frame());
        assertEquals(1,selected.getFirst().visibleFrame());
        assertEquals(1,selected.getFirst().blueprint().revision());
        assertEquals("minecraft:snowy_taiga",selected.getFirst().blueprint().target());
        assertTrue(selected.getFirst().blueprint().boundaryUnchanged());
        assertTrue(engine.sample(bounds->false,0,202,4.5D,true).isEmpty());
        assertFalse(engine.prepare(prepared(FAR,1)));
        assertTrue(engine.prepare(prepared(FAR,2)));
        // A late background preparation cannot switch while already hidden.
        assertTrue(engine.sample(bounds->false,0,202,4.5D,true).isEmpty());
        engine.sample(bounds->true,0,202,4.5D,true);
        assertEquals(2,engine.sample(bounds->false,0,202,4.5D,true)
                .getFirst().blueprint().revision());
    }

    @Test void nearPlayerAndInvalidCameraSuppressTransitionEvenAtTurnSpeed() {
        var engine=new OffscreenBiomeTransition();
        engine.watch(FAR);
        engine.prepare(prepared(FAR,1));
        engine.sample(bounds->true,-10,202,-40,true);
        // Only two blocks outside the selected region boundary: unsafe.
        assertTrue(engine.sample(bounds->false,-10,202,-30,true).isEmpty());
        assertTrue(engine.sample(bounds->false,0,202,4,true).isEmpty());
        engine.sample(bounds->true,0,202,4,true);
        assertTrue(engine.sample(bounds->false,Double.NaN,202,4,true).isEmpty());
        assertTrue(engine.sample(bounds->false,0,202,4,true).isEmpty());
        engine.sample(bounds->true,0,202,4,true);
        assertTrue(engine.sample(bounds->false,0,202,4,false).isEmpty());
        assertTrue(engine.sample(bounds->false,0,202,4,true).isEmpty());
        engine.sample(bounds->true,0,202,4,true);
        assertEquals(1,engine.sample(bounds->false,0,202,4,true).size());
    }

    @Test void throwingFrustumCancelsAllRegionsWithoutPartialSwitch() {
        var engine=new OffscreenBiomeTransition();
        var other=new BiomeRegionBlueprint.Origin(0,192,-96);
        engine.watch(FAR);
        engine.watch(other);
        engine.prepare(prepared(FAR,1));
        engine.prepare(prepared(other,1));
        engine.sample(bounds->true,0,202,4,true);
        assertTrue(engine.sample(bounds->{
            if(bounds.minX()>=0)throw new IllegalStateException("camera reset");
            return false;
        },0,202,4,true).isEmpty());
        assertTrue(engine.sample(bounds->false,0,202,4,true).isEmpty());
        engine.sample(bounds->true,0,202,4,true);
        assertEquals(2,engine.sample(bounds->false,0,202,4,true).size());
    }

    @Test void emptyPlanAndUnwatchedOrStaleRegionsDoNotSwitch() {
        var engine=new OffscreenBiomeTransition();
        assertFalse(engine.prepare(prepared(FAR,1)));
        engine.watch(FAR);
        engine.sample(bounds->true,0,202,4,true);
        assertTrue(engine.sample(bounds->false,0,202,4,true).isEmpty());
        assertTrue(engine.prepare(prepared(FAR,1)));
        assertTrue(engine.sample(bounds->false,0,202,4,true).isEmpty());
        engine.sample(bounds->true,0,202,4,true);
        engine.clear();
        assertEquals(0,engine.watching());
        assertTrue(engine.sample(bounds->false,0,202,4,true).isEmpty());
    }
}
