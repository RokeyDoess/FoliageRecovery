# FoliageRecovery
FoliageRecovery is a Paper plugin that restores leaf blocks changed by Aeternum Seasons. It scans existing chunks without generating new terrain, uses saved originals when available, and repairs stray leaves when nearby trunks and canopies support a match. It includes progress checkpoints and rollback journals.

Install the JAR on Paper 26.3 and run `/foliagerecovery recover all` from the server console. That command disables Aeternum Seasons to prevent repainting.

<img width="2560" height="1369" alt="2026-11-23_10 48 06" src="https://github.com/user-attachments/assets/fc5007ae-89f6-4cd1-96ba-d6655f9eb89f" />
Before/After

[Download the latest JAR](https://github.com/RokeyDoess/FoliageRecovery/releases/latest).

`all` selects loaded Overworld-type worlds. Use `/fr recover world_name` for one loaded world. No per-tree approval is required. Recovery and rollback are the only commands that edit blocks.

## Installation

1. Keep a complete world backup.
2. Put `FoliageRecovery-0.2.1.jar` in the server's `plugins` directory and start Paper. When upgrading, remove the old JAR and keep `plugins/FoliageRecovery/` intact.
3. Leave Aeternum installed for the initial run if its live leaf records still need saving. The command disables Aeternum through the server plugin manager, saving its current leaf records, and snapshots that file before importing it.
4. Run `/fr recover all`. Console commands omit the initial slash.

If Aeternum is already removed, place its `autumn-leaves.dat` in `plugins/FoliageRecovery/` before recovery. If no original-leaf file is available, the command clearly reports that it will use inference alone. A malformed available file stops preparation before recovery block edits begin.

Saving Aeternum's large live leaf map can pause the server. Start during quiet hours. Aeternum remains disabled to prevent repainting. A marker in FoliageRecovery's own folder keeps Aeternum disabled on subsequent starts while this recovery plugin remains installed. After verification, remove Aeternum's JAR before removing FoliageRecovery. Original Aeternum files are preserved.

## Automatic recovery

- Copies original-leaf data into a run directory and creates a disk index, without retaining the full leaf map in memory.
- Reads existing Anvil region-file location tables. Every Paper asynchronous chunk request and retry uses `generate=false`. Chunks are held as soon as Paper returns them. A failed center load gets one priority retry.
- Prepares up to 12 center chunks concurrently by default. Neighbor chunks are requested only when an unrecorded leaf's search can reach another chunk. Shared tickets are released after the last scan using them.
- Restores recorded birch/spruce originals at locations that still contain managed autumn foliage. Persistent, possibly player-placed leaves are skipped.
- Scans immutable chunk snapshots without lighting data on worker threads and bypasses air-only sections. Live-world access and edits take place on the server thread.
- Compares trunks reachable within six steps through non-persistent foliage, including already-restored leaves across chunk borders. For unrecorded poplar stragglers, oak is also an eligible replacement. Birch and spruce win a tie with oak. Two or more adjacent normal leaves of the same species can break some ties when a matching trunk also supports that choice. A reachable matching poplar or acacia trunk always protects the leaf.
- Skips close trunk ties, unavailable context, persistent foliage, and leaves without a reachable birch or spruce trunk.
- Preserves leaf distance, persistence, and waterlogging when changing the material.
- Saves durable before/after edit intents and committed outcomes. Full current block states are checked again immediately before writing.

Chunk generation is never requested. Missing neighbor chunks prevent inference only where their context is needed; recorded originals in an existing central chunk can still be restored. Region files and original backups are read only.

The region list covers chunks present when the command prepares its scan. New exploration after that list is prepared is outside that run. Unloaded worlds are not created or loaded by this plugin.

## Progress and control

```text
/fr status
/fr where
/fr pause
/fr resume
/fr cancel
/fr resume RUN_ID
```

Progress is logged every 15 seconds by default. `/fr where` shows the active world, chunk, and block X/Z near its center. Reports distinguish ambiguous ownership, protected native leaves, persistent leaves, unavailable center chunks, and missing context for leaf searches. Proposals identify edits made from recorded originals and inferred edits.

Pause holds the current tickets. Cancel releases them and saves the completed scan position. Scans flush edits to disk before saving a checkpoint, by default every five minutes and on a normal stop or cancel. A crash may require repeating work after the last saved checkpoint. Use `/fr resume RUN_ID` with the most recent recovery run ID to continue. The continuation has its own run ID and rollback journal, with earlier IDs listed in `previous-runs.txt`. To undo an entire resumed recovery, roll back each segment in reverse order.

Version 0.1.0 runs did not save their scan position. After upgrading from 0.1.0, launch one fresh recovery from the beginning. Already-restored leaves are skipped, and the old run's journal remains available for rollback.

Version 0.2.0 checkpoints can be continued on 0.2.1. To apply the oak rule to areas already scanned by 0.2.0, start a fresh recovery with `/fr recover all`.

The time budget limits normal live-block work. Chunk snapshots, Aeternum shutdown, checkpoints, and durable journal writes can exceed that budget. The prefetch window is configurable with `prefetch-chunks`. Completion time and TPS impact depend on the world and storage.

## Optional preview and rollback

```text
/fr preview all
/fr rollback RUN_ID
```

Preview scans and writes proposals without changing blocks or disabling Aeternum. It uses the latest saved leaf file, which can be older than Aeternum's live records. A later recovery automatically rescans and rechecks actual states; it requires no approval for individual trees.

Rollback reverses committed edits only when each current full block state still matches its replacement. Conflicting later edits are skipped. Each rollback has its own journal and run ID, so its committed changes can also be reversed.

After an abrupt process interruption, intents without completion records are written to `unresolved-intents.tsv` during rollback and are not automatically reversed. Their outcome needs inspection because a crash can occur between a block write and its commit record.

## Reports

Each run is stored under `plugins/FoliageRecovery/runs/RUN_ID/` with applicable files:

- `originals-snapshot.dat`, `originals-info.txt`, and `index/`: preserved input, SHA-256, and chunk index.
- `applied-proposals.tsv` or `changes.tsv`: world UUID, X/Y/Z, full before/after states, and recorded/inferred reason.
- `journal.tsv`: durable intents and outcomes.
- `ambiguities.txt`: skipped inference reasons.
- `skipped-samples.tsv`: up to 25 example leaf coordinates per inference outcome.
- `unavailable-chunks.tsv`: center chunks skipped after a priority retry, with coordinates and the last load outcome. `NO_FULL_CHUNK` means Paper returned no full chunk. `TICKET_EXPIRED` means it returned a chunk that could no longer be held safely.
- `targets.bin`, `checkpoint.txt`, and `previous-runs.txt`: saved scan targets, completed position, and earlier journal IDs as applicable.
- `COMPLETE.txt` or `STOPPED.txt`: final counters and outcome.
- `unresolved-intents.tsv`: uncertain crash entries found during rollback.

A completed scan does not mean every orange leaf changed. Unrecorded leaves have no authoritative original species. Inference deliberately leaves ambiguous cases unchanged, including overlapping real poplar and birch/spruce canopies. Exact restoration of those cases requires an older world backup or another authoritative source. There is no blanket replacement of poplar leaves.

## Compatibility and build

Target: Paper 26.3 with standard server scheduling. Folia is unsupported. The plugin requires Java 21+; use the Java version required by the server.

The standard Maven build uses the shared Spigot 1.21.11 API. The source was also compiled against the embedded API from Paper 26.3 build 241. Paper's public `getChunkAtAsync(int,int,boolean)` API is used at runtime. Poplar materials are recognized by name, allowing the source to compile without newer Material enum constants.

Build with Maven 3.9+ and a JDK:

```text
mvn verify
```

The JAR is produced under `target/`. Tests cover indexing, malformed data, negative coordinates, real poplar/acacia protection, ambiguous ownership, bounded inference, no-generation requests, ticket release, immutable snapshot scanning, full block-state serialization, and committed/interrupted rollback records.

[Paper World API](https://jd.papermc.io/paper/26.3/org/bukkit/World.html) · [ChunkSnapshot API](https://jd.papermc.io/paper/26.3/org/bukkit/ChunkSnapshot.html)
