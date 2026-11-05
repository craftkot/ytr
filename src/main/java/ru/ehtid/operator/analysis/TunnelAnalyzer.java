package ru.ehtid.operator.analysis;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import ru.ehtid.operator.data.PlayerMemory;

/**
 * Reconstructs connected mined volumes from block positions observed by Operator.
 * This does not inspect existing air blocks: only recorded player-mined cells are analysed.
 */
public final class TunnelAnalyzer {
    private static final int[][] DIRECTIONS = {
            {1, 0, 0}, {-1, 0, 0},
            {0, 1, 0}, {0, -1, 0},
            {0, 0, 1}, {0, 0, -1}
    };

    private TunnelAnalyzer() { }

    public static Analysis analyze(Collection<PlayerMemory.TunnelCell> cells, String dimension) {
        Map<BlockPos, PlayerMemory.TunnelCell> positions = new HashMap<>();
        if (cells != null) {
            for (PlayerMemory.TunnelCell cell : cells) {
                if (cell == null || cell.dimension == null) continue;
                if (dimension != null && !dimension.isBlank() && !dimension.equals(cell.dimension)) continue;
                positions.put(new BlockPos(cell.dimension, cell.x, cell.y, cell.z), cell);
            }
        }

        Set<BlockPos> remaining = new HashSet<>(positions.keySet());
        List<ComponentSummary> components = new ArrayList<>();
        while (!remaining.isEmpty()) {
            BlockPos start = remaining.iterator().next();
            Set<BlockPos> component = collectComponent(start, remaining);
            components.add(summarize(component));
        }

        components.sort(Comparator.comparingInt((ComponentSummary item) -> item.cells).reversed());
        int linear = 0;
        int chambers = 0;
        int irregular = 0;
        int small = 0;
        for (ComponentSummary component : components) {
            switch (component.type) {
                case LINEAR -> linear++;
                case CHAMBER -> chambers++;
                case IRREGULAR -> irregular++;
                case SMALL -> small++;
            }
        }

        ComponentSummary largest = components.isEmpty() ? null : components.get(0);
        return new Analysis(
                dimension == null || dimension.isBlank() ? "all dimensions" : dimension,
                positions.size(), components.size(), linear, chambers, irregular, small,
                largest, components
        );
    }

    private static Set<BlockPos> collectComponent(BlockPos start, Set<BlockPos> remaining) {
        Set<BlockPos> component = new HashSet<>();
        ArrayDeque<BlockPos> queue = new ArrayDeque<>();
        remaining.remove(start);
        queue.add(start);

        while (!queue.isEmpty()) {
            BlockPos current = queue.removeFirst();
            component.add(current);
            for (int[] direction : DIRECTIONS) {
                BlockPos neighbour = new BlockPos(
                        current.dimension,
                        current.x + direction[0],
                        current.y + direction[1],
                        current.z + direction[2]
                );
                if (remaining.remove(neighbour)) queue.addLast(neighbour);
            }
        }
        return component;
    }

