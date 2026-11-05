package ru.ehtid.operator.data;

import java.util.LinkedHashMap;
import java.util.Map;

public final class OperatorWorldData {
    public int schemaVersion = 3;
    public long observationStartedAt = -1L;
    public boolean observationComplete = false;
    public long lastSavedAt = 0L;
    public Map<String, PlayerMemory> players = new LinkedHashMap<>();

    public void normalize() {
        if (players == null) players = new LinkedHashMap<>();
        if (schemaVersion < 1) schemaVersion = 1;
        // Schema 3 adds a bounded per-player map of mined terrain cells.
        schemaVersion = 3;
        players.entrySet().removeIf(entry -> entry.getKey() == null || entry.getValue() == null);
        for (PlayerMemory memory : players.values()) memory.normalize();
    }

    public long worldObservationElapsed(long currentGameTick) {
        if (observationStartedAt < 0L) return 0L;
        return Math.max(0L, currentGameTick - observationStartedAt);
    }
}
