# Changelog

## 0.2.0 — Individual learning and mining profile

- Added per-player active observation ticks; offline time no longer counts toward personal training.
- Kept the first 24,000 world ticks strictly passive.
- Added a readiness gate requiring both the world observation and the player's personal observation to complete.
- Improved route distance accumulation and increased movement sampling to once per second.
- Made route points more granular to retain turns and short branches.
- Fixed mining-chain reset after a non-rock block is broken.
- Narrowed rock classification to avoid counting redstone lamps and torches as stone.
- Added mining session counts, continuous mining chains, depth bands and dimension-aware chain resets.
- Corrected mining direction labels and fixed the previous mine-dimension update ordering.
- Added bounded counters for block types, dimensions and mining depth bands.
- Increased bounded mining history to 2,048 samples.
- Added richer operator status and profile diagnostics.
- Added schema normalization for old saved profiles.
- Added JUnit test support and a dependency-free model smoke test.
- Added GitHub Actions build workflow (Java 25 / Gradle 9.8.2).
- World mutations remain disabled intentionally.

## 0.1.0 — Observer foundation

- Added Fabric 26.3 project structure.
- Added the first 24,000-tick passive observation phase.
- Added per-player UUID profiles.
- Added movement-route sampling.
- Added block-break and rock-mining history.
- Added basic mining direction statistics.
- Added bounded in-memory histories.
- Added atomic JSON persistence under the world save directory.
- Added operator status and profile diagnostics.
- World mutations are intentionally disabled.
