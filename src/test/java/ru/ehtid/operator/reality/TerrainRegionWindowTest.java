package ru.ehtid.operator.reality;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class TerrainRegionWindowTest {
    @Test void movingCameraNeverExceeds64AndRetainsStableRequests() {
        var window=new TerrainRegionWindow(4,64);
        var initial=window.update(.5,4.5,180);
        assertEquals(64,initial.requested().size());
        assertTrue(initial.retained().isEmpty());
        var same=window.update(.5,4.5,180);
        assertEquals(64,same.retained().size());
        assertTrue(same.requested().isEmpty());
        assertTrue(same.evicted().isEmpty());
        assertEquals(initial.requested(),same.retained());
        for(int i=0;i<100;i++) {
            var update=window.update(i*32+.5,Math.floorMod(i,4)*32+.5,180);
            assertEquals(64,window.size());
            assertEquals(64,new HashSet<>(window.snapshot()).size());
            assertTrue(update.requested().stream().allMatch(window::accepts));
            assertTrue(update.evicted().stream().noneMatch(window::accepts));
        }
    }

    @Test void staleWorkerResultCannotBecomeValidAfterDepartureAndReentry() {
        var window=new TerrainRegionWindow(1,2);
        var old=window.update(.5,4.5,180).requested().getFirst();
        assertTrue(window.accepts(old));
        var far=window.update(800.5,800.5,180);
        assertTrue(far.evicted().contains(old));
        assertFalse(window.accepts(old));
        window.update(.5,4.5,180);
        var fresh=window.snapshot().stream().filter(r->r.key().equals(old.key())).findFirst().orElseThrow();
        assertNotEquals(old.generation(),fresh.generation());
        assertTrue(window.accepts(fresh));
        assertFalse(window.accepts(old));
        window.clear();
        assertFalse(window.accepts(fresh));
    }

    @Test void invalidCameraLeavesPreviousPlanUnmodified() {
        var window=new TerrainRegionWindow(1,3);
        var first=window.update(0,0,0);
        assertThrows(IllegalArgumentException.class,()->window.update(Double.NaN,0,180));
        assertEquals(first.requested(),window.snapshot());
    }
}
