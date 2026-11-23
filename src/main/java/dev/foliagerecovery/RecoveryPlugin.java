package dev.foliagerecovery;

import org.bukkit.*;
import org.bukkit.block.Block;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.type.Leaves;
import org.bukkit.command.*;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import java.io.*;
import java.lang.reflect.Method;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.logging.Level;
import dev.foliagerecovery.ScanProgress.Target;

public final class RecoveryPlugin extends JavaPlugin implements CommandExecutor {
    private record WorldFiles(UUID world, Path region) {}
    private record Prepared(Path directory, Path index, List<Target> targets, String information,
                            ScanProgress.Checkpoint checkpoint) {}
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FoliageRecovery-files"); t.setDaemon(true); return t;
    });
    private final ExecutorService snapshots = Executors.newFixedThreadPool(2, r -> {
        Thread thread = new Thread(r, "FoliageRecovery-snapshots");
        thread.setDaemon(true);
        return thread;
    });
    private Path root;
    private Job job;
    private CompletableFuture<?> preparation;
    private String preparing;
    private String lastReport = "Idle. No completed run in this session.";
    private int operations;
    private int prefetch;
    private long nanos;
    private ChunkLease.Tickets tickets;
    private Method snapshotWithoutLighting;
    private Method flushWorld;

    @Override public void onEnable() {
        saveDefaultConfig(); root = getDataFolder().toPath();
        operations = Math.max(64, Math.min(16384, getConfig().getInt("operations-per-tick", 2048)));
        prefetch = Math.max(2, Math.min(64, getConfig().getInt("prefetch-chunks", 12)));
        tickets = new ChunkLease.Tickets(this);
        try {
            snapshotWithoutLighting = Chunk.class.getMethod("getChunkSnapshot",
                    boolean.class, boolean.class, boolean.class, boolean.class);
            flushWorld = World.class.getMethod("save", boolean.class);
        } catch (NoSuchMethodException unavailable) {
            getLogger().severe("FoliageRecovery requires Paper's snapshot and flushed world-save APIs.");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        nanos = Math.max(1, Math.min(20, getConfig().getInt("milliseconds-per-tick", 2))) * 1_000_000L;
        Objects.requireNonNull(getCommand("foliagerecovery")).setExecutor(this);
        if (Files.exists(root.resolve("aeternum-disabled.flag"))) disableAeternum();
        getServer().getScheduler().runTaskTimer(this, this::tick, 1L, 1L);
        getLogger().info("Ready. No recovery runs automatically. /fr recover <world|all> starts cleanup.");
    }
    @Override public void onDisable() {
        if (job != null) stopJob("Server/plugin stopped");
        io.shutdown();
        snapshots.shutdownNow();
    }
    private void tick() {
        if (job == null || job.paused) return;
        try {
            Plugin aeternum = getServer().getPluginManager().getPlugin("AeternumSeasons");
            if (!job.preview && aeternum != null && aeternum.isEnabled()) {
                job.paused = true; announce("Paused: Aeternum was re-enabled. Disable it before /fr resume."); return;
            }
            job.tick();
        } catch (Throwable error) {
            getLogger().log(Level.SEVERE, "Recovery stopped; journal and partial report were retained", error);
            if (job != null) job.finish(false, "ERROR: " + error.getMessage());
        }
    }
    private void announce(String text) {
        getLogger().info(text);
        if (job != null && job.requester instanceof Player p && p.isOnline()) p.sendMessage("[FoliageRecovery] " + text);
    }
    private void disableAeternum() {
        Plugin plugin = getServer().getPluginManager().getPlugin("AeternumSeasons");
        if (plugin != null && plugin.isEnabled()) {
            getLogger().info("Disabling Aeternum to save its leaf records and prevent repainting.");
            getServer().getPluginManager().disablePlugin(plugin);
        }
    }
    private void editingMode() throws IOException {
        Files.createDirectories(root);
        Files.writeString(root.resolve("aeternum-disabled.flag"), "Keep Aeternum disabled during and after foliage recovery.\n");
        disableAeternum();
    }
    private boolean busy(CommandSender sender) {
        if (job != null || preparing != null) {
            sender.sendMessage("A job is active. /fr status, /fr pause, /fr resume, /fr cancel"); return true;
        }
        return false;
    }
    @Override public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!sender.hasPermission("foliagerecovery.admin")) { sender.sendMessage("Permission denied."); return true; }
        try {
            String action = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
            switch (action) {
                case "recover", "preview" -> {
                    if (busy(sender)) return true;
                    boolean preview = action.equals("preview");
                    String selected = args.length > 1 ? args[1] : sender instanceof Player p ? p.getWorld().getName() : "all";
                    List<World> worlds = selected.equalsIgnoreCase("all") ? getServer().getWorlds().stream()
                            .filter(w -> w.getEnvironment() == World.Environment.NORMAL).toList() : List.of(findWorld(selected));
                    if (worlds.isEmpty()) throw new IOException("No matching loaded worlds");
                    if (!preview) editingMode();
                    startRecovery(sender, worlds, preview);
                }
                case "status" -> sender.sendMessage(job == null ? preparing == null ? lastReport : preparing : job.status());
                case "where" -> sender.sendMessage(job instanceof ScanJob scan ? scan.location() : "No active scan.");
                case "pause" -> { if (job != null) { job.paused = true; sender.sendMessage("Paused. Current chunk tickets are retained."); } }
                case "resume" -> {
                    if (args.length == 2) {
                        if (!busy(sender)) startContinuation(sender, safeRun(args[1]));
                    } else if (args.length == 1 && job != null) {
                        job.paused = false;
                        sender.sendMessage("Resuming.");
                    } else sender.sendMessage("Usage: /fr resume <run-id> to resume a saved scan.");
                }
                case "cancel" -> {
                    if (job != null) stopJob("Cancelled");
                    else if (preparing != null) sender.sendMessage("Preparing the disk index; cancellation is available once scanning starts.");
                }
                case "rollback" -> {
                    if (args.length != 2) throw new IOException("Usage: /fr rollback <run-id>");
                    if (busy(sender)) return true;
                    editingMode(); startRollback(sender, safeRun(args[1]));
                }
                default -> sender.sendMessage(new String[]{
                        "/fr recover <world|all> — restore originals, then infer remaining leaves; no chunk generation",
                        "/fr preview <world|all> — same scan, write proposals without changing blocks",
                        "/fr status | where | pause | resume [run-id] | cancel",
                        "/fr rollback <run-id> — undo committed edits whose blocks still match",
                        "recover disables Aeternum and leaves it disabled. Keep a full world backup."});
            }
        } catch (Exception error) { sender.sendMessage("[FoliageRecovery] " + error.getMessage()); }
        return true;
    }
    private World findWorld(String name) throws IOException {
        World world = getServer().getWorld(name);
        if (world == null) throw new IOException("World is not loaded: " + name);
        return world;
    }
    private Path safeRun(String id) throws IOException {
        if (!id.matches("[a-f0-9]{12}")) throw new IOException("Invalid run ID");
        Path directory = root.resolve("runs").resolve(id);
        if (!Files.isDirectory(directory)) throw new IOException("Run not found");
        return directory;
    }
    private Path newRun() throws IOException {
        Files.createDirectories(root.resolve("runs"));
        return Files.createDirectory(root.resolve("runs").resolve(UUID.randomUUID().toString().replace("-", "").substring(0, 12)));
    }
    private void startRecovery(CommandSender sender, List<World> worlds, boolean preview) throws IOException {
        Path directory = newRun();
        Plugin aeternum = getServer().getPluginManager().getPlugin("AeternumSeasons");
        Path nativeFile = aeternum == null ? null : aeternum.getDataFolder().toPath().resolve("autumn-leaves.dat");
        Path source = nativeFile != null && Files.isRegularFile(nativeFile) ? nativeFile : root.resolve("autumn-leaves.dat");
        List<WorldFiles> files = worlds.stream().map(w -> new WorldFiles(w.getUID(), w.getWorldFolder().toPath().resolve("region"))).toList();
        preparing = "Preparing " + directory.getFileName() + ": snapshotting originals and indexing existing region files";
        sender.sendMessage(preparing);
        preparation = CompletableFuture.supplyAsync(() -> {
            try {
                Path index = null;
                String info = "No original-leaf file found; conservative inference only.";
                if (Files.isRegularFile(source)) {
                    Path snapshot = directory.resolve("originals-snapshot.dat"); Files.copy(source, snapshot);
                    index = directory.resolve("index");
                    LeafIndex.Result result = LeafIndex.build(snapshot, index);
                    info = "Originals: " + result.records() + " leaves in " + result.chunks() + " chunks. SHA256=" + result.sha256();
                }
                List<Target> targets = new ArrayList<>();
                for (WorldFiles world : files) {
                    Set<Long> keys = new LinkedHashSet<>(RegionChunks.existing(world.region()));
                    if (index != null) keys.addAll(LeafIndex.chunks(index, world.world()));
                    for (long key : keys) targets.add(new Target(world.world(), key));
                }
                ScanProgress.Checkpoint checkpoint = new ScanProgress.Checkpoint(directory.getFileName().toString(), 0, List.of());
                if (!preview) ScanProgress.writeTargets(directory.resolve("targets.bin"), targets);
                return new Prepared(directory, index, targets, info, checkpoint);
            } catch (IOException e) { throw new CompletionException(e); }
        }, io).whenComplete((prepared, error) -> completeOnMain(() -> {
            preparing = null;
            if (error != null) { preparationFailed(sender, error); return; }
            try { job = new ScanJob(sender, prepared, preview); announce(prepared.information()); announce(job.status()); }
            catch (IOException e) { preparationFailed(sender, e); }
        }));
    }
    private void stopJob(String reason) {
        if (job instanceof ScanJob scan && !scan.preview) {
            try {
                scan.checkpoint();
                reason += ". Continue with /fr resume " + scan.directory.getFileName();
            } catch (IOException | RuntimeException error) {
                getLogger().log(Level.WARNING, "Final checkpoint failed. The last saved checkpoint is still available.", error);
            }
        }
        job.finish(false, reason + ". Completed edits remain in the rollback journal");
    }
    private void startContinuation(CommandSender sender, Path previous) throws IOException {
        ScanProgress.Checkpoint checkpoint = ScanProgress.read(previous);
        Path original = safeRun(checkpoint.manifestRun());
        if (Files.exists(previous.resolve("COMPLETE.txt"))) throw new IOException("This scan is already complete");
        editingMode();
        Path directory = newRun();
        preparing = "Reading saved scan " + previous.getFileName() + " for continuation " + directory.getFileName();
        sender.sendMessage(preparing);
        preparation = CompletableFuture.supplyAsync(() -> {
            try {
                List<Target> targets = ScanProgress.readTargets(original.resolve("targets.bin"));
                if (checkpoint.cursor() > targets.size()) throw new IOException("Checkpoint exceeds scan target list");
                List<String> previousRuns = new ArrayList<>(checkpoint.previousRuns());
                previousRuns.add(previous.getFileName().toString());
                Path index = original.resolve("index");
                String information = Files.readString(original.resolve("originals-info.txt")).strip();
                if (information.startsWith("Originals:") && !Files.isRegularFile(index.resolve("READY"))) {
                    throw new IOException("The original run's leaf index is missing or incomplete");
                }
                return new Prepared(directory, Files.isDirectory(index) ? index : null, targets, information,
                        new ScanProgress.Checkpoint(checkpoint.manifestRun(), checkpoint.cursor(), previousRuns));
            } catch (IOException error) { throw new CompletionException(error); }
        }, io).whenComplete((prepared, error) -> completeOnMain(() -> {
            preparing = null;
            if (error != null) { preparationFailed(sender, error); return; }
            try { job = new ScanJob(sender, prepared, false); announce(job.status()); }
            catch (IOException failure) { preparationFailed(sender, failure); }
        }));
    }
    private void startRollback(CommandSender sender, Path original) throws IOException {
        Path directory = newRun(); preparing = "Preparing rollback " + directory.getFileName(); sender.sendMessage(preparing);
        preparation = CompletableFuture.supplyAsync(() -> {
            try { return PlanFiles.rollback(original.resolve("journal.tsv"), directory); }
            catch (IOException e) { throw new CompletionException(e); }
        }, io).whenComplete((unresolved, error) -> completeOnMain(() -> {
            preparing = null;
            if (error != null) { preparationFailed(sender, error); return; }
            try {
                job = new RollbackJob(sender, directory);
                announce("Rollback started. " + unresolved + " unresolved crash intents were saved for review and will not be edited automatically.");
            } catch (IOException e) { preparationFailed(sender, e); }
        }));
    }
    private void completeOnMain(Runnable action) {
        if (isEnabled()) getServer().getScheduler().runTask(this, action);
    }
    private void preparationFailed(CommandSender sender, Throwable error) {
        getLogger().log(Level.SEVERE, "Preparation failed; no recovery block edits started", error);
        sender.sendMessage("Preparation failed: " + error.getMessage() + ". Saved files were retained.");
    }
    private abstract class Job {
        final CommandSender requester;
        final Path directory;
        final boolean preview;
        final BufferedWriter changes;
        final RecoveryJournal journal;
        final List<Change> batch = new ArrayList<>();
        final Set<UUID> dirtyWorlds = new HashSet<>();
        ChunkLease lease;
        boolean paused, ended;
        long examined, proposed, changed, skipped, unavailable, rejected, completedChunks;
        long lastProgress = System.nanoTime();
        Job(CommandSender requester, Path directory, boolean preview) throws IOException {
            this.requester = requester; this.directory = directory; this.preview = preview;
            // Rollback uses a separate output, preserving its immutable input changes.tsv.
            changes = Files.newBufferedWriter(directory.resolve(preview ? "changes.tsv" : "applied-proposals.tsv"));
            journal = preview ? null : new RecoveryJournal(directory.resolve("journal.tsv"));
            Files.writeString(directory.resolve("server.txt"), getServer().getVersion() + "\n");
        }
        abstract void tick() throws Exception;
        String status() {
            return "Run " + directory.getFileName() + (preview ? " PREVIEW" : " RECOVERY") + (paused ? " PAUSED" : "")
                    + ": chunks=" + completedChunks + ", examined=" + examined + ", proposed=" + proposed
                    + ", changed=" + changed + ", stale/skipped=" + skipped + ", ambiguous=" + rejected
                    + ", unavailable=" + unavailable;
        }
        void progress() {
            if (System.nanoTime() - lastProgress >= Math.max(1, getConfig().getInt("progress-interval-seconds", 15)) * 1_000_000_000L) {
                announce(status()); lastProgress = System.nanoTime();
            }
        }
        void propose(Change change) throws IOException {
            changes.write(change.encode()); changes.newLine(); proposed++;
            if (!preview) batch.add(change);
            if (batch.size() == 64) flush();
        }
        void flush() throws IOException {
            if (batch.isEmpty()) return;
            int applied = journal.apply(batch, change -> {
                World world = Bukkit.getWorld(change.world());
                Position p = change.position();
                if (world == null || !world.isChunkLoaded(p.x() >> 4, p.z() >> 4)) return false;
                Block block = world.getBlockAt(p.x(), p.y(), p.z());
                if (!block.getBlockData().getAsString().equals(change.before())) return false;
                block.setBlockData(Bukkit.createBlockData(change.after()), false);
                dirtyWorlds.add(change.world());
                return true;
            });
            changed += applied; skipped += batch.size() - applied; batch.clear();
        }
        void release() { if (lease != null) { lease.close(); lease = null; } }
        void finish(boolean success, String message) {
            if (ended) return; ended = true;
            try {
                // Cancelled previews never apply pending proposals. Cancelled recovery batches
                // are discarded before a durable intent exists; earlier edits remain journaled.
                batch.clear(); changes.close(); if (journal != null) journal.close();
                Files.writeString(directory.resolve(success ? "COMPLETE.txt" : "STOPPED.txt"), status() + "\n" + message + "\n");
                if (success && preview) PlanFiles.ready(directory);
            } catch (IOException e) { getLogger().log(Level.SEVERE, "Could not finalize recovery report", e); }
            release(); lastReport = message + ". " + status(); announce(lastReport); job = null;
        }
    }

    private record ChunkData(List<LeafIndex.Original> originals, List<Position> candidates) {}

    private final class ScanJob extends Job {
        private final class PendingChunk {
            final Target target;
            final World world;
            final ChunkLease center;
            CompletableFuture<ChunkData> data;

            PendingChunk(Target target) throws IOException, ReflectiveOperationException {
                this.target = target;
                world = Bukkit.getWorld(target.world());
                if (world == null) throw new IOException("World unloaded: " + target.world());
                center = new ChunkLease(tickets, world, target.chunk(), false);
            }

            void capture() throws ReflectiveOperationException {
                if (data != null || !center.ready()) return;
                if (center.centralUnavailable()) {
                    data = CompletableFuture.completedFuture(null);
                    return;
                }
                ChunkSnapshot snapshot = (ChunkSnapshot) snapshotWithoutLighting.invoke(
                        center.central(), true, false, false, false);
                int minY = world.getMinHeight(), maxY = world.getMaxHeight();
                CompletableFuture<List<Position>> candidates = CompletableFuture.supplyAsync(
                        () -> SnapshotCandidates.find(snapshot, minY, maxY, target.chunk()), snapshots);
                CompletableFuture<List<LeafIndex.Original>> originals = CompletableFuture.supplyAsync(() -> {
                    try { return index == null ? List.of() : LeafIndex.read(index, target.world(), target.chunk()); }
                    catch (IOException error) { throw new CompletionException(error); }
                }, io);
                data = originals.thenCombine(candidates, ChunkData::new);
            }
        }

        final List<Target> targets;
        final Path index;
        final String manifestRun;
        final List<String> previousRuns;
        final Deque<PendingChunk> pending = new ArrayDeque<>();
        final Set<Position> recorded = new HashSet<>();
        final Map<String, Long> rejections = new TreeMap<>();
        final Map<String, List<String>> samples = new TreeMap<>();
        final BufferedWriter unavailableChunks;
        int cursor, submitted, originalCursor, candidateCursor, lastCheckpoint;
        long nextCheckpoint;
        long protectedNative, placed, missingContext;
        long noFullChunk, expiredTicket, recoveredLoad;
        long lastRateTime = System.nanoTime();
        int lastRateCursor;
        double scanRate;
        OwnerProbe probe;
        Position seed;
        World world;

        ScanJob(CommandSender requester, Prepared prepared, boolean preview) throws IOException {
            super(requester, prepared.directory(), preview);
            targets = prepared.targets();
            index = prepared.index();
            var checkpoint = prepared.checkpoint();
            manifestRun = checkpoint.manifestRun();
            previousRuns = checkpoint.previousRuns();
            cursor = submitted = lastCheckpoint = checkpoint.cursor();
            lastRateCursor = cursor;
            completedChunks = cursor;
            Files.writeString(directory.resolve("originals-info.txt"), prepared.information() + "\n");
            if (!previousRuns.isEmpty()) Files.write(directory.resolve("previous-runs.txt"), previousRuns);
            nextCheckpoint = System.nanoTime() + checkpointInterval();
            if (!preview) saveCursor();
            unavailableChunks = Files.newBufferedWriter(directory.resolve("unavailable-chunks.tsv"));
            unavailableChunks.write("world_uuid\tchunk_x\tchunk_z\treason");
            unavailableChunks.newLine();
        }

        private long checkpointInterval() {
            return Math.max(60, getConfig().getInt("checkpoint-interval-seconds", 300)) * 1_000_000_000L;
        }

        private void saveCursor() throws IOException {
            ScanProgress.save(directory, new ScanProgress.Checkpoint(manifestRun, cursor, previousRuns));
            lastCheckpoint = cursor;
            nextCheckpoint = System.nanoTime() + checkpointInterval();
        }

        void checkpoint() throws IOException {
            if (preview) return;
            if (cursor == lastCheckpoint && dirtyWorlds.isEmpty()) {
                nextCheckpoint = System.nanoTime() + checkpointInterval();
                return;
            }
            // Flush Paper's world saves before advancing the saved scan prefix.
            for (UUID id : dirtyWorlds) {
                World edited = Bukkit.getWorld(id);
                if (edited == null) throw new IOException("Cannot save edited world " + id);
                try {
                    flushWorld.invoke(edited, true);
                } catch (ReflectiveOperationException error) {
                    throw new IOException("Could not flush edited world " + id, error);
                }
            }
            dirtyWorlds.clear();
            unavailableChunks.flush();
            saveCursor();
        }

        String location() {
            if (cursor >= targets.size()) return "Scan finished.";
            Target target = targets.get(cursor);
            World current = Bukkit.getWorld(target.world());
            int x = Position.chunkX(target.chunk()), z = Position.chunkZ(target.chunk());
            return "world=" + (current == null ? target.world() : current.getName())
                    + ", chunk=" + x + "," + z + ", block-x/z=" + ((x << 4) + 8) + "," + ((z << 4) + 8);
        }

        @Override String status() {
            return super.status() + ", total-chunks=" + targets.size() + ", native-protected=" + protectedNative
                    + ", persistent=" + placed + ", missing-leaf-context=" + missingContext
                    + ", no-full-chunk=" + noFullChunk + ", ticket-expired=" + expiredTicket
                    + ", load-recovered=" + recoveredLoad
                    + String.format(Locale.ROOT, ", chunks/sec=%.1f", scanRate)
                    + (preview ? "" : ", checkpoint=" + lastCheckpoint) + ", " + location();
        }

        @Override void tick() throws Exception {
            long now = System.nanoTime();
            long elapsed = now - lastRateTime;
            if (elapsed >= 5_000_000_000L) {
                scanRate = (cursor - lastRateCursor) * 1_000_000_000.0 / elapsed;
                lastRateCursor = cursor;
                lastRateTime = now;
            }
            progress();
            if (cursor >= targets.size()) {
                checkpoint();
                Files.writeString(directory.resolve("ambiguities.txt"), rejections + "\n");
                try (BufferedWriter report = Files.newBufferedWriter(directory.resolve("skipped-samples.tsv"))) {
                    report.write("reason\tworld_uuid\tx\ty\tz");
                    report.newLine();
                    for (var group : samples.entrySet()) {
                        for (String sample : group.getValue()) {
                            report.write(group.getKey() + "\t" + sample);
                            report.newLine();
                        }
                    }
                }
                finish(true, "Finished scanning existing chunks");
                return;
            }

            if (!preview && System.nanoTime() >= nextCheckpoint) checkpoint();
            long deadline = System.nanoTime() + nanos;
            while (submitted < targets.size() && pending.size() < prefetch) {
                pending.addLast(new PendingChunk(targets.get(submitted++)));
            }
            // Rotate snapshot collection when the front chunk is slow to load.
            for (PendingChunk chunk : pending) {
                chunk.capture();
                if (System.nanoTime() >= deadline) break;
            }

            for (int op = 0; op < operations && System.nanoTime() < deadline; op++) {
                PendingChunk current = pending.peekFirst();
                if (current == null || current.data == null || !current.data.isDone()) break;
                ChunkData data = current.data.join();
                world = current.world;
                if (data == null) {
                    unavailable++;
                    ChunkLease.Failure reason = current.center.centralFailure();
                    if (reason == ChunkLease.Failure.NO_FULL_CHUNK) noFullChunk++;
                    else expiredTicket++;
                    Target target = current.target;
                    unavailableChunks.write(target.world() + "\t" + Position.chunkX(target.chunk()) + "\t"
                            + Position.chunkZ(target.chunk()) + "\t" + reason);
                    unavailableChunks.newLine();
                    nextChunk();
                    continue;
                }

                if (originalCursor < data.originals().size()) {
                    LeafIndex.Original original = data.originals().get(originalCursor++);
                    recorded.add(original.position());
                    examined++;
                    Block block = block(original.position());
                    if (eligible(block)) {
                        propose(change(block, original.material(), "recorded"));
                    } else {
                        skipped++;
                    }
                    continue;
                }

                if (probe != null) {
                    probe.step();
                    if (!probe.finished()) continue;
                    if (probe.accepted()) {
                        Block block = block(seed);
                        if (eligible(block)) propose(change(block, probe.original(), "inferred"));
                        else skipped++;
                    } else {
                        reject(probe.reason(), seed);
                    }
                    probe = null;
                    continue;
                }

                if (candidateCursor >= data.candidates().size()) {
                    flush();
                    if (current.center.centralRecovered()) recoveredLoad++;
                    nextChunk();
                    continue;
                }
                Position position = data.candidates().get(candidateCursor);
                if (recorded.contains(position)) { candidateCursor++; continue; }

                Block block = block(position);
                if (eligible(block) && position.touchesChunkEdge(6)) {
                    if (lease == null) lease = new ChunkLease(tickets, world, current.target.chunk(), true);
                    if (!lease.ready()) break;
                }

                candidateCursor++;
                examined++;
                if (eligible(block)) {
                    seed = position;
                    probe = new OwnerProbe(this::cell, seed, block.getType().name());
                } else if (block != null && OwnerProbe.painted(block.getType().name())
                        && block.getBlockData() instanceof Leaves leaf && leaf.isPersistent()) {
                    reject("persistent-leaf", position);
                } else skipped++;
            }
            flush();
        }

        private boolean eligible(Block block) {
            return block != null && OwnerProbe.painted(block.getType().name())
                    && block.getBlockData() instanceof Leaves leaf && !leaf.isPersistent();
        }

        private void reject(String reason, Position position) {
            rejections.merge(reason, 1L, Long::sum);
            switch (reason) {
                case "protected-native-wood" -> protectedNative++;
                case "persistent-leaf" -> placed++;
                case "unavailable-context" -> missingContext++;
                default -> rejected++;
            }
            List<String> examples = samples.computeIfAbsent(reason, ignored -> new ArrayList<>());
            if (examples.size() < 25) {
                examples.add(world.getUID() + "\t" + position.x() + "\t" + position.y() + "\t" + position.z());
            }
        }

        private Block block(Position p) {
            if (p.y() < world.getMinHeight() || p.y() >= world.getMaxHeight()
                    || !world.isChunkLoaded(p.x() >> 4, p.z() >> 4)) return null;
            return world.getBlockAt(p.x(), p.y(), p.z());
        }

        private OwnerProbe.Cell cell(Position p) {
            if (p.y() < world.getMinHeight() || p.y() >= world.getMaxHeight()) return new OwnerProbe.Cell("AIR", false);
            Block block = block(p);
            if (block == null) return null;
            String material = block.getType().name();
            boolean persistent = material.endsWith("_LEAVES")
                    && block.getBlockData() instanceof Leaves leaf && leaf.isPersistent();
            return new OwnerProbe.Cell(material, persistent);
        }

        private void nextChunk() {
            release();
            pending.removeFirst().center.close();
            cursor++;
            completedChunks++;
            originalCursor = candidateCursor = 0;
            recorded.clear();
        }

        @Override void finish(boolean success, String message) {
            for (PendingChunk chunk : pending) chunk.center.close();
            pending.clear();
            try {
                unavailableChunks.close();
            } catch (IOException error) {
                getLogger().log(Level.WARNING, "Could not finish recording unavailable chunks", error);
            }
            super.finish(success, message);
        }
    }
    private Change change(Block block, String original, String reason) {
        BlockData before = block.getBlockData();
        BlockData after = Objects.requireNonNull(Material.matchMaterial(original)).createBlockData();
        LeafStates.copyProperties(before, after);
        return new Change(block.getWorld().getUID(), new Position(block.getX(), block.getY(), block.getZ()),
                before.getAsString(), after.getAsString(), reason);
    }
    private final class RollbackJob extends Job {
        final BufferedReader input;
        Change next;
        UUID currentWorld;
        long currentChunk;
        RollbackJob(CommandSender sender, Path directory) throws IOException {
            super(sender, directory, false); input = Files.newBufferedReader(directory.resolve("changes.tsv"));
        }
        @Override void tick() throws Exception {
            progress();
            long deadline = System.nanoTime() + nanos;
            for (int op = 0; op < Math.min(64, operations) && System.nanoTime() < deadline; op++) {
                if (next == null) {
                    String row = input.readLine();
                    if (row == null) { flush(); input.close(); finish(true, "Rollback finished; conflicting blocks were skipped"); return; }
                    next = Change.decode(row);
                }
                if (lease == null || !next.world().equals(currentWorld) || next.position().chunk() != currentChunk) {
                    flush(); release();
                    currentWorld = next.world(); currentChunk = next.position().chunk();
                    World world = Bukkit.getWorld(currentWorld);
                    if (world == null) throw new IOException("Rollback world is not loaded: " + currentWorld);
                    lease = new ChunkLease(tickets, world, currentChunk, false); return;
                }
                if (!lease.ready()) return;
                if (lease.unavailable()) { skipped++; unavailable++; next = null; continue; }
                propose(next); examined++; next = null;
            }
            flush();
        }
        @Override void finish(boolean success, String message) {
            try { input.close(); } catch (IOException ignored) { }
            super.finish(success, message);
        }
    }
}
