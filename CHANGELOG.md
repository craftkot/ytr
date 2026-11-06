# Changelog

## 0.15.0 — Experimental Manual Sandbox Relocation

- Introduced the first opt-in **live Minecraft block writes**, restricted to a privileged `/operator sandbox apply` command and a small whitelist of default-state vanilla blocks.
- Maximum 128 cells in each source/destination AABB; requires a valid PREPARED journal and source snapshot, fully empty destination, no entities in either region, matching loaded-world BEFORE state and a conservative one-block neighbor risk screen.
- Rejects all block entity NBT/inventories, fluids, falling or attached blocks, redstone, modded blocks, non-default BlockStates and all geometry outside the recognized player-placed construction.
- Introduced a separate `RelocationExecutionLedger`, bound to the exact journal checksum, recording whether this mod actually initiated block writes, with durable per-cell APPLY/RESTORE intents and acknowledgements.
- Added fsynced, atomic, per-journal execution WAL files, refusing damaged files and any implicit fallback to an unrelated historical operation.
- Added `/operator sandbox status`, `/operator sandbox apply`, `/operator sandbox rollback`. The latter requires an execution WAL, checks all current cells against the preimages, refuses foreign edits and uses the CAS-based RecoveryExecutor for idempotent manual restoration.
- After a verified complete move or rollback, translates remembered player construction coordinates without incrementing placement or break counters.
- Added tests for write intent persistence, mid-operation crash state, invalid phase transitions, WAL file corruption, wrong journal binding, block palette rejection and construction memory remapping.

Known limitations:
- The feature is **experimental and destructive**; it must be tested on a disposable world copy, not an important save.
- No multi-chunk filesystem atomicity, block entity inventory relocation, entity relocation, physics/redstone restoration, scheduled ticks, or autonomous AI usage is implemented.
- The source/destination block writes use a small palette with no neighbor-dependent block states; a successful server smoke test does not constitute full integration testing of live apply/rollback in a real game world.
- An unexpected server crash can leave partial world changes. Manual rollback will refuse foreign edits rather than overwriting them.

## 0.14.0 — Durable Recovery Checkpoints and Neighbor Safety

- Added hash-chained, audit-only `RecoveryCheckpointLedger` bound to the exact journal checksum, world, player and transaction UUID.
- Added `RecoveryCheckpointStorage` with atomic JSON writes, corruption rejection, 128-event history and 1 MiB file limits. Existing legacy PREPARED journals obtain a checkpoint on their first explicit audit.
- `/operator transaction prepare` now creates checkpoint #0 only after the PREPARED before-image journal is durable; failure is reported without authorizing world changes.
- `/operator transaction audit` persists its read-only results, and `/operator transaction checkpoint` verifies and displays saved results after server restart.
- Extracted `MinecraftRecoveryWorldView`, a read-only live-world adapter that accesses only already-loaded chunks and captures real BlockState/BlockEntity metadata.
- Added conservative `RelocationHazardAnalyzer` for fluids, redstone, gravity/attached blocks, modded and unknown blocks, plus BlockEntity contents.
- Added a 1-block AABB halo inspection around source and destination: max 8192 sampled neighboring positions; no chunk loading, no claims of safety when skipped or incomplete.
- The abstract, non-Minecraft `RecoveryExecutor` now accepts before/after write callbacks, with injected checkpoint failures and idempotent retry tests.
- Added JUnit tests for recovery history hashing, journal identity binding, history bounds, serialized reload, atomic file replacement, corruption rejection and high-risk block classification.
- **No live block modification, operator-driven relocation, execute/rollback command or write-authorized operation phase is included.**

Known limitations:
- Audit checkpoints are observations, NOT an APPLYING/COMMITTED execution log. They cannot prove that Operator actually moved a structure.
- The only real-world adapter is read-only; the guarded mutation interface remains a test harness.
- Multi-chunk atomicity, entity movement, scheduled ticks, complex block physics, inventory-safe BlockEntity relocation and full rollback are still absent.
- SHA-256 detects corruption but does not authenticate data against an attacker who can rewrite the world's files.

## 0.13.0 — Recovery Audit and Fault-Injection Engine

