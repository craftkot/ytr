# Seeded region regeneration

## Verified upstream reference
WorldEdit revision 349ea5155b4968ccf416bd79b55e31c98aa8fbcf: [CoreMcWorld.java](https://github.com/EngineHub/WorldEdit/blob/349ea5155b4968ccf416bd79b55e31c98aa8fbcf/worldedit-core-mc/src/main/java/com/sk89q/worldedit/coremc/internal/CoreMcWorld.java). Its regenerate method rejects a seed override explicitly for 26.1+. doRegen creates a temporary ServerLevel using original level data, generator and seed; submitChunkLoadTasks requests FEATURES; regenForWorld extracts block states, block entity data and optional biomes. It drives executors synchronously, so this is not evidence of frame-safe background generation. LICENSE.txt identifies GPLv3; do not copy implementation into this project without license compatibility review.

## Proposed implementation (not yet implemented)
1. Prove independent seed ownership in isolated generation context on target 26.3 mappings. Compare same coordinates with two seeds and verify repeatability, including biome palettes and features. Do not modify level.dat or shared generator random state.
2. Generate a bounded region plus dependency halo before visibility changes. Extract immutable snapshots; cancel stale epochs. Bound concurrency and memory.
3. Archive player-built components and mined-air masks before replacement; preserve inventories exactly once via durable relocation identities. Do not treat natural cave air as player mining.
4. Commit only when all observers are excluded and source revision matches. Reuse existing WAL; include biome palettes, block entities, heightmaps, lighting and client invalidation in transaction design. Fail closed until recovery is proven.
5. Test real client rapid camera turns, cross-chunk seams, multiple observers, crash recovery and duplicate inventory prevention. Never report preview as physical regeneration.

C2ME scheduling and YUNG tunnel generation still need source review. No runtime implementation or game test is claimed by this document.
