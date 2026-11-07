package ru.ehtid.operator.data;

import java.util.LinkedHashMap;
import java.util.Map;

public final class OperatorWorldData {
    public int schemaVersion = 9;
    /** Off by default even on worlds created in an earlier version. */
    public boolean autonomousAnomaliesEnabled = false;
    public long observationStartedAt = -1L;
    public boolean observationComplete = false;
    public long lastSavedAt = 0L;
    public Map<String, PlayerMemory> players = new LinkedHashMap<>();

    public void normalize() {
        int previousSchemaVersion = schemaVersion;
        if (players == null) players = new LinkedHashMap<>();
        if (schemaVersion < 1) schemaVersion = 1;
        // Schema 5 planner state, 6 construction memory, 7 block states/shell, 8 room graph summaries.
        players.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        for (PlayerMemory memory : players.values()) {
            if (previousSchemaVersion < 5) {
                // Missing primitive fields in legacy JSON deserialize as zero, not their Java initializers.
                memory.lastBrainEvaluationAt = -1L;
                memory.lastOperatorDecisionAt = -1L;
            }
            if (previousSchemaVersion < 7) {
                // Old construction records contain block IDs but no serialized BlockState properties.
                if (memory.constructionBlocks != null) {
                    for (PlayerMemory.ConstructionBlock block : memory.constructionBlocks.values()) {
                        if (block != null && block.blockState == null) block.blockState = "";
                    }
                    memory.constructionAnalysisDirty = !memory.constructionBlocks.isEmpty();
                }
                memory.lastConstructionAnalysisAt = -1L;
                memory.recognizedEnclosedBuildingCount = 0;
                memory.largestEnclosedVoidCells = 0;
                memory.largestDoorCount = 0;
                memory.largestWindowBlockCount = 0;
                memory.largestStairBlockCount = 0;
                memory.largestSlabBlockCount = 0;
                memory.largestFloorLayerCount = 0;
                memory.largestFloorCoverage = 0.0D;
                memory.largestRoofCoverage = 0.0D;
                memory.largestWallCoverage = 0.0D;
            }
            if (previousSchemaVersion < 8) {
                // Room statistics did not exist in previous saves; recompute from existing placements.
                memory.recognizedRoomCount = 0;
                memory.recognizedRoomConnections = 0;
                memory.recognizedFurnishedRoomCount = 0;
                memory.largestRoomCellCount = 0;
                if (memory.constructionBlocks != null && !memory.constructionBlocks.isEmpty()) {
                    memory.constructionAnalysisDirty = true;
                }
                memory.lastConstructionAnalysisAt = -1L;
            }
            memory.normalize();
        }
        schemaVersion = 9;
    }

    public long worldObservationElapsed(long currentGameTick) {
        if (observationStartedAt < 0L) return 0L;
        return Math.max(0L, currentGameTick - observationStartedAt);
    }
}
