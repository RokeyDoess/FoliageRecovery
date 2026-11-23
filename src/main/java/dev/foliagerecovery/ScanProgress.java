package dev.foliagerecovery;

import java.io.*;
import java.nio.channels.FileChannel;
import java.nio.file.*;
import java.util.*;

/** A fixed target list and a completed prefix. Each continuation has its own rollback journal. */
final class ScanProgress {
    record Target(UUID world, long chunk) {}
    record Checkpoint(String manifestRun, int cursor, List<String> previousRuns) {}
    private ScanProgress() {}

    static void writeTargets(Path file, List<Target> targets) throws IOException {
        try (DataOutputStream output = new DataOutputStream(new BufferedOutputStream(Files.newOutputStream(file)))) {
            output.writeInt(targets.size());
            for (Target target : targets) {
                output.writeLong(target.world().getMostSignificantBits());
                output.writeLong(target.world().getLeastSignificantBits());
                output.writeLong(target.chunk());
            }
        }
    }

    static List<Target> readTargets(Path file) throws IOException {
        try (DataInputStream input = new DataInputStream(new BufferedInputStream(Files.newInputStream(file)))) {
            int count = input.readInt();
            if (count < 0 || 4L + 24L * count != Files.size(file)) throw new IOException("Invalid scan target list");
            List<Target> result = new ArrayList<>(count);
            for (int i = 0; i < count; i++) {
                result.add(new Target(new UUID(input.readLong(), input.readLong()), input.readLong()));
            }
            return result;
        }
    }

    static void save(Path directory, Checkpoint checkpoint) throws IOException {
        Path temporary = directory.resolve("checkpoint.tmp");
        List<String> lines = new ArrayList<>();
        lines.add(checkpoint.manifestRun());
        lines.add(Integer.toString(checkpoint.cursor()));
        lines.addAll(checkpoint.previousRuns());
        Files.write(temporary, lines);
        try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
        try {
            Files.move(temporary, directory.resolve("checkpoint.txt"), StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException error) {
            Files.move(temporary, directory.resolve("checkpoint.txt"), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    static Checkpoint read(Path directory) throws IOException {
        List<String> lines = Files.readAllLines(directory.resolve("checkpoint.txt"));
        if (lines.size() < 2 || !validRun(lines.getFirst())
                || lines.subList(2, lines.size()).stream().anyMatch(id -> !validRun(id))) {
            throw new IOException("Invalid checkpoint");
        }
        try {
            int cursor = Integer.parseInt(lines.get(1));
            if (cursor < 0) throw new NumberFormatException();
            return new Checkpoint(lines.getFirst(), cursor, List.copyOf(lines.subList(2, lines.size())));
        } catch (NumberFormatException error) {
            throw new IOException("Invalid checkpoint cursor", error);
        }
    }

    private static boolean validRun(String id) { return id.matches("[a-f0-9]{12}"); }
}
