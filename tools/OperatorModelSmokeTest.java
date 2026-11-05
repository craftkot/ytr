import ru.ehtid.operator.data.PlayerMemory;
import ru.ehtid.operator.analysis.TunnelAnalyzer;
import ru.ehtid.operator.analysis.TunnelNetworkAnalyzer;
import ru.ehtid.operator.analysis.Tunnel3DNetworkAnalyzer;
import java.util.ArrayList;
import java.util.List;

public final class OperatorModelSmokeTest {
    public static void main(String[] args) {
        PlayerMemory memory = new PlayerMemory();
        for (int tick = 0; tick < 24_000; tick++) memory.recordActiveTick(tick);
        check(memory.personalObservationComplete(24_000), "personal training gate should open at 24,000 active ticks");

        memory.recordMovement(0, -20, 0, "minecraft:overworld", 24_000);
        memory.recordMovement(1, -20, 0, "minecraft:overworld", 24_100);
        memory.recordMovement(2, -20, 0, "minecraft:overworld", 24_200);
        memory.recordMovement(3, -20, 0, "minecraft:overworld", 24_300);
        check(Math.abs(memory.distanceTravelled - 3.0D) < 0.0001D, "small movement segments should accumulate");
        check(memory.route.size() == 4, "route should retain meaningful movement samples");

        memory.recordBrokenBlock(0, -20, 0, "minecraft:overworld", "minecraft:stone", true, 24_310);
        memory.recordBrokenBlock(1, -20, 0, "minecraft:overworld", "minecraft:stone", true, 24_320);
        memory.recordBrokenBlock(2, -20, 0, "minecraft:overworld", "minecraft:deepslate", true, 24_330);
        check(memory.currentMiningChain == 3, "adjacent mining blocks should form one chain");
        check(memory.longestMiningChain == 3, "longest mining chain should be retained");
        check("east".equals(memory.dominantMiningDirection()), "positive X mining should be east");
        check("minecraft:stone".equals(memory.mostMinedBlockType()), "most mined type should only include rock-like blocks");

        memory.recordBrokenBlock(3, -20, 0, "minecraft:the_nether", "minecraft:netherrack", true, 24_340);
        check(memory.currentMiningChain == 1, "dimension change must start a new mining chain");
        check(memory.miningSessions == 2, "dimension change must start a new mining session");

        PlayerMemory tunnelMemory = new PlayerMemory();
        for (int x = 0; x < 8; x++) {
            tunnelMemory.recordBrokenBlock(
                    x, -20, 0, "minecraft:overworld", "minecraft:stone",
                    true, true, 25_000L + x
            );
        }
        TunnelAnalyzer.Analysis tunnelAnalysis =
                TunnelAnalyzer.analyze(tunnelMemory.tunnelCells.values(), "minecraft:overworld");
        check(tunnelAnalysis.mappedCells == 8, "mined voxel map should contain eight unique cells");
        check(tunnelAnalysis.components == 1, "connected mined blocks should form one area");
        check(tunnelAnalysis.largest != null
                && tunnelAnalysis.largest.type == TunnelAnalyzer.ComponentType.LINEAR,
                "eight adjacent mined blocks should classify as a linear tunnel");
        check(tunnelMemory.removeTunnelCell("minecraft:overworld", 3, -20, 0),
                "placing a block in a mined voxel should invalidate that cell");
        check(tunnelMemory.getTunnelCellCount() == 7,
                "voxel invalidation should update the map without erasing mining totals");

        List<PlayerMemory.TunnelCell> networkCells = new ArrayList<>();
        for (int x = -5; x <= 5; x++) {
            networkCells.add(new PlayerMemory.TunnelCell(
                    x, 20, 0, "minecraft:overworld", "minecraft:stone", 30_000L + x
            ));
        }
        for (int z = -5; z <= 0; z++) {
            networkCells.add(new PlayerMemory.TunnelCell(
                    0, 20, z, "minecraft:overworld", "minecraft:stone", 31_000L + z
            ));
        }
        TunnelNetworkAnalyzer.Analysis network =
                TunnelNetworkAnalyzer.analyze(networkCells, "minecraft:overworld");
        check(network.deadEnds == 3, "T-shaped tunnel should have three dead ends");
        check(network.junctions == 1, "T-shaped tunnel should have one junction");
        check(network.corridorSegments == 3, "T-shaped tunnel should have three corridor segments");

        List<PlayerMemory.TunnelCell> stacked = new ArrayList<>();
        for (int x = 0; x < 6; x++) {
            for (int y : new int[]{20, 21, 25, 26}) {
                stacked.add(new PlayerMemory.TunnelCell(
                        x, y, 0, "minecraft:overworld", "minecraft:stone", 40_000L + y + x
                ));
            }
        }
        Tunnel3DNetworkAnalyzer.Analysis network3d =
                Tunnel3DNetworkAnalyzer.analyze(stacked, "minecraft:overworld");
        check(network3d.networkComponents == 2,
                "stacked passages at separate heights must remain separate 3D networks");
        check(network3d.corridorSegments == 2,
                "each stacked passage must retain its own corridor segment");

        PlayerMemory bounded = new PlayerMemory();
        for (int i = 0; i < 700; i++) {
            bounded.recordMovement(i * 3.0D, 64, 0, "minecraft:overworld", i * 100L);
        }
        for (int i = 0; i < 2_200; i++) {
            bounded.recordBrokenBlock(i, -40, 0, "minecraft:overworld", "test:stone_" + i, true, i);
        }
        check(bounded.route.size() == PlayerMemory.MAX_ROUTE_SAMPLES, "route history must remain bounded");
        check(bounded.mineHistory.size() == PlayerMemory.MAX_MINE_SAMPLES, "mining history must remain bounded");
        check(bounded.minedBlockTypes.size() <= PlayerMemory.MAX_COUNTER_KEYS, "mined block counters must remain bounded");

        System.out.println("PASS: player learning, movement, mining chain, bounded memory, 2D tunnel geometry, and height-aware 3D tunnel topology.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
