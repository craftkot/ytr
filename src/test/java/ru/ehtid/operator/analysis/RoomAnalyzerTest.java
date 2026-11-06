package ru.ehtid.operator.analysis;

import org.junit.jupiter.api.Test;
import ru.ehtid.operator.data.PlayerMemory;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RoomAnalyzerTest {
    private static final String DIMENSION = "minecraft:overworld";

    @Test
    void oneClosedHollowHouseHasOneRoomAndNoLinks() {
        List<PlayerMemory.ConstructionBlock> blocks = shell(false, false);
        ConstructionAnalyzer.Structure house =
                ConstructionAnalyzer.analyze(blocks).structures().get(0);

        assertEquals(1, house.roomCount());
        assertEquals(0, house.doorLinkCount());
        assertEquals(30, house.enclosedVoidCells());
        assertEquals("UNCLASSIFIED", house.rooms().get(0).role());
        assertTrue(house.enclosedBuildingCandidate());
    }

    @Test
    void internalWallSplitsRoomsAndDoorLinksThem() {
        List<PlayerMemory.ConstructionBlock> blocks = shell(true, true);
        ConstructionAnalyzer.Structure house =
                ConstructionAnalyzer.analyze(blocks).structures().get(0);

        assertEquals(2, house.roomCount());
        assertEquals(1, house.doorLinkCount());
        assertEquals(24, house.enclosedVoidCells());
        RoomAnalyzer.DoorLink connection = house.doorLinks().get(0);
        assertNotEquals(connection.roomA(), connection.roomB());
        assertEquals(3, connection.x());
        assertEquals(1, connection.y());
        assertEquals(2, connection.z());
    }

    @Test
    void missingInteriorDoorDoesNotCreateInventedConnection() {
        ConstructionAnalyzer.Structure house =
                ConstructionAnalyzer.analyze(shell(true, false)).structures().get(0);

        assertEquals(2, house.roomCount());
        assertEquals(0, house.doorLinkCount());
    }

    @Test
    void furnishingMarkersAssignDifferentRoomPurposes() {
        List<PlayerMemory.ConstructionBlock> blocks = shell(true, true);
        place(blocks, 1, 1, 2, "minecraft:red_bed",
                "Block{minecraft:red_bed}[part=foot,facing=east]");
        place(blocks, 5, 1, 2, "minecraft:crafting_table", "");

        ConstructionAnalyzer.Structure house =
                ConstructionAnalyzer.analyze(blocks).structures().get(0);

        assertEquals(2, house.roomCount());
        assertEquals(2, house.furnishedRoomCount());
        assertTrue(house.rooms().stream().anyMatch(room -> "BEDROOM".equals(room.role())));
        assertTrue(house.rooms().stream().anyMatch(room -> "WORKSHOP".equals(room.role())));
        assertEquals(22, house.enclosedVoidCells());
    }

    @Test
    void exposedRoofPreventsFalseRoomInference() {
        List<PlayerMemory.ConstructionBlock> blocks = shell(true, true);
        blocks.removeIf(block -> block.x == 2 && block.y == 3 && block.z == 2);

        ConstructionAnalyzer.Structure house =
                ConstructionAnalyzer.analyze(blocks).structures().get(0);
        assertTrue(house.interiorScanComplete());
        assertEquals(1, house.roomCount(),
                "the intact half remains enclosed; the exposed half must not count as a room");
        assertEquals(0, house.doorLinkCount(),
                "a door to an exterior volume must not be reported as an internal room link");
    }

    @Test
    void strictVolumeBudgetSuppressesExpensiveRoomScan() {
        List<PlayerMemory.ConstructionBlock> blocks = shell(false, false);
        RoomAnalyzer.Analysis rooms = RoomAnalyzer.analyze(
                blocks, 0, 0, 0, 6, 3, 4, 16L
        );
        assertFalse(rooms.complete());
        assertEquals(0, rooms.roomCount());
        assertEquals(0, rooms.enclosedVoidCells());
    }

    @Test
    void exteriorDoorIsNotAnInternalDoorLink() {
        List<PlayerMemory.ConstructionBlock> blocks = shell(false, false);
        // Replace a boundary block with a door; only one enclosed room exists.
        blocks.removeIf(block -> block.x == 0 && block.y == 1 && block.z == 2);
        place(blocks, 0, 1, 2, "minecraft:oak_door",
                "Block{minecraft:oak_door}[half=lower,facing=west]");
        ConstructionAnalyzer.Structure house =
                ConstructionAnalyzer.analyze(blocks).structures().get(0);

        assertEquals(1, house.roomCount());
        assertEquals(0, house.doorLinkCount());
    }

    private static List<PlayerMemory.ConstructionBlock> shell(
            boolean partition, boolean door
    ) {
        List<PlayerMemory.ConstructionBlock> blocks = new ArrayList<>();
        for (int x = 0; x <= 6; x++) {
            for (int y = 0; y <= 3; y++) {
                for (int z = 0; z <= 4; z++) {
                    boolean boundary = x == 0 || x == 6 || y == 0 || y == 3
                            || z == 0 || z == 4;
                    if (boundary) {
                        place(blocks, x, y, z, "minecraft:stone_bricks", "");
                    } else if (partition && x == 3) {
                        if (door && z == 2 && (y == 1 || y == 2)) {
                            place(blocks, x, y, z, "minecraft:oak_door",
                                    "Block{minecraft:oak_door}[half="
                                            + (y == 1 ? "lower" : "upper")
                                            + ",facing=east,open=false]");
                        } else {
                            place(blocks, x, y, z, "minecraft:stone_bricks", "");
                        }
                    }
                }
            }
        }
        return blocks;
    }

    private static void place(List<PlayerMemory.ConstructionBlock> blocks, int x, int y,
                              int z, String blockId, String state) {
        blocks.add(new PlayerMemory.ConstructionBlock(
                x, y, z, DIMENSION, blockId, state, blocks.size()
        ));
    }
}
