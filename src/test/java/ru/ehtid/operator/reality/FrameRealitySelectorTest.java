package ru.ehtid.operator.reality;

import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FrameRealitySelectorTest {
    private static final FrameRealitySelector.Bounds BOX =
            new FrameRealitySelector.Bounds(0,0,0,16,16,16);

    @Test void readyVariantIsSelectedOnFirstInvisibleFrameAndNeverAgain() {
        var engine = new FrameRealitySelector<String>();
        assertTrue(engine.watch("a", BOX));
        assertTrue(engine.prepare("a",1,"prepared mesh reference"));
        assertTrue(engine.sample(b -> false).isEmpty(), "initially unseen is not an exit");
        assertTrue(engine.sample(b -> true).isEmpty());
        var out = engine.sample(b -> false);
        assertEquals(1, out.size());
        assertEquals("prepared mesh reference",out.getFirst().variant());
        assertEquals(1, out.getFirst().exitFrame()-out.getFirst().previousVisibleFrame());
        assertTrue(engine.sample(b -> true).isEmpty(), "instant turn-back never selects");
        assertTrue(engine.sample(b -> false).isEmpty(), "revision consumed once");
    }

    @Test void missedDeadlineNeverSelectsWhileStillHidden() {
        var engine = new FrameRealitySelector<String>();
        engine.watch("a",BOX);
        engine.sample(b -> true);
        assertTrue(engine.sample(b -> false).isEmpty());
        assertTrue(engine.prepare("a",2,"late"));
        for (int i=0;i<10;i++) assertTrue(engine.sample(b -> false).isEmpty());
        assertTrue(engine.sample(b -> true).isEmpty());
        assertEquals("late", engine.sample(b -> false).getFirst().variant());
        assertFalse(engine.prepare("a",1,"old revision"));
        assertFalse(engine.prepare("a",2,"duplicate"));
    }

    @Test void missingOrFailedVisibilityCannotAuthorizeSelection() {
        var engine = new FrameRealitySelector<String>();
        engine.watch("a",BOX);
        engine.watch("b",BOX);
        engine.prepare("a",1,"A");
        engine.prepare("b",1,"B");
        engine.sample(b -> true);
        assertTrue(engine.sample(null).isEmpty());
        assertTrue(engine.sample(b -> false).isEmpty());
        engine.sample(b -> true);
        int[] calls={0};
        assertTrue(engine.sample(b -> {
            if (++calls[0] == 2) throw new IllegalStateException("invalid camera");
            return false;
        }).isEmpty());
        assertTrue(engine.sample(b -> false).isEmpty());
        engine.sample(b -> true);
        assertEquals(2,engine.sample(b -> false).size());
    }

    @Test void max64PreparedRegionsAreEvaluatedInOnePassAndClearOnWorldChange() {
        var engine = new FrameRealitySelector<String>();
        for(int i=0;i<64;i++) {
            assertTrue(engine.watch("r"+i,BOX));
            assertTrue(engine.prepare("r"+i,0,"v"+i));
        }
        assertFalse(engine.watch("overflow",BOX));
        int[] calls={0};
        engine.sample(b -> {calls[0]++;return true;});
        assertEquals(64,calls[0]);
        var selections = engine.sample(b -> false);
        assertEquals(64,selections.size());
        assertEquals("r63",selections.getLast().regionId());
        engine.clear();
        assertEquals(0,engine.watchedCount());
        assertEquals(0,engine.frame());
        assertTrue(engine.sample(b -> false).isEmpty());
    }
}
