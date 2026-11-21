package ru.ehtid.operator.transaction;

/** Pure chunk-boundary checks used before durable multi-chunk WAL execution. */
public final class ChunkBoundaryPolicy {
    private ChunkBoundaryPolicy() { }

    public static boolean crossesChunkBoundary(TransactionJournal journal) {
        if (!SandboxTransactionEngine.isTunnelFold(journal)) return false;
        if (journal.source.volume() != 2L || journal.target.volume() != 2L) return false;
        int sourceChunkX = Math.floorDiv(journal.source.minX, 16);
        int sourceChunkZ = Math.floorDiv(journal.source.minZ, 16);
        int targetChunkX = Math.floorDiv(journal.target.minX, 16);
        int targetChunkZ = Math.floorDiv(journal.target.minZ, 16);
        return sourceChunkX != targetChunkX || sourceChunkZ != targetChunkZ;
    }

    public static boolean spansExactlyTwoChunks(TransactionJournal journal) {
        if (!crossesChunkBoundary(journal)) return false;
        java.util.Set<String> chunks = new java.util.HashSet<>();
        for (TransactionJournal.Cell cell : journal.source.before)
            chunks.add(Math.floorDiv(cell.x(),16) + ":" + Math.floorDiv(cell.z(),16));
        for (TransactionJournal.Cell cell : journal.target.before)
            chunks.add(Math.floorDiv(cell.x(),16) + ":" + Math.floorDiv(cell.z(),16));
        return chunks.size() == 2;
    }
}
