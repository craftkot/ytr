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
 * Height-aware tunnel topology reconstructed from mapped air voxels.
 * Horizontal centerlines are thinned independently for each Y layer and then
 * linked using their real heights. Vertical runs up to three cells high are
 * treated as tunnel clearance; taller runs are retained as vertical shafts.
 * This is a layer-wise 3D approximation, not a full Euclidean medial axis.
 */
public final class Tunnel3DNetworkAnalyzer {
    private static final int MAX_ITERATIONS = 128;
    private static final int COLLAPSED_VERTICAL_HEIGHT = 3;
    private static final int[][] NEIGHBOURS_2D = {
            {0, -1}, {1, -1}, {1, 0}, {1, 1},
            {0, 1}, {-1, 1}, {-1, 0}, {-1, -1}
    };
    private static final int[][] HORIZONTAL_DIRECTIONS = {{1, 0}, {0, 1}};

    private Tunnel3DNetworkAnalyzer() { }

    public static Analysis analyze(Collection<PlayerMemory.TunnelCell> cells, String dimension) {
        Set<Voxel> voxels = new HashSet<>();
        Map<Slice, Set<Column>> slices = new HashMap<>();
        if (cells != null) {
            for (PlayerMemory.TunnelCell cell : cells) {
                if (cell == null || cell.dimension == null) continue;
                if (dimension != null && !dimension.isBlank() && !dimension.equals(cell.dimension)) continue;
                if (!voxels.add(new Voxel(cell.dimension, cell.x, cell.y, cell.z))) continue;
                slices.computeIfAbsent(new Slice(cell.dimension, cell.y), ignored -> new HashSet<>())
                        .add(new Column(cell.x, cell.z));
            }
        }

        String label = dimension == null || dimension.isBlank() ? "all dimensions" : dimension;
        if (voxels.isEmpty()) return Analysis.empty(label);

        Map<ColumnKey, Set<Integer>> levelsByColumn = new HashMap<>();
        int iterations = 0;
        boolean converged = true;
        List<Slice> orderedSlices = new ArrayList<>(slices.keySet());
        orderedSlices.sort(Comparator.comparing((Slice s) -> s.dimension).thenComparingInt(s -> s.y));
        for (Slice slice : orderedSlices) {
            ThinResult result = thin(slices.get(slice));
            iterations += result.iterations;
            converged &= result.converged;
            for (Column pixel : result.pixels) {
                levelsByColumn.computeIfAbsent(
                        new ColumnKey(slice.dimension, pixel.x, pixel.z), ignored -> new HashSet<>()
                ).add(slice.y);
            }
        }

        List<ColumnKey> columns = new ArrayList<>(levelsByColumn.keySet());
        columns.sort(Comparator.comparing((ColumnKey c) -> c.dimension)
                .thenComparingInt(c -> c.z).thenComparingInt(c -> c.x));

        Map<Integer, Node> nodes = new HashMap<>();
        Map<ColumnKey, List<Span>> spansAt = new HashMap<>();
        List<Span> spans = new ArrayList<>();
        int nextId = 0;
        for (ColumnKey column : columns) {
            List<Integer> ys = new ArrayList<>(levelsByColumn.get(column));
            ys.sort(Integer::compareTo);
            if (ys.isEmpty()) continue;
            int start = ys.get(0);
            int end = start;
            for (int i = 1; i <= ys.size(); i++) {
                if (i < ys.size() && ys.get(i) == end + 1) {
                    end = ys.get(i);
                    continue;
                }
                Span span = new Span(column, start, end);
                if (span.height() <= COLLAPSED_VERTICAL_HEIGHT) {
                    Node node = new Node(nextId++, column.dimension, column.x,
                            (start + end) / 2.0D, column.z);
                    span.nodes.add(node);
                    nodes.put(node.id, node);
                } else {
                    for (int y = start; y <= end; y++) {
                        Node node = new Node(nextId++, column.dimension, column.x, y, column.z);
                        span.nodes.add(node);
                        nodes.put(node.id, node);
                    }
                    for (int j = 1; j < span.nodes.size(); j++) {
                        addEdge(span.nodes.get(j - 1), span.nodes.get(j));
                    }
                }
                spans.add(span);
                spansAt.computeIfAbsent(column, ignored -> new ArrayList<>()).add(span);
                if (i < ys.size()) {
                    start = ys.get(i);
                    end = start;
                }
            }
        }

        connectSpans(spans, spansAt);
        Set<Integer> remaining = new HashSet<>(nodes.keySet());
        List<Component> components = new ArrayList<>();
        while (!remaining.isEmpty()) {
            int start = remaining.stream().min(Integer::compareTo).orElseThrow();
            Set<Integer> ids = collectComponent(start, remaining, nodes);
            components.add(summarizeComponent(ids, nodes));
        }

        int deadEnds = 0, junctions = 0, corridorSegments = 0;
        int verticalSegments = 0, loops = 0, isolated = 0;
        double longest = 0.0D, totalLength = 0.0D;
        List<Junction> junctionPoints = new ArrayList<>();
        List<CorridorSegment> segments = new ArrayList<>();
        for (Component component : components) {
            deadEnds += component.deadEnds;
            junctions += component.junctions;
            corridorSegments += component.segments.size();
            verticalSegments += component.verticalSegments;
            loops += component.loops;
            isolated += component.isolatedAreas;
            longest = Math.max(longest, component.longest);
            totalLength += component.totalLength;
            junctionPoints.addAll(component.junctionsFound);
            segments.addAll(component.segments);
        }

        junctionPoints.sort(Comparator.comparingInt((Junction j) -> j.exits).reversed()
                .thenComparing(j -> j.dimension).thenComparingDouble(j -> j.y)
                .thenComparingDouble(j -> j.z).thenComparingDouble(j -> j.x));
        if (junctionPoints.size() > 8) junctionPoints = new ArrayList<>(junctionPoints.subList(0, 8));
        segments.sort(Comparator.comparingDouble((CorridorSegment s) -> s.length).reversed()
                .thenComparing(s -> s.dimension).thenComparingDouble(s -> Math.min(s.startY, s.endY))
                .thenComparingDouble(s -> Math.min(s.startX, s.endX)));
        if (segments.size() > 8) segments = new ArrayList<>(segments.subList(0, 8));

        return new Analysis(label, voxels.size(), slices.size(), nodes.size(), components.size(),
                deadEnds, junctions, corridorSegments, verticalSegments, loops, isolated,
                longest, totalLength, iterations, converged, junctionPoints, segments);
    }

