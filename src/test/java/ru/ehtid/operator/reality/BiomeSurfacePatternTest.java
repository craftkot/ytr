package ru.ehtid.operator.reality;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BiomeSurfacePatternTest {
    @Test void producesRealGrassSnowAndTwoDistinctTreeCanopies() {
        var plan=BiomeSurfacePattern.changes();
        assertTrue(plan.size()>200 && plan.size()<BiomeSurfacePattern.MAX_CHANGED);
        assertEquals(plan.size(),plan.stream().map(c->c.x()+":"+c.y()+":"+c.z())
                .distinct().count());
        assertTrue(plan.stream().anyMatch(c->c.before()==BiomeSurfacePattern.Material.GRASS
                && c.after()==BiomeSurfacePattern.Material.SNOW_BLOCK));
        assertTrue(plan.stream().anyMatch(c->c.before()==BiomeSurfacePattern.Material.OAK_LOG
                && c.after()==BiomeSurfacePattern.Material.SPRUCE_LOG));
        assertTrue(plan.stream().anyMatch(c->c.after()==BiomeSurfacePattern.Material.SPRUCE_LEAVES));
        assertTrue(plan.stream().anyMatch(c->c.before()==BiomeSurfacePattern.Material.OAK_LEAVES
                && c.after()==BiomeSurfacePattern.Material.AIR));
        assertTrue(plan.stream().anyMatch(c->c.before()==BiomeSurfacePattern.Material.AIR
                && c.after()==BiomeSurfacePattern.Material.SPRUCE_LEAVES));
    }

    @Test void changesStayInsideOriginalBiomeRegionWithLargeProtectedShell() {
        for(var change:BiomeSurfacePattern.changes()) {
            assertTrue(change.x()>=-24 && change.x()<=-9);
            assertTrue(change.z()>=-55 && change.z()<=-38);
            assertTrue(change.y()>=199 && change.y()<=208);
            // Biome region is [-32,0) x [-64,-32) and y [192,256).
            assertTrue(change.x()>=-32+8 && change.x()<=-1-8);
            assertTrue(change.z()>=-64+8 && change.z()<=-33-5);
            assertTrue(change.y()>192 && change.y()<255);
        }
    }

    @Test void innerSnowPatchIsIrregularButStableAndLeavesTreesSupported() {
        int snow=0,grass=0,podzol=0;
        for(int x=BiomeSurfacePattern.MIN_X;x<=BiomeSurfacePattern.MAX_X;x++)
            for(int z=BiomeSurfacePattern.MIN_Z;z<=BiomeSurfacePattern.MAX_Z;z++) {
                var after=BiomeSurfacePattern.at(x,199,z,true);
                if(after==BiomeSurfacePattern.Material.SNOW_BLOCK)snow++;
                else if(after==BiomeSurfacePattern.Material.GRASS)grass++;
                else if(after==BiomeSurfacePattern.Material.PODZOL)podzol++;
                else fail("Unexpected surface material");
                assertEquals(after,BiomeSurfacePattern.at(x,199,z,true));
            }
        assertTrue(snow>100 && grass>0 && podzol>=40);
        assertEquals(BiomeSurfacePattern.Material.PODZOL,
                BiomeSurfacePattern.at(-19,199,-49,false));
        assertEquals(BiomeSurfacePattern.Material.PODZOL,
                BiomeSurfacePattern.at(-19,199,-49,true));
        assertEquals(BiomeSurfacePattern.Material.OAK_LOG,
                BiomeSurfacePattern.at(-19,201,-49,false));
        assertEquals(BiomeSurfacePattern.Material.SPRUCE_LOG,
                BiomeSurfacePattern.at(-19,206,-49,true));
        assertEquals(BiomeSurfacePattern.Material.SPRUCE_LEAVES,
                BiomeSurfacePattern.at(-17,202,-49,true));
        assertThrows(IndexOutOfBoundsException.class,
                ()->BiomeSurfacePattern.at(-32,200,-49,true));
    }
}
