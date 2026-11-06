package ru.ehtid.operator.transaction;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class SafeBlockPaletteTest {
    @Test
    void permitsOnlySimpleDefaultStatesWithoutBlockEntityData() {
        assertTrue(SafeBlockPalette.accepts(
                "minecraft:oak_planks", "Block{minecraft:oak_planks}", null));
        assertTrue(SafeBlockPalette.accepts(
                "minecraft:stone_bricks", "Block{minecraft:stone_bricks}", null));
        assertTrue(SafeBlockPalette.accepts(
                "minecraft:air", "Block{minecraft:air}", null));
        assertFalse(SafeBlockPalette.accepts(
                "minecraft:chest", "Block{minecraft:chest}", "{Items:[]}"));
        assertFalse(SafeBlockPalette.accepts(
                "minecraft:oak_door", "Block{minecraft:oak_door}[facing=east]", null));
        assertFalse(SafeBlockPalette.accepts(
                "minecraft:water", "Block{minecraft:water}", null));
        assertFalse(SafeBlockPalette.accepts(
                "techmod:machine", "Block{techmod:machine}", null));
        assertFalse(SafeBlockPalette.accepts(
                "minecraft:oak_planks", "Block{minecraft:oak_planks}", "{Items:[]}"));
        assertFalse(SafeBlockPalette.accepts(
                "minecraft:oak_log", "Block{minecraft:oak_log}[axis=x]", null));
    }

    @Test
    void completeJournalWithUnknownTargetBlockCannotBeExecuted() {
        TransactionJournal j = new TransactionJournal();
        j.source.minX = j.source.maxX = 0;
        j.source.minY = j.source.maxY = 64;
        j.source.minZ = j.source.maxZ = 0;
        j.target.minX = j.target.maxX = 10;
        j.target.minY = j.target.maxY = 64;
        j.target.minZ = j.target.maxZ = 0;
        j.offsetX = 10;
        j.source.before.add(new TransactionJournal.Cell(0, 64, 0,
                "minecraft:stone", "Block{minecraft:stone}", null));
        j.target.before.add(new TransactionJournal.Cell(10, 64, 0,
                "minecraft:air", "Block{minecraft:air}", null));
        j.sha256 = TransactionIntegrity.fingerprint(j);
        assertTrue(SafeBlockPalette.accepts(j));
        j.target.before.set(0, new TransactionJournal.Cell(10, 64, 0,
                "minecraft:water", "Block{minecraft:water}", null));
        j.sha256 = TransactionIntegrity.fingerprint(j);
        assertFalse(SafeBlockPalette.accepts(j));
    }
}
