package ru.ehtid.operator.worldgen;

/** Pure seed derivation; does not mutate Minecraft's shared generator. */
public final class RegionSeed {
    private RegionSeed() {}
    public static long derive(long worldSeed, String dimension, int regionX, int regionZ, long epoch) {
        if (dimension == null || dimension.isBlank() || epoch < 0)
            throw new IllegalArgumentException("Invalid dimension or epoch");
        long h = mix(worldSeed ^ 0xD1B54A32D192ED03L);
        for (int i = 0; i < dimension.length(); i++) h = mix(h ^ dimension.charAt(i));
        h = mix(h ^ dimension.length());
        h = mix(h ^ Integer.toUnsignedLong(regionX));
        h = mix(h ^ Integer.toUnsignedLong(regionZ));
        return mix(h ^ epoch);
    }
    public static int regionOfChunk(int chunk, int width) {
        if (width <= 0) throw new IllegalArgumentException("Invalid region width");
        return Math.floorDiv(chunk, width);
    }
    private static long mix(long x) {
        x = (x ^ (x >>> 30)) * 0xBF58476D1CE4E5B9L;
        x = (x ^ (x >>> 27)) * 0x94D049BB133111EBL;
        return x ^ (x >>> 31);
    }
}
