# Changelog

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
