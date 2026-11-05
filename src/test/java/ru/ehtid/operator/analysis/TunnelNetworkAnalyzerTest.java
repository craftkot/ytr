package ru.ehtid.operator.analysis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import ru.ehtid.operator.data.PlayerMemory;

import static org.junit.jupiter.api.Assertions.*;

class TunnelNetworkAnalyzerTest {
    @Test
    void reconstructsStraightCorridorAsOneSegmentAndTwoDeadEnds() {
        List<PlayerMemory.TunnelCell> cells = cellsForColumns(line(-4, 4, 0), "minecraft:overworld");

        TunnelNetworkAnalyzer.Analysis result =
                TunnelNetworkAnalyzer.analyze(cells, "minecraft:overworld");

        assertEquals(18, result.mappedVoxels);
        assertEquals(9, result.footprintColumns);
        assertEquals(1, result.networkComponents);
        assertEquals(2, result.deadEnds);
        assertEquals(0, result.junctions);
        assertEquals(1, result.corridorSegments);
        assertEquals(0, result.loops);
        assertEquals(9, result.longestSegmentCells);
        assertTrue(result.thinningConverged);
    }

    @Test
    void reducesTwoBlockWideCorridorToOneCenterline() {
        Set<Column> footprint = new HashSet<>();
        for (int x = 0; x < 9; x++) {
            footprint.add(new Column(x, 0));
            footprint.add(new Column(x, 1));
        }

        TunnelNetworkAnalyzer.Analysis result = TunnelNetworkAnalyzer.analyze(
                cellsForColumns(footprint, "minecraft:overworld"), "minecraft:overworld"
        );

        assertEquals(18, result.footprintColumns);
        assertEquals(2, result.deadEnds);
        assertEquals(0, result.junctions);
        assertEquals(1, result.corridorSegments);
        assertEquals(0, result.loops);
        assertTrue(result.skeletonCells < result.footprintColumns);
    }

    @Test
    void detectsTJunctionAndThreeCorridorSegments() {
        Set<Column> footprint = line(-5, 5, 0);
        for (int z = -5; z <= 0; z++) footprint.add(new Column(0, z));

        TunnelNetworkAnalyzer.Analysis result = TunnelNetworkAnalyzer.analyze(
                cellsForColumns(footprint, "minecraft:overworld"), "minecraft:overworld"
        );

        assertEquals(3, result.deadEnds);
        assertEquals(1, result.junctions);
        assertEquals(3, result.corridorSegments);
        assertEquals(0, result.loops);
        assertEquals(1, result.junctionPoints.size());
        assertEquals(3, result.junctionPoints.get(0).exits);
    }

    @Test
    void detectsFourWayCrossing() {
        Set<Column> footprint = line(-5, 5, 0);
        for (int z = -5; z <= 5; z++) footprint.add(new Column(0, z));

        TunnelNetworkAnalyzer.Analysis result = TunnelNetworkAnalyzer.analyze(
                cellsForColumns(footprint, "minecraft:overworld"), "minecraft:overworld"
        );

        assertEquals(4, result.deadEnds);
        assertEquals(1, result.junctions);
        assertEquals(4, result.corridorSegments);
        assertEquals(4, result.junctionPoints.get(0).exits);
    }

    @Test
    void detectsThickTJunctionWithoutCountingCorridorWidthAsExtraBranches() {
        Set<Column> base = line(-5, 5, 0);
        for (int z = -5; z <= 0; z++) base.add(new Column(0, z));
        Set<Column> thick = thicken(base, 2);

        TunnelNetworkAnalyzer.Analysis result = TunnelNetworkAnalyzer.analyze(
                cellsForColumns(thick, "minecraft:overworld"), "minecraft:overworld"
        );

        assertEquals(3, result.deadEnds);
        assertEquals(1, result.junctions);
        assertEquals(3, result.corridorSegments);
    }

    @Test
    void detectsClosedLoopWithoutFalseDeadEnds() {
        Set<Column> ring = new HashSet<>();
        for (int coordinate = 0; coordinate <= 8; coordinate++) {
            ring.add(new Column(coordinate, 0));
            ring.add(new Column(coordinate, 8));
            ring.add(new Column(0, coordinate));
            ring.add(new Column(8, coordinate));
        }

        TunnelNetworkAnalyzer.Analysis result = TunnelNetworkAnalyzer.analyze(
                cellsForColumns(ring, "minecraft:overworld"), "minecraft:overworld"
        );

        assertEquals(1, result.networkComponents);
        assertEquals(0, result.deadEnds);
        assertEquals(0, result.junctions);
        assertEquals(1, result.corridorSegments);
        assertEquals(1, result.loops);
    }