    private static void connectSpans(List<Span> spans, Map<ColumnKey, List<Span>> spansAt) {
        for (Span span : spans) {
            for (int[] direction : HORIZONTAL_DIRECTIONS) {
                ColumnKey key = new ColumnKey(span.column.dimension,
                        span.column.x + direction[0], span.column.z + direction[1]);
                List<Span> adjacent = spansAt.get(key);
                if (adjacent == null) continue;
                for (Span other : adjacent) {
                    if (verticalGap(span, other) <= 1) connectSpanPair(span, other);
                }
            }
        }
    }

    private static int verticalGap(Span a, Span b) {
        if (a.maxY < b.minY) return b.minY - a.maxY;
        if (b.maxY < a.minY) return a.minY - b.maxY;
        return 0;
    }

    private static void connectSpanPair(Span a, Span b) {
        if (a.nodes.size() == 1 && b.nodes.size() == 1) {
            addEdge(a.nodes.get(0), b.nodes.get(0));
        } else if (a.nodes.size() == 1) {
            addEdge(a.nodes.get(0), nearest(b, a.centerY()));
        } else if (b.nodes.size() == 1) {
            addEdge(nearest(a, b.centerY()), b.nodes.get(0));
        } else {
            for (Node node : a.nodes) {
                Node other = nearest(b, node.y);
                if (Math.abs(other.y - node.y) <= 1.01D) addEdge(node, other);
            }
        }
    }

    private static Node nearest(Span span, double y) {
        return span.nodes.stream().min(Comparator
                .comparingDouble((Node node) -> Math.abs(node.y - y))
                .thenComparingDouble(node -> node.y)).orElseThrow();
    }

    private static void addEdge(Node a, Node b) {
        if (a.id == b.id) return;
        double dx = a.x - b.x, dy = a.y - b.y, dz = a.z - b.z;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        a.links.putIfAbsent(b.id, length);
        b.links.putIfAbsent(a.id, length);
    }

    private static Set<Integer> collectComponent(int start, Set<Integer> remaining, Map<Integer, Node> nodes) {
        Set<Integer> result = new HashSet<>();
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        remaining.remove(start);
        queue.add(start);
        while (!queue.isEmpty()) {
            int id = queue.removeFirst();
            result.add(id);
            for (int neighbour : nodes.get(id).links.keySet()) {
                if (remaining.remove(neighbour)) queue.addLast(neighbour);
            }
        }
        return result;
    }

