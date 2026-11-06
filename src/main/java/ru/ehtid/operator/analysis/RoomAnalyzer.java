package ru.ehtid.operator.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.ehtid.operator.data.PlayerMemory;

/**
 * Bounded, offline room graph derived ONLY from recorded player placements.
 *
 * Closed door blocks are treated as partitions even if their current BlockState
 * says "open"; a door between two enclosed volumes becomes a graph edge.
 * A missing/untracked wall is treated as empty space. Neither inference proves
 * an actual navigable Minecraft room.
 */
public final class RoomAnalyzer {
    public static final long MAX_CELLS_PER_COMPONENT = 32_768L;
    public static final int MAX_ROOMS = 128;
    public static final int MAX_DOOR_LINKS = 128;

    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0}, {0, 1, 0},
            {0, -1, 0}, {0, 0, 1}, {0, 0, -1}
    };

    private RoomAnalyzer() { }

    public static Analysis analyze(
            List<PlayerMemory.ConstructionBlock> placed,
            int minX, int minY, int minZ,
            int maxX, int maxY, int maxZ,
            long remainingBudget
    ) {
        long spanX = (long) maxX - minX + 1L;
        long spanY = (long) maxY - minY + 1L;
        long spanZ = (long) maxZ - minZ + 1L;
        long volume = spanX * spanY * spanZ;
        if (spanX <= 0 || spanY <= 0 || spanZ <= 0
                || volume <= 0 || volume > MAX_CELLS_PER_COMPONENT
                || volume > remainingBudget) {
            return new Analysis(false, 0, 0, List.of(), List.of());
        }

        Set<Cell> occupied = new HashSet<>();
        for (PlayerMemory.ConstructionBlock block : placed) {
            if (block != null) occupied.add(new Cell(block.x, block.y, block.z));
        }

        Set<Cell> outside = new HashSet<>();
        ArrayDeque<Cell> frontier = new ArrayDeque<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                seed(new Cell(x, y, minZ), occupied, outside, frontier);
                seed(new Cell(x, y, maxZ), occupied, outside, frontier);
            }
        }
        for (int x = minX; x <= maxX; x++) {
            for (int z = minZ; z <= maxZ; z++) {
                seed(new Cell(x, minY, z), occupied, outside, frontier);
                seed(new Cell(x, maxY, z), occupied, outside, frontier);
            }
        }
        for (int y = minY; y <= maxY; y++) {
            for (int z = minZ; z <= maxZ; z++) {
                seed(new Cell(minX, y, z), occupied, outside, frontier);
                seed(new Cell(maxX, y, z), occupied, outside, frontier);
            }
        }

        while (!frontier.isEmpty()) {
            Cell cell = frontier.removeFirst();
            for (int[] direction : DIRECTIONS) {
                Cell adjacent = offset(cell, direction[0], direction[1], direction[2]);
                if (!inside(adjacent, minX, minY, minZ, maxX, maxY, maxZ)
                        || occupied.contains(adjacent) || !outside.add(adjacent)) continue;
                frontier.addLast(adjacent);
            }
        }

        int unoccupied = (int) (volume - occupied.size());
        Map<Cell, Integer> roomByCell = new HashMap<>();
        List<MutableRoom> rooms = new ArrayList<>();
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    Cell first = new Cell(x, y, z);
                    if (occupied.contains(first) || outside.contains(first)
                            || roomByCell.containsKey(first)) continue;
                    if (rooms.size() >= MAX_ROOMS) {
                        // High fragmentation is not proof of 128 separate rooms.
                        return new Analysis(false, unoccupied, 0, List.of(), List.of());
                    }
                    int id = rooms.size();
                    MutableRoom room = new MutableRoom(id);
                    rooms.add(room);
                    frontier.addLast(first);
                    roomByCell.put(first, id);
                    while (!frontier.isEmpty()) {
                        Cell cell = frontier.removeFirst();
                        room.add(cell);
                        for (int[] direction : DIRECTIONS) {
                            Cell adjacent = offset(cell, direction[0], direction[1], direction[2]);
                            if (!inside(adjacent, minX, minY, minZ, maxX, maxY, maxZ)
                                    || occupied.contains(adjacent)
                                    || outside.contains(adjacent)
                                    || roomByCell.putIfAbsent(adjacent, id) != null) continue;
                            frontier.addLast(adjacent);
                        }
                    }
                }
            }
        }

        for (PlayerMemory.ConstructionBlock block : placed) {
            if (block == null || block.blockId == null) continue;
            int marker = markerKind(block.blockId, block.blockState);
            if (marker == 0) continue;
            Set<Integer> adjacentRooms = new HashSet<>();
            Cell cell = new Cell(block.x, block.y, block.z);
            for (int[] direction : DIRECTIONS) {
                Integer id = roomByCell.get(offset(cell, direction[0], direction[1], direction[2]));
                if (id != null) adjacentRooms.add(id);
            }
            for (int id : adjacentRooms) rooms.get(id).mark(marker);
        }

        List<DoorLink> links = new ArrayList<>();
        Set<String> seenDoorPairs = new HashSet<>();
        for (PlayerMemory.ConstructionBlock block : placed) {
            if (block == null || block.blockId == null || !isDoor(block.blockId)
                    || (block.blockState != null && block.blockState.contains("half=upper"))) {
                continue;
            }
            Cell door = new Cell(block.x, block.y, block.z);
            maybeConnect(door, 1, 0, roomByCell, links, seenDoorPairs);
            maybeConnect(door, 0, 1, roomByCell, links, seenDoorPairs);
        }

        int enclosed = roomByCell.size();
        return new Analysis(true, unoccupied, enclosed,
                rooms.stream().map(MutableRoom::snapshot).toList(), List.copyOf(links));
    }

    private static boolean inside(Cell p, int minX, int minY, int minZ,
                                  int maxX, int maxY, int maxZ) {
        return p.x() >= minX && p.x() <= maxX
                && p.y() >= minY && p.y() <= maxY
                && p.z() >= minZ && p.z() <= maxZ;
    }

    private static Cell offset(Cell c, int dx, int dy, int dz) {
        return new Cell(c.x() + dx, c.y() + dy, c.z() + dz);
    }

    private static void seed(Cell cell, Set<Cell> occupied,
                             Set<Cell> outside, ArrayDeque<Cell> frontier) {
        if (!occupied.contains(cell) && outside.add(cell)) frontier.addLast(cell);
    }

    private static void maybeConnect(Cell door, int dx, int dz,
                                     Map<Cell, Integer> roomByCell,
                                     List<DoorLink> links, Set<String> seen) {
        Integer a = roomByCell.get(offset(door, -dx, 0, -dz));
        Integer b = roomByCell.get(offset(door, dx, 0, dz));
        if (a == null || b == null || a.equals(b)) return;
        int from = Math.min(a, b);
        int to = Math.max(a, b);
        String key = from + "|" + to + "|" + door.x() + "|" + door.y() + "|" + door.z();
        if (links.size() < MAX_DOOR_LINKS && seen.add(key)) {
            links.add(new DoorLink(from, to, door.x(), door.y(), door.z()));
        }
    }

    private static boolean isDoor(String blockId) {
        return path(blockId).endsWith("_door");
    }

    private static String path(String id) {
        int colon = id.indexOf(':');
        return colon < 0 ? id : id.substring(colon + 1);
    }

    /**
     * 1=bed, 2=storage, 3=workstation, 4=smelting.
     * The name heuristics cover vanilla blocks, not arbitrary modded equivalents.
     */
    private static int markerKind(String id, String state) {
        String path = path(id);
        if (path.endsWith("_bed")) {
            return state != null && state.contains("part=head") ? 0 : 1;
        }
        if (path.equals("chest") || path.equals("trapped_chest")
                || path.equals("barrel") || path.equals("ender_chest")
                || path.endsWith("_shulker_box") || path.equals("hopper")) return 2;
        if (path.equals("furnace") || path.equals("blast_furnace")
                || path.equals("smoker")) return 4;
        if (path.equals("crafting_table") || path.equals("smithing_table")
                || path.equals("cartography_table") || path.equals("fletching_table")
                || path.equals("loom") || path.equals("stonecutter")
                || path.equals("grindstone") || path.equals("enchanting_table")
                || path.equals("brewing_stand") || path.equals("anvil")
                || path.equals("chipped_anvil") || path.equals("damaged_anvil")) return 3;
        return 0;
    }

    public record Analysis(boolean complete, int unoccupiedCells,
                           int enclosedVoidCells, List<Room> rooms,
                           List<DoorLink> doorLinks) {
        public Analysis {
            rooms = List.copyOf(rooms);
            doorLinks = List.copyOf(doorLinks);
        }
        public int roomCount() { return rooms.size(); }
        public int furnishedRoomCount() {
            return (int) rooms.stream().filter(r -> !"UNCLASSIFIED".equals(r.role())).count();
        }
    }

    public record Room(int id, int cells,
                       int minX, int minY, int minZ, int maxX, int maxY, int maxZ,
                       int beds, int storage, int workstations, int smelters,
                       String role) {
        public String bounds() {
            return minX + "," + minY + "," + minZ + "->"
                    + maxX + "," + maxY + "," + maxZ;
        }
    }

    public record DoorLink(int roomA, int roomB, int x, int y, int z) { }

    private record Cell(int x, int y, int z) { }

    private static final class MutableRoom {
        private final int id;
        private int cells;
        private int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        private int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        private int beds, storage, workstations, smelters;

        private MutableRoom(int id) { this.id = id; }

        private void add(Cell p) {
            cells++;
            minX = Math.min(minX, p.x()); minY = Math.min(minY, p.y());
            minZ = Math.min(minZ, p.z());
            maxX = Math.max(maxX, p.x()); maxY = Math.max(maxY, p.y());
            maxZ = Math.max(maxZ, p.z());
        }

        private void mark(int kind) {
            switch (kind) {
                case 1 -> beds++;
                case 2 -> storage++;
                case 3 -> workstations++;
                case 4 -> smelters++;
                default -> { }
            }
        }

        private Room snapshot() {
            int highest = Math.max(Math.max(beds, storage), Math.max(workstations, smelters));
            int ties = (beds == highest ? 1 : 0)
                    + (storage == highest ? 1 : 0)
                    + (workstations == highest ? 1 : 0)
                    + (smelters == highest ? 1 : 0);
            String role = highest == 0 ? "UNCLASSIFIED"
                    : ties > 1 ? "MIXED"
                    : beds == highest ? "BEDROOM"
                    : storage == highest ? "STORAGE"
                    : workstations == highest ? "WORKSHOP" : "SMELTERY";
            return new Room(id, cells, minX, minY, minZ, maxX, maxY, maxZ,
                    beds, storage, workstations, smelters, role);
        }
    }
}