- Implemented a deterministic recovery planner comparing every journal preimage with the original and predicted translated whole-AABB state.
- Added fail-closed outcomes for corrupted journal, unloaded cells, unknown third-party edits and unsupported BlockEntity NBT translation.
- Added an abstract compare-and-restore engine with mandatory full preflight and post-restore verification; it is deliberately NOT connected to live Minecraft world writes.
- Added unit tests covering fully and partially simulated moves, forced mid-operation interruption, idempotent retry, concurrent modifications, unloaded cells and NBT safety gating.
- Added `/operator transaction audit` to inspect loaded Minecraft world blocks against the latest PREPARED journal, without changing blocks.
- Fixed a journal safety issue: if the newest saved journal is malformed or checksum-invalid, refuse instead of silently falling back to an older journal.
- Hardened bounded AABB validation against malformed huge bounds and inconsistent source/destination offsets.
- World mutations, real Minecraft rollback and autonomous house movement remain disabled.

Known limitations:
- Simulated recovery works only through an abstract compare-and-restore adapter; live in-game writes and durable phase transitions are not implemented.
- The predicted AFTER world assumes a complete AABB translation, not sparse-only block relocation.
- BlockEntity inventory NBT is captured but not automatically replayed or restored: source journals containing it are unsupported by the recovery planner.
- A matching predicted AFTER state does not prove that Operator performed the change; recovery must never be automatically triggered on that evidence alone.

## 0.12.0 — Transaction Preimage Journals (read-only)

- Added bounded complete source/target AABB before-image capture, including air, natural blocks, serialized BlockState and BlockEntity NBT.
- Added deterministic transaction checksum over both regions and all journal metadata, plus duplicate/missing-cell and overlap rejection.
- Added non-overwriting per-player journal storage with 8 MiB per-file and four-file retention limits.
- Added opt-in `/operator transaction prepare <x> <y> <z>`, which requires a valid current source snapshot, a successful preflight, all chunks loaded and a completely empty target AABB.
- Added read-only `/operator transaction status`, which checks journal integrity without exposing NBT/inventory data.
- Added a pure sorted reverse-preimage plan for future recovery and tests for its completeness, tamper detection and limit enforcement.
- Maximum of 4096 cells per region, 8192 total, 256 KiB per block entity and 2 MiB total NBT.
- **No world mutation, automatic rollback, crash recovery or actual house movement is enabled in 0.12.0.**

Known limitations:
- The preimages are a necessary prerequisite for rollback, not an executable transaction coordinator.
- The journal only reflects a coherent moment during an explicit command; it does not lock other mods, players or asynchronous chunk changes.
- Source construction detection remains restricted to observed player placements and bounded geometry.
- Inventory NBT is sensitive; server operators must protect world-folder access and backups.

## 0.11.0 — Snapshot Integrity & Relocation Preflight

- Added a bounded, pure-Java selection of the nearest connected recorded building, never combining unrelated components or dimensions.
- Added an explicit `/operator snapshot` command: server-local read-only preimage capture of up to 4096 tracked placed blocks.
- Captures current BlockState properties and full BlockEntity NBT (including container contents) only when source chunks are loaded and each tracked block matches the world.
- Added per-block 256 KiB and aggregate 2 MiB NBT caps and an overall 4 MiB snapshot-file cap.
- Added deterministic SHA-256 integrity checks over snapshot metadata, source positions, block states and serialized BlockEntity NBT.
- Stores up to eight separately named snapshots per player in atomic, non-overwriting JSON files inside the world folder; no NBT is printed to chat or sent externally.
- Added `/operator preview <x> <y> <z>`, a non-mutating destination inspection using the latest valid snapshot.
- Preflight rejects changed source blocks/NBT, unloaded source or destination chunks, occupied destination cells, source/destination bounding-box overlap, excessive displacement and invalid height/border coordinates.
- Added unit tests for source component selection, size limits, load/occupancy checks, bounds and snapshot checksums.
- **World mutation remains disabled.** This is not a rollback system: natural terrain, gaps/air and adjacent modded dependencies are not yet captured.

Known limitations:
- Structure detection still relies on remembered player placements, not all physical blocks in the world.
- The snapshot includes inventory NBT; secure access to the server world directory is essential.
- Preflight cannot guarantee a later move would be safe or reversible; no atomic chunk transaction, rollback or entity relocation is implemented.
- Existing snapshots are never automatically deleted. After eight snapshots per player, manual archival is required.

