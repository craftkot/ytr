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
 * Builds an approximate horizontal tunnel graph from mined terrain voxels.
 *
 * The first network model projects recorded voxels onto the X/Z plane, then
 * applies Zhang-Suen thinning before compressing the centerline into nodes
 * and corridor edges. It is intentionally a topology estimate, not a live
 * three-dimensional collision or air-space map.
 */
public final class TunnelNetworkAnalyzer {
    private static final int MAX_THINNING_ITERATIONS = 128;
    private static final int[][] CLOCKWISE_NEIGHBOURS = {
            {0, -1}, {1, -1}, {1, 0}, {1, 1},
            {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}
    };
    private static final int[][] CARDINAL_NEIGHBOURS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}
    };

    private TunnelNetworkAnalyzer() { }

    public static Analysis analyze(Collection<PlayerMemory.TunnelCell> cells, String dimension) {
        Set<VoxelPos> voxels = new HashSet<>();
        Set<ColumnPos> footprint = new HashSet<>();

        if (cells != null) {
            for (PlayerMemory.TunnelCell cell : cells) {
                if (cell == null || cell.dimension == null) continue;
                if (dimension != null && !dimension.isBlank() && !dimension.equals(cell.dimension)) continue;

                VoxelPos voxel = new VoxelPos(cell.dimension, cell.x, cell.y, cell.z);
                if (voxels.add(voxel)) {
                    footprint.add(new ColumnPos(cell.dimension, cell.x, cell.z));
                }
            }
        }

        Set<ColumnPos> remaining = new HashSet<>(footprint);
        List<ComponentSummary> components = new ArrayList<>();
        while (!remaining.isEmpty()) {
            ColumnPos start = remaining.stream().min(COLUMN_ORDER).orElseThrow();
            Set<ColumnPos> component = collectFootprintComponent(start, remaining);
            components.add(analyzeComponent(component));
        }
        components.sort(Comparator.comparingInt((ComponentSummary item) -> item.columns).reversed());

        int skeletonCells = 0;
        int deadEnds = 0;
        int junctions = 0;
        int corridorSegments = 0;
        int loops = 0;
        int isolatedAreas = 0;
        int longestSegment = 0;
        int totalCenterlineLength = 0;
        int thinningIterations = 0;
        boolean thinningConverged = true;
        List<Junction> junctionPoints = new ArrayList<>();

        for (ComponentSummary component : components) {
            skeletonCells += component.skeletonCells;
            deadEnds += component.deadEnds;
            junctions += component.junctions;
            corridorSegments += component.corridorSegments;
            loops += component.loops;
            isolatedAreas += component.isolatedAreas;
            longestSegment = Math.max(longestSegment, component.longestSegmentCells);
            totalCenterlineLength += component.totalCenterlineLength;
            thinningIterations += component.thinningIterations;
            thinningConverged &= component.thinningConverged;
            junctionPoints.addAll(component.junctionPoints);
        }

        junctionPoints.sort(Comparator
                .comparingInt((Junction item) -> item.exits).reversed()
                .thenComparing(item -> item.dimension)
                .thenComparingInt(item -> item.z)
                .thenComparingInt(item -> item.x));
        if (junctionPoints.size() > 8) {
            junctionPoints = new ArrayList<>(junctionPoints.subList(0, 8));
        }

        return new Analysis(
                dimension == null || dimension.isBlank() ? "all dimensions" : dimension,
                voxels.size(), footprint.size(), components.size(), skeletonCells,
                deadEnds, junctions, corridorSegments, loops, isolatedAreas,
                longestSegment, totalCenterlineLength, thinningIterations,
                thinningConverged, components, junctionPoints
        );
    }

    private static Set<ColumnPos> collectFootprintComponent(
            ColumnPos start,
            Set<ColumnPos> remaining
    ) {
        Set<ColumnPos> component = new HashSet<>();
        ArrayDeque<ColumnPos> queue = new ArrayDeque<>();
        remaining.remove(start);
        queue.add(start);

        while (!queue.isEmpty()) {
            ColumnPos current = queue.removeFirst();
            component.add(current);
            for (int[] direction : CARDINAL_NEIGHBOURS) {
                ColumnPos neighbour = new ColumnPos(
                        current.dimension,
                        current.x + direction[0],
                        current.z + direction[1]
                );
                if (remaining.remove(neighbour)) queue.addLast(neighbour);
            }
        }
        return component;
    }

    private static ComponentSummary analyzeComponent(Set<ColumnPos> footprint) {
        int minX = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE;
        int minZ = Integer.MAX_VALUE;
        int maxZ = Integer.MIN_VALUE;
        String dimension = "";

        for (ColumnPos pos : footprint) {
            dimension = pos.dimension;
            minX = Math.min(minX, pos.x);
            maxX = Math.max(maxX, pos.x);
            minZ = Math.min(minZ, pos.z);
            maxZ = Math.max(maxZ, pos.z);
        }

        ThinResult thin = thin(footprint);
        Map<ColumnPos, List<ColumnPos>> adjacency = buildAdjacency(thin.pixels);
        GraphSummary graph = compressGraph(thin.pixels, adjacency, dimension);

        return new ComponentSummary(
                dimension, footprint.size(), thin.pixels.size(),
                minX, maxX, minZ, maxZ,
                graph.deadEnds, graph.junctions, graph.edges.size(),
                graph.loops, graph.isolatedAreas, graph.longestSegmentCells,
                graph.totalCenterlineLength, thin.iterations, thin.converged,
                graph.junctionPoints
        );
    }

    /**
     * Zhang-Suen two-subiteration thinning. Removals are collected before
     * mutation so every pixel in a subiteration sees the same input image.
     */
    private static ThinResult thin(Set<ColumnPos> input) {
        Set<ColumnPos> pixels = new HashSet<>(input);
        int iterations = 0;
        boolean converged = false;

        for (; iterations < MAX_THINNING_ITERATIONS; iterations++) {
            boolean changed = false;
            for (int step = 0; step < 2; step++) {
                List<ColumnPos> remove = new ArrayList<>();
                for (ColumnPos pixel : pixels) {
                    boolean[] neighbours = new boolean[8];
                    int occupied = 0;
                    for (int i = 0; i < CLOCKWISE_NEIGHBOURS.length; i++) {
                        int[] offset = CLOCKWISE_NEIGHBOURS[i];
                        boolean present = pixels.contains(new ColumnPos(
                                pixel.dimension, pixel.x + offset[0], pixel.z + offset[1]
                        ));
                        neighbours[i] = present;
                        if (present) occupied++;
                    }

                    if (occupied < 2 || occupied > 6) continue;
                    int transitions = 0;
                    for (int i = 0; i < neighbours.length; i++) {
                        if (!neighbours[i] && neighbours[(i + 1) % neighbours.length]) {
                            transitions++;
                        }
                    }
                    if (transitions != 1) continue;

                    boolean north = neighbours[0];
                    boolean east = neighbours[2];
                    boolean south = neighbours[4];
                    boolean west = neighbours[6];

                    if (step == 0) {
                        if (north && east && south) continue;
                        if (east && south && west) continue;
                    } else {
                        if (north && east && west) continue;
                        if (north && south && west) continue;
                    }
                    remove.add(pixel);
                }

                if (!remove.isEmpty()) {
                    pixels.removeAll(remove);
                    changed = true;
                }
            }

            if (!changed) {
                converged = true;
                iterations++;
                break;
            }
        }

        return new ThinResult(pixels, iterations, converged);
    }

    /**
     * Uses 8-neighbour centerline adjacency, but suppresses diagonal corner
     * shortcuts whenever either orthogonal intermediary is also present.
     */
    private static Map<ColumnPos, List<ColumnPos>> buildAdjacency(Set<ColumnPos> pixels) {
        Map<ColumnPos, List<ColumnPos>> adjacency = new HashMap<>();
        for (ColumnPos pixel : pixels) {
            List<ColumnPos> neighbours = new ArrayList<>(8);
            for (int[] offset : CLOCKWISE_NEIGHBOURS) {
                int dx = offset[0];
                int dz = offset[1];
                ColumnPos candidate = new ColumnPos(
                        pixel.dimension, pixel.x + dx, pixel.z + dz
                );
                if (!pixels.contains(candidate)) continue;

                if (dx != 0 && dz != 0) {
                    ColumnPos horizontal = new ColumnPos(pixel.dimension, pixel.x + dx, pixel.z);
                    ColumnPos vertical = new ColumnPos(pixel.dimension, pixel.x, pixel.z + dz);
                    if (pixels.contains(horizontal) || pixels.contains(vertical)) continue;
                }
                neighbours.add(candidate);
            }
            adjacency.put(pixel, neighbours);
        }
        return adjacency;
    }

    private static GraphSummary compressGraph(
            Set<ColumnPos> pixels,
            Map<ColumnPos, List<ColumnPos>> adjacency,
            String dimension
    ) {
        Set<ColumnPos> special = new HashSet<>();
        for (Map.Entry<ColumnPos, List<ColumnPos>> entry : adjacency.entrySet()) {
            if (entry.getValue().size() != 2) special.add(entry.getKey());
        }

        boolean pureLoop = special.isEmpty();
        if (pureLoop && !pixels.isEmpty()) {
            special.add(pixels.stream().min(COLUMN_ORDER).orElseThrow());
        }

        List<Set<ColumnPos>> nodeClusters = clusterSpecialPixels(special, adjacency);
        Map<ColumnPos, Integer> nodeOfPixel = new HashMap<>();
        for (int i = 0; i < nodeClusters.size(); i++) {
            for (ColumnPos pixel : nodeClusters.get(i)) nodeOfPixel.put(pixel, i);
        }

        Set<EdgeKey> visitedEdges = new HashSet<>();
        List<GraphEdge> edges = new ArrayList<>();
        for (int nodeIndex = 0; nodeIndex < nodeClusters.size(); nodeIndex++) {
            List<ColumnPos> nodePixels = new ArrayList<>(nodeClusters.get(nodeIndex));
            nodePixels.sort(COLUMN_ORDER);
            Set<ColumnPos> cluster = nodeClusters.get(nodeIndex);

            for (ColumnPos pixel : nodePixels) {
                for (ColumnPos neighbour : adjacency.getOrDefault(pixel, List.of())) {
                    if (cluster.contains(neighbour)) continue;
                    EdgeKey firstEdge = EdgeKey.of(pixel, neighbour);
                    if (!visitedEdges.add(firstEdge)) continue;

                    ColumnPos previous = pixel;
                    ColumnPos current = neighbour;
                    int steps = 1;
                    int guard = 0;

                    while (!nodeOfPixel.containsKey(current)) {
                        ColumnPos next = null;
                        for (ColumnPos candidate : adjacency.getOrDefault(current, List.of())) {
                            if (!candidate.equals(previous)) {
                                next = candidate;
                                break;
                            }
                        }
                        if (next == null) break;

                        EdgeKey edge = EdgeKey.of(current, next);
                        if (!visitedEdges.add(edge) && !nodeOfPixel.containsKey(next)) break;

                        previous = current;
                        current = next;
                        steps++;
                        guard++;
                        if (guard > pixels.size() + 1) break;
                    }

                    Integer endNode = nodeOfPixel.get(current);
                    if (endNode != null) {
                        int length = steps + (nodeIndex == endNode ? 0 : 1);
                        edges.add(new GraphEdge(nodeIndex, endNode, length));
                    }
                }
            }
        }

        int[] externalDegrees = new int[nodeClusters.size()];
        int longest = 0;
        int totalLength = 0;
        for (GraphEdge edge : edges) {
            if (edge.startNode == edge.endNode) {
                externalDegrees[edge.startNode] += 2;
            } else {
                externalDegrees[edge.startNode]++;
                externalDegrees[edge.endNode]++;
            }
            longest = Math.max(longest, edge.lengthCells);
            totalLength += edge.lengthCells;
        }

        int deadEnds = 0;
        int junctions = 0;
        int isolatedAreas = 0;
        List<Junction> junctionPoints = new ArrayList<>();
        for (int i = 0; i < nodeClusters.size(); i++) {
            int exits = externalDegrees[i];
            if (exits == 1) {
                deadEnds++;
            } else if (exits >= 3) {
                junctions++;
                junctionPoints.add(centroid(nodeClusters.get(i), exits, dimension));
            } else if (exits == 0) {
                isolatedAreas++;
            }
        }

        int loops = Math.max(0, edges.size() - nodeClusters.size() + 1);
        return new GraphSummary(
                edges, deadEnds, junctions, loops, isolatedAreas,
                longest, totalLength, junctionPoints
        );
    }

    private static List<Set<ColumnPos>> clusterSpecialPixels(
            Set<ColumnPos> special,
            Map<ColumnPos, List<ColumnPos>> adjacency
    ) {
        Set<ColumnPos> remaining = new HashSet<>(special);
        List<ColumnPos> ordered = new ArrayList<>(special);
        ordered.sort(COLUMN_ORDER);
        List<Set<ColumnPos>> clusters = new ArrayList<>();

        for (ColumnPos start : ordered) {
            if (!remaining.remove(start)) continue;
            Set<ColumnPos> cluster = new HashSet<>();
            ArrayDeque<ColumnPos> queue = new ArrayDeque<>();
            queue.add(start);

            while (!queue.isEmpty()) {
                ColumnPos current = queue.removeFirst();
                cluster.add(current);
                for (ColumnPos neighbour : adjacency.getOrDefault(current, List.of())) {
                    if (remaining.remove(neighbour)) queue.addLast(neighbour);
                }
            }
            clusters.add(cluster);
        }
        return clusters;
    }

    private static Junction centroid(Set<ColumnPos> pixels, int exits, String dimension) {
        long sumX = 0L;
        long sumZ = 0L;
        for (ColumnPos pixel : pixels) {
            sumX += pixel.x;
            sumZ += pixel.z;
        }
        int x = (int) Math.round(sumX / (double) pixels.size());
        int z = (int) Math.round(sumZ / (double) pixels.size());
        return new Junction(dimension, x, z, exits);
    }

    private static final Comparator<ColumnPos> COLUMN_ORDER = Comparator
            .comparing((ColumnPos pos) -> pos.dimension)
            .thenComparingInt(pos -> pos.z)
            .thenComparingInt(pos -> pos.x);

    private record VoxelPos(String dimension, int x, int y, int z) { }
    private record ColumnPos(String dimension, int x, int z) { }
    private record ThinResult(Set<ColumnPos> pixels, int iterations, boolean converged) { }
    private record GraphEdge(int startNode, int endNode, int lengthCells) { }

    private record EdgeKey(ColumnPos first, ColumnPos second) {
        private static EdgeKey of(ColumnPos first, ColumnPos second) {
            return COLUMN_ORDER.compare(first, second) <= 0
                    ? new EdgeKey(first, second)
                    : new EdgeKey(second, first);
        }
    }

    private static final class GraphSummary {
        private final List<GraphEdge> edges;
        private final int deadEnds;
        private final int junctions;
        private final int loops;
        private final int isolatedAreas;
        private final int longestSegmentCells;
        private final int totalCenterlineLength;
        private final List<Junction> junctionPoints;

        private GraphSummary(List<GraphEdge> edges, int deadEnds, int junctions,
                             int loops, int isolatedAreas, int longestSegmentCells,
                             int totalCenterlineLength, List<Junction> junctionPoints) {
            this.edges = edges;
            this.deadEnds = deadEnds;
            this.junctions = junctions;
            this.loops = loops;
            this.isolatedAreas = isolatedAreas;
            this.longestSegmentCells = longestSegmentCells;
            this.totalCenterlineLength = totalCenterlineLength;
            this.junctionPoints = junctionPoints;
        }
    }

    public static final class Junction {
        public final String dimension;
        public final int x;
        public final int z;
        public final int exits;

        private Junction(String dimension, int x, int z, int exits) {
            this.dimension = dimension;
            this.x = x;
            this.z = z;
            this.exits = exits;
        }
    }

    public static final class ComponentSummary {
        public final String dimension;
        public final int columns;
        public final int skeletonCells;
        public final int minX;
        public final int maxX;
        public final int minZ;
        public final int maxZ;
        public final int deadEnds;
        public final int junctions;
        public final int corridorSegments;
        public final int loops;
        public final int isolatedAreas;
        public final int longestSegmentCells;
        public final int totalCenterlineLength;
        public final int thinningIterations;
        public final boolean thinningConverged;
        public final List<Junction> junctionPoints;

        private ComponentSummary(String dimension, int columns, int skeletonCells,
                                 int minX, int maxX, int minZ, int maxZ,
                                 int deadEnds, int junctions, int corridorSegments,
                                 int loops, int isolatedAreas, int longestSegmentCells,
                                 int totalCenterlineLength, int thinningIterations,
                                 boolean thinningConverged, List<Junction> junctionPoints) {
            this.dimension = dimension;
            this.columns = columns;
            this.skeletonCells = skeletonCells;
            this.minX = minX;
            this.maxX = maxX;
            this.minZ = minZ;
            this.maxZ = maxZ;
            this.deadEnds = deadEnds;
            this.junctions = junctions;
            this.corridorSegments = corridorSegments;
            this.loops = loops;
            this.isolatedAreas = isolatedAreas;
            this.longestSegmentCells = longestSegmentCells;
            this.totalCenterlineLength = totalCenterlineLength;
            this.thinningIterations = thinningIterations;
            this.thinningConverged = thinningConverged;
            this.junctionPoints = List.copyOf(junctionPoints);
        }
    }

    public static final class Analysis {
        public final String dimension;
        public final int mappedVoxels;
        public final int footprintColumns;
        public final int networkComponents;
        public final int skeletonCells;
        public final int deadEnds;
        public final int junctions;
        public final int corridorSegments;
        public final int loops;
        public final int isolatedAreas;
        public final int longestSegmentCells;
        public final int totalCenterlineLength;
        public final int thinningIterations;
        public final boolean thinningConverged;
        public final List<ComponentSummary> componentList;
        public final List<Junction> junctionPoints;

        private Analysis(String dimension, int mappedVoxels, int footprintColumns,
                         int networkComponents, int skeletonCells, int deadEnds,
                         int junctions, int corridorSegments, int loops, int isolatedAreas,
                         int longestSegmentCells, int totalCenterlineLength,
                         int thinningIterations, boolean thinningConverged,
                         List<ComponentSummary> componentList, List<Junction> junctionPoints) {
            this.dimension = dimension;
            this.mappedVoxels = mappedVoxels;
            this.footprintColumns = footprintColumns;
            this.networkComponents = networkComponents;
            this.skeletonCells = skeletonCells;
            this.deadEnds = deadEnds;
            this.junctions = junctions;
            this.corridorSegments = corridorSegments;
            this.loops = loops;
            this.isolatedAreas = isolatedAreas;
            this.longestSegmentCells = longestSegmentCells;
            this.totalCenterlineLength = totalCenterlineLength;
            this.thinningIterations = thinningIterations;
            this.thinningConverged = thinningConverged;
            this.componentList = List.copyOf(componentList);
            this.junctionPoints = List.copyOf(junctionPoints);
        }

        public String describe() {
            if (footprintColumns == 0) {
                return "No horizontal tunnel footprint recorded in " + dimension
                        + ". Mine natural terrain while Operator is active.";
            }

            StringBuilder result = new StringBuilder("Tunnel network (")
                    .append(dimension).append(')')
                    .append(" | mapped voxels: ").append(mappedVoxels)
                    .append(" | X/Z footprint columns: ").append(footprintColumns)
                    .append(" | connected networks: ").append(networkComponents)
                    .append(" | centerline cells: ").append(skeletonCells)
                    .append(" | dead ends: ").append(deadEnds)
                    .append(" | junctions: ").append(junctions)
                    .append(" | corridor segments: ").append(corridorSegments)
                    .append(" | loops: ").append(loops)
                    .append(" | isolated areas: ").append(isolatedAreas)
                    .append(" | longest centerline segment: ").append(longestSegmentCells).append(" cells");

            if (!junctionPoints.isEmpty()) {
                result.append(" | junction coordinates: ");
                int shown = Math.min(5, junctionPoints.size());
                for (int i = 0; i < shown; i++) {
                    Junction junction = junctionPoints.get(i);
                    if (i > 0) result.append("; ");
                    result.append('(').append(junction.x).append(", ").append(junction.z)
                            .append(", exits ").append(junction.exits).append(')');
                }
            }

            result.append(" | model: horizontal X/Z projection; vertical layers are collapsed");
            if (!thinningConverged) result.append(" | warning: centerline thinning reached its iteration limit");
            return result.toString();
        }
    }
}
