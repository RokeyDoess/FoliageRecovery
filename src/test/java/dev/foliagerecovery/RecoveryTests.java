package dev.foliagerecovery;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;
import java.io.*;
import java.nio.file.*;
import java.util.*;

class RecoveryTests {
    @TempDir Path temp;
    private static final UUID WORLD = UUID.fromString("12345678-1234-1234-1234-123456789abc");
    private Path backup(String... entries) throws IOException {
        Path path = temp.resolve(UUID.randomUUID() + ".dat");
        try (DataOutputStream out = new DataOutputStream(Files.newOutputStream(path))) {
            out.writeInt(1095517745); out.writeInt(entries.length / 2);
            for (String entry : entries) out.writeUTF(entry);
        }
        return path;
    }
    @Test void indexRestoresExactCoordinatesAcrossNegativeChunksAndKeepsSourceUntouched() throws Exception {
        Path source = backup(WORLD + ":-17:90:48", "BIRCH_LEAVES", WORLD + ":32:80:-1", "SPRUCE_LEAVES");
        byte[] before = Files.readAllBytes(source);
        Path index = temp.resolve("index");
        var result = LeafIndex.build(source, index);
        assertEquals(2, result.records()); assertEquals(2, result.chunks());
        assertEquals("BIRCH_LEAVES", LeafIndex.read(index, WORLD, Position.chunkKey(-2, 3)).getFirst().material());
        assertEquals(new Position(-17,90,48), LeafIndex.read(index, WORLD, Position.chunkKey(-2, 3)).getFirst().position());
        assertArrayEquals(before, Files.readAllBytes(source));
        assertTrue(LeafIndex.read(index, WORLD, Position.chunkKey(0,0)).isEmpty());
    }
    @Test void malformedOrDuplicateBackupsNeverGetReadyMarker() throws Exception {
        Path source = backup(WORLD + ":0:80:0", "BIRCH_LEAVES", WORLD + ":0:80:0", "SPRUCE_LEAVES");
        Path index = temp.resolve("duplicate");
        assertThrows(IOException.class, () -> LeafIndex.build(source, index));
        assertFalse(Files.exists(index.resolve("READY")));
        Path truncated = backup(WORLD + ":0:80:0", "BIRCH_LEAVES");
        byte[] bytes = Files.readAllBytes(truncated);
        Files.write(truncated, Arrays.copyOf(bytes, bytes.length - 2));
        assertThrows(IOException.class, () -> LeafIndex.build(truncated, temp.resolve("truncated")));
        Path unsupported = backup(WORLD + ":0:80:0", "OAK_LEAVES");
        assertThrows(IOException.class, () -> LeafIndex.build(unsupported, temp.resolve("unsupported")));
    }
    private OwnerProbe probe(Map<Position, OwnerProbe.Cell> cells) {
        OwnerProbe probe = new OwnerProbe(p -> cells.getOrDefault(p, new OwnerProbe.Cell("AIR", false)), new Position(0,80,0));
        for (int i=0; i<10000 && !probe.finished(); i++) probe.step();
        assertTrue(probe.finished()); return probe;
    }
    @Test void unrecordedBirchAndSpruceAreRecoveredAutomatically() {
        for (String species : List.of("BIRCH", "SPRUCE")) {
            var cells = new HashMap<Position, OwnerProbe.Cell>();
            cells.put(new Position(0,80,0), new OwnerProbe.Cell("ORANGE_POPLAR_LEAVES", false));
            cells.put(new Position(0,79,0), new OwnerProbe.Cell("STRIPPED_" + species + "_LOG", false));
            var result = probe(cells);
            assertTrue(result.accepted()); assertEquals(species + "_LEAVES", result.original());
        }
    }
    @Test void realPoplarAcaciaAndMixedTrunksAreLeftAlone() {
        for (String type : List.of("POPLAR_LOG", "STRIPPED_POPLAR_WOOD", "ACACIA_LOG", "DARK_OAK_LOG", "SPRUCE_LOG")) {
            var cells = new HashMap<Position, OwnerProbe.Cell>();
            cells.put(new Position(0,80,0), new OwnerProbe.Cell("ORANGE_POPLAR_LEAVES", false));
            cells.put(new Position(0,79,0), new OwnerProbe.Cell("BIRCH_LOG", false));
            cells.put(new Position(1,80,0), new OwnerProbe.Cell(type, false));
            assertFalse(probe(cells).accepted(), type);
        }
    }
    @Test void poplarStragglersCanMatchAnOakCanopyOrRetainBirchWhenTied() {
        var leaf = new OwnerProbe.Cell("ORANGE_POPLAR_LEAVES", false);
        var oak = new OwnerProbe.Cell("STRIPPED_OAK_LOG", false);
        var birch = new OwnerProbe.Cell("BIRCH_LOG", false);
        var cells = new HashMap<Position, OwnerProbe.Cell>();
        cells.put(new Position(0, 80, 0), leaf);
        cells.put(new Position(0, 79, 0), oak);
        assertEquals("OAK_LEAVES", probe(cells).original());
        cells.put(new Position(1, 80, 0), new OwnerProbe.Cell("BIRCH_LEAVES", false));
        cells.put(new Position(2, 80, 0), birch);
        assertEquals("OAK_LEAVES", probe(cells).original());
        cells.put(new Position(1, 80, 0), birch);
        assertEquals("BIRCH_LEAVES", probe(cells).original());
        cells.put(new Position(-1, 80, 0), new OwnerProbe.Cell("POPLAR_LOG", false));
        assertEquals("protected-native-wood", probe(cells).reason());
    }
    @Test void oakFallbackDoesNotExpandTheAcaciaRepairRule() {
        Position seed = new Position(0, 80, 0);
        var cells = Map.of(seed, new OwnerProbe.Cell("ACACIA_LEAVES", false),
                seed.offset(0, -1, 0), new OwnerProbe.Cell("OAK_LOG", false));
        var result = new OwnerProbe(p -> cells.getOrDefault(p, new OwnerProbe.Cell("AIR", false)),
                seed, "ACACIA_LEAVES");
        while (!result.finished()) result.step();
        assertFalse(result.accepted());
        assertEquals("other-wood-only", result.reason());
    }
    @Test void connectedNormalCanopyResolvesCompetingTrunksWhenSupported() {
        var cells = new HashMap<Position, OwnerProbe.Cell>();
        cells.put(new Position(0, 80, 0), new OwnerProbe.Cell("ORANGE_POPLAR_LEAVES", false));
        cells.put(new Position(0, 79, 0), new OwnerProbe.Cell("SPRUCE_LOG", false));
        cells.put(new Position(0, 81, 0), new OwnerProbe.Cell("BIRCH_LOG", false));
        cells.put(new Position(1, 80, 0), new OwnerProbe.Cell("BIRCH_LEAVES", false));
        assertEquals("mixed-birch-spruce-evidence", probe(cells).reason());
        cells.put(new Position(-1, 80, 0), new OwnerProbe.Cell("BIRCH_LEAVES", false));
        assertEquals("BIRCH_LEAVES", probe(cells).original());
        cells.put(new Position(0, 80, 1), new OwnerProbe.Cell("SPRUCE_LEAVES", false));
        assertEquals("mixed-birch-spruce-evidence", probe(cells).reason());
        cells.put(new Position(0, 80, -1), new OwnerProbe.Cell("POPLAR_LOG", false));
        assertEquals("protected-native-wood", probe(cells).reason());
    }
    @Test void nearbyWoodAcrossAirDoesNotClaimALeaf() {
        assertFalse(probe(Map.of(new Position(0,80,0), new OwnerProbe.Cell("YELLOW_POPLAR_LEAVES", false),
                new Position(0,77,0), new OwnerProbe.Cell("BIRCH_LOG", false))).accepted());
    }
    @Test void clearBirchOwnershipSurvivesABorderAndDistantCompetingOak() {
        Position target = new Position(-17, 80, 32);
        var cells = Map.of(target, new OwnerProbe.Cell("RED_POPLAR_LEAVES", false),
                target.offset(1, 0, 0), new OwnerProbe.Cell("BIRCH_LEAVES", false),
                target.offset(2, 0, 0), new OwnerProbe.Cell("BIRCH_LOG", false),
                target.offset(-1, 0, 0), new OwnerProbe.Cell("OAK_LEAVES", false),
                target.offset(-2, 0, 0), new OwnerProbe.Cell("OAK_LEAVES", false),
                target.offset(-3, 0, 0), new OwnerProbe.Cell("OAK_LEAVES", false),
                target.offset(-4, 0, 0), new OwnerProbe.Cell("OAK_LOG", false));
        var result = new OwnerProbe(p -> cells.getOrDefault(p, new OwnerProbe.Cell("AIR", false)),
                target, "RED_POPLAR_LEAVES");
        assertTrue(target.touchesChunkEdge(6));
        assertFalse(new Position(-9, 80, 40).touchesChunkEdge(6));
        while (!result.finished()) result.step();
        assertTrue(result.accepted());
        assertEquals("BIRCH_LEAVES", result.original());
    }
    @Test void nativePoplarTrunkStillProtectsALeafEvenWithCloserBirchWood() {
        var result = probe(Map.of(
                new Position(0,80,0), new OwnerProbe.Cell("YELLOW_POPLAR_LEAVES", false),
                new Position(1,80,0), new OwnerProbe.Cell("BIRCH_LOG", false),
                new Position(-1,80,0), new OwnerProbe.Cell("YELLOW_POPLAR_LEAVES", false),
                new Position(-2,80,0), new OwnerProbe.Cell("YELLOW_POPLAR_LEAVES", false),
                new Position(-3,80,0), new OwnerProbe.Cell("POPLAR_LOG", false)));
        assertFalse(result.accepted());
        assertEquals("protected-native-wood", result.reason());
    }
    @Test void checkpointPreservesTheExactTargetOrderAndPreviousJournals() throws Exception {
        Path manifest = temp.resolve("targets.bin");
        List<ScanProgress.Target> targets = List.of(
                new ScanProgress.Target(WORLD, Position.chunkKey(-2, 1)),
                new ScanProgress.Target(UUID.randomUUID(), Position.chunkKey(0, -1)));
        ScanProgress.writeTargets(manifest, targets);
        var saved = new ScanProgress.Checkpoint("123456789abc", 1, List.of("abcdef123456"));
        ScanProgress.save(temp, saved);
        var loaded = ScanProgress.read(temp);
        assertEquals(saved, loaded);
        assertEquals(targets.get(1), ScanProgress.readTargets(manifest).get(loaded.cursor()));
        ScanProgress.save(temp, new ScanProgress.Checkpoint(saved.manifestRun(), 2, saved.previousRuns()));
        assertEquals(2, ScanProgress.read(temp).cursor());
    }
    @Test void leafPathSearchIsBoundedAndPlacedLeavesAreExcluded() {
        var cells = new HashMap<Position, OwnerProbe.Cell>();
        for (int x=0;x<7;x++) cells.put(new Position(x,80,0), new OwnerProbe.Cell("RED_POPLAR_LEAVES", false));
        cells.put(new Position(7,80,0), new OwnerProbe.Cell("BIRCH_LOG", false));
        assertFalse(probe(cells).accepted());
        cells.put(new Position(6,80,0), new OwnerProbe.Cell("BIRCH_LOG", false));
        assertTrue(probe(cells).accepted());
        cells.put(new Position(0,80,0), new OwnerProbe.Cell("RED_POPLAR_LEAVES", true));
        assertFalse(probe(cells).accepted());
    }
    @Test void unavailableContextNeverProducesARepair() {
        var result = new OwnerProbe(p -> null, new Position(0,80,0)); result.step();
        assertFalse(result.accepted()); assertEquals("unavailable-context", result.reason());
    }
    @Test void regionHeadersEnumerateOnlyExistingLocationsIncludingNegativeRegions() throws Exception {
        Path region = Files.createDirectory(temp.resolve("region"));
        byte[] header = new byte[8192];
        try (RandomAccessFile f = new RandomAccessFile(region.resolve("r.-1.2.mca").toFile(), "rw")) {
            f.write(header); f.seek(0); f.writeInt((2 << 8) | 1);
            f.seek(4L * 1023); f.writeInt((3 << 8) | 1);
        }
        assertEquals(Set.of(Position.chunkKey(-32,64), Position.chunkKey(-1,95)), new HashSet<>(RegionChunks.existing(region)));
        assertEquals(1, Files.list(region).count());
    }
    private Change change() {
        return new Change(WORLD, new Position(-17,80,0),
                "minecraft:orange_poplar_leaves[distance=2,persistent=false,waterlogged=true]",
                "minecraft:birch_leaves[distance=2,persistent=false,waterlogged=true]", "inferred");
    }
    @Test void finishedPreviewHasAVerifiableContentHash() throws Exception {
        Path plan = Files.createDirectory(temp.resolve("plan"));
        Files.writeString(plan.resolve("changes.tsv"), change().encode() + "\n");
        assertFalse(Files.exists(plan.resolve("READY")));
        PlanFiles.ready(plan);
        assertEquals(PlanFiles.hash(plan.resolve("changes.tsv")), Files.readString(plan.resolve("READY")));
        Files.writeString(plan.resolve("changes.tsv"), "\n", StandardOpenOption.APPEND);
        assertNotEquals(PlanFiles.hash(plan.resolve("changes.tsv")), Files.readString(plan.resolve("READY")));
    }
    @Test void journalRollbackIncludesOnlyCommittedEditsAndPreservesFullBlockStates() throws Exception {
        Path journal = temp.resolve("journal.tsv");
        try (RecoveryJournal writer = new RecoveryJournal(journal)) {
            assertEquals(1, writer.apply(List.of(change(), change()), new java.util.function.Predicate<>() {
                int calls; public boolean test(Change c) { return calls++ == 0; }
            }));
        }
        Path reverse = Files.createDirectory(temp.resolve("reverse"));
        assertEquals(0, PlanFiles.rollback(journal, reverse));
        var rows = Files.readAllLines(reverse.resolve("changes.tsv"));
        assertEquals(1, rows.size()); assertEquals(change().reversed(), Change.decode(rows.getFirst()));
    }
    @Test void interruptedBatchKeepsDurableIntentAndDoesNotAutomaticallyUndoUncertainEdits() throws Exception {
        Path journal = temp.resolve("interrupted.tsv");
        try (RecoveryJournal writer = new RecoveryJournal(journal)) {
            assertThrows(IllegalStateException.class, () -> writer.apply(List.of(change()), c -> { throw new IllegalStateException("interrupted"); }));
        }
        Path reverse = Files.createDirectory(temp.resolve("interrupted-reverse"));
        assertEquals(1, PlanFiles.rollback(journal, reverse));
        assertEquals("", Files.readString(reverse.resolve("changes.tsv")));
        assertEquals(change().encode(), Files.readString(reverse.resolve("unresolved-intents.tsv")).trim());
    }
    @Test void intentIsVisibleOnDiskBeforeTheEditCallbackRuns() throws Exception {
        Path journal = temp.resolve("durable.tsv");
        try (RecoveryJournal writer = new RecoveryJournal(journal)) {
            writer.apply(List.of(change()), c -> {
                try {
                    String visible = Files.readString(journal);
                    assertTrue(visible.startsWith("I\t1\t"));
                    assertFalse(visible.contains("\nC\t"));
                } catch (IOException e) { throw new UncheckedIOException(e); }
                return true;
            });
        }
        assertTrue(Files.readString(journal).lines().anyMatch(line -> line.equals("C\t1")));
    }
    @Test void trailingPartialCrashRecordPreservesEarlierCommittedRollbackEntries() throws Exception {
        Path journal = temp.resolve("partial.tsv");
        Files.writeString(journal, "I\t1\t" + change().encode() + "\nC\t1\nI\t2\t" + change().encode() + "\nC\t");
        Path reverse = Files.createDirectory(temp.resolve("partial-reverse"));
        assertEquals(1, PlanFiles.rollback(journal, reverse));
        assertEquals(1, Files.readAllLines(reverse.resolve("changes.tsv")).size());
        assertEquals("C\t", Files.readString(reverse.resolve("journal-tail-fragment.txt")));
    }
}
