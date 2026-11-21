package ru.ehtid.operator.data;

import java.util.LinkedHashMap;
import java.util.Map;
import ru.ehtid.operator.transaction.DeferredChunkPlan;

public final class OperatorWorldData {
    public int schemaVersion = 11;
    /** Persisted intent only: no world blocks modified until all checks repeat. */
    public Map<String, DeferredChunkPlan> worldFieldPending = new LinkedHashMap<>();
    public long lastDeferredPlanTick = 0L;
    /** Independently opt-in; never activated just by enabling cave anomalies. */
    public boolean worldFieldEnabled = false;
    /** Sector -> owner UUID / journal UUID, persisted before all field writes. */
    public Map<String, String> worldFieldHistory = new LinkedHashMap<>();
    public long lastWorldFieldTick = 0L;
    /** Off by default even on worlds created in an earlier version. */
    public boolean autonomousAnomaliesEnabled = false;
    public long observationStartedAt = -1L;
    public boolean observationComplete = false;
    public long lastSavedAt = 0L;
    public Map<String, PlayerMemory> players = new LinkedHashMap<>();

    public void normalize() {
        int previousSchemaVersion = schemaVersion;
        if (players == null) players = new LinkedHashMap<>();
        if (worldFieldHistory == null) worldFieldHistory = new LinkedHashMap<>();
        if (worldFieldPending == null) worldFieldPending = new LinkedHashMap<>();
        if (lastDeferredPlanTick < 0L) lastDeferredPlanTick = 0L;
        if (lastWorldFieldTick < 0L) lastWorldFieldTick = 0L;
        // Do not truncate suspicious history: the field scanner fails closed
        // when data exceeds its sector budget.
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
        schemaVersion = 11;
    }

    public long worldObservationElapsed(long currentGameTick) {
        if (observationStartedAt < 0L) return 0L;
        return Math.max(0L, currentGameTick - observationStartedAt);
    }
}
