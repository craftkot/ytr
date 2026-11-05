import ru.ehtid.operator.data.PlayerMemory;
import ru.ehtid.operator.analysis.TunnelAnalyzer;

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

        System.out.println("PASS: player learning, movement, mining chain, bounded memory, and tunnel voxel geometry.");
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