## 0.10.0 — Room Intelligence

- Added a bounded offline RoomAnalyzer that separates exterior-connected air from enclosed cavities, then labels separate connected enclosed volumes as room candidates.
- Added door-link edges between two enclosed rooms on opposite sides of the same recorded door. An exterior door does not create an internal link.
- Added per-room functional markers for beds, storage, workstations and smelting blocks, yielding BEDROOM, STORAGE, WORKSHOP, SMELTERY, MIXED or UNCLASSIFIED roles.
- Preserved the previous construction shell analysis and its 32,768-cell per-component and 65,536-cell per-profile work limits.
- Added hard limits of 128 rooms and 128 door links per component. Highly fragmented scans do not claim complete results.
- Captured verified companion block cells for ordinary door/bed placement if the companion position was air before the interaction and matched the placed block afterwards.
- Added read-only `/operator rooms` diagnostics with bounded room/door output and persisted per-player room counts, door links and furnished-room metrics.
- Migrated memory schema 7 -> 8 while preserving BlockState, route, mining and construction history and scheduling room summary recomputation.
- Updated HOME_ECHO shadow-plan reasoning to consider room counts and internal door links; world changes are still disabled.
- Added tests for enclosure, paired rooms, internal vs exterior doors, missing partitions, role markers, scan limits and migration.

Known limitations:
- Room and function labels are heuristic, from recorded player-placed blocks only.
- An untracked natural or command-placed block is treated as absent; open doors are still treated as partitions.
- A doorway without recorded door blocks may not separate two spaces.
- No block-entity NBT, container contents, house relocation, world rewriting or undo mechanism is implemented.

## 0.9.0 — Structural Semantics

- Persist serialized Minecraft BlockState values for each confirmed player-placed block, including available orientation, half, shape, open and waterlogged properties.
- Refresh stored block states during the loaded-chunk audit without incrementing lifetime placement counts.
- Added bounded shell analysis for floor, roof and four wall faces.
- Added estimates for internal horizontal floor planes, door blocks (counting the lower half once), glass blocks/panes, stairs and slabs.
- Added a flood-fill estimate of enclosed void cells inside each connected component's bounding box, capped at 32,768 cells per component and 65,536 cells total per player analysis.
- Added geometric classifications: ENCLOSED_BUILDING, OPEN_BUILDING, BUILDING_CANDIDATE and STRUCTURE.
- Persisted best-building shell metrics and enclosed-volume evidence; migrated world memory schema 6 -> 7 and scheduled legacy construction maps for re-analysis.
- Builder behaviour scoring and HOME_ECHO shadow planning now consider enclosed-volume and shell evidence.
- Expanded `/operator structures` output with shell coverage, internal void estimates, doors/windows/stairs/slabs, floor layers and unique block-state count.
- Added tests for closed and open shells, capped interior analysis, floor-plane detection, block role counts, block-state refresh and schema migration.
- **World mutation remains disabled.** This release still does not relocate, reconstruct or roll back player builds.

Known limitations:
- Placement tracking still depends on ordinary BlockItem interaction and confirmation on the following tick; commands, WorldEdit, world generation and some modded placement paths are not captured.
- Enclosed voids are inferred from the recorded placed-block set, not from a full world scan. Natural terrain, untracked blocks and open doorways can make the estimate incomplete or inaccurate.
- The per-component 32,768-cell cap and per-analysis 65,536-cell cap skip interior flood-fill for larger or numerous bounding boxes rather than scanning them without a budget.
- BlockState properties are stored, but block-entity NBT and container contents are not captured.
- Geometry labels do not establish a room's purpose, safety or usability.



## 0.8.0 — Construction Intelligence

