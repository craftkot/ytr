package ru.ehtid.operator.analysis;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import ru.ehtid.operator.data.PlayerMemory;

import static org.junit.jupiter.api.Assertions.*;

class Tunnel3DNetworkAnalyzerTest {
    @Test
    void collapsesTwoBlockHighTunnelAndRetainsItsHeight() {
        List<PlayerMemory.TunnelCell> cells = new ArrayList<>();
        for (int x = -4; x <= 4; x++) {
            cells.add(cell(x, 20, 0, "minecraft:overworld"));
            cells.add(cell(x, 21, 0, "minecraft:overworld"));
        }
        var result = Tunnel3DNetworkAnalyzer.analyze(cells, "minecraft:overworld");
        assertEquals(18, result.mappedVoxels);
        assertEquals(2, result.verticalLayers);
        assertEquals(9, result.centerlineNodes);
        assertEquals(1, result.networkComponents);
        assertEquals(2, result.deadEnds);
        assertEquals(1, result.corridorSegments);
        assertEquals(0, result.verticalSegments);
        assertEquals(20.5D, result.segments.get(0).startY, 0.0001D);
        assertEquals(20.5D, result.segments.get(0).endY, 0.0001D);
    }

    @Test
    void keepsStackedTunnelsAtDifferentHeightsSeparate() {
        List<PlayerMemory.TunnelCell> cells = new ArrayList<>();
        for (int x = 0; x <= 6; x++) {
            for (int y : new int[]{20, 21, 25, 26}) cells.add(cell(x, y, 0, "minecraft:overworld"));
        }
        var result = Tunnel3DNetworkAnalyzer.analyze(cells, "minecraft:overworld");
        assertEquals(2, result.networkComponents);
        assertEquals(4, result.deadEnds);
        assertEquals(2, result.corridorSegments);
    }

    @Test
    void reconstructsVerticalShaftAsAThreeDimensionalSegment() {
        List<PlayerMemory.TunnelCell> cells = new ArrayList<>();
        for (int y = 20; y <= 26; y++) cells.add(cell(3, y, -2, "minecraft:overworld"));
        var result = Tunnel3DNetworkAnalyzer.analyze(cells, "minecraft:overworld");
        assertEquals(1, result.networkComponents);
        assertEquals(7, result.centerlineNodes);
        assertEquals(2, result.deadEnds);
        assertEquals(1, result.corridorSegments);
        assertEquals(1, result.verticalSegments);
        assertEquals(6.0D, result.totalCenterlineLength, 0.0001D);
        assertEquals(20.0D, result.segments.get(0).startY, 0.0001D);
        assertEquals(26.0D, result.segments.get(0).endY, 0.0001D);
    }

    @Test
    void connectsAscendingTunnelAcrossAdjacentHeightLayers() {
        List<PlayerMemory.TunnelCell> cells = new ArrayList<>();
        for (int x = 0; x <= 2; x++) cells.add(cell(x, 20, 0, "minecraft:overworld"));
        for (int x = 3; x <= 5; x++) cells.add(cell(x, 21, 0, "minecraft:overworld"));
        for (int x = 6; x <= 8; x++) cells.add(cell(x, 22, 0, "minecraft:overworld"));
        var result = Tunnel3DNetworkAnalyzer.analyze(cells, "minecraft:overworld");
        assertEquals(1, result.networkComponents);
        assertEquals(2, result.deadEnds);
        assertEquals(1, result.corridorSegments);
        assertEquals(6.0D + 2.0D * Math.sqrt(2.0D), result.totalCenterlineLength, 0.0001D);
        assertNotEquals(result.segments.get(0).startY, result.segments.get(0).endY);
    }

    @Test
    void detectsTJunctionAndReportsItsThreeDimensionalPosition() {
        Set<Column> footprint = new java.util.HashSet<>();
        for (int x = -5; x <= 5; x++) footprint.add(new Column(x, 0));
        for (int z = -5; z <= 0; z++) footprint.add(new Column(0, z));
        List<PlayerMemory.TunnelCell> cells = new ArrayList<>();
        for (Column p : footprint) {
            cells.add(cell(p.x, 20, p.z, "minecraft:overworld"));
            cells.add(cell(p.x, 21, p.z, "minecraft:overworld"));
        }
        var result = Tunnel3DNetworkAnalyzer.analyze(cells, "minecraft:overworld");
        assertEquals(3, result.deadEnds);
        assertEquals(1, result.junctions);
        assertEquals(3, result.corridorSegments);
        assertEquals(1, result.junctionPoints.size());
        assertEquals(0.0D, result.junctionPoints.get(0).x, 0.0001D);
        assertEquals(20.5D, result.junctionPoints.get(0).y, 0.0001D);
        assertEquals(0.0D, result.junctionPoints.get(0).z, 0.0001D);
        assertEquals(3, result.junctionPoints.get(0).exits);
    }

    @Test
    void separatesDimensions() {
        List<PlayerMemory.TunnelCell> cells = new ArrayList<>();
        for (String dimension : List.of("minecraft:overworld", "minecraft:the_nether")) {
            for (int x = 0; x <= 4; x++) {
                cells.add(cell(x, 20, 0, dimension));
                cells.add(cell(x, 21, 0, dimension));
            }
        }
        var result = Tunnel3DNetworkAnalyzer.analyze(cells, null);
        assertEquals(2, result.networkComponents);
        assertEquals(4, result.deadEnds);
        assertEquals(2, result.corridorSegments);
    }

    @Test
    void doesNotConnectDiagonalOnlyVoxels() {
        List<PlayerMemory.TunnelCell> cells = List.of(
                cell(0, 20, 0, "minecraft:overworld"),
                cell(1, 20, 1, "minecraft:overworld")
        );
        var result = Tunnel3DNetworkAnalyzer.analyze(cells, "minecraft:overworld");
        assertEquals(2, result.networkComponents);
        assertEquals(2, result.isolatedAreas);
        assertEquals(0, result.corridorSegments);
    }

    @Test
    void handlesEmptyMap() {
        var result = Tunnel3DNetworkAnalyzer.analyze(List.of(), "minecraft:overworld");
        assertEquals(0, result.mappedVoxels);
        assertEquals(0, result.verticalLayers);
        assertEquals(0, result.networkComponents);
        assertTrue(result.describe().contains("No mined tunnel voxels"));
    }

    private static PlayerMemory.TunnelCell cell(int x, int y, int z, String dimension) {
        return new PlayerMemory.TunnelCell(x, y, z, dimension, "minecraft:stone", 100L);
    }

    private record Column(int x, int z) { }
}