    private static ComponentSummary summarize(Set<BlockPos> cells) {
        int minX = Integer.MAX_VALUE;
        int minY = Integer.MAX_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int maxY = Integer.MIN_VALUE;
        int maxZ = Integer.MIN_VALUE;

        for (BlockPos pos : cells) {
            minX = Math.min(minX, pos.x);
            minY = Math.min(minY, pos.y);
            minZ = Math.min(minZ, pos.z);
            maxX = Math.max(maxX, pos.x);
            maxY = Math.max(maxY, pos.y);
            maxZ = Math.max(maxZ, pos.z);
        }

        int extentX = maxX - minX + 1;
        int extentY = maxY - minY + 1;
        int extentZ = maxZ - minZ + 1;
        Axis axis = dominantAxis(extentX, extentY, extentZ);
        int longest = Math.max(extentX, Math.max(extentY, extentZ));
        int secondLongest = secondLargest(extentX, extentY, extentZ);
        long volume = (long) extentX * extentY * extentZ;
        double density = volume == 0L ? 0.0D : cells.size() / (double) volume;

        int edges = 0;
        int axisEdges = 0;
        for (BlockPos pos : cells) {
            if (cells.contains(new BlockPos(pos.x + 1, pos.y, pos.z))) {
                edges++;
                if (axis == Axis.X) axisEdges++;
            }
            if (cells.contains(new BlockPos(pos.x, pos.y + 1, pos.z))) {
                edges++;
                if (axis == Axis.Y) axisEdges++;
            }
            if (cells.contains(new BlockPos(pos.x, pos.y, pos.z + 1))) {
                edges++;
                if (axis == Axis.Z) axisEdges++;
            }
        }
        double axisEdgeRatio = edges == 0 ? 0.0D : axisEdges / (double) edges;

        ComponentType type;
        if (cells.size() < 4) {
            type = ComponentType.SMALL;
        } else if (Math.min(extentX, Math.min(extentY, extentZ)) >= 3
                && longest <= secondLongest * 1.5D
                && density >= 0.45D) {
            type = ComponentType.CHAMBER;
        } else if (longest >= 4 && longest >= secondLongest * 1.5D && axisEdgeRatio >= 0.50D) {
            type = ComponentType.LINEAR;
        } else {
            type = ComponentType.IRREGULAR;
        }

        return new ComponentSummary(
                cells.size(), extentX, extentY, extentZ, axis, longest,
                density, axisEdgeRatio, type
        );
    }

    private static Axis dominantAxis(int x, int y, int z) {
        if (x >= y && x >= z) return Axis.X;
        if (y >= z) return Axis.Y;
        return Axis.Z;
    }

    private static int secondLargest(int x, int y, int z) {
        int[] extents = {x, y, z};
        java.util.Arrays.sort(extents);
        return extents[1];
    }

    private record BlockPos(String dimension, int x, int y, int z) { }

    public enum Axis {
        X, Y, Z
    }

    public enum ComponentType {
        LINEAR, CHAMBER, IRREGULAR, SMALL
    }

    public static final class ComponentSummary {
        public final int cells;
        public final int extentX;
        public final int extentY;
        public final int extentZ;
        public final Axis axis;
        public final int length;
        public final double density;
        public final double axisEdgeRatio;
        public final ComponentType type;

        private ComponentSummary(int cells, int extentX, int extentY, int extentZ,
                                 Axis axis, int length, double density,
                                 double axisEdgeRatio, ComponentType type) {
            this.cells = cells;
            this.extentX = extentX;
            this.extentY = extentY;
            this.extentZ = extentZ;
            this.axis = axis;
            this.length = length;
            this.density = density;
            this.axisEdgeRatio = axisEdgeRatio;
            this.type = type;
        }
    }

    public static final class Analysis {
        public final String dimension;
        public final int mappedCells;
        public final int components;
        public final int linearComponents;
        public final int chamberComponents;
        public final int irregularComponents;
        public final int smallComponents;
        public final ComponentSummary largest;
        public final List<ComponentSummary> componentList;

        private Analysis(String dimension, int mappedCells, int components,
                         int linearComponents, int chamberComponents,
                         int irregularComponents, int smallComponents,
                         ComponentSummary largest, List<ComponentSummary> componentList) {
            this.dimension = dimension;
            this.mappedCells = mappedCells;
            this.components = components;
            this.linearComponents = linearComponents;
            this.chamberComponents = chamberComponents;
            this.irregularComponents = irregularComponents;
            this.smallComponents = smallComponents;
            this.largest = largest;
            this.componentList = List.copyOf(componentList);
        }

        public String describe() {
            if (mappedCells == 0) {
                return "No mined tunnel cells recorded in " + dimension
                        + ". Mine natural terrain blocks while Operator is active.";
            }
            String largestDescription = largest == null
                    ? "none"
                    : largest.type + ", " + largest.cells + " cells, axis " + largest.axis
                    + ", extent " + largest.length + " blocks";
            return "Tunnel map (" + dimension + ")"
                    + " | mapped dug cells: " + mappedCells
                    + " | connected areas: " + components
                    + " | linear: " + linearComponents
                    + " | chambers: " + chamberComponents
                    + " | irregular: " + irregularComponents
                    + " | small: " + smallComponents
                    + " | largest: " + largestDescription;
        }
    }
}