    @Test
    void separatesDisconnectedHorizontalNetworks() {
        Set<Column> footprint = line(0, 4, 0);
        footprint.addAll(line(20, 23, 0));

        TunnelNetworkAnalyzer.Analysis result = TunnelNetworkAnalyzer.analyze(
                cellsForColumns(footprint, "minecraft:overworld"), "minecraft:overworld"
        );

        assertEquals(2, result.networkComponents);
        assertEquals(4, result.deadEnds);
        assertEquals(2, result.corridorSegments);
        assertEquals(0, result.loops);
    }

    @Test
    void keepsIdenticalCoordinatesInDifferentDimensionsSeparate() {
        List<PlayerMemory.TunnelCell> cells = new ArrayList<>();
        cells.addAll(cellsForColumns(line(0, 4, 0), "minecraft:overworld"));
        cells.addAll(cellsForColumns(line(0, 4, 0), "minecraft:the_nether"));

        TunnelNetworkAnalyzer.Analysis result = TunnelNetworkAnalyzer.analyze(cells, null);

        assertEquals(20, result.mappedVoxels);
        assertEquals(10, result.footprintColumns);
        assertEquals(2, result.networkComponents);
        assertEquals(4, result.deadEnds);
        assertEquals(2, result.corridorSegments);
    }

    @Test
    void reportsThatVerticalLayersAreProjectedIntoOneHorizontalColumn() {
        List<PlayerMemory.TunnelCell> cells = List.of(
                new PlayerMemory.TunnelCell(2, 10, 3, "minecraft:overworld", "minecraft:stone", 1),
                new PlayerMemory.TunnelCell(2, 40, 3, "minecraft:overworld", "minecraft:stone", 2)
        );

        TunnelNetworkAnalyzer.Analysis result =
                TunnelNetworkAnalyzer.analyze(cells, "minecraft:overworld");

        assertEquals(2, result.mappedVoxels);
        assertEquals(1, result.footprintColumns);
        assertEquals(1, result.networkComponents);
        assertEquals(1, result.isolatedAreas);
        assertTrue(result.describe().contains("vertical layers are collapsed"));
    }

    @Test
    void doesNotJoinOnlyDiagonallyTouchingColumns() {
        Set<Column> footprint = Set.of(new Column(0, 0), new Column(1, 1));

        TunnelNetworkAnalyzer.Analysis result = TunnelNetworkAnalyzer.analyze(
                cellsForColumns(footprint, "minecraft:overworld"), "minecraft:overworld"
        );

        assertEquals(2, result.networkComponents);
        assertEquals(2, result.isolatedAreas);
        assertEquals(0, result.corridorSegments);
    }

    @Test
    void handlesEmptyTunnelMap() {
        TunnelNetworkAnalyzer.Analysis result =
                TunnelNetworkAnalyzer.analyze(List.of(), "minecraft:overworld");

        assertEquals(0, result.mappedVoxels);
        assertEquals(0, result.footprintColumns);
        assertEquals(0, result.networkComponents);
        assertEquals(0, result.junctions);
        assertTrue(result.describe().contains("No horizontal tunnel footprint"));
    }

    private static Set<Column> line(int minX, int maxX, int z) {
        Set<Column> result = new HashSet<>();
        for (int x = minX; x <= maxX; x++) result.add(new Column(x, z));
        return result;
    }

    private static Set<Column> thicken(Set<Column> base, int width) {
        Set<Column> result = new HashSet<>();
        int start = -(width / 2);
        for (Column column : base) {
            for (int dx = start; dx < start + width; dx++) {
                for (int dz = start; dz < start + width; dz++) {
                    result.add(new Column(column.x + dx, column.z + dz));
                }
            }
        }
        return result;
    }

    private static List<PlayerMemory.TunnelCell> cellsForColumns(
            Set<Column> columns,
            String dimension
    ) {
        List<PlayerMemory.TunnelCell> result = new ArrayList<>();
        for (Column column : columns) {
            result.add(new PlayerMemory.TunnelCell(
                    column.x, 20, column.z, dimension, "minecraft:stone", 100L
            ));
            result.add(new PlayerMemory.TunnelCell(
                    column.x, 21, column.z, dimension, "minecraft:stone", 101L
            ));
        }
        return result;
    }

    private record Column(int x, int z) { }
}
