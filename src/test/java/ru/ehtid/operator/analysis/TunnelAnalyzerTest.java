package ru.ehtid.operator.analysis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import ru.ehtid.operator.data.PlayerMemory;

import static org.junit.jupiter.api.Assertions.*;

class TunnelAnalyzerTest {
    @Test
    void recognizesLongStraightTunnel() {
        List<PlayerMemory.TunnelCell> cells = new ArrayList<>();
        for (int x = 0; x < 8; x++) cells.add(cell(x, 20, 0, "minecraft:overworld"));

        TunnelAnalyzer.Analysis result = TunnelAnalyzer.analyze(cells, "minecraft:overworld");

        assertEquals(8, result.mappedCells);
        assertEquals(1, result.components);
        assertEquals(1, result.linearComponents);
        assertNotNull(result.largest);
        assertEquals(TunnelAnalyzer.ComponentType.LINEAR, result.largest.type);
        assertEquals(TunnelAnalyzer.Axis.X, result.largest.axis);
        assertEquals(8, result.largest.length);
        assertEquals(0, result.largest.minX);
        assertEquals(7, result.largest.maxX);
        assertEquals(20, result.largest.minY);
        assertEquals(0, result.largest.minZ);
    }

    @Test
    void recognizesTwoBlockHighMiningCorridorAsLinear() {
        List<PlayerMemory.TunnelCell> cells = new ArrayList<>();
        for (int x = 0; x < 6; x++) {
            cells.add(cell(x, 20, 0, "minecraft:overworld"));
            cells.add(cell(x, 21, 0, "minecraft:overworld"));
        }

        TunnelAnalyzer.Analysis result = TunnelAnalyzer.analyze(cells, "minecraft:overworld");

        assertEquals(12, result.mappedCells);
        assertEquals(TunnelAnalyzer.ComponentType.LINEAR, result.largest.type);
        assertEquals(TunnelAnalyzer.Axis.X, result.largest.axis);
        assertEquals(6, result.largest.length);
    }

    @Test
    void recognizesDenseThreeDimensionalExcavationAsChamber() {
        List<PlayerMemory.TunnelCell> cells = new ArrayList<>();
        for (int x = 0; x < 3; x++) {
            for (int y = 20; y < 23; y++) {
                for (int z = 0; z < 3; z++) {
                    cells.add(cell(x, y, z, "minecraft:overworld"));
                }
            }
        }

        TunnelAnalyzer.Analysis result = TunnelAnalyzer.analyze(cells, "minecraft:overworld");

        assertEquals(27, result.mappedCells);
        assertEquals(1, result.chamberComponents);
        assertEquals(TunnelAnalyzer.ComponentType.CHAMBER, result.largest.type);
    }

    @Test
    void doesNotMislabelCrossShapedExcavationAsStraightTunnel() {
        List<PlayerMemory.TunnelCell> cells = new ArrayList<>();
        for (int coordinate = -2; coordinate <= 2; coordinate++) {
            cells.add(cell(coordinate, 20, 0, "minecraft:overworld"));
            if (coordinate != 0) cells.add(cell(0, 20, coordinate, "minecraft:overworld"));
        }

        TunnelAnalyzer.Analysis result = TunnelAnalyzer.analyze(cells, "minecraft:overworld");

        assertEquals(9, result.mappedCells);
        assertEquals(TunnelAnalyzer.ComponentType.IRREGULAR, result.largest.type);
        assertEquals(1, result.irregularComponents);
        assertEquals(0, result.linearComponents);
    }

    @Test
    void separatesDisconnectedExcavations() {
        List<PlayerMemory.TunnelCell> cells = new ArrayList<>();
        for (int x = 0; x < 4; x++) cells.add(cell(x, 20, 0, "minecraft:overworld"));
        cells.add(cell(20, 20, 0, "minecraft:overworld"));
        cells.add(cell(21, 20, 0, "minecraft:overworld"));

        TunnelAnalyzer.Analysis result = TunnelAnalyzer.analyze(cells, "minecraft:overworld");

        assertEquals(2, result.components);
        assertEquals(4, result.largest.cells);
        assertEquals(1, result.linearComponents);
        assertEquals(1, result.smallComponents);
    }

    @Test
    void filtersCellsByDimension() {
        List<PlayerMemory.TunnelCell> cells = List.of(
                cell(0, 20, 0, "minecraft:overworld"),
                cell(1, 20, 0, "minecraft:overworld"),
                cell(2, 20, 0, "minecraft:the_nether"),
                cell(3, 20, 0, "minecraft:the_nether")
        );

        TunnelAnalyzer.Analysis result = TunnelAnalyzer.analyze(cells, "minecraft:the_nether");

        assertEquals(2, result.mappedCells);
        assertEquals("minecraft:the_nether", result.dimension);
        assertEquals(1, result.components);
        assertEquals(1, result.smallComponents);
    }

    @Test
    void keepsDimensionsSeparateWhenAnalysingAllDimensions() {
        List<PlayerMemory.TunnelCell> cells = List.of(
                cell(0, 20, 0, "minecraft:overworld"),
                cell(1, 20, 0, "minecraft:overworld"),
                cell(0, 20, 0, "minecraft:the_nether"),
                cell(1, 20, 0, "minecraft:the_nether")
        );

        TunnelAnalyzer.Analysis result = TunnelAnalyzer.analyze(cells, null);

        assertEquals(4, result.mappedCells);
        assertEquals(2, result.components);
        assertEquals(2, result.smallComponents);
    }

    @Test
    void doesNotClassifyLongWideCorridorAsChamber() {
        List<PlayerMemory.TunnelCell> cells = new ArrayList<>();
        for (int x = 0; x < 8; x++) {
            for (int y = 20; y < 23; y++) {
                for (int z = 0; z < 3; z++) {
                    cells.add(cell(x, y, z, "minecraft:overworld"));
                }
            }
        }

        TunnelAnalyzer.Analysis result = TunnelAnalyzer.analyze(cells, "minecraft:overworld");

        assertEquals(TunnelAnalyzer.ComponentType.LINEAR, result.largest.type);
        assertEquals(TunnelAnalyzer.Axis.X, result.largest.axis);
    }

    @Test
    void deduplicatesRepeatedCoordinates() {
        List<PlayerMemory.TunnelCell> cells = List.of(
                cell(0, 20, 0, "minecraft:overworld"),
                cell(0, 20, 0, "minecraft:overworld"),
                cell(1, 20, 0, "minecraft:overworld")
        );

        TunnelAnalyzer.Analysis result = TunnelAnalyzer.analyze(cells, "minecraft:overworld");

        assertEquals(2, result.mappedCells);
        assertEquals(1, result.components);
    }

    @Test
    void handlesEmptyMap() {
        TunnelAnalyzer.Analysis result = TunnelAnalyzer.analyze(List.of(), "minecraft:overworld");

        assertEquals(0, result.mappedCells);
        assertEquals(0, result.components);
        assertNull(result.largest);
        assertTrue(result.describe().contains("No mapped tunnel cells"));
    }

    private static PlayerMemory.TunnelCell cell(int x, int y, int z, String dimension) {
        return new PlayerMemory.TunnelCell(x, y, z, dimension, "minecraft:stone", 100L);
    }
}
