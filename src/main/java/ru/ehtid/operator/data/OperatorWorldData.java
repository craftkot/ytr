package ru.ehtid.operator.data;

import java.util.LinkedHashMap;
import java.util.Map;

public final class OperatorWorldData {
    public int schemaVersion = 6;
    public long observationStartedAt = -1L;
    public boolean observationComplete = false;
    public long lastSavedAt = 0L;
    public Map<String, PlayerMemory> players = new LinkedHashMap<>();

    public void normalize() {
        int previousSchemaVersion = schemaVersion;
        if (players == null) players = new LinkedHashMap<>();
        if (schemaVersion < 1) schemaVersion = 1;
        // Schema 5 adds planner state; schema 6 adds bounded player-placed construction memory.
        players.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        for (PlayerMemory memory : players.values()) {
            if (previousSchemaVersion < 5) {
                // Missing primitive fields in legacy JSON deserialize as zero, not their Java initializers.
                memory.lastBrainEvaluationAt = -1L;
                memory.lastOperatorDecisionAt = -1L;
            }
            memory.normalize();
        }
        schemaVersion = 6;
    }

    public long worldObservationElapsed(long currentGameTick) {
        if (observationStartedAt < 0L) return 0L;
        return Math.max(0L, currentGameTick - observationStartedAt);
    }
}