    private static Component summarizeComponent(Set<Integer> ids, Map<Integer, Node> nodes) {
        Set<Integer> special = new HashSet<>();
        int originalEdges = 0;
        for (int id : ids) {
            Node node = nodes.get(id);
            if (node.links.size() != 2) special.add(id);
            originalEdges += node.links.size();
        }
        originalEdges /= 2;
        if (special.isEmpty() && !ids.isEmpty()) special.add(ids.stream().min(Integer::compareTo).orElseThrow());

        List<Set<Integer>> clusters = clusterSpecial(special, nodes);
        Map<Integer, Integer> clusterOf = new HashMap<>();
        List<Point> centers = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++) {
            for (int id : clusters.get(i)) clusterOf.put(id, i);
            centers.add(centroid(clusters.get(i), nodes));
        }

        Set<EdgeKey> visited = new HashSet<>();
        List<CompressedEdge> edges = new ArrayList<>();
        for (int clusterIndex = 0; clusterIndex < clusters.size(); clusterIndex++) {
            List<Integer> ordered = new ArrayList<>(clusters.get(clusterIndex));
            ordered.sort(Integer::compareTo);
            for (int nodeId : ordered) {
                Node node = nodes.get(nodeId);
                List<Integer> neighbours = new ArrayList<>(node.links.keySet());
                neighbours.sort(Integer::compareTo);
                for (int neighbour : neighbours) {
                    if (clusters.get(clusterIndex).contains(neighbour)) continue;
                    if (!visited.add(EdgeKey.of(nodeId, neighbour))) continue;
                    int previous = nodeId;
                    int current = neighbour;
                    double length = node.links.get(neighbour);
                    int guard = 0;
                    while (!clusterOf.containsKey(current)) {
                        Node currentNode = nodes.get(current);
                        Integer next = null;
                        int previousNode = previous;
                        for (int candidate : currentNode.links.keySet()) {
                            if (candidate != previousNode && (next == null || candidate < next)) next = candidate;
                        }
                        if (next == null) break;
                        EdgeKey key = EdgeKey.of(current, next);
                        if (!visited.add(key) && !clusterOf.containsKey(next)) break;
                        length += currentNode.links.get(next);
                        previous = current;
                        current = next;
                        if (++guard > ids.size() + 1) break;
                    }
                    Integer endCluster = clusterOf.get(current);
                    if (endCluster != null) edges.add(new CompressedEdge(clusterIndex, endCluster, length));
                }
            }
        }

        int[] degrees = new int[clusters.size()];
        double longest = 0.0D, total = 0.0D;
        int verticalSegments = 0;
        List<CorridorSegment> segments = new ArrayList<>();
        for (CompressedEdge edge : edges) {
            if (edge.start == edge.end) degrees[edge.start] += 2;
            else {
                degrees[edge.start]++;
                degrees[edge.end]++;
            }
            Point a = centers.get(edge.start), b = centers.get(edge.end);
            longest = Math.max(longest, edge.length);
            total += edge.length;
            if (Math.abs(a.x - b.x) < 0.001D && Math.abs(a.z - b.z) < 0.001D
                    && Math.abs(a.y - b.y) >= 1.0D) verticalSegments++;
            String dim = nodes.get(clusters.get(edge.start).iterator().next()).dimension;
            segments.add(new CorridorSegment(dim, a.x, a.y, a.z, b.x, b.y, b.z, edge.length));
        }