- Added a bounded, per-player history of confirmed placed blocks, captured by checking BlockItem interactions against the next server tick's actual world state.
- Added dimension-aware six-neighbour connected-component analysis for player-placed blocks.
- Added structure recognition (8+ connected blocks) and geometric building candidates (24+ blocks with at least 3×3×3 bounds).
- Added block removal invalidation when a tracked construction block is broken by any player, including creative players.
- Added a loaded-chunk round-robin audit of up to 64 tracked construction blocks per tick to remove stale entries after explosions, pistons and external replacement.
- Added a maximum of 8,192 retained construction blocks per player and one dirty-profile analysis per server tick, throttled to 20 ticks per profile.
- Added persisted construction summaries and schema 5 -> 6 migration without resetting existing player, mining, route or Operator planner data.
- Added `/operator structures`, construction counters to `/operator profile` and `/operator status`, and builder-aware `HOME_ECHO` shadow planning.
- Added unit tests for connected buildings, narrow pillars, dimension separation, placement memory bounds, invalidation, schema migration and brain strategy selection.
- **No world mutation is executed.** `HOME_ECHO` is a proposal only; actual house relocation, interior reconstruction and rollback are not part of 0.8.0.

Known limitations:
- Only ordinary BlockItem placement confirmed through the block-interaction callback is tracked; commands, WorldEdit, world generation and some modded placement paths are not.
- Geometric building candidates are not semantic house recognition. Interiors, rooms, doors, windows and functional roles are not analysed.
- Block states, block-entity NBT and container contents are not captured; exact reconstruction is not yet possible.
- Construction history is capped at 8,192 blocks per player and older entries are evicted first.

# Changelog

## 0.7.0 — Operator decision core (shadow planning)

- Added a deterministic, server-local decision engine that converts accumulated player observations into a proposed intervention.
- Added behaviour scoring for miners, explorers and cave-focused players, with mixed/unknown fallbacks.
- Added four evidence-gated shadow plans: TUNNEL_FOLD, PATH_ECHO, DEPTH_LURE and DIMENSION_DRIFT.
- Kept the two independent 24,000-tick gates: world observation and each player's active observation.
- Evaluates the decision core every 200 game ticks and applies a 6,000-tick cooldown before switching a player's plan.
- Persists planner phase, archetype, current plan, confidence, reason and decision counters per player.
- Migrated world-memory schema 4 to schema 5 without discarding routes, mining history or tunnel cells.
- Added `/operator brain` diagnostics and aggregate planner counts to `/operator status`.
- Added unit tests for observation gates, evidence thresholds, plan selection, cooldown and schema migration.
- **No world mutations are executed in 0.7.0.** This release creates the decision/planning layer only; execution, validation and rollback remain separate future work.

Known limitations:
- The planner is deterministic heuristic logic, not a remote or generative LLM.
- It can only reason about movement, mining, dimensions and locally mapped air currently recorded by the observer.
- It does not yet detect built structures, player homes, biome history or general entity lifecycles.
- Planned intervention names are strategy proposals, not implemented world-changing effects.

## 0.6.0 — Nearby underground-air discovery

- Added a bounded, per-player flood-fill scanner for pre-existing connected underground air spaces near the player.
- Scanner starts when the player's feet or head cell has two full-collision neighbours, or one such neighbour while at least six blocks below the local no-leaves heightmap surface.
- Scan bounds: 12 blocks horizontally, 6 blocks below and 3 blocks above the player's starting position.
- Maximum 4,096 visited positions per scan, 16 concurrent scan sessions and 128 block checks per server tick globally.
- Scanner reads only already loaded chunks and never force-loads terrain.
- Added explicit tunnel-cell provenance: mined vs locally discovered air.
- Discovered air never overwrites mined evidence or increments mined-terrain totals.
- Bounded storage evicts old scanned-air cells before mined cells; scanned air cannot displace a full set of mined evidence.
- Updated profile/status output to distinguish mined and discovered-air cells.
- Migrated world-memory schema 3 to schema 4 without discarding existing player data; legacy tunnel cells default to mined.
- Removed duplicate /operator network2d command registration.
- Added tests for discovered-air deduplication, provenance, bounded eviction priority and schema migration.
- Kept automatic world mutation disabled.

Known limitations:
- This is a local scan, not a full-world scan. Remote caves and tunnels remain undiscovered until a player approaches.
- Large connected caves may be truncated by the per-scan visit cap.
- The scanner records geometry of accessible air; it does not infer whether a cave was natural or player-built.
- Explosions and other non-player destruction are detected only when the resulting air space is later reached by a nearby scan.
- 3D topology remains a layer-wise approximation rather than a full 3D medial-axis transform.


## 0.5.0 — Height-aware tunnel networks and voxel audit

