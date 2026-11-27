package ru.ehtid.operator.worldgen;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RegionSeedTest {
    @Test void stableAndSeparated() {
        long a = RegionSeed.derive(42, "minecraft:overworld", -1, 2, 0);
        assertEquals(a, RegionSeed.derive(42, "minecraft:overworld", -1, 2, 0));
        assertNotEquals(a, RegionSeed.derive(42, "minecraft:overworld", -1, 2, 1));
        assertNotEquals(a, RegionSeed.derive(42, "minecraft:the_nether", -1, 2, 0));
        assertNotEquals(a, RegionSeed.derive(42, "minecraft:overworld", 2, -1, 0));
        assertNotEquals(a, RegionSeed.derive(43, "minecraft:overworld", -1, 2, 0));
    }
    @Test void negativeCoordinates() {
        assertEquals(-1, RegionSeed.regionOfChunk(-1, 4));
        assertEquals(-1, RegionSeed.regionOfChunk(-4, 4));
        assertEquals(-2, RegionSeed.regionOfChunk(-5, 4));
        assertEquals(1, RegionSeed.regionOfChunk(4, 4));
    }
    @Test void invalidInputs() {
        assertThrows(IllegalArgumentException.class, () -> RegionSeed.derive(0, "", 0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> RegionSeed.derive(0, "minecraft:overworld", 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> RegionSeed.regionOfChunk(0, 0));
    }
}