        int deadEnds = 0, junctions = 0, isolated = 0;
        List<Junction> junctionsFound = new ArrayList<>();
        for (int i = 0; i < clusters.size(); i++) {
            if (degrees[i] == 1) deadEnds++;
            else if (degrees[i] >= 3) {
                junctions++;
                Point p = centers.get(i);
                String dim = nodes.get(clusters.get(i).iterator().next()).dimension;
                junctionsFound.add(new Junction(dim, p.x, p.y, p.z, degrees[i]));
            } else if (degrees[i] == 0) isolated++;
        }
        return new Component(deadEnds, junctions, Math.max(0, originalEdges - ids.size() + 1),
                isolated, verticalSegments, longest, total, junctionsFound, segments);
    }

    private static List<Set<Integer>> clusterSpecial(Set<Integer> special, Map<Integer, Node> nodes) {
        Set<Integer> remaining = new HashSet<>(special);
        List<Integer> ordered = new ArrayList<>(special);
        ordered.sort(Integer::compareTo);
        List<Set<Integer>> clusters = new ArrayList<>();
        for (int start : ordered) {
            if (!remaining.remove(start)) continue;
            Set<Integer> cluster = new HashSet<>();
            ArrayDeque<Integer> queue = new ArrayDeque<>();
            queue.add(start);
            while (!queue.isEmpty()) {
                int current = queue.removeFirst();
                cluster.add(current);
                for (int neighbour : nodes.get(current).links.keySet()) {
                    if (remaining.remove(neighbour)) queue.addLast(neighbour);
                }
            }
            clusters.add(cluster);
        }
        return clusters;
    }

    private static Point centroid(Set<Integer> ids, Map<Integer, Node> nodes) {
        long sx = 0L, sz = 0L;
        double sy = 0.0D;
        for (int id : ids) {
            Node node = nodes.get(id);
            sx += node.x;
            sy += node.y;
            sz += node.z;
        }
        return new Point(sx / (double) ids.size(), sy / ids.size(), sz / (double) ids.size());
    }

    private static ThinResult thin(Set<Column> input) {
        Set<Column> pixels = new HashSet<>(input);
        int iterations = 0;
        boolean converged = false;
        for (; iterations < MAX_ITERATIONS; iterations++) {
            boolean changed = false;
            for (int step = 0; step < 2; step++) {
                List<Column> remove = new ArrayList<>();
                for (Column pixel : pixels) {
                    boolean[] n = new boolean[8];
                    int occupied = 0;
                    for (int i = 0; i < 8; i++) {
                        int[] d = NEIGHBOURS_2D[i];
                        n[i] = pixels.contains(new Column(pixel.x + d[0], pixel.z + d[1]));
                        if (n[i]) occupied++;
                    }
                    if (occupied < 2 || occupied > 6) continue;
                    int transitions = 0;
                    for (int i = 0; i < 8; i++) if (!n[i] && n[(i + 1) % 8]) transitions++;
                    if (transitions != 1) continue;
                    boolean north = n[0], east = n[2], south = n[4], west = n[6];
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

    private static String format(double value) {
        if (Math.abs(value - Math.rint(value)) < 0.001D) return Long.toString(Math.round(value));
        return String.format(java.util.Locale.ROOT, "%.1f", value);
    }

    private record Voxel(String dimension, int x, int y, int z) { }
    private record Slice(String dimension, int y) { }
    private record Column(int x, int z) { }
    private record ColumnKey(String dimension, int x, int z) { }
    private record ThinResult(Set<Column> pixels, int iterations, boolean converged) { }
    private record Point(double x, double y, double z) { }
    private record EdgeKey(int a, int b) {
        private static EdgeKey of(int a, int b) {
            return a <= b ? new EdgeKey(a, b) : new EdgeKey(b, a);
        }
    }
    private record CompressedEdge(int start, int end, double length) { }

    private static final class Node {
        private final int id;
        private final String dimension;
        private final int x;
        private final double y;
        private final int z;
        private final Map<Integer, Double> links = new HashMap<>();
        private Node(int id, String dimension, int x, double y, int z) {
            this.id = id; this.dimension = dimension; this.x = x; this.y = y; this.z = z;
        }
    }

    private static final class Span {
        private final ColumnKey column;
        private final int minY, maxY;
        private final List<Node> nodes = new ArrayList<>();
        private Span(ColumnKey column, int minY, int maxY) {
            this.column = column; this.minY = minY; this.maxY = maxY;
        }
        private int height() { return maxY - minY + 1; }
        private double centerY() { return (minY + maxY) / 2.0D; }
    }

    private static final class Component {
        private final int deadEnds, junctions, loops, isolatedAreas, verticalSegments;
        private final double longest, totalLength;
        private final List<Junction> junctionsFound;
        private final List<CorridorSegment> segments;
        private Component(int deadEnds, int junctions, int loops, int isolatedAreas,
                          int verticalSegments, double longest, double totalLength,
                          List<Junction> junctionsFound, List<CorridorSegment> segments) {
            this.deadEnds = deadEnds; this.junctions = junctions; this.loops = loops;
            this.isolatedAreas = isolatedAreas; this.verticalSegments = verticalSegments;
            this.longest = longest; this.totalLength = totalLength;
            this.junctionsFound = junctionsFound; this.segments = segments;
        }
    }

    public static final class Junction {
        public final String dimension;
        public final double x, y, z;
        public final int exits;
        private Junction(String dimension, double x, double y, double z, int exits) {
            this.dimension = dimension; this.x = x; this.y = y; this.z = z; this.exits = exits;
        }
    }

    public static final class CorridorSegment {
        public final String dimension;
        public final double startX, startY, startZ, endX, endY, endZ, length;
        private CorridorSegment(String dimension, double startX, double startY, double startZ,
                                double endX, double endY, double endZ, double length) {
            this.dimension = dimension; this.startX = startX; this.startY = startY;
            this.startZ = startZ; this.endX = endX; this.endY = endY; this.endZ = endZ;
            this.length = length;
        }
    }

    public static final class Analysis {
        public final String dimension;
        public final int mappedVoxels, verticalLayers, centerlineNodes, networkComponents;
        public final int deadEnds, junctions, corridorSegments, verticalSegments, loops, isolatedAreas;
        public final double longestSegment, totalCenterlineLength;
        public final int thinningIterations;
        public final boolean thinningConverged;
        public final List<Junction> junctionPoints;
        public final List<CorridorSegment> segments;

        private Analysis(String dimension, int mappedVoxels, int verticalLayers, int centerlineNodes,
                         int networkComponents, int deadEnds, int junctions, int corridorSegments,
                         int verticalSegments, int loops, int isolatedAreas, double longestSegment,
                         double totalCenterlineLength, int thinningIterations, boolean thinningConverged,
                         List<Junction> junctionPoints, List<CorridorSegment> segments) {
            this.dimension = dimension; this.mappedVoxels = mappedVoxels; this.verticalLayers = verticalLayers;
            this.centerlineNodes = centerlineNodes; this.networkComponents = networkComponents;
            this.deadEnds = deadEnds; this.junctions = junctions; this.corridorSegments = corridorSegments;
            this.verticalSegments = verticalSegments; this.loops = loops; this.isolatedAreas = isolatedAreas;
            this.longestSegment = longestSegment; this.totalCenterlineLength = totalCenterlineLength;
            this.thinningIterations = thinningIterations; this.thinningConverged = thinningConverged;
            this.junctionPoints = List.copyOf(junctionPoints); this.segments = List.copyOf(segments);
        }

        private static Analysis empty(String dimension) {
            return new Analysis(dimension, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                    0.0D, 0.0D, 0, true, List.of(), List.of());
        }

        public String describe() {
            if (mappedVoxels == 0) return "No mapped tunnel voxels recorded in " + dimension
                    + ". Explore enclosed underground spaces while Operator is active.";
            StringBuilder out = new StringBuilder("3D tunnel network (").append(dimension).append(')')
                    .append(" | mapped voxels: ").append(mappedVoxels)
                    .append(" | Y layers: ").append(verticalLayers)
                    .append(" | centerline nodes: ").append(centerlineNodes)
                    .append(" | connected networks: ").append(networkComponents)
                    .append(" | dead ends: ").append(deadEnds)
                    .append(" | junctions: ").append(junctions)
                    .append(" | corridor segments: ").append(corridorSegments)
                    .append(" | vertical shafts: ").append(verticalSegments)
                    .append(" | loops: ").append(loops)
                    .append(" | isolated areas: ").append(isolatedAreas)
                    .append(" | longest segment: ").append(format(longestSegment)).append(" blocks");
            if (!junctionPoints.isEmpty()) {
                out.append(" | 3D junctions: ");
                for (int i = 0; i < Math.min(4, junctionPoints.size()); i++) {
                    Junction j = junctionPoints.get(i);
                    if (i > 0) out.append("; ");
                    out.append('(').append(format(j.x)).append(", ").append(format(j.y))
                            .append(", ").append(format(j.z)).append(", exits ").append(j.exits).append(')');
                }
            }
            if (!segments.isEmpty()) {
                out.append(" | main segments: ");
                for (int i = 0; i < Math.min(4, segments.size()); i++) {
                    CorridorSegment s = segments.get(i);
                    if (i > 0) out.append("; ");
                    out.append('(').append(format(s.startX)).append(", ").append(format(s.startY))
                            .append(", ").append(format(s.startZ)).append(")->(")
                            .append(format(s.endX)).append(", ").append(format(s.endY))
                            .append(", ").append(format(s.endZ)).append(") ~")
                            .append(format(s.length)).append(" blocks");
                }
            }
            out.append(" | model: per-Y centerlines linked in 3D; vertical bands up to ")
                    .append(COLLAPSED_VERTICAL_HEIGHT).append(" blocks are treated as tunnel clearance");
            if (!thinningConverged) out.append(" | warning: a layer reached the thinning iteration limit");
            return out.toString();
        }
    }
}