- Added per-Y horizontal centerline reconstruction and 3D graph linking.
- Kept stacked passages at different heights as separate networks.
- Collapsed contiguous vertical bands up to three blocks high into normal tunnel clearance.
- Preserved taller continuous vertical runs as vertical shaft segments.
- Added 3D dead-end, junction, corridor, isolated-area and loop metrics.
- Added 3D junction coordinates, segment endpoints and 3D path-length estimates.
- Changed /operator network to the height-aware graph; retained the 0.4 projection as /operator network2d.
- Added a round-robin audit of up to 128 mapped cells per server tick.
- Audit checks only already loaded chunks and never force-loads terrain.
- Any recorded cell that is no longer air is invalidated for all player profiles, including changes made by pistons, dispensers, fluids or other mods.
- New air cells caused by explosions or other non-player destruction are not automatically discovered.
- Preserved world memory schema 3 and kept all world mutation disabled.
- Added tests for two-block-high passages, stacked tunnels, vertical shafts, ascending routes, 3D junctions, dimension separation and diagonal-only contact.


## 0.4.0 — Tunnel network topology

- Added a horizontal X/Z footprint projection for recorded mined terrain voxels.
- Added bounded Zhang-Suen centerline thinning for tunnel footprints.
- Added graph compression into corridor segments and topology nodes.
- Added dead-end and junction detection, including branch exit counts.
- Added loop detection using graph cycle rank.
- Added junction coordinate reporting, corridor segment endpoints and centerline length estimates.
- Added /operator network for the current dimension.
- Added topology tests for straight and thick corridors, T-junctions, four-way crossings, loops, disconnected networks, dimension separation, vertical projection and diagonal-only contacts.
- Kept world mutations disabled; the graph is an observation and analysis feature only.
- Known limitation: vertical layers are projected onto X/Z and can merge stacked passages.


## 0.3.0 — Tunnel Intelligence foundation

- Added a bounded per-player voxel map of mined terrain cells (up to 4,096 most recent unique positions).
- Added terrain-material recognition for stone, ores, dirt, sand, gravel, clay, ice and related natural blocks.
- Added six-face connected-component reconstruction.
- Added dimension-aware analysis; coordinates in the Nether and Overworld are never joined.
- Added preliminary geometry classes: linear tunnel, chamber, irregular excavation and small area, including bounds for the largest component.
- Added /operator tunnels to inspect the current dimension.
- Added mapped-cell counts to /operator status and /operator profile.
- Added delayed block-use verification: when a player fills a previously mined cell, that voxel is removed from every affected player profile.
- Bounded pending placement checks to 256 entries and kept the callback non-cancelling for mod compatibility.
- Migrated world memory to schema 3 while retaining old player profiles.
- Added geometry tests for straight tunnels, two-block-high corridors, chambers, cross-shaped excavations, disconnected areas, dimension isolation, duplicate cells and empty maps.
- Extended model smoke tests to cover tunnel voxel reconstruction.
- Kept world mutations disabled; 0.3 records and analyses mined terrain only.

## 0.2.0 — Individual learning and mining profile

- Added per-player online observation ticks; offline time no longer counts toward personal training.
- Kept the first 24,000 world ticks strictly passive.
- Added a readiness gate requiring both world observation and the player's personal observation to complete.
- Improved route distance accumulation and increased movement sampling to once per second.
- Made route points more granular to retain turns and short branches.
- Fixed mining-chain reset after a non-rock block is broken.
- Narrowed rock classification to avoid counting redstone lamps and torches as stone.
- Added mining session counts, continuous mining chains, depth bands and dimension-aware chain resets.
- Corrected mining direction labels and fixed mine-dimension update ordering.
- Added bounded counters and schema normalization.
- Added JUnit tests and a dependency-free model smoke test.
- Added GitHub Actions build and dedicated-server smoke test.

## 0.1.0 — Observer foundation

- Added Fabric 26.3 project structure.
- Added the first 24,000-tick passive observation phase.
- Added per-player UUID profiles.
- Added movement-route sampling.
- Added block-break and rock-mining history.
- Added basic mining direction statistics.
- Added bounded in-memory histories.
- Added atomic JSON persistence under the world save directory.
- Added /operator status and /operator profile.
